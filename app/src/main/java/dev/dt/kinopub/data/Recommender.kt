package dev.dt.kinopub.data

import android.util.Log
import dev.dt.kinopub.data.api.ImdbRec
import dev.dt.kinopub.data.api.Item
import dev.dt.kinopub.data.api.KinopubApi
import dev.dt.kinopub.data.api.MoviesRecs
import dev.dt.kinopub.data.api.Posters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** One recommended kino.pub title and the one-line "why" shown under its card. */
@Serializable
data class RecItem(val item: Item, val reason: String? = null, val score: Double = 1.0)

/** A recommendation row: the whole candidate [pool]; what is on screen is a shuffle of it. */
@Serializable
data class RecRow(val key: String, val title: String, val pool: List<RecItem>)

@Serializable
private data class Mapped(val item: Item? = null, val at: Long = 0)

@Serializable
private data class RecsCache(
    val version: Int = 0,                            // rows built by older rules are rebuilt at once
    val sourceAt: String? = null,                    // movies service computedAt the rows came from
    val builtAt: Long = 0,
    val rows: List<RecRow> = emptyList(),
    val map: Map<String, Mapped> = emptyMap(),       // tconst → kino.pub item (null = not on kino.pub)
    val touched: Set<Long> = emptySet(),             // kino.pub ids already watched/started — never recommended
    val checked: Map<Long, Long> = emptyMap(),       // kino.pub id → when /watching said "untouched"
    val dismissed: Set<Long> = emptySet(),           // kino.pub ids removed with «Убрать из рекомендаций»
)

data class RecsState(
    val rows: List<RecRow> = emptyList(),
    /** Per row: how many times its ↻ was pressed (part of the shuffle seed). */
    val rowShuffle: Map<String, Int> = emptyMap(),
    /** Per row: titles already shown there since its pool last ran out — ↻ brings others. */
    val seen: Map<String, Set<Long>> = emptyMap(),
    val building: Boolean = false,
    /** Removed by the user. Cards already on screen stay (badged) until that row's ↻ / a rebuild. */
    val dismissed: Set<Long> = emptySet(),
) {
    /**
     * Rows as shown: a weighted shuffle of each pool (better matches more likely), new every day
     * and on that row's ↻. A title belongs to the first row whose pool has it, so rows never
     * repeat each other and refreshing one row leaves the others as they are.
     */
    fun shown(perRow: Int = PER_ROW): List<Pair<RecRow, List<RecItem>>> {
        val owner = HashMap<Long, String>()
        rows.forEach { r -> r.pool.forEach { owner.putIfAbsent(it.item.id, r.key) } }
        return rows.map { row -> row to pick(row, owner, perRow) }.filter { it.second.isNotEmpty() }
    }

    private fun own(row: RecRow, owner: Map<Long, String>) =
        row.pool.filter { owner[it.item.id] == row.key && it.item.id !in dismissed }

    private fun pick(row: RecRow, owner: Map<Long, String>, perRow: Int): List<RecItem> {
        val day = System.currentTimeMillis() / 86_400_000L
        val rnd = Random(day * 1_000_003 + (rowShuffle[row.key] ?: 0) * 7_919L + row.key.hashCode())
        val mine = own(row, owner)
        val seenHere = seen[row.key].orEmpty()
        val fresh = mine.filter { it.item.id !in seenHere }
        val from = if (fresh.size >= minOf(perRow, mine.size)) fresh else mine
        val max = from.maxOfOrNull { it.score }?.takeIf { it > 0 } ?: 1.0
        // Efraimidis–Spirakis: key = u^(1/w). √ flattens the scores so the tail gets a chance.
        return from.map { it to Math.pow(rnd.nextDouble(), 1.0 / Math.sqrt((it.score / max).coerceAtLeast(0.01))) }
            .sortedByDescending { it.second }
            .take(perRow).map { it.first }
    }

    /** State after ↻ on [key]: what is on screen there now counts as seen; reset once the pool runs dry. */
    fun refreshed(key: String, perRow: Int = PER_ROW): RecsState {
        val row = rows.firstOrNull { it.key == key } ?: return this
        val owner = HashMap<Long, String>().also { o -> rows.forEach { r -> r.pool.forEach { o.putIfAbsent(it.item.id, r.key) } } }
        val now = pick(row, owner, perRow).map { it.item.id }.toSet()
        val mine = own(row, owner).map { it.item.id }.toSet()
        val seenNext = (seen[key].orEmpty() + now).let { if ((mine - it).size < minOf(perRow, mine.size)) now else it }
        return copy(rowShuffle = rowShuffle + (key to (rowShuffle[key] ?: 0) + 1), seen = seen + (key to seenNext))
    }

    companion object { const val PER_ROW = 20 }
}

/**
 * Personal recommendation rows for Home. Sources:
 *  - the owner's movies service ([MoviesRecs]): titles scored from his IMDb ratings, with reasons;
 *  - kino.pub `items/similar` for the last titles watched here;
 *  - his own 9–10★ IMDb ratings (the "liked" seeds behind those recommendations) for re-watching.
 * IMDb titles are mapped to kino.pub by searching the English title and matching the IMDb id;
 * the mapping is cached for good. Titles already watched or started on kino.pub are dropped.
 * Rebuilt at most every [MAX_AGE_MS] in the background; the shuffle on screen changes daily.
 */
class Recommender(
    private val api: KinopubApi,
    private val recs: MoviesRecs,
    dir: File,
    private val scope: CoroutineScope,
) {
    private val file = File(dir, "recs.json")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val lock = Mutex()
    private var cache: RecsCache = runCatching { json.decodeFromString(RecsCache.serializer(), file.readText()) }.getOrDefault(RecsCache())

    private val _state = MutableStateFlow(RecsState(rows = cache.rows, dismissed = cache.dismissed))
    val state: StateFlow<RecsState> = _state

    /** Rebuild in the background if the rows are missing or older than [MAX_AGE_MS]. */
    /**
     * Rebuild in the background if the rows are missing, built by older rules, older than
     * [MAX_AGE_MS] — or the movies service recomputed since (after a rating, a sync that found
     * new ratings, or its nightly pass): one tiny request tells.
     */
    fun ensure(force: Boolean = false) {
        if (_state.value.building) return
        val fresh = !force && cache.rows.isNotEmpty() && cache.version == VERSION &&
            System.currentTimeMillis() - cache.builtAt < MAX_AGE_MS
        scope.launch {
            if (fresh) {
                val src = runCatching { recs.list("movie", 1).computedAt }.getOrNull()
                if (src == null || src == cache.sourceAt) return@launch
                Log.i(TAG, "recs: service recomputed ($src, rows from ${cache.sourceAt}), rebuilding")
            }
            lock.withLock {
                _state.update { it.copy(building = true) }
                try {
                    build()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "recs: build failed: ${e.message}")
                } finally {
                    _state.update { it.copy(building = false, rows = cache.rows) }
                }
            }
        }
    }

    /**
     * «Убрать из рекомендаций» ([on]) or «Вернуть» (!on): hidden here at once, and sent to the movies
     * service, which stops recommending it and trusts its reasons less (then recomputes, and the
     * TV rebuilds when it sees the new snapshot). Titles without an IMDb id are only hidden here.
     */
    fun dismiss(item: Item, on: Boolean = true) {
        val ids = if (on) cache.dismissed + item.id else cache.dismissed - item.id
        cache = cache.copy(dismissed = ids)
        _state.update { it.copy(dismissed = ids) }
        scope.launch {
            lock.withLock { runCatching { file.writeText(json.encodeToString(RecsCache.serializer(), cache)) } }
            val imdb = item.imdb ?: return@launch
            runCatching { recs.dismiss(dev.dt.kinopub.data.api.ImdbRater.tconst(imdb), on) }
                .onSuccess { Log.i(TAG, "recs: ${if (on) "dismissed" else "restored"} ${item.id} ${item.title.take(30)}") }
                .onFailure { Log.w(TAG, "recs: dismiss ${item.id} failed: ${it.message}") }
        }
    }

    /**
     * A row's ↻: other titles from that row's pool at once (not the ones just shown); also a
     * background rebuild when the data is older than a few hours.
     */
    fun refreshRow(key: String) {
        _state.update { it.refreshed(key) }
        if (System.currentTimeMillis() - cache.builtAt > RESHUFFLE_REBUILD_MS) ensure(force = true)
    }

    private suspend fun build() = coroutineScope {
        val t0 = System.currentTimeMillis()
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val moviesD = async { recs.list("movie") }
        val seriesD = async { runCatching { recs.list("series", 400) }.getOrNull() }
        val historyD = async { runCatching { api.history(1) + api.history(2) }.getOrDefault(emptyList()) }
        val recentD = async { runCatching { recs.recent() }.getOrDefault(emptyList()) }
        val movies = moviesD.await()
        val series = seriesD.await()
        val history = historyD.await()
        val age = runCatching { t0 - java.time.Instant.parse(movies.computedAt).toEpochMilli() }.getOrDefault(0L)
        if (age > RECOMPUTE_AFTER_MS && !movies.computing) {
            Log.i(TAG, "recs: movies service snapshot is ${age / 86_400_000} days old, asking to recompute")
            launch { recs.recompute() }
        }
        val m = movies.items
        val s = series?.items.orEmpty()
        Log.i(TAG, "recs: service ${m.size} movies, ${s.size} series (computed ${movies.computedAt}); history ${history.size}")

        // ---- candidate pools on the IMDb side ----
        val fresh = (m + s).filter { (it.year ?: 0) >= year - 3 }.sortedByDescending { it.score }.take(POOL)
        val kids = m.filter(::kidsOk).take(POOL)
        val couple = m.filter(::coupleOk)
            .sortedByDescending { it.score }.take(POOL)
        // The titles he rated 9–10 that drive his recommendations: favourites worth seeing again.
        data class Fav(val tconst: String, val title: String, val my: Int, var hits: Int)
        val favs = LinkedHashMap<String, Fav>()
        (m + s).flatMap { it.reasons }.filter { it.type == "like" && (it.myRating ?: 0) >= 9 && it.tconst != null }.forEach { r ->
            favs.getOrPut(r.tconst!!) { Fav(r.tconst, r.title.orEmpty(), r.myRating!!, 0) }.hits++
        }
        // Not what he rated (= watched) in the last 3 years: a favourite seen recently isn't a rewatch.
        val recentlyRated = recentD.await().filter { r ->
            runCatching { t0 - java.time.Instant.parse(r.ratedAt).toEpochMilli() < REWATCH_GAP_MS }.getOrDefault(false)
        }.map { it.tconst }.toSet()
        val rewatch = favs.values.filter { it.tconst !in recentlyRated }.sortedByDescending { it.hits * it.my }.take(POOL)

        // ---- IMDb → kino.pub ----
        val map = ConcurrentHashMap(cache.map)
        val gate = Semaphore(2) // kino.pub answers 429 "Rate limit exceeded" to bursts of searches
        val failedN = java.util.concurrent.atomic.AtomicInteger()
        /** One kino.pub call through [gate], with 4/8/12 s back-off on 429. Null (counted) on failure. */
        suspend fun <T> kpCall(label: String, block: suspend () -> T): T? {
            for (attempt in 0..3) {
                val r = gate.withPermit { runCatching { block() } }
                val e = r.exceptionOrNull() ?: return r.getOrNull()
                if (e is kotlinx.coroutines.CancellationException) throw e
                if ((e as? dev.dt.kinopub.data.api.ApiException)?.code != 429 || attempt == 3) {
                    Log.w(TAG, "recs: $label failed: ${e.message?.take(160)}"); break
                }
                kotlinx.coroutines.delay(4_000L * (attempt + 1)) // docs: wait a few seconds after a 429
            }
            failedN.incrementAndGet()
            return null
        }
        suspend fun kp(tconst: String, title: String, y: Int?): Item? {
            map[tconst]?.let { if (it.item != null || t0 - it.at < REMAP_MISS_MS) return it.item }
            val num = tconst.removePrefix("tt").toLongOrNull() ?: return null
            val found = kpCall("search '$title'") { api.search(title).items } ?: return null
            val hit = found.firstOrNull { it.imdb == num && !it.type.equals("3D", true) }
                ?: found.firstOrNull { it.imdb == null && it.year == y && original(it.title).equals(title, true) }
            map[tconst] = Mapped(hit?.lean(), t0)
            return hit?.lean()
        }
        suspend fun mapRecs(list: List<ImdbRec>, reason: (ImdbRec) -> String?) =
            list.map { r -> async { kp(r.tconst, r.title, r.year)?.let { RecItem(it, reason(r), r.score) } } }.awaitAll().filterNotNull()

        val freshD = async { mapRecs(fresh) { listOfNotNull(it.year?.toString(), why(it)).joinToString(" · ") } }
        val kidsD = async { mapRecs(kids) { listOfNotNull(why(it), it.year?.toString()).joinToString(" · ") } }
        val coupleD = async { mapRecs(couple) { listOfNotNull(why(it), it.year?.toString()).joinToString(" · ") } }
        val rewatchD = async {
            // Films only (nobody rewatches Game of Thrones), at least 5 years old, not in recent
            // kino.pub history.
            val recentIds = history.map { it.item.id }.toSet()
            rewatch.map { f -> async { kp(f.tconst, f.title, null)?.let { RecItem(it, listOfNotNull("ваша ★${f.my}", it.year?.toString()).joinToString(" · "), (f.hits * f.my).toDouble()) } } }
                .awaitAll().filterNotNull()
                .filter { it.item.type !in Item.SERIAL_TYPES && (it.item.year ?: 0) in 1..(year - 5) && it.item.id !in recentIds }
        }
        // kino.pub's "similar" for the last few titles watched here (newest first, interleaved).
        val seeds = history.map { it.item }.filterNot { it.type.equals("3D", true) }.distinctBy { it.id }.take(SIMILAR_SEEDS)
        val similarD = async {
            seeds.map { seed ->
                async {
                    val name = seed.title.substringBefore(" / ").trim()
                    (kpCall("similar ${seed.id}") { api.similar(seed.id) } ?: emptyList())
                        .filter { (it.imdbRating ?: 7.0) >= 6.5 && !it.type.equals("3D", true) }
                        .take(12)
                        .mapIndexed { i, it -> RecItem(it.lean(), "как «$name»", 1.0 / (1 + i)) }
                }
            }.awaitAll().flatten().distinctBy { it.item.id }.filter { it.item.id !in seeds.map(Item::id) }
        }

        // ---- drop what was already watched or started on kino.pub ----
        val pools = listOf(freshD.await(), similarD.await(), kidsD.await(), coupleD.await())
        val touched = HashSet(cache.touched).apply { addAll(history.map { it.item.id }) }
        val checked = ConcurrentHashMap(cache.checked)
        val newlyTouched = ConcurrentHashMap.newKeySet<Long>()
        pools.flatten().map { it.item.id }.distinct()
            .filter { it !in touched && t0 - (checked[it] ?: 0L) > RECHECK_MS }
            .map { id ->
                async {
                    val info = kpCall("watching $id") { api.watching(id) } ?: return@async
                    val all = info.videos + info.seasons.flatMap { it.episodes }
                    if (all.any { it.status == 1 || it.time > 0 }) newlyTouched += id else checked[id] = t0
                }
            }.awaitAll()
        touched += newlyTouched
        fun clean(p: List<RecItem>) = p.filter { it.item.id !in touched }.distinctBy { it.item.id }
        val (freshP, similarP, kidsP, coupleP) = pools.map(::clean)

        val rows = listOf(
            RecRow("fresh", "Новинки для вас · ${year - 3}–$year", freshP),
            RecRow("similar", "Похоже на недавнее", similarP),
            RecRow("kids", "Смотреть с ребёнком", kidsP),
            RecRow("couple", "Вечер вдвоём", coupleP),
            RecRow("rewatch", "Пересмотреть любимое", rewatchD.await().distinctBy { it.item.id }),
        ).filter { it.pool.isNotEmpty() }
        rows.forEach { r -> Log.i(TAG, "recs: ${r.key} ${r.pool.size}: " + r.pool.take(8).joinToString { it.item.title.substringBefore(" / ").take(18) }) }
        Log.i(TAG, "recs: built in ${System.currentTimeMillis() - t0} ms; mapped ${map.count { it.value.item != null }}/${map.size}, touched ${touched.size}")

        // An empty build (network down) never replaces good rows; lookups that failed (rate limit)
        // are retried by an earlier rebuild, in an hour instead of 12.
        val failed = failedN.get()
        if (failed > 0) Log.w(TAG, "recs: $failed lookups failed, rebuilding in 1 h")
        cache = RecsCache(
            version = VERSION,
            sourceAt = if (rows.isEmpty()) cache.sourceAt else movies.computedAt,
            builtAt = when {
                rows.isEmpty() -> cache.builtAt
                failed > 0 -> t0 - MAX_AGE_MS + 3_600_000
                else -> t0
            },
            rows = rows.ifEmpty { cache.rows },
            map = map, touched = touched, checked = checked, dismissed = cache.dismissed,
        )
        runCatching { file.writeText(json.encodeToString(RecsCache.serializer(), cache)) }
    }

    private companion object {
        const val TAG = "denopub"
        const val POOL = 200
        /** Bump when the row rules change, so the TV rebuilds instead of waiting up to 12 h. */
        const val VERSION = 6
        const val REWATCH_GAP_MS = 3L * 365 * 86_400_000
        const val SIMILAR_SEEDS = 4
        const val MAX_AGE_MS = 12L * 3600_000
        const val RESHUFFLE_REBUILD_MS = 3L * 3600_000
        const val RECOMPUTE_AFTER_MS = 7L * 86_400_000
        const val REMAP_MISS_MS = 7L * 86_400_000
        const val RECHECK_MS = 3L * 86_400_000

        private val KIDS_BAD = setOf("Horror", "Thriller", "Crime", "War")
        private val COUPLE_GOOD = setOf("Romance", "Comedy")
        private val COUPLE_BAD = setOf("Animation", "Family", "Horror", "Documentary", "War")

        /**
         * Family or animation, or a fantasy adventure without action (Life of Pi), and nothing
         * dark. "No action" keeps out Spider-Man, Star Wars and Everything Everywhere All at Once.
         */
        fun kidsOk(r: ImdbRec) = r.genres.none { it in KIDS_BAD } &&
            ("Family" in r.genres || "Animation" in r.genres ||
                ("Adventure" in r.genres && "Fantasy" in r.genres && "Action" !in r.genres))

        fun coupleOk(r: ImdbRec) = r.genres.any { it in COUPLE_GOOD } && r.genres.none { it in COUPLE_BAD }

        /** The strongest reason: a person he rates high, or a title he loved. */
        fun why(r: ImdbRec): String? = r.reasons.firstNotNullOfOrNull { x ->
            when (x.type) {
                "person" -> x.name
                "like" -> x.title?.let { "как $it" }
                else -> null
            }
        }

        /** "Русское / Original" → "Original" (the part an English title should match). */
        fun original(title: String) = title.split(" / ").getOrNull(1)?.trim() ?: title.trim()

        fun Item.lean() = Item(
            id = id, title = title, type = type, year = year, imdb = imdb, imdbRating = imdbRating,
            genres = genres, posters = posters?.let { Posters(medium = it.medium, big = it.big) },
        )
    }
}
