package dev.dt.kinopub.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Kinopub API v1 DTOs. Only the fields the app uses; the Json instance ignores the rest.
// Reference: docs/kinopub-api.md

// ---------- OAuth device flow ----------

@Serializable
data class DeviceCodeResponse(
    val code: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    val interval: Int = 5,
    @SerialName("expires_in") val expiresIn: Int = 300,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0,
    val error: String? = null,
)

// ---------- Items ----------

@Serializable
data class Posters(
    val small: String? = null,
    val medium: String? = null,
    val big: String? = null,
    val wide: String? = null,
)

@Serializable
data class Named(val id: Int = 0, val title: String? = null)

@Serializable
data class Item(
    val id: Long,
    val title: String,
    val type: String? = null,           // movie | serial | docuserial | tvshow | concert | 3D | documovie
    val subtype: String? = null,
    val year: Int? = null,
    val plot: String? = null,
    val posters: Posters? = null,
    val imdb: Long? = null,                      // numeric IMDb id → "tt" + 7 digits
    @SerialName("imdb_rating") val imdbRating: Double? = null,
    @SerialName("kinopoisk_rating") val kinopoiskRating: Double? = null,
    val genres: List<Named> = emptyList(),
    val quality: Int? = null,           // max quality, e.g. 2160
    val duration: Duration? = null,
    val finished: Boolean? = null,
    @SerialName("new") val newEpisodes: Int? = null,
    @SerialName("in_watchlist") val inWatchlist: Boolean? = null, // serials: "Я смотрю"
    val subscribed: Boolean? = null,
    val videos: List<Video> = emptyList(),   // movies (and multi-part films)
    val seasons: List<Season> = emptyList(), // serials
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
) {
    val isSerial: Boolean get() = seasons.isNotEmpty() || type in SERIAL_TYPES

    // Kinopub's own "watched/total" convenience counters are not on Item; see WatchingSerial.
    companion object {
        val SERIAL_TYPES = setOf("serial", "docuserial", "tvshow")
    }
}

@Serializable
data class Duration(val average: Double? = null, val total: Double? = null)

@Serializable
data class Season(
    val id: Long? = null,
    val number: Int,
    val title: String? = null,
    val episodes: List<Video> = emptyList(),
    val watching: WatchState? = null,
)

/** A playable media unit: a movie part or a serial episode. */
@Serializable
data class Video(
    val id: Long,
    val number: Int = 1,
    val title: String? = null,
    val thumbnail: String? = null,
    val duration: Int = 0,              // seconds
    val watched: Int = 0,               // -1 not watched, 0 in progress, 1 watched (per API)
    val watching: WatchState? = null,
    val files: List<MediaFile> = emptyList(),
    val audios: List<AudioTrack> = emptyList(),
    val subtitles: List<Subtitle> = emptyList(),
)

@Serializable
data class WatchState(
    val status: Int = -1,               // -1 not started, 0 in progress, 1 watched
    val time: Int = 0,                  // seconds
)

@Serializable
data class MediaFile(
    val quality: String? = null,        // "2160p", "1080p", ...
    @SerialName("quality_id") val qualityId: Int? = null,
    val codec: String? = null,          // "h264" | "h265" (UNVERIFIED for HDR marking)
    val w: Int = 0,
    val h: Int = 0,
    val url: FileUrls = FileUrls(),
)

@Serializable
data class FileUrls(
    val http: String? = null,
    val hls: String? = null,
    val hls2: String? = null,
    val hls4: String? = null,
)

@Serializable
data class AudioTrack(
    val id: Long? = null,
    val index: Int = 0,
    val codec: String? = null,
    val channels: Int? = null,
    val lang: String? = null,
    val type: Named? = null,            // voiceover type, e.g. "Многоголосый"
    val author: Named? = null,          // studio, e.g. "LostFilm"
)

@Serializable
data class Subtitle(
    val lang: String,
    val shift: Int = 0,
    val embed: Boolean = false,
    val forced: Boolean = false,
    val url: String? = null,
)

@Serializable
data class ItemResponse(val item: Item)

@Serializable
data class ItemsResponse(
    val items: List<Item> = emptyList(),
    val pagination: Pagination? = null,
)

@Serializable
data class Pagination(
    val total: Int = 0,
    val current: Int = 1,
    val perpage: Int = 0,
    @SerialName("total_items") val totalItems: Int = 0,
)

// ---------- Watching ----------

/** Row of /v1/watching/serials: a serial with progress counters. */
@Serializable
data class WatchingSerial(
    val id: Long,
    val title: String,
    val type: String? = null,
    val posters: Posters? = null,
    val total: Int = 0,
    val watched: Int = 0,
    @SerialName("new") val newEpisodes: Int = 0,
    @kotlinx.serialization.Transient val subscribed: Boolean = false, // which list it came from
)

@Serializable
data class WatchingSerialsResponse(val items: List<WatchingSerial> = emptyList())

@Serializable
data class WatchingMovie(
    val id: Long,
    val title: String,
    val type: String? = null,
    val posters: Posters? = null,
)

@Serializable
data class WatchingMoviesResponse(val items: List<WatchingMovie> = emptyList())

/** /v1/watching?id= — per-media progress for one item. */
@Serializable
data class WatchingInfoResponse(val item: WatchingInfo)

@Serializable
data class WatchingInfo(
    val id: Long? = null,
    val videos: List<WatchingVideo> = emptyList(),
    val seasons: List<WatchingSeason> = emptyList(),
)

@Serializable
data class WatchingSeason(
    val id: Long? = null,
    val number: Int,
    val status: Int = -1,
    val episodes: List<WatchingVideo> = emptyList(),
)

@Serializable
data class WatchingVideo(
    val id: Long,
    val number: Int = 1,
    val title: String? = null,
    val time: Int = 0,
    val duration: Int = 0,
    val status: Int = -1,
)

// ---------- History ----------

@Serializable
data class HistoryResponse(val history: List<HistoryEntry> = emptyList())

@Serializable
data class HistoryEntry(
    val time: Int = 0,
    val counter: Int = 0,
    @SerialName("first_seen") val firstSeen: Long = 0,
    @SerialName("last_seen") val lastSeen: Long = 0,
    val item: Item,
    val media: HistoryMedia? = null,
)

@Serializable
data class HistoryMedia(
    val id: Long,
    val number: Int = 1,
    val snumber: Int = 0,
    val title: String? = null,
    val duration: Int = 0,
)

// ---------- Bookmarks ----------

@Serializable
data class BookmarkFolder(val id: Long, val title: String, val count: Int = 0)

@Serializable
data class BookmarkFoldersResponse(val items: List<BookmarkFolder> = emptyList())

@Serializable
data class BookmarkFolderCreated(val folder: BookmarkFolder? = null)

// ---------- Device ----------

@Serializable
data class DeviceInfoResponse(val device: DeviceInfo)

@Serializable
data class DeviceInfo(val id: Long, val title: String? = null)

@Serializable
data class StreamingType(val id: Int, val code: String? = null, val name: String? = null)

@Serializable
data class StreamingTypesResponse(val items: List<StreamingType> = emptyList())

@Serializable
data class ItemFoldersResponse(val folders: List<BookmarkFolder> = emptyList())

@Serializable
data class ToggleWatchlistResponse(val watching: kotlinx.serialization.json.JsonElement? = null)

@Serializable
data class ToggleWatchedResponse(val watched: Int? = null)
