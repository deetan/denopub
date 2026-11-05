package dev.dt.kinopub.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

enum class SegmentKind { RECAP, INTRO, CREDITS }

/** A skippable stretch. [endMs] = null means "until the end of the file". */
data class Segment(val kind: SegmentKind, val startMs: Long, val endMs: Long?)

/**
 * Intro / recap / credits times from two free, keyless, crowdsourced databases, both looked up
 * by the title's IMDb id + season/episode:
 *  - TheIntroDB  `api.theintrodb.org/v2/media` — arrays of {start_ms,end_ms}, null = file edge
 *  - IntroDB     `api.introdb.app/segments`    — one {start_ms,end_ms} per kind
 * kino.pub has no such markers. Russian titles are mostly missing from both.
 *
 * Conservative merge: when both answer for a kind they must overlap, else the kind is dropped
 * (they disagree on some titles, e.g. Fallout S1E3 intro 0:00–1:24 vs 7:05–7:14). A kind is
 * also dropped when it doesn't fit the episode length (another release's timeline).
 */
class SkipSegments(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<Segment>>()

    suspend fun forEpisode(imdbNumericId: Long, season: Int?, episode: Int?, durationSec: Int): List<Segment> =
        withContext(Dispatchers.IO) {
            val tt = "tt" + imdbNumericId.toString().padStart(7, '0')
            val key = "$tt/$season/$episode"
            cache[key]?.let { return@withContext it }
            val q = if (season != null && episode != null) "&season=$season&episode=$episode" else ""
            val (a, b) = coroutineScope {
                val a = async { fetch("https://api.theintrodb.org/v2/media?imdb_id=$tt$q")?.let(::fromTheIntroDb) }
                val b = async { fetch("https://api.introdb.app/segments?imdb_id=$tt$q")?.let(::fromIntroDb) }
                a.await() to b.await()
            }
            val durMs = durationSec * 1000L
            val merged = SegmentKind.entries.mapNotNull { k -> merge(k, a?.get(k).orEmpty(), b?.get(k).orEmpty()) }
                .filter { fits(it, durMs) }
            android.util.Log.i("denopub", "skip $key tidb=${a?.values?.flatten()} idb=${b?.values?.flatten()} -> $merged")
            // Only cache real answers: a network failure (both null) is retried next episode load.
            if (a != null || b != null) cache[key] = merged
            merged
        }

    private fun fetch(url: String): JsonObject? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            // 404 "media not found" is a real answer (nothing known), not a failure.
            if (r.code == 404) return@runCatching JsonObject(emptyMap())
            if (!r.isSuccessful) return@runCatching null
            json.parseToJsonElement(body).jsonObject
        }
    }.getOrNull()

    private fun fromTheIntroDb(o: JsonObject): Map<SegmentKind, List<Segment>> = mapOf(
        SegmentKind.RECAP to tidb(o["recap"], SegmentKind.RECAP),
        SegmentKind.INTRO to tidb(o["intro"], SegmentKind.INTRO),
        SegmentKind.CREDITS to tidb(o["credits"], SegmentKind.CREDITS),
    )

    private fun tidb(e: JsonElement?, k: SegmentKind): List<Segment> = (e as? JsonArray).orEmpty().mapNotNull {
        val s = it.jsonObject.ms("start_ms") ?: 0L
        val end = it.jsonObject.ms("end_ms")
        // {0,0} is a "none" placeholder (seen for Bluey recap/preview).
        if (end != null && end <= s) null else Segment(k, s, end)
    }

    private fun fromIntroDb(o: JsonObject): Map<SegmentKind, List<Segment>> = mapOf(
        SegmentKind.RECAP to idb(o["recap"], SegmentKind.RECAP),
        SegmentKind.INTRO to idb(o["intro"], SegmentKind.INTRO),
        SegmentKind.CREDITS to idb(o["outro"], SegmentKind.CREDITS),
    )

    private fun idb(e: JsonElement?, k: SegmentKind): List<Segment> {
        val o = e as? JsonObject ?: return emptyList()
        val s = o.ms("start_ms") ?: 0L
        val end = o.ms("end_ms") ?: return emptyList()
        return if (end <= s) emptyList() else listOf(Segment(k, s, end))
    }

    private fun JsonObject.ms(key: String): Long? {
        val p = (this[key] as? kotlinx.serialization.json.JsonPrimitive) ?: return null
        return p.longOrNull ?: p.doubleOrNull?.toLong()
    }

    private fun merge(k: SegmentKind, a: List<Segment>, b: List<Segment>): Segment? {
        fun overlaps(x: Segment, y: Segment) = x.startMs < (y.endMs ?: Long.MAX_VALUE) && y.startMs < (x.endMs ?: Long.MAX_VALUE)
        return when {
            a.isEmpty() && b.isEmpty() -> null
            a.isEmpty() -> b.singleOrNull()
            // TheIntroDB sometimes holds two different intros for one episode: unusable alone.
            b.isEmpty() -> a.singleOrNull()
            else -> {
                val y = b.first()
                val x = a.firstOrNull { overlaps(it, y) } ?: return null
                if (k == SegmentKind.CREDITS) {
                    // Offer it from the earlier start (it's only a button; the owner sees the
                    // screen) — Silo S1E3: 61:02 vs 61:48 of a 62:02 file, the later one left 14 s.
                    // End: the earlier known one (a post-credits scene may follow).
                    Segment(k, minOf(x.startMs, y.startMs), listOfNotNull(x.endMs, y.endMs).minOrNull())
                } else {
                    // Never skip past what both call the intro: land on the earlier end.
                    Segment(k, minOf(x.startMs, y.startMs), minOf(x.endMs ?: Long.MAX_VALUE, y.endMs ?: Long.MAX_VALUE).takeIf { it != Long.MAX_VALUE })
                }
            }
        }
    }

    /** Sanity against our own file: openings in the first third, credits in the last 30 % and ≤ 8 min. */
    private fun fits(s: Segment, durMs: Long): Boolean {
        if (durMs <= 0) return true
        return when (s.kind) {
            SegmentKind.RECAP, SegmentKind.INTRO -> s.endMs != null && s.endMs < durMs / 3
            SegmentKind.CREDITS -> s.startMs in (durMs * 7 / 10) until durMs && durMs - s.startMs <= 8 * 60_000L
        }
    }
}
