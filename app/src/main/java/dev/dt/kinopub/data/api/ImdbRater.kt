package dev.dt.kinopub.data.api

import dev.dt.kinopub.data.prefs.LocalPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface RateResult {
    data class Ok(val value: Int) : RateResult
    /** This TV has no (valid) device key yet: enter a pairing code from the movies site. */
    data object NeedPair : RateResult
    data class Failed(val message: String) : RateResult
}

/**
 * Ratings on the owner's IMDb account, through his movies service (den.tanaev.su/movies,
 * repo deetan/movies). The IMDb session cookie lives there, encrypted; the TV only holds a
 * per-device key, obtained once by claiming a pairing code ("Ratings & sync → Add another
 * device"). Reading the current rating needs no key: IMDb ratings of this profile are public.
 */
class ImdbRater(private val http: OkHttpClient, private val prefs: LocalPrefs) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    /** The owner's own rating of a title (1–10), or null when unrated / unknown. */
    suspend fun myRating(tconst: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val q = """{ title(id: "$tconst") { userRating(userId: "$USER") { value } } }"""
            val req = Request.Builder().url("https://caching.graphql.imdb.com/")
                .header("x-imdb-client-name", "imdb-web-next")
                .post(buildJsonObject { put("query", q) }.toString().toRequestBody(jsonType)).build()
            http.newCall(req).execute().use { r ->
                val t = json.parseToJsonElement(r.body!!.string()).jsonObject["data"]?.jsonObject?.get("title") as? JsonObject
                (t?.get("userRating") as? JsonObject)?.get("value")?.jsonPrimitive?.intOrNull
            }
        }.onFailure { android.util.Log.w("denopub", "rate: my rating of $tconst failed: $it") }.getOrNull()
    }

    suspend fun rate(tconst: String, value: Int): RateResult = withContext(Dispatchers.IO) {
        val key = prefs.rateKey() ?: return@withContext RateResult.NeedPair
        runCatching {
            val body = buildJsonObject { put("tconst", tconst); put("rating", value) }
            post("/api/rate", body, key).use { r ->
                val o = parse(r.body?.string())
                when {
                    r.isSuccessful -> RateResult.Ok(o?.get("rating")?.jsonPrimitive?.intOrNull ?: value)
                    o.code() == "DEVICE" -> { prefs.saveRateKey(null); RateResult.NeedPair }
                    else -> RateResult.Failed(o.error() ?: "HTTP ${r.code}")
                }
            }
        }.getOrElse { RateResult.Failed(it.message ?: "нет сети") }
    }

    /** Claim a pairing code; null on success, else the reason. */
    suspend fun pair(code: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            post("/api/rating/claim", buildJsonObject { put("code", code) }, null).use { r ->
                val o = parse(r.body?.string())
                val key = o?.get("deviceKey")?.jsonPrimitive?.contentOrNull
                if (r.isSuccessful && key != null) { prefs.saveRateKey(key); null } else o.error() ?: "HTTP ${r.code}"
            }
        }.getOrElse { it.message ?: "нет сети" }
    }

    private fun post(path: String, body: JsonObject, key: String?) = http.newCall(
        Request.Builder().url(BASE + path)
            .header("x-imdb-user", USER)
            .apply { if (key != null) header("x-rate-key", key) }
            .post(body.toString().toRequestBody(jsonType)).build(),
    ).execute()

    private fun parse(s: String?): JsonObject? = runCatching { json.parseToJsonElement(s!!).jsonObject }.getOrNull()
    private fun JsonObject?.code() = this?.get("code")?.jsonPrimitive?.contentOrNull
    private fun JsonObject?.error() = this?.get("error")?.jsonPrimitive?.contentOrNull

    companion object {
        const val BASE = "https://den.tanaev.su/movies"
        const val USER = "ur0218725" // the owner's public IMDb profile (deetan)

        fun tconst(imdbNumericId: Long) = "tt" + imdbNumericId.toString().padStart(7, '0')
    }
}
