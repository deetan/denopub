package dev.dt.kinopub.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class ImdbVideo(val id: String, val name: String, val seconds: Int?, val thumbnail: String?, val url: String)
data class ImdbInfo(val plot: String?, val rating: Double?, val videos: List<ImdbVideo>)

/**
 * IMDb data via the GraphQL endpoint IMDb's own web app uses (no key). imdb.com pages are behind
 * an anti-bot wall (HTTP 202, empty body), this endpoint is not. Unofficial: may change.
 */
class ImdbClient(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ImdbInfo>()

    suspend fun info(imdbNumericId: Long): ImdbInfo? = withContext(Dispatchers.IO) {
        val tt = "tt" + imdbNumericId.toString().padStart(7, '0')
        cache[tt]?.let { return@withContext it }
        runCatching {
            val q = """{ title(id: "$tt") { plot { plotText { plainText } } ratingsSummary { aggregateRating }
                primaryVideos(first: 10) { edges { node { id name { value } runtime { value } contentType { displayName { value } }
                thumbnail { url } playbackURLs { url videoMimeType videoDefinition } } } } } }"""
            val body = buildJsonObject { put("query", q) }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("https://caching.graphql.imdb.com/")
                .header("x-imdb-client-name", "imdb-web-next").post(body).build()
            http.newCall(req).execute().use { r ->
                val t = json.parseToJsonElement(r.body!!.string()).jsonObject["data"]!!.jsonObject["title"]!!.jsonObject
                val plot = t.path("plot", "plotText", "plainText")
                val rating = t.path("ratingsSummary", "aggregateRating")?.toDoubleOrNull()
                val videos = (t["primaryVideos"]?.jsonObject?.get("edges") as? JsonArray).orEmpty().mapNotNull { e ->
                    val n = e.jsonObject["node"]?.jsonObject ?: return@mapNotNull null
                    val type = n.path("contentType", "displayName", "value").orEmpty()
                    val name = n.path("name", "value").orEmpty()
                    // Trailers and teasers only; clips/featurettes are noise here.
                    if (!(type.contains("Trailer", true) || name.contains("Trailer", true) || name.contains("Teaser", true))) return@mapNotNull null
                    val urls = (n["playbackURLs"] as? JsonArray).orEmpty().map { it.jsonObject }
                    fun pick(def: String) = urls.firstOrNull { it.path("videoDefinition") == def }?.path("url")
                    val url = pick("DEF_1080p") ?: pick("DEF_AUTO") ?: pick("DEF_720p") ?: urls.firstOrNull()?.path("url") ?: return@mapNotNull null
                    ImdbVideo(
                        id = n.path("id").orEmpty(), name = name,
                        seconds = n["runtime"]?.jsonObject?.get("value")?.jsonPrimitive?.intOrNull,
                        thumbnail = n.path("thumbnail", "url"), url = url,
                    )
                }
                ImdbInfo(plot, rating, videos).also { cache[tt] = it }
            }
        }.getOrNull()
    }

    private fun JsonObject.path(vararg keys: String): String? {
        var cur: JsonElement = this
        for (k in keys) cur = (cur as? JsonObject)?.get(k) ?: return null
        return if (cur is JsonNull) null else cur.jsonPrimitive.contentOrNull
    }
}
