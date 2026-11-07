package dev.dt.kinopub.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The owner's personal recommendations, computed by his movies service (`src/recommend.js` in
 * deetan/movies) from his IMDb ratings: "more like this" from titles he rated high, plus people
 * he rates high (directors, writers, top-billed cast). Read-only and keyless (`x-imdb-user`).
 * Everything is IMDb-side (tconst, English titles); [dev.dt.kinopub.data.Recommender] maps it
 * onto kino.pub.
 */
class MoviesRecs(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** [kind] = "movie" | "series". The service caps [limit] at 400. */
    suspend fun list(kind: String, limit: Int = 400): ImdbRecs = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${ImdbRater.BASE}/api/recommendations?kind=$kind&limit=$limit")
            .header("x-imdb-user", ImdbRater.USER)
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "recommendations ${r.code}")
            json.decodeFromString(ImdbRecs.serializer(), r.body!!.string())
        }
    }

    /** The owner's latest IMDb ratings, newest first (the service caps [limit] at 100). */
    suspend fun recent(limit: Int = 100): List<ImdbRating> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${ImdbRater.BASE}/api/recent?limit=$limit")
            .header("x-imdb-user", ImdbRater.USER)
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "recent ${r.code}")
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ImdbRating.serializer()), r.body!!.string())
        }
    }

    /**
     * Asks the service to recompute (a ~1 min background job there, rate-limited to once per
     * 6 h; a 429 is fine). Called when its snapshot is older than a week, so new IMDb ratings
     * reach the TV.
     */
    suspend fun recompute() = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${ImdbRater.BASE}/api/recommendations/compute")
            .header("x-imdb-user", ImdbRater.USER)
            .post(ByteArray(0).toRequestBody())
            .build()
        runCatching { http.newCall(req).execute().use { it.code } }.getOrNull()
    }
}

@Serializable
data class ImdbRecs(
    val computedAt: String? = null,
    val computing: Boolean = false,
    val items: List<ImdbRec> = emptyList(),
)

@Serializable
data class ImdbRec(
    val tconst: String,
    val title: String = "",
    val score: Double = 0.0,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val reasons: List<ImdbRecReason> = emptyList(),
)

/** type = person (name, roles) | like (tconst, title, myRating) | genre (genre). */
@Serializable
data class ImdbRecReason(
    val type: String = "",
    val name: String? = null,
    val tconst: String? = null,
    val title: String? = null,
    val myRating: Int? = null,
    val genre: String? = null,
)

/** One of the owner's IMDb ratings; [ratedAt] is IMDb's own date (ISO). */
@Serializable
data class ImdbRating(
    val tconst: String,
    @kotlinx.serialization.SerialName("my_rating") val myRating: Int? = null,
    @kotlinx.serialization.SerialName("rated_at") val ratedAt: String? = null,
)
