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
@kotlinx.serialization.Serializable
data class ResumeTarget(
    val itemId: Long,
    val season: Int?,          // null for movies
    val episode: Int,          // episode number, or part number for movies
    val startSec: Int,
)

@kotlinx.serialization.Serializable
data class ContinueEntry(
    val itemId: Long,
    val title: String,
    val poster: String?,
    val isSerial: Boolean,
    val target: ResumeTarget,
    val durationSec: Int,
    val newEpisodes: Int,
    val remainingEpisodes: Int,
    val isKids: Boolean = false,
) {
    /** "S2·E5" for serials, null for movies. */
    val episodeLabel: String? get() = target.season?.let { "S$it·E${target.episode}" }
    val progress: Float get() = if (durationSec > 0) target.startSec.toFloat() / durationSec else 0f
}

@kotlinx.serialization.Serializable
data class WishlistEntry(val item: Item, val isNew: Boolean)

@kotlinx.serialization.Serializable
data class HomeData(
    val continueWatching: List<ContinueEntry>,   // all, most recent first
    val wishlist: List<WishlistEntry>,
    val freshMovies: List<Item>,
    val freshSerials: List<Item>,
    val hot: List<Item>,
) {
    val newInWishlist: Int get() = wishlist.count { it.isNew }
    val continueAdult: List<ContinueEntry> get() = continueWatching.filterNot { it.isKids }
    val continueKids: List<ContinueEntry> get() = continueWatching.filter { it.isKids }
}

/**
 * Kids row rule: animation and children's genres. "Семейный" alone is not enough — plenty of
 * family films are grown-up viewing — and "Аниме" is mostly not for kids.
 */
fun isKidsContent(item: Item): Boolean = item.genres.any { g ->
    val t = g.title.orEmpty().lowercase()
    t.startsWith("мульт") || t.startsWith("детск")
}

class Repository(
    private val api: KinopubApi,
    private val prefs: LocalPrefs,
    private val cacheDir: java.io.File,
) {
    private val cacheJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val homeCache get() = java.io.File(cacheDir, "home.json")

    /** Last Home shown, so launch can render instantly while the network catches up. */
    fun cachedHome(): HomeData? = runCatching {
        cacheJson.decodeFromString(HomeData.serializer(), homeCache.readText())
    }.getOrNull()

    private fun saveHome(d: HomeData) {
        runCatching { homeCache.writeText(cacheJson.encodeToString(HomeData.serializer(), d)) }
    }

    private var wishlistFolderId: Long? = null

    suspend fun home(): HomeData = coroutineScope {
        val cont = async { runCatching { continueWatching() }.getOrDefault(emptyList()) }
        val wish = async { runCatching { wishlist() }.getOrDefault(emptyList()) }
        val fm = async { runCatching { api.fresh("movie").items }.getOrDefault(emptyList()) }
        val fs = async { runCatching { api.fresh("serial").items }.getOrDefault(emptyList()) }
        val hot = async { runCatching { api.hot("movie").items }.getOrDefault(emptyList()) }
        HomeData(cont.await(), wish.await(), fm.await(), fs.await(), hot.await()).also(::saveHome)
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
        // Just started via "Начал смотреть" and newer than the last history entry: lead the row.
        // (Without this a fresh subscription sat after ~100 history titles and was cut off.)
        val lastSeenMs = (history.await().maxOfOrNull { it.lastSeen } ?: 0L) * 1000
        val started = prefs.startedAt().filter { (_, ts) -> System.currentTimeMillis() - ts < 14L * 24 * 3600 * 1000 }
        val allById = (serials.await().map { Item(id = it.id, title = it.title, type = it.type, posters = it.posters) } +
            movies.await().map { Item(id = it.id, title = it.title, type = it.type, posters = it.posters) }).associateBy { it.id }
        started.filter { (_, ts) -> ts > lastSeenMs }.entries.sortedByDescending { it.value }.forEach { (id, _) ->
            ordered.putIfAbsent(id, allById[id] ?: runCatching { api.item(id) }.getOrNull() ?: return@forEach)
        }
        // Subscribed serials with nothing watched yet were started explicitly too (e.g. before
        // startedAt existed): they also go before history, or they'd never make the cut.
        val historyIds = history.await().map { it.item.id }.toSet()
        serials.await().filter { it.subscribed && it.watched == 0 && it.id !in historyIds }.forEach { s ->
            ordered.putIfAbsent(s.id, Item(id = s.id, title = s.title, type = s.type, posters = s.posters))
        }
        history.await().forEach { ordered.putIfAbsent(it.item.id, it.item) }
        // Subscribed serials ("Я смотрю") belong on the row even without recent history.
        serials.await().filter { it.subscribed }.forEach { s ->
            ordered.putIfAbsent(s.id, Item(id = s.id, title = s.title, type = s.type, posters = s.posters))
        }
        movies.await().forEach { m ->
            ordered.putIfAbsent(m.id, Item(id = m.id, title = m.title, type = m.type, posters = m.posters))
        }

        // Kids/adult comes from a persistent cache only, so Home never waits on /items calls;
        // unknown titles are classified afterwards by classifyKids().
        val kids = prefs.kidsFlags()
        ordered.values.take(limit * 2).map { item ->
            async {
                runCatching {
                    val info = api.watching(item.id)
                    resumeEntry(item, info, newCounts[item.id] ?: 0)?.copy(isKids = kids[item.id] == true)
                }.getOrNull()
            }
        }.awaitAll().filterNotNull().take(limit)
    }

    /** Titles in [entries] not yet classified as kids/adult. */
    suspend fun unclassified(entries: List<ContinueEntry>): List<Long> {
        val known = prefs.kidsFlags().keys
        return entries.map { it.itemId }.filter { it !in known }
    }

    /** Fetches genres for [ids] and stores the verdict; returns ids that turned out to be kids'. */
    suspend fun classifyKids(ids: List<Long>): Set<Long> = coroutineScope {
        val flags = ids.map { id -> async { id to runCatching { isKidsContent(api.item(id)) }.getOrNull() } }
            .awaitAll().mapNotNull { (id, k) -> k?.let { id to it } }.toMap()
        prefs.saveKidsFlags(flags)
        flags.filterValues { it }.keys
    }

    private fun resumeEntry(item: Item, info: WatchingInfo, newEpisodes: Int): ContinueEntry? {
        val poster = item.posters?.wide ?: item.posters?.big ?: item.posters?.medium
        if (info.seasons.isNotEmpty()) {
            val flat = info.seasons.sortedBy { it.number }.flatMap { s ->
                s.episodes.sortedBy { it.number }.map { s.number to it }
            }
            // First episode after the last one touched that is not finished.
            // marktime stores time without touching status: "in progress" = time > 0, status != 1.
            val lastTouched = flat.indexOfLast { (_, e) -> e.status == 1 || e.time > 0 }
            val next = flat.drop(maxOf(lastTouched, 0)).firstOrNull { (_, e) -> e.status != 1 }
                ?: return null // fully watched, nothing new
            val (season, ep) = next
            val remaining = flat.count { (_, e) -> e.status != 1 }
            return ContinueEntry(
                item.id, item.title, poster, isSerial = true,
                target = ResumeTarget(item.id, season, ep.number, ep.time),
                durationSec = ep.duration, newEpisodes = newEpisodes, remainingEpisodes = remaining,
            )
        }
        val part = info.videos.firstOrNull { it.status != 1 && (it.status == 0 || it.time > 0) } ?: return null
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
        runCatching { api.setWatched(itemId, true, season, episode) }

    // ---------- User actions (all written to kino.pub) ----------

    /** One episode (serial) or one part (movie). */
    suspend fun setEpisodeWatched(itemId: Long, season: Int?, episode: Int, watched: Boolean) =
        api.setWatched(itemId, watched, season, episode)

    suspend fun setSeasonWatched(itemId: Long, season: Int, watched: Boolean) =
        api.setWatched(itemId, watched, season, null)

    /** Whole title: every season of a serial, every part of a movie. */
    suspend fun setItemWatched(item: Item, watched: Boolean) {
        if (item.seasons.isNotEmpty()) item.seasons.forEach { api.setWatched(item.id, watched, it.number, null) }
        else item.videos.forEach { api.setWatched(item.id, watched, null, it.number) }
    }

    suspend fun isInWishlist(itemId: Long): Boolean =
        api.itemFolders(itemId).any { it.id == wishlistFolder() }

    /** Returns the new state. */
    suspend fun setWishlisted(itemId: Long, on: Boolean): Boolean {
        val folder = wishlistFolder()
        if (on) api.addBookmark(itemId, folder) else api.removeBookmark(itemId, folder)
        return on
    }

    /**
     * "Начал смотреть": serials → kino.pub "Я смотрю" subscription (shows up in Continue);
     * movies → a 1-second progress mark, which puts the movie into watching/movies.
     */
    suspend fun setWatching(item: Item, on: Boolean): Boolean {
        prefs.markStarted(item.id, on)
        if (item.isSerial) {
            val now = item.inWatchlist == true
            return if (now != on) api.toggleWatchlist(item.id) else now
        }
        val part = item.videos.firstOrNull()?.number ?: 1
        if (on) api.markTime(item.id, part, 1) else api.clearHistory(item.id)
        return on
    }

    /** Drop from Continue on kino.pub: clear history and unsubscribe a serial. */
    suspend fun removeFromContinue(itemId: Long) {
        prefs.markStarted(itemId, false)
        api.clearHistory(itemId)
        runCatching {
            val item = api.item(itemId)
            if (item.isSerial && item.inWatchlist == true) api.toggleWatchlist(itemId)
        }
    }

    suspend fun search(query: String, type: String?): List<Item> =
        api.search(query).items.filter { type == null || matchesType(it, type) }

    private fun matchesType(item: Item, type: String) = when (type) {
        "serial" -> item.type in Item.SERIAL_TYPES
        "movie" -> item.type !in Item.SERIAL_TYPES
        else -> true
    }

    companion object {
        const val WISHLIST_TITLE = "Wishlist"
        private val WISHLIST_NAMES = setOf("wishlist", "хочу посмотреть", "посмотреть")
    }
}
