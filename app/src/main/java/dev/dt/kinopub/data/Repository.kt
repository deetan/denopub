package dev.dt.kinopub.data

import dev.dt.kinopub.data.api.Item
import dev.dt.kinopub.data.api.KinopubApi
import dev.dt.kinopub.data.api.Video
import dev.dt.kinopub.data.api.WatchingInfo
import dev.dt.kinopub.data.prefs.LocalPrefs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Where to start playback: one tap on a home card goes straight here. */
data class ResumeTarget(
    val itemId: Long,
    val season: Int?,          // null for movies
    val episode: Int,          // episode number, or part number for movies
    val startSec: Int,
)

data class ContinueEntry(
    val itemId: Long,
    val title: String,
    val poster: String?,
    val isSerial: Boolean,
    val target: ResumeTarget,
    val durationSec: Int,
    val newEpisodes: Int,
    val remainingEpisodes: Int,
) {
    /** "S2·E5" for serials, null for movies. */
    val episodeLabel: String? get() = target.season?.let { "S$it·E${target.episode}" }
    val progress: Float get() = if (durationSec > 0) target.startSec.toFloat() / durationSec else 0f
}

data class WishlistEntry(val item: Item, val isNew: Boolean)

data class HomeData(
    val continueWatching: List<ContinueEntry>,
    val wishlist: List<WishlistEntry>,
    val freshMovies: List<Item>,
    val freshSerials: List<Item>,
    val hot: List<Item>,
) {
    val newInWishlist: Int get() = wishlist.count { it.isNew }
}

class Repository(
    private val api: KinopubApi,
    private val prefs: LocalPrefs,
) {
    private var wishlistFolderId: Long? = null

    suspend fun home(): HomeData = coroutineScope {
        val cont = async { runCatching { continueWatching() }.getOrDefault(emptyList()) }
        val wish = async { runCatching { wishlist() }.getOrDefault(emptyList()) }
        val fm = async { runCatching { api.fresh("movie").items }.getOrDefault(emptyList()) }
        val fs = async { runCatching { api.fresh("serial").items }.getOrDefault(emptyList()) }
        val hot = async { runCatching { api.hot("movie").items }.getOrDefault(emptyList()) }
        HomeData(cont.await(), wish.await(), fm.await(), fs.await(), hot.await())
    }

    /**
     * Everything in progress, most recently watched first. History gives the order and the
     * last-touched episode; /watching gives exact per-episode status to pick the resume point.
     */
    suspend fun continueWatching(limit: Int = 15): List<ContinueEntry> = coroutineScope {
        // One binge can fill a whole history page, so read two.
        val history = async { api.history(1) + runCatching { api.history(2) }.getOrDefault(emptyList()) }
        val serials = async { api.watchingSerials() }
        val movies = async { api.watchingMovies() }

        val newCounts = serials.await().associate { it.id to it.newEpisodes }
        val ordered = LinkedHashMap<Long, Item>()
        history.await().forEach { ordered.putIfAbsent(it.item.id, it.item) }
        // In-progress titles missing from the first history page still belong on the row.
        serials.await().forEach { s ->
            ordered.putIfAbsent(s.id, Item(id = s.id, title = s.title, type = s.type, posters = s.posters))
        }
        movies.await().forEach { m ->
            ordered.putIfAbsent(m.id, Item(id = m.id, title = m.title, type = m.type, posters = m.posters))
        }

        ordered.values.take(limit * 2).map { item ->
            async {
                runCatching {
                    val info = api.watching(item.id)
                    resumeEntry(item, info, newCounts[item.id] ?: 0)
                }.getOrNull()
            }
        }.awaitAll().filterNotNull().take(limit)
    }

    private fun resumeEntry(item: Item, info: WatchingInfo, newEpisodes: Int): ContinueEntry? {
        val poster = item.posters?.wide ?: item.posters?.big ?: item.posters?.medium
        if (info.seasons.isNotEmpty()) {
            val flat = info.seasons.sortedBy { it.number }.flatMap { s ->
                s.episodes.sortedBy { it.number }.map { s.number to it }
            }
            // First episode after the last one touched that is not finished.
            val lastTouched = flat.indexOfLast { (_, e) -> e.status >= 0 || e.time > 0 }
            val next = flat.drop(maxOf(lastTouched, 0)).firstOrNull { (_, e) -> e.status != 1 }
                ?: return null // fully watched, nothing new
            val (season, ep) = next
            val remaining = flat.count { (_, e) -> e.status != 1 }
            return ContinueEntry(
                item.id, item.title, poster, isSerial = true,
                target = ResumeTarget(item.id, season, ep.number, if (ep.status == 0) ep.time else 0),
                durationSec = ep.duration, newEpisodes = newEpisodes, remainingEpisodes = remaining,
            )
        }
        val part = info.videos.firstOrNull { it.status == 0 } ?: return null
        return ContinueEntry(
            item.id, item.title, poster, isSerial = false,
            target = ResumeTarget(item.id, null, part.number, part.time),
            durationSec = part.duration, newEpisodes = 0, remainingEpisodes = 0,
        )
    }

    // ---------- Wishlist = a kinopub bookmark folder ----------

    private suspend fun wishlistFolder(): Long {
        wishlistFolderId?.let { return it }
        val folders = api.bookmarkFolders()
        val found = folders.firstOrNull { it.title.trim().lowercase() in WISHLIST_NAMES }
        val id = found?.id ?: api.createBookmarkFolder(WISHLIST_TITLE)?.id
            ?: api.bookmarkFolders().first { it.title == WISHLIST_TITLE }.id
        wishlistFolderId = id
        return id
    }

    suspend fun wishlist(): List<WishlistEntry> {
        val items = api.bookmarkItems(wishlistFolder()).items
        val seen = prefs.seenWishlistIds()
        // Newest bookmarks first is what the API returns; keep it.
        return items.map { WishlistEntry(it, isNew = it.id !in seen) }
    }

    suspend fun markWishlistSeen(ids: Collection<Long>) = prefs.markWishlistSeen(ids)

    suspend fun addToWishlist(itemId: Long) = api.addBookmark(itemId, wishlistFolder())

    // ---------- Details / playback ----------

    suspend fun item(id: Long): Item = api.item(id)

    /** Resolves the media unit for a target within a full item. */
    fun video(item: Item, season: Int?, episode: Int): Video? =
        if (season == null) item.videos.firstOrNull { it.number == episode } ?: item.videos.firstOrNull()
        else item.seasons.firstOrNull { it.number == season }?.episodes?.firstOrNull { it.number == episode }

    /** The episode after (season, episode), crossing season boundaries. */
    fun nextOf(item: Item, season: Int?, episode: Int): Pair<Int?, Int>? {
        if (season == null) {
            return item.videos.firstOrNull { it.number == episode + 1 }?.let { null to it.number }
        }
        val flat = item.seasons.sortedBy { it.number }.flatMap { s -> s.episodes.sortedBy { it.number }.map { s.number to it.number } }
        val i = flat.indexOf(season to episode)
        return flat.getOrNull(i + 1)
    }

    suspend fun markTime(itemId: Long, season: Int?, episode: Int, timeSec: Int) =
        runCatching { api.markTime(itemId, episode, timeSec, season) }

    suspend fun markWatched(itemId: Long, season: Int?, episode: Int) =
        runCatching { api.markWatched(itemId, episode, season) }

    companion object {
        const val WISHLIST_TITLE = "Wishlist"
        private val WISHLIST_NAMES = setOf("wishlist", "хочу посмотреть", "посмотреть")
    }
}
