package dev.dt.kinopub.ui.player

import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.dt.kinopub.AppContainer
import dev.dt.kinopub.data.ResumeTarget
import dev.dt.kinopub.data.api.AudioTrack
import dev.dt.kinopub.data.api.Item
import dev.dt.kinopub.data.api.MediaFile
import dev.dt.kinopub.data.api.Video
import dev.dt.kinopub.data.prefs.TrackChoice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One selectable option in the tracks panel. */
data class TrackOption(
    val key: String,
    val label: String,       // "LostFilm", "Оригинал", "RU", "2160p HDR"
    val detail: String?,     // "RU · 5.1 · AC3"
    val selected: Boolean,
)

/** One episode (or movie part) as a card in the tracks panel. */
data class EpisodeCard(
    val season: Int?,
    val episode: Int,
    val label: String,       // "S2·E5"
    val title: String?,
    val thumbnail: String?,
    val progress: Float,     // 0..1 of the saved position; 1 when watched
    val current: Boolean,
)

data class PlayerUi(
    val title: String = "",
    val episodeLabel: String? = null,      // "S2·E5 · Название серии"
    val audio: List<TrackOption> = emptyList(),
    val subtitles: List<TrackOption> = emptyList(),
    val qualities: List<TrackOption> = emptyList(),
    val hasNext: Boolean = false,
    val prev: EpisodeCard? = null,
    val next: EpisodeCard? = null,
    val episodes: List<EpisodeCard> = emptyList(), // all of them, for the long-press picker
    val error: String? = null,
)

/**
 * Owns ExoPlayer for one playback session (which may span several episodes).
 *
 * Track memory: every manual audio/subtitle/quality pick is saved as a [TrackChoice] for the
 * title; each new episode re-applies it once its tracks are known.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    app: Application,
    private val c: AppContainer,
    start: ResumeTarget,
) : AndroidViewModel(app) {

    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setRenderersFactory(
            // Prefer the TV's hardware decoders (HEVC/HDR/DV); fall back to software for odd audio.
            DefaultRenderersFactory(app).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
        )
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(c.http)))
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui

    private var item: Item? = null
    private var current: ResumeTarget = start
    private var video: Video? = null
    private var file: MediaFile? = null
    private var choice: TrackChoice = TrackChoice()
    private var choiceApplied = false
    private var progressJob: Job? = null
    // Positions saved during this session (kino.pub's copy in [item] is from when playback started).
    private val savedSec = mutableMapOf<Pair<Int?, Int>, Int>()

    init {
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                if (!choiceApplied && tracks.groups.isNotEmpty()) {
                    choiceApplied = true
                    applyChoice()
                }
                publishTracks()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveProgress()
            }

            override fun onPlayerError(error: PlaybackException) {
                _ui.value = _ui.value.copy(error = error.errorCodeName)
            }
        })
        viewModelScope.launch {
            runCatching {
                val it = c.repository.item(start.itemId)
                item = it
                choice = c.prefs.trackChoice(it.id) ?: TrackChoice()
                // Playing counts as activity: the title jumps to the front of its Continue row,
                // even before (or without) kino.pub updating its history.
                c.appScope.launch { c.prefs.markPlayed(it.id) }
                load(start)
            }.onFailure { _ui.value = _ui.value.copy(error = it.message) }
        }
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                if (player.isPlaying) saveProgress()
            }
        }
    }

    // ---------- loading ----------

    private fun load(target: ResumeTarget, positionMs: Long = target.startSec * 1000L) {
        val it = item ?: return
        val v = c.repository.video(it, target.season, target.episode) ?: run {
            _ui.value = _ui.value.copy(error = "Нет такой серии")
            return
        }
        current = target
        video = v
        val f = pickFile(v.files, choice.maxHeight) ?: run {
            _ui.value = _ui.value.copy(error = "Нет файлов")
            return
        }
        file = f
        choiceApplied = false
        releasePreview()

        val url = f.url.hls4 ?: f.url.hls ?: f.url.http!!
        val subs = v.subtitles.filter { s -> s.url != null }.mapIndexed { i, s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                .setId("sub_$i")
                .setMimeType(subtitleMime(s.url!!))
                .setLanguage(s.lang)
                .setLabel(subtitleLabel(s.lang, s.forced))
                .build()
        }
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .apply { if (url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(subs)
            .build()

        c.appScope.launch {
            runCatching {
                c.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                    val lines = r.body?.string().orEmpty().lines().filter { l -> l.startsWith("#EXT-X-MEDIA") || l.startsWith("#EXT-X-STREAM-INF") }
                    android.util.Log.i("denopub", "manifest ${lines.size} lines\n" + lines.take(40).joinToString("\n"))
                }
            }
        }
        // Stay at the chosen quality: ABR otherwise starts low and often sits at 1080p on a 4K
        // file even on a fast network. Cap at the chosen file's height.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setForceHighestSupportedBitrate(true)
            .setMaxVideoSize(Int.MAX_VALUE, if (choice.maxHeight != null) f.h else Int.MAX_VALUE)
            .build()
        player.setMediaItem(mediaItem, positionMs)
        player.prepare()
        player.playWhenReady = true

        val cards = episodeCards(it, target)
        val at = cards.indexOfFirst { e -> e.current }
        _ui.value = PlayerUi(
            prev = cards.getOrNull(at - 1)?.takeIf { at > 0 },
            next = cards.getOrNull(at + 1)?.takeIf { at >= 0 },
            title = it.title,
            episodeLabel = target.season?.let { s ->
                "S$s·E${target.episode}" + (v.title?.takeIf { t -> t.isNotBlank() }?.let { t -> " · $t" } ?: "")
            },
            hasNext = c.repository.nextOf(it, target.season, target.episode) != null,
            episodes = cards,
            qualities = qualityOptions(v.files, f),
        )
    }

    /** Best file not above the quality cap; at equal height prefer HEVC (that is where HDR lives). */
    private fun pickFile(files: List<MediaFile>, maxHeight: Int?): MediaFile? {
        val usable = files.filter { it.url.hls4 != null || it.url.hls != null || it.url.http != null }
        val capped = usable.filter { maxHeight == null || it.h <= maxHeight }.ifEmpty { usable }
        return capped.maxWithOrNull(compareBy<MediaFile> { it.h }.thenBy { if (it.codec == "h265") 1 else 0 })
    }

    /** Every episode in order across seasons (or the parts of a movie). */
    private fun flatEpisodes(it: Item): List<Pair<Int?, Video>> =
        if (it.seasons.isNotEmpty()) it.seasons.sortedBy { s -> s.number }.flatMap { s -> s.episodes.sortedBy { v -> v.number }.map { v -> s.number to v } }
        else it.videos.sortedBy { v -> v.number }.map { v -> null to v }

    private fun episodeCards(it: Item, at: ResumeTarget): List<EpisodeCard> {
        val all = flatEpisodes(it)
        if (all.size < 2) return emptyList()
        return all.map { (s, v) ->
            val sec = resumeSec(s, v)
            val watched = sec == 0 && (v.watching?.status == 1 || v.watched == 1)
            EpisodeCard(
                season = s, episode = v.number,
                label = if (s != null) "S$s·E${v.number}" else "Часть ${v.number}",
                title = v.title?.takeIf { t -> t.isNotBlank() },
                thumbnail = v.thumbnail,
                progress = if (watched) 1f else if (v.duration > 0) (sec.toFloat() / v.duration).coerceIn(0f, 1f) else 0f,
                current = s == at.season && v.number == at.episode,
            )
        }
    }

    /** Where an episode resumes: this session's saved spot, else kino.pub's unfinished time, else 0. */
    private fun resumeSec(season: Int?, v: Video): Int =
        savedSec[season to v.number] ?: v.watching?.takeIf { w -> w.status != 1 && w.time > 0 }?.time ?: 0

    /**
     * Switch to any episode. The one you leave keeps its position and is NOT marked watched
     * (a stray "next" used to wipe its progress); the target resumes where it was left.
     */
    fun playEpisode(season: Int?, episode: Int) {
        val it = item ?: return
        if (season == current.season && episode == current.episode) return
        val v = c.repository.video(it, season, episode) ?: return
        saveProgress()
        load(ResumeTarget(it.id, season, episode, 0), resumeSec(season, v) * 1000L)
    }

    fun playNext() {
        val it = item ?: return
        val next = c.repository.nextOf(it, current.season, current.episode) ?: return
        playEpisode(next.first, next.second)
    }

    /** Played to the end: that one is watched; the next starts (from its own saved spot). */
    private fun onEnded() {
        val it = item ?: return
        val done = current
        savedSec[done.season to done.episode] = 0
        c.appScope.launch { c.repository.markWatched(it.id, done.season, done.episode) }
        val next = c.repository.nextOf(it, done.season, done.episode) ?: return
        val v = c.repository.video(it, next.first, next.second) ?: return
        load(ResumeTarget(it.id, next.first, next.second, 0), resumeSec(next.first, v) * 1000L)
    }

    fun saveProgress() {
        val it = item ?: return
        val sec = (player.currentPosition / 1000).toInt()
        // At the end onEnded marks it watched; a time write here would race it.
        if (sec < 5 || player.playbackState == Player.STATE_ENDED) return
        val t = current
        savedSec[t.season to t.episode] = sec
        // appScope, not viewModelScope: this also runs from onCleared, when viewModelScope is dead.
        c.appScope.launch {
            c.repository.markTime(it.id, t.season, t.episode, sec)
            c.prefs.markPlayed(it.id) // keeps "last watched" right even when kino.pub history lags
        }
    }

    // ---------- tracks ----------

    /** Audio formats in manifest order; kinopub's audios[] (1-based index) follows the same order. */
    /** One selectable voiceover: an HLS audio rendition group + its kino.pub metadata. */
    private data class AudioOpt(val group: Tracks.Group, val tracks: List<Int>, val format: Format, val meta: AudioTrack?)

    /**
     * kino.pub hls4 manifests repeat every voiceover once per quality (GROUP-ID audio2160/1080/720/480),
     * so ExoPlayer exposes one TrackGroup per voiceover containing a copy per quality. A voiceover is
     * therefore a *group*, never a single track (mapping tracks by position picked "Яроцкий 1080"
     * when the chip said "Невафильм"). Names carry kino.pub's 1-based index: "02. Дубляж. Невафильм (RUS)".
     */
    private fun audioOptions(): List<AudioOpt> {
        val audios = video?.audios?.sortedBy { it.index }.orEmpty()
        return player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .mapNotNull { g -> (0 until g.length).filter { g.isTrackSupported(it) }.takeIf { it.isNotEmpty() }?.let { g to it } }
            .mapIndexed { i, (g, tracks) ->
                val f = g.getTrackFormat(tracks.first())
                val n = Regex("^(\\d+)\\.").find(f.label.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                val m = n?.let { k -> audios.firstOrNull { it.index == k } } ?: audios.getOrNull(i)
                AudioOpt(g, tracks, f, m)
            }
    }

    private fun cleanAudioLabel(raw: String?): String? =
        raw?.replace(Regex("^\\d+\\.\\s*"), "")?.trim()?.takeIf { it.isNotBlank() }

    /**
     * Subtitles, most useful first: the remembered language, then Russian, then English, then the
     * rest alphabetically. kino.pub manifests often carry 20+ languages.
     */
    private fun textTracks(): List<Triple<Tracks.Group, Int, Format>> {
        val all = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g ->
            (0 until g.length).map { i -> Triple(g, i, g.getTrackFormat(i)) }
        }
        val remembered = choice.subtitleLang?.let { subtitleName(it) }
        fun rank(f: Format): Int {
            val name = subtitleName(f.label ?: f.language.orEmpty())
            val lang = f.language.orEmpty().lowercase()
            return when {
                remembered != null && name == remembered -> 0
                lang.startsWith("ru") -> 1
                lang.startsWith("en") -> 2
                else -> 3
            }
        }
        return all.sortedWith(compareBy({ rank(it.third) }, { subtitleName(it.third.label ?: it.third.language.orEmpty()) }))
    }


    private fun logTracks(tag: String) {
        val lines = audioOptions().mapIndexed { i, o ->
            "a$i sel=${o.group.isSelected} tracks=${o.tracks.size} label=${o.format.label} lang=${o.format.language} | " +
                "kp[${o.meta?.index}]=${o.meta?.author?.title}/${o.meta?.type?.title}/${o.meta?.lang}"
        }
        android.util.Log.i("denopub", "$tag audio groups=${player.currentTracks.groups.count { it.type == C.TRACK_TYPE_AUDIO }}\n" + lines.joinToString("\n"))
    }

    private fun publishTracks() {
        logTracks("tracks")
        val audio = audioOptions().mapIndexed { i, o ->
            val m = o.meta
            val f = o.format
            TrackOption(
                key = "a$i",
                label = m?.author?.title?.takeIf { it.isNotBlank() }
                    ?: m?.type?.title?.takeIf { it.isNotBlank() }
                    ?: cleanAudioLabel(f.label) ?: f.language ?: "Дорожка ${i + 1}",
                detail = listOfNotNull(
                    (m?.lang ?: f.language)?.takeIf { it.isNotBlank() }?.uppercase(),
                    channelsLabel(m?.channels ?: f.channelCount),
                    (m?.codec ?: f.sampleMimeType?.substringAfter('/'))?.uppercase(),
                ).joinToString(" · ").ifBlank { null },
                selected = o.group.isSelected,
            )
        }
        val text = textTracks()
        val subsOn = text.any { (g, t, _) -> g.isTrackSelected(t) }
        val subs = listOf(TrackOption("s-off", "Выкл", null, selected = !subsOn)) +
            run {
                val seen = mutableMapOf<String, Int>()
                text.mapIndexed { i, (g, t, f) ->
                    val name = subtitleName(f.label ?: f.language ?: "Субтитры ${i + 1}")
                    val n = (seen[name] ?: 0) + 1
                    seen[name] = n
                    TrackOption("s$i", if (n > 1) "$name $n" else name, null, g.isTrackSelected(t))
                }
            }
        _ui.value = _ui.value.copy(audio = audio, subtitles = subs)
    }

    fun selectAudio(index: Int, remember: Boolean = true) {
        val o = audioOptions().getOrNull(index) ?: return
        android.util.Log.i("denopub", "selectAudio $index -> ${o.format.label}")
        // Whole group: ExoPlayer picks the copy that matches the playing video variant.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.tracks))
            .build()
        if (remember) {
            val m = o.meta
            saveChoice(choice.copy(audioLang = m?.lang ?: o.format.language, audioAuthor = m?.author?.title, audioType = m?.type?.title))
        }
    }

    /** index = null turns subtitles off. */
    fun selectSubtitle(index: Int?, remember: Boolean = true) {
        val b = player.trackSelectionParameters.buildUpon()
        val picked = index?.let { textTracks().getOrNull(it) }
        if (picked == null) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(picked.first.mediaTrackGroup, picked.second))
        }
        player.trackSelectionParameters = b.build()
        if (remember) saveChoice(choice.copy(subtitleLang = picked?.third?.let { subtitleName(it.label ?: it.language.orEmpty()) }))
    }

    fun selectQuality(height: Int) {
        val pos = player.currentPosition
        saveChoice(choice.copy(maxHeight = height))
        load(current, pos)
    }

    /** Remote shortcut (audio-track key): next voiceover. */
    fun cycleAudio() {
        val n = audioOptions().size
        if (n < 2) return
        val cur = _ui.value.audio.indexOfFirst { it.selected }
        selectAudio((cur + 1) % n)
    }

    /** Remote shortcut (captions key): off ↔ last used language. */
    fun toggleSubtitles() {
        val on = _ui.value.subtitles.firstOrNull()?.selected == false
        if (on) selectSubtitle(null) else selectSubtitle(bestSubtitleIndex(choice.subtitleLang) ?: 0)
    }

    private fun saveChoice(new: TrackChoice) {
        choice = new
        val id = item?.id ?: return
        viewModelScope.launch { c.prefs.saveTrackChoice(id, new) }
    }

    /** Re-apply the remembered picks to a freshly loaded episode, without re-saving them. */
    private fun applyChoice() {
        val audio = audioOptions()
        if (audio.isNotEmpty()) {
            val scored = audio.indices.map { i ->
                val m = audio[i].meta
                val f = audio[i].format
                var s = 0
                if (choice.audioAuthor != null && m?.author?.title == choice.audioAuthor) s += 4
                if (choice.audioType != null && m?.type?.title == choice.audioType) s += 1
                if (choice.audioLang != null && (m?.lang ?: f.language) == choice.audioLang) s += 2
                i to s
            }
            val best = scored.maxBy { it.second }
            if (best.second > 0) selectAudio(best.first, remember = false)
        }
        if (choice.subtitleLang == null) selectSubtitle(null, remember = false)
        else bestSubtitleIndex(choice.subtitleLang)?.let { selectSubtitle(it, remember = false) }
    }

    private fun bestSubtitleIndex(want: String?): Int? {
        if (want == null) return null
        val text = textTracks()
        return text.indexOfFirst { (_, _, f) -> subtitleName(f.label ?: "") == subtitleName(want) }.takeIf { it >= 0 }
            ?: text.indexOfFirst { (_, _, f) -> f.language.equals(want, ignoreCase = true) }.takeIf { it >= 0 }
    }

    private fun qualityOptions(files: List<MediaFile>, selected: MediaFile): List<TrackOption> =
        files.map { it.h }.distinct().sortedDescending().map { h ->
            val f = files.filter { it.h == h }.maxBy { if (it.codec == "h265") 1 else 0 }
            TrackOption(
                key = "q$h",
                label = qualityLabel(f),
                detail = f.codec?.uppercase(),
                selected = h == selected.h,
            )
        }

    private val bandwidth = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(app)

    /** One-line playback stats for the OSD, like the original kino.pub player shows. */
    fun stats(): String {
        val v = player.videoFormat
        val parts = mutableListOf<String>()
        if (v != null && v.width > 0) {
            parts += "${v.width}×${v.height}" + (if (v.frameRate > 0) " ${"%.0f".format(v.frameRate)}fps" else "")
            val codec = when {
                v.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
                v.sampleMimeType == MimeTypes.VIDEO_H265 -> "HEVC"
                v.sampleMimeType == MimeTypes.VIDEO_H264 -> "AVC"
                else -> v.sampleMimeType?.substringAfter('/')?.uppercase()
            }
            val hdr = when (v.colorInfo?.colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> "HDR10"
                C.COLOR_TRANSFER_HLG -> "HLG"
                else -> null
            }
            parts += listOfNotNull(codec, hdr).joinToString(" ")
            val br = maxOf(v.bitrate, v.peakBitrate, v.averageBitrate)
            if (br > 0) parts += "поток %.1f Мбит/с".format(br / 1e6)
        }
        val net = bandwidth.bitrateEstimate
        if (net > 0) parts += "сеть %.0f Мбит/с".format(net / 1e6)
        val buf = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0) / 1000
        parts += "буфер ${buf} с"
        player.audioFormat?.let { a ->
            listOfNotNull(a.sampleMimeType?.substringAfter('/')?.uppercase(), channelsLabel(a.channelCount))
                .joinToString(" ").takeIf { it.isNotBlank() }?.let { parts += "звук $it" }
        }
        return parts.filter { it.isNotBlank() }.joinToString("  ·  ")
    }

    // ---------- scrub preview ----------

    private var preview: ExoPlayer? = null

    /**
     * A second, muted, lowest-quality player used only to show the frame at the scrub position.
     * kino.pub has no thumbnail sprites, so we decode the real frame (keyframe-accurate, cheap at 360p).
     */
    fun previewPlayer(): ExoPlayer? {
        preview?.let { return it }
        val v = video ?: return null
        val f = v.files.filter { it.url.hls4 != null || it.url.hls != null || it.url.http != null }.minByOrNull { it.h } ?: return null
        val url = f.url.hls4 ?: f.url.hls ?: f.url.http!!
        val p = ExoPlayer.Builder(getApplication())
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(c.http)))
            .build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setMaxVideoSize(854, 480)
            .setForceLowestBitrate(true)
            .build()
        p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
        p.setMediaItem(
            MediaItem.Builder().setUri(url).apply { if (url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }.build(),
        )
        p.volume = 0f
        p.playWhenReady = false
        p.prepare()
        preview = p
        return p
    }

    fun previewAt(ms: Long) { previewPlayer()?.seekTo(ms) }

    fun releasePreview() {
        preview?.release()
        preview = null
    }

    override fun onCleared() {
        releasePreview()
        saveProgress()
        progressJob?.cancel()
        player.release()
    }

    companion object {
        fun subtitleMime(url: String): String = when {
            url.contains(".vtt", true) -> MimeTypes.TEXT_VTT
            url.contains(".ass", true) || url.contains(".ssa", true) -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }

        /** Manifest names look like "ENG #12"; the number is noise. */
        fun subtitleName(raw: String): String = raw.replace(Regex("\\s*#\\d+$"), "").trim().uppercase()

        fun subtitleLabel(lang: String, forced: Boolean) = lang.uppercase() + if (forced) " (forced)" else ""

        fun channelsLabel(ch: Int?): String? = when (ch) {
            null, Format.NO_VALUE, 0 -> null
            1 -> "моно"
            2 -> "2.0"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${ch}ch"
        }

        /** By width: wide films are 3840×1600 etc., so height alone mislabels 4K as 1080p. */
        fun qualityLabel(f: MediaFile): String = when {
            f.w >= 3200 || f.h >= 2000 -> "4K"
            f.w >= 1800 || f.h >= 1000 -> "1080p"
            f.w >= 1200 || f.h >= 700 -> "720p"
            f.w > 0 -> "${f.h}p"
            else -> f.quality ?: "${f.h}p"
        }
    }
}
