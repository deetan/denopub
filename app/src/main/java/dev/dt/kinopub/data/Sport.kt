package dev.dt.kinopub.data

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.util.Xml
import dev.dt.kinopub.data.api.KinopubApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * The Home "Спорт" row: kino.pub live channels (Eurosport → Okko → Match), what's on now from an
 * outside XMLTV guide (kino.pub's own `current` field is always empty), and a still frame per
 * channel cut from the first ~700 KB of the newest HLS segment — no player, no audio.
 */
class Sport(private val api: KinopubApi, http: OkHttpClient, private val dir: File) {

    /** kino.pub channel `name` → iptvx.one XMLTV channel id, in row order. */
    private val lineup = listOf(
        // kino.pub carries the European Eurosport feed: the Polish listing has the right times.
        "eurosport1" to "eurosport1-pl",
        "eurosport2" to "eurosport2-pl",
        "okkosport" to "okko-sport",
        "okkoprime" to "okko-sport-2",
        "okkofootball" to "okko-football",
        "okkochampionat" to "okko-chemp",
        "matchtv" to "match-tv",
        "nashfootball" to "match-premier", // kino.pub calls Матч Премьер "nashfootball"
        "matcharena" to "match-arena",
        "matchigra" to "match-igra",
        "football1" to "match-football1",
        "football2" to "match-football2",
        "football3" to "match-football3",
    )

    /**
     * Russian titles for feeds whose own listing isn't Russian. iptvx's Russian Eurosport listing
     * has the same programmes but shifted by a whole hour (and gaps), so it only lends titles:
     * see [withTitlesFrom].
     */
    private val titleSources = mapOf("eurosport1-pl" to "eurosport1", "eurosport2-pl" to "eurosport2")

    private val http = http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val epgFile = File(dir, "epg.json")
    private val snapDir = File(dir, "snaps").apply { mkdirs() }
    private val epgLock = Mutex()

    private val _state = MutableStateFlow(SportState(epg = loadEpg()?.progs ?: emptyMap(), snaps = existingSnaps()))
    val state: StateFlow<SportState> = _state

    /** Runs while Home is on screen: channels once, guide when stale, frames every few minutes. */
    suspend fun run() = coroutineScope {
        launch { attempt("epg") { ensureEpg() } }
        while (true) {
            if (_state.value.channels.isEmpty()) attempt("tv") { loadChannels() }
            for (ch in _state.value.channels) {
                // Coming back to Home doesn't re-fetch frames that are still fresh.
                val age = System.currentTimeMillis() - (_state.value.snaps[ch.name]?.let { File(it).lastModified() } ?: 0L)
                if (age < SNAP_EVERY_MS) continue
                val f = attempt("snap ${ch.name}") { snapshot(ch) }
                // A failed grab drops the old frame: better the logo than a picture from hours ago.
                _state.update { it.copy(snaps = if (f != null) it.snaps + (ch.name to f.path) else it.snaps - ch.name) }
            }
            delay(SNAP_EVERY_MS)
        }
    }

    /** Like runCatching, but leaving Home (cancellation) really stops the loop. */
    private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what: $e"); null
    }

    private suspend fun loadChannels() {
        val all = api.tvChannels().associateBy { it.name }
        val list = lineup.mapNotNull { (name, epgId) ->
            all[name]?.takeIf { it.stream.isNotBlank() }?.let { SportChannel(it.id, name, it.title, it.logos?.m ?: it.logos?.s, it.stream, epgId) }
        }
        Log.i(TAG, "sport: ${list.size} channels")
        _state.update { it.copy(channels = list) }
    }

    // ---------- program guide ----------

    private fun loadEpg(): EpgCache? = runCatching { json.decodeFromString(EpgCache.serializer(), epgFile.readText()) }.getOrNull()

    /** Re-download when older than 12 h or when the guide no longer reaches 3 h ahead. */
    private suspend fun ensureEpg() = epgLock.withLock {
        val now = System.currentTimeMillis()
        val cached = loadEpg()
        val reaches = cached?.progs?.values?.flatten()?.maxOfOrNull { it.stop } ?: 0L
        if (cached != null && cached.version == EPG_VERSION && now - cached.fetchedAt < 12 * HOUR && reaches > now + 3 * HOUR) return@withLock
        val wanted = lineup.map { it.second }.toSet() + titleSources.values
        val t0 = System.currentTimeMillis()
        val progs = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(EPG_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("EPG HTTP ${resp.code}")
                // ~15 MB gzip / ~170 MB XML: stream it, keep only our channels for the next 36 h.
                parseXmltv(GZIPInputStream(resp.body!!.byteStream(), 64 * 1024), wanted, now - 6 * HOUR, now + 36 * HOUR)
            }
        }
        val merged = progs.filterKeys { it !in titleSources.values }.mapValues { (id, list) ->
            titleSources[id]?.let { progs[it] }?.let { withTitlesFrom(list, it) } ?: list
        }
        val cache = EpgCache(now, merged, EPG_VERSION)
        epgFile.writeText(json.encodeToString(EpgCache.serializer(), cache))
        Log.i(TAG, "epg: ${progs.values.sumOf { it.size }} programmes for ${progs.size} channels in ${System.currentTimeMillis() - t0} ms")
        _state.update { it.copy(epg = merged) }
    }

    /**
     * Keep [timing]'s slots, take titles from [titles] after finding the whole shift between the
     * two listings (the one that lines up the most programme starts). No match → own title.
     */
    private fun withTitlesFrom(timing: List<Programme>, titles: List<Programme>): List<Programme> {
        val starts = timing.map { it.start }.toSet()
        val shift = (-6..6).map { it * HOUR / 2 }
            .map { off -> off to titles.count { it.start + off in starts } }
            .filter { it.second >= 2 }.maxByOrNull { it.second }?.first
            ?: return timing
        val byStart = titles.associateBy { it.start + shift }
        return timing.map { p -> byStart[p.start]?.let { p.copy(title = it.title) } ?: p }
    }

    private fun parseXmltv(input: InputStream, wanted: Set<String>, from: Long, to: Long): Map<String, List<Programme>> {
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val out = HashMap<String, MutableList<Programme>>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, "UTF-8")
        var ch: String? = null
        var start = 0L
        var stop = 0L
        var inTitle = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "programme" -> {
                        ch = p.getAttributeValue(null, "channel")?.takeIf { it in wanted }
                        if (ch != null) {
                            start = runCatching { fmt.parse(p.getAttributeValue(null, "start"))!!.time }.getOrDefault(0L)
                            stop = runCatching { fmt.parse(p.getAttributeValue(null, "stop"))!!.time }.getOrDefault(0L)
                            if (stop < from || start > to) ch = null
                        }
                    }
                    "title" -> inTitle = ch != null
                }
                XmlPullParser.TEXT -> if (inTitle) {
                    out.getOrPut(ch!!) { mutableListOf() } += Programme(start, stop, p.text.trim())
                    inTitle = false
                    ch = null // first title only
                }
                XmlPullParser.END_TAG -> if (p.name == "title") inTitle = false
            }
            ev = p.next()
        }
        return out.mapValues { (_, v) -> v.sortedBy { it.start } }
    }

    // ---------- still frames ----------

    private fun existingSnaps(): Map<String, String> =
        snapDir.listFiles().orEmpty().filter { it.extension == "jpg" }
            .groupBy { it.name.substringBefore('-') }
            .mapValues { (_, files) -> files.maxBy { it.lastModified() }.path }

    private fun get(url: String, range: Long? = null): okhttp3.Response =
        http.newCall(Request.Builder().url(url).apply { range?.let { header("Range", "bytes=0-${it - 1}") } }.build()).execute()

    private fun text(url: String): String = get(url).use { if (!it.isSuccessful) error("HTTP ${it.code}"); it.body!!.string() }

    private fun lastUri(playlist: String): String? = playlist.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("#") }

    /** Newest segment → first keyframe → JPEG. One file per channel; the name changes so Coil reloads. */
    private suspend fun snapshot(ch: SportChannel): File? = withContext(Dispatchers.IO) {
        val master = text(ch.stream)
        val mediaUrl = if (master.contains("#EXTINF")) ch.stream
        else ch.stream.toHttpUrl().resolve(lastUri(master) ?: return@withContext null).toString()
        val media = if (mediaUrl == ch.stream) master else text(mediaUrl)
        val segUrl = mediaUrl.toHttpUrl().resolve(lastUri(media) ?: return@withContext null).toString()
        val ts = File(snapDir, "seg.ts")
        get(segUrl, SNAP_BYTES).use { r ->
            if (!r.isSuccessful) error("segment HTTP ${r.code}")
            ts.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
        }
        val out = File(snapDir, "${ch.name}-${System.currentTimeMillis()}.jpg")
        val ok = runCatching { decodeFirstFrame(ts, out) }.onFailure { Log.w(TAG, "decode ${ch.name}: $it") }.getOrDefault(false)
        Log.i(TAG, "snap ${ch.name}: ${if (ok) "ok ${out.length() / 1024} KB" else "no frame"} (segment ${ts.length() / 1024} KB)")
        ts.delete()
        if (!ok) return@withContext null
        snapDir.listFiles().orEmpty().filter { it.name.startsWith("${ch.name}-") && it != out }.forEach { it.delete() }
        out
    }

    private fun decodeFirstFrame(ts: File, out: File): Boolean {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var inputs = 0
        var outputs = 0
        var why = "timeout"
        try {
            ex.setDataSource(ts.path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            // Android's TS extractor misses the video of interlaced streams (Eurosport 2 is 1080i):
            // then demux the H.264 ourselves, one PES = one access unit, SPS/PPS in-band.
            val pes = if (track == null) videoPes(ts.readBytes()) else null
            if (track == null && pes.isNullOrEmpty()) { why = "no video track of ${ex.trackCount}"; return false }
            if (track != null) ex.selectTrack(track)
            val format = if (track != null) ex.getTrackFormat(track) else MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
            var next = 0
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            // Software decoder first: the Chromecast's hardware one returns no image for
            // interlaced 1080i (Eurosport 2), and this way snapshots never touch the HW decoder.
            val sw = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { !it.isEncoder && it.isSoftwareOnly && it.supportedTypes.any { t -> t.equals(mime, true) } }
            codec = sw?.let { MediaCodec.createByCodecName(it.name) } ?: MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = if (pes == null) ex.readSampleData(buf, 0)
                        else pes.getOrNull(next)?.takeIf { it.size <= buf.capacity() }?.let { buf.put(it); it.size } ?: -1
                        val t = if (pes == null) ex.sampleTime else next * 40_000L
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, t, 0); if (pes == null) ex.advance() else next++; inputs++ }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    outputs++
                    if (info.size > 0) {
                        val img = codec.getOutputImage(o)
                        if (img == null) why = "image null"
                        if (img != null) {
                            img.use { writeJpeg(it, out) }
                            codec.releaseOutputBuffer(o, false)
                            return true
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { if (why == "timeout") why = "eos"; return false }
                }
            }
            return false
        } finally {
            if (outputs == 0 || why != "timeout") Log.i(TAG, "decode ${codec?.name}: in=$inputs out=$outputs $why")
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    /** Payloads of the H.264 PES packets in an MPEG-TS file (PAT → PMT → stream type 0x1B). */
    private fun videoPes(b: ByteArray): List<ByteArray> {
        var pmtPid = -1
        var videoPid = -1
        val out = mutableListOf<ByteArray>()
        var cur: java.io.ByteArrayOutputStream? = null
        var off = 0
        while (off + 188 <= b.size) {
            if (b[off] != 0x47.toByte()) { off++; continue }
            val pusi = b[off + 1].toInt() and 0x40 != 0
            val pid = ((b[off + 1].toInt() and 0x1f) shl 8) or (b[off + 2].toInt() and 0xff)
            val afc = (b[off + 3].toInt() shr 4) and 3
            var p = off + 4
            if (afc == 2 || afc == 3) p += 1 + (b[p].toInt() and 0xff)
            val end = off + 188
            if (afc != 2 && p < end) when {
                pid == 0 && pusi -> {
                    p += 1 + (b[p].toInt() and 0xff) // pointer field
                    pmtPid = ((b[p + 10].toInt() and 0x1f) shl 8) or (b[p + 11].toInt() and 0xff)
                }
                pid == pmtPid && pusi && videoPid < 0 -> {
                    p += 1 + (b[p].toInt() and 0xff)
                    val secLen = ((b[p + 1].toInt() and 0x0f) shl 8) or (b[p + 2].toInt() and 0xff)
                    val infoLen = ((b[p + 10].toInt() and 0x0f) shl 8) or (b[p + 11].toInt() and 0xff)
                    var q = p + 12 + infoLen
                    val secEnd = minOf(p + 3 + secLen - 4, end)
                    while (q + 5 <= secEnd) {
                        val type = b[q].toInt() and 0xff
                        val epid = ((b[q + 1].toInt() and 0x1f) shl 8) or (b[q + 2].toInt() and 0xff)
                        if (type == 0x1b) { videoPid = epid; break }
                        q += 5 + (((b[q + 3].toInt() and 0x0f) shl 8) or (b[q + 4].toInt() and 0xff))
                    }
                }
                pid == videoPid -> {
                    if (pusi) {
                        cur?.let { out += it.toByteArray() }
                        cur = java.io.ByteArrayOutputStream()
                        p += 9 + (b[p + 8].toInt() and 0xff) // PES header
                    }
                    if (p < end) cur?.write(b, p, end - p)
                }
            }
            off = end
        }
        // The last PES is cut off by the byte range: drop it.
        return out
    }

    /** YUV_420_888 (any strides) → NV21 → JPEG, shrunk ×2 to keep files small. */
    private fun writeJpeg(img: android.media.Image, out: File) {
        val crop = img.cropRect
        val w = crop.width() and 1.inv()
        val h = crop.height() and 1.inv()
        val nv21 = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]
        for (row in 0 until h) {
            val src = (row + crop.top) * y.rowStride + crop.left * y.pixelStride
            for (col in 0 until w) nv21[row * w + col] = y.buffer.get(src + col * y.pixelStride)
        }
        val u = img.planes[1]
        val v = img.planes[2]
        var o = w * h
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val ui = (row + crop.top / 2) * u.rowStride + (col + crop.left / 2) * u.pixelStride
                val vi = (row + crop.top / 2) * v.rowStride + (col + crop.left / 2) * v.pixelStride
                nv21[o++] = v.buffer.get(vi)
                nv21[o++] = u.buffer.get(ui)
            }
        }
        val full = java.io.ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 85, full)
        val bmp = android.graphics.BitmapFactory.decodeByteArray(
            full.toByteArray(), 0, full.size(), android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 },
        )
        out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, it) }
        bmp.recycle()
    }

    companion object {
        private const val TAG = "denopub"
        private const val HOUR = 3_600_000L
        private const val SNAP_EVERY_MS = 5 * 60_000L
        private const val SNAP_BYTES = 700_000L
        // Free XMLTV guide with every channel above (lite = no descriptions). See docs/kinopub-api.md.
        private const val EPG_VERSION = 2 // bump when the stored ids/merge change
        private const val EPG_URL = "https://iptvx.one/epg/epg_lite.xml.gz"
    }
}

data class SportState(
    val channels: List<SportChannel> = emptyList(),
    val epg: Map<String, List<Programme>> = emptyMap(),
    val snaps: Map<String, String> = emptyMap(),
) {
    fun now(ch: SportChannel, at: Long = System.currentTimeMillis()): Programme? =
        epg[ch.epgId]?.firstOrNull { at >= it.start && at < it.stop }
}

data class SportChannel(val id: Long, val name: String, val title: String, val logo: String?, val stream: String, val epgId: String)

@Serializable
data class Programme(val start: Long, val stop: Long, val title: String)

@Serializable
data class EpgCache(val fetchedAt: Long, val progs: Map<String, List<Programme>>, val version: Int = 1)
