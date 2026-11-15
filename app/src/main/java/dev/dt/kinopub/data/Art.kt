package dev.dt.kinopub.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder

/** One painting of the screensaver catalog (`assets/art/paintings.json`). */
@Serializable
data class Painting(
    val id: String,
    val titleRu: String,
    val titleEn: String = "",
    val date: String = "",
    val artistRu: String,
    val artistEn: String = "",
    val artistLife: String = "",
    val museumRu: String = "",
    val museumEn: String = "",
    val story: String = "",
    /** Wikimedia Commons file name (no "File:"), or null when [url] is used. */
    val commons: String? = null,
    val url: String? = null,
    val w: Int = 0,
    val h: Int = 0,
) {
    val aspect: Float get() = if (w > 0 && h > 0) w.toFloat() / h else 1.5f

    /** Very wide works (scrolls, panoramas) are panned at full screen height instead of fitted. */
    val panorama: Boolean get() = aspect > 2.3f

    /** Image URL sized for a 1080p TV. Commons serves standard thumbnail widths (1280/1920/3840). */
    fun imageUrl(): String? {
        commons?.let { name ->
            val width = when {
                panorama -> 3840
                aspect < 1f -> 1280      // portrait: 1280 wide is already ≥ 1080 tall
                else -> 1920
            }
            if (w in 1 until width) return "https://commons.wikimedia.org/wiki/Special:FilePath/${enc(name)}"
            return "https://commons.wikimedia.org/wiki/Special:FilePath/${enc(name)}?width=$width"
        }
        return url
    }

    private fun enc(s: String) = URLEncoder.encode(s.replace(' ', '_'), "UTF-8").replace("+", "%20")
}

/**
 * The screensaver catalog. Bundled in the APK (`assets/art/paintings.json`) and refreshed daily from the
 * same file on GitHub `main`, so new paintings arrive without an app release. Images are never stored in
 * the repo: they are loaded by URL (Wikimedia Commons, or a direct URL for works still under copyright).
 * Paintings are dealt from a shuffled deck so none repeats until all were shown.
 */
class ArtCatalog(private val context: Context, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = File(context.filesDir, "art.json")
    private val prefs = context.getSharedPreferences("art", Context.MODE_PRIVATE)

    suspend fun load(): List<Painting> = withContext(Dispatchers.IO) {
        val bundled = runCatching {
            context.assets.open("art/paintings.json").use { json.decodeFromString<List<Painting>>(it.reader().readText()) }
        }.getOrDefault(emptyList())
        val cached = runCatching { json.decodeFromString<List<Painting>>(cache.readText()) }.getOrNull()
        // A cached copy older than the APK's own list would hide paintings added in a release.
        val list = if (cached != null && cached.size >= bundled.size) cached else bundled
        list.filter { it.commons != null || it.url != null }
    }

    /** Pulls the latest catalog from GitHub if the cached one is older than a day. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 24 * 3600_000L) return@withContext
        runCatching {
            http.newCall(Request.Builder().url(REMOTE).header("User-Agent", USER_AGENT).build()).execute().use { r ->
                val body = r.body?.string()
                if (!r.isSuccessful || body == null) error("HTTP ${r.code}")
                val list = json.decodeFromString<List<Painting>>(body)
                if (list.isNotEmpty()) {
                    cache.writeText(body)
                    Log.i(TAG, "art: catalog refreshed, ${list.size} paintings")
                }
            }
        }.onFailure { Log.w(TAG, "art: catalog refresh failed: $it") }
    }

    /** Next painting from a persisted shuffled deck; reshuffles once every painting was shown. */
    @Synchronized
    fun deal(all: List<Painting>): Painting? {
        if (all.isEmpty()) return null
        val ids = all.map { it.id }.toSet()
        var deck = prefs.getString("deck", "")!!.split(',').filter { it in ids }.toMutableList()
        if (deck.isEmpty()) {
            val last = prefs.getString("last", null)
            deck = ids.shuffled().toMutableList()
            if (deck.size > 1 && deck.first() == last) deck.add(deck.removeAt(0))
        }
        val id = deck.removeAt(0)
        prefs.edit().putString("deck", deck.joinToString(",")).putString("last", id).apply()
        return all.first { it.id == id }
    }

    companion object {
        const val TAG = "denopub"
        const val REMOTE = "https://raw.githubusercontent.com/deetan/denopub/main/app/src/main/assets/art/paintings.json"
        /** Wikimedia asks for a descriptive User-Agent; generic ones get throttled. */
        const val USER_AGENT = "denopub/1.0 (Android TV screensaver; https://github.com/deetan/denopub) Mozilla/5.0"
    }
}
