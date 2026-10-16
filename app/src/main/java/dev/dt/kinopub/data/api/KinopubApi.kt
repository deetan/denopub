package dev.dt.kinopub.data.api

import dev.dt.kinopub.data.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class ApiException(val code: Int, message: String) : IOException(message)
class NotAuthorizedException : IOException("Not authorized")

/**
 * Thin hand-written client for api.service-kp.com. No Retrofit: the API is small and
 * hand-rolled calls keep token refresh and query params explicit.
 */
class KinopubApi(
    private val http: OkHttpClient,
    private val tokens: TokenStore,
) {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private val refreshLock = Mutex()

    // ---------- OAuth device flow ----------

    suspend fun requestDeviceCode(): DeviceCodeResponse = post(
        OAUTH_URL, mapOf("grant_type" to "device_code") + CLIENT, auth = false,
    )

    /** Returns tokens once the user confirmed the code, or null while still pending. */
    suspend fun pollDeviceToken(code: String): TokenResponse? {
        val r: TokenResponse = post(
            OAUTH_URL, mapOf("grant_type" to "device_token", "code" to code) + CLIENT,
            auth = false, allowErrorBody = true,
        )
        if (r.accessToken != null) {
            tokens.save(r.accessToken, r.refreshToken.orEmpty(), r.expiresIn)
            return r
        }
        if (r.error == "authorization_pending" || r.error == "slow_down") return null
        throw ApiException(400, r.error ?: "device_token failed")
    }

    private suspend fun refresh(): Boolean = refreshLock.withLock {
        val t = tokens.current() ?: return false
        if (!t.isExpired) return true
        val r: TokenResponse = post(
            TOKEN_URL,
            mapOf("grant_type" to "refresh_token", "refresh_token" to t.refresh) + CLIENT,
            auth = false, allowErrorBody = true,
        )
        if (r.accessToken == null) {
            tokens.clear()
            return false
        }
        tokens.save(r.accessToken, r.refreshToken ?: t.refresh, r.expiresIn)
        true
    }

    // ---------- Content ----------

    suspend fun item(id: Long): Item = get<ItemResponse>("items/$id").item
    /** Docs say `items/fresh`; some live clients see only `items/new`. Try both. */
    suspend fun fresh(type: String, page: Int = 1): ItemsResponse =
        runCatching { get<ItemsResponse>("items/fresh", "type" to type, "page" to page) }
            .getOrElse { get("items/new", "type" to type, "page" to page) }
    suspend fun hot(type: String): ItemsResponse = get("items/hot", "type" to type)
    suspend fun popular(type: String): ItemsResponse = get("items/popular", "type" to type)
    suspend fun search(q: String): ItemsResponse = get("items/search", "q" to q, "perpage" to 30)

    // ---------- Watching ----------

    /** `subscribed` splits serials into two disjoint lists; we want both. */
    suspend fun watchingSerials(): List<WatchingSerial> =
        get<WatchingSerialsResponse>("watching/serials", "subscribed" to 1).items +
            get<WatchingSerialsResponse>("watching/serials", "subscribed" to 0).items
    suspend fun watchingMovies(): List<WatchingMovie> =
        get<WatchingMoviesResponse>("watching/movies").items
    suspend fun watching(id: Long): WatchingInfo =
        get<WatchingInfoResponse>("watching", "id" to id).item
    suspend fun history(page: Int = 1): List<HistoryEntry> =
        get<HistoryResponse>("history", "page" to page, "perpage" to 50).history

    /** [video] is the episode/part number, [season] only for serials. */
    suspend fun markTime(id: Long, video: Int, timeSec: Int, season: Int? = null) {
        get<Unit>("watching/marktime", "id" to id, "video" to video, "time" to timeSec, "season" to season)
    }
    /** `status=1` makes it an idempotent "mark watched" instead of a toggle. */
    suspend fun markWatched(id: Long, video: Int, season: Int? = null) {
        get<Unit>("watching/toggle", "id" to id, "video" to video, "season" to season, "status" to 1)
    }

    // ---------- Bookmarks (wishlist lives in a folder) ----------

    suspend fun bookmarkFolders(): List<BookmarkFolder> =
        get<BookmarkFoldersResponse>("bookmarks").items
    suspend fun bookmarkItems(folder: Long, page: Int = 1): ItemsResponse =
        get("bookmarks/$folder", "page" to page)
    suspend fun createBookmarkFolder(title: String): BookmarkFolder? =
        post<BookmarkFolderCreated>(url("bookmarks/create"), mapOf("title" to title)).folder
    suspend fun addBookmark(item: Long, folder: Long) {
        post<Unit>(url("bookmarks/add"), mapOf("item" to "$item", "folder" to "$folder"))
    }

    // ---------- Device ----------

    suspend fun streamingTypes(): List<StreamingType> =
        get<StreamingTypesResponse>("references/streaming-type").items

    suspend fun deviceInfo(): DeviceInfo = get<DeviceInfoResponse>("device/info").device

    suspend fun notifyDevice(title: String, hardware: String, software: String) {
        post<Unit>(url("device/notify"), mapOf("title" to title, "hardware" to hardware, "software" to software))
    }

    /** Ask kinopub to serve HEVC / 4K / HDR files and multi-audio HLS to this device. */
    suspend fun saveDeviceSettings(deviceId: Long, settings: Map<String, String>) {
        post<Unit>(url("device/$deviceId/settings"), settings)
    }

    // ---------- plumbing ----------

    private fun url(path: String, vararg q: Pair<String, Any?>): HttpUrl =
        API_URL.toHttpUrl().newBuilder().addPathSegments(path).apply {
            q.forEach { (k, v) -> if (v != null) addQueryParameter(k, v.toString()) }
        }.build()

    private suspend inline fun <reified T> get(path: String, vararg q: Pair<String, Any?>): T =
        exec(Request.Builder().url(url(path, *q)).get(), auth = true, allowErrorBody = false)

    private suspend inline fun <reified T> post(
        url: Any, form: Map<String, String>, auth: Boolean = true, allowErrorBody: Boolean = false,
    ): T {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val httpUrl = if (url is HttpUrl) url else url.toString().toHttpUrl()
        return exec(Request.Builder().url(httpUrl).post(body), auth, allowErrorBody)
    }

    private suspend inline fun <reified T> exec(
        builder: Request.Builder, auth: Boolean, allowErrorBody: Boolean,
    ): T = withContext(Dispatchers.IO) {
        if (auth) {
            if (!refresh()) throw NotAuthorizedException()
            builder.header("Authorization", "Bearer ${tokens.current()!!.access}")
        }
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401) throw NotAuthorizedException()
            if (!resp.isSuccessful && !allowErrorBody) throw ApiException(resp.code, text.take(300))
            if (T::class == Unit::class) Unit as T else json.decodeFromString<T>(text)
        }
    }

    companion object {
        const val API_URL = "https://api.service-kp.com/v1/"
        const val OAUTH_URL = "https://api.service-kp.com/oauth2/device"
        const val TOKEN_URL = "https://api.service-kp.com/oauth2/token"

        // Public client credentials of the open-source Kodi plugin; kinopub lists the device
        // under this client. See docs/kinopub-api.md.
        private val CLIENT = mapOf(
            "client_id" to "xbmc",
            "client_secret" to "cgg3gtifu46urtfp2zp1nqtba0k2ezxh",
        )
    }
}
