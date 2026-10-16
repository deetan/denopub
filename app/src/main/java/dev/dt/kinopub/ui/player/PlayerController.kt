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

data class PlayerUi(
    val title: String = "",
    val episodeLabel: String? = null,      // "S2·E5 · Название серии"
    val audio: List<TrackOption> = emptyList(),
    val subtitles: List<TrackOption> = emptyList(),
    val qualities: List<TrackOption> = emptyList(),
    val hasNext: Boolean = false,
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
        .setSeekForwardIncrementMs(30_000)
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

        player.setMediaItem(mediaItem, positionMs)
        player.prepare()
        player.playWhenReady = true

        _ui.value = PlayerUi(
            title = it.title,
            episodeLabel = target.season?.let { s ->
                "S$s·E${target.episode}" + (v.title?.takeIf { t -> t.isNotBlank() }?.let { t -> " · $t" } ?: "")
            },
            hasNext = c.repository.nextOf(it, target.season, target.episode) != null,
            qualities = qualityOptions(v.files, f),
        )
    }

    /** Best file not above the quality cap; at equal height prefer HEVC (that is where HDR lives). */
    private fun pickFile(files: List<MediaFile>, maxHeight: Int?): MediaFile? {
        val usable = files.filter { it.url.hls4 != null || it.url.hls != null || it.url.http != null }
        val capped = usable.filter { maxHeight == null || it.h <= maxHeight }.ifEmpty { usable }
        return capped.maxWithOrNull(compareBy<MediaFile> { it.h }.thenBy { if (it.codec == "h265") 1 else 0 })
    }

    fun playNext() {
        val it = item ?: return
        val next = c.repository.nextOf(it, current.season, current.episode) ?: return
        viewModelScope.launch { c.repository.markWatched(it.id, current.season, current.episode) }
        load(ResumeTarget(it.id, next.first, next.second, 0), 0)
    }

    private fun onEnded() {
        val it = item ?: return
        viewModelScope.launch { c.repository.markWatched(it.id, current.season, current.episode) }
        if (c.repository.nextOf(it, current.season, current.episode) != null) playNext()
    }

    fun saveProgress() {
        val it = item ?: return
        val sec = (player.currentPosition / 1000).toInt()
        if (sec < 5) return
        val t = current
        viewModelScope.launch { c.repository.markTime(it.id, t.season, t.episode, sec) }
    }

    // ---------- tracks ----------

    /** Audio formats in manifest order; kinopub's audios[] (1-based index) follows the same order. */
    private fun audioTracks(): List<Triple<Tracks.Group, Int, Format>> =
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { i -> Triple(g, i, g.getTrackFormat(i)) }
        }

    private fun textTracks(): List<Triple<Tracks.Group, Int, Format>> =
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g ->
            (0 until g.length).map { i -> Triple(g, i, g.getTrackFormat(i)) }
        }

    private fun meta(i: Int): AudioTrack? = video?.audios?.sortedBy { it.index }?.getOrNull(i)

    private fun publishTracks() {
        val audio = audioTracks().mapIndexed { i, (g, t, f) ->
            val m = meta(i)
            TrackOption(
                key = "a$i",
                label = m?.author?.title?.takeIf { it.isNotBlank() }
                    ?: m?.type?.title?.takeIf { it.isNotBlank() }
                    ?: if (m != null && m.lang.isNullOrBlank()) "Оригинал" else f.label ?: f.language ?: "Дорожка ${i + 1}",
                detail = listOfNotNull(
                    (m?.lang ?: f.language)?.takeIf { it.isNotBlank() }?.uppercase(),
                    channelsLabel(m?.channels ?: f.channelCount),
                    (m?.codec ?: f.sampleMimeType?.substringAfter('/'))?.uppercase(),
                ).joinToString(" · ").ifBlank { null },
                selected = g.isTrackSelected(t),
            )
        }
        val text = textTracks()
        val subsOn = text.any { (g, t, _) -> g.isTrackSelected(t) }
        val subs = listOf(TrackOption("s-off", "Выкл", null, selected = !subsOn)) +
            text.mapIndexed { i, (g, t, f) ->
                TrackOption("s$i", f.label ?: f.language?.uppercase() ?: "Субтитры ${i + 1}", null, g.isTrackSelected(t))
            }
        _ui.value = _ui.value.copy(audio = audio, subtitles = subs)
    }

    fun selectAudio(index: Int, remember: Boolean = true) {
        val (g, t, f) = audioTracks().getOrNull(index) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t))
            .build()
        if (remember) {
            val m = meta(index)
            saveChoice(choice.copy(audioLang = m?.lang ?: f.language, audioAuthor = m?.author?.title, audioType = m?.type?.title))
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
        if (remember) saveChoice(choice.copy(subtitleLang = picked?.third?.let { it.label ?: it.language }))
    }

    fun selectQuality(height: Int) {
        val pos = player.currentPosition
        saveChoice(choice.copy(maxHeight = height))
        load(current, pos)
    }

    /** Remote shortcut (audio-track key): next voiceover. */
    fun cycleAudio() {
        val n = audioTracks().size
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
        val audio = audioTracks()
        if (audio.isNotEmpty()) {
            val scored = audio.indices.map { i ->
                val m = meta(i)
                val f = audio[i].third
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
        return text.indexOfFirst { (_, _, f) -> f.label == want }.takeIf { it >= 0 }
            ?: text.indexOfFirst { (_, _, f) -> f.language.equals(want, ignoreCase = true) }.takeIf { it >= 0 }
    }

    private fun qualityOptions(files: List<MediaFile>, selected: MediaFile): List<TrackOption> =
        files.map { it.h }.distinct().sortedDescending().map { h ->
            val f = files.filter { it.h == h }.maxBy { if (it.codec == "h265") 1 else 0 }
            TrackOption(
                key = "q$h",
                label = qualityLabel(h),
                detail = f.codec?.uppercase(),
                selected = h == selected.h,
            )
        }

    override fun onCleared() {
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

        fun subtitleLabel(lang: String, forced: Boolean) = lang.uppercase() + if (forced) " (forced)" else ""

        fun channelsLabel(ch: Int?): String? = when (ch) {
            null, Format.NO_VALUE, 0 -> null
            1 -> "моно"
            2 -> "2.0"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${ch}ch"
        }

        fun qualityLabel(h: Int): String = when {
            h >= 2000 -> "4K"
            h >= 1000 -> "1080p"
            h >= 700 -> "720p"
            else -> "${h}p"
        }
    }
}
