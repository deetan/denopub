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
    val builtAt: Long = 0,
    val rows: List<RecRow> = emptyList(),
    val map: Map<String, Mapped> = emptyMap(),       // tconst → kino.pub item (null = not on kino.pub)
    val touched: Set<Long> = emptySet(),             // kino.pub ids already watched/started — never recommended
    val checked: Map<Long, Long> = emptyMap(),       // kino.pub id → when /watching said "untouched"
)

data class RecsState(
    val rows: List<RecRow> = emptyList(),
    val shuffle: Int = 0,
    val building: Boolean = false,
) {
    /**
     * Rows as shown: a weighted shuffle of each pool (better matches more likely), new every day
     * and on every ↻. A title appears in one row only, the first that picks it.
     */
    fun shown(perRow: Int = PER_ROW): List<Pair<RecRow, List<RecItem>>> {
        val used = HashSet<Long>()
        val day = System.currentTimeMillis() / 86_400_000L
        return rows.map { row ->
            val rnd = Random(day * 1_000_003 + shuffle * 7_919L + row.key.hashCode())
            val max = row.pool.maxOfOrNull { it.score }?.takeIf { it > 0 } ?: 1.0
            // Efraimidis–Spirakis: key = u^(1/w). √ flattens the scores so the tail gets a chance.
            val picked = row.pool.filter { it.item.id !in used }
                .map { it to Math.pow(rnd.nextDouble(), 1.0 / Math.sqrt((it.score / max).coerceAtLeast(0.01))) }
                .sortedByDescending { it.second }
                .take(perRow).map { it.first }
            used += picked.map { it.item.id }
            row to picked
        }.filter { it.second.isNotEmpty() }
    }

    companion object { const val PER_ROW = 14 }
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

    private val _state = MutableStateFlow(RecsState(rows = cache.rows))
    val state: StateFlow<RecsState> = _state

    /** Rebuild in the background if the rows are missing or older than [MAX_AGE_MS]. */
    fun ensure(force: Boolean = false) {
        if (_state.value.building) return
        if (!force && cache.rows.isNotEmpty() && cache.version == VERSION && System.currentTimeMillis() - cache.builtAt < MAX_AGE_MS) return
        scope.launch {
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

    /** ↻: a new shuffle at once; also a rebuild when the data is older than a few hours. */
    fun reshuffle() {
        _state.update { it.copy(shuffle = it.shuffle + 1) }
        if (System.currentTimeMillis() - cache.builtAt > RESHUFFLE_REBUILD_MS) ensure(force = true)
    }

    private suspend fun build() = coroutineScope {
        val t0 = System.currentTimeMillis()
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val moviesD = async { recs.list("movie") }
        val seriesD = async { runCatching { recs.list("series", 200) }.getOrNull() }
        val historyD = async { runCatching { api.history(1) + api.history(2) }.getOrDefault(emptyList()) }
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
        val rewatch = favs.values.sortedByDescending { it.hits * it.my }.take(POOL)

        // ---- IMDb → kino.pub ----
        val map = ConcurrentHashMap(cache.map)
        val gate = Semaphore(2) // kino.pub answers 429 "Rate limit exceeded" to bursts of searches
        val failedN = java.util.concurrent.atomic.AtomicInteger()
        suspend fun kp(tconst: String, title: String, y: Int?): Item? {
            map[tconst]?.let { if (it.item != null || t0 - it.at < REMAP_MISS_MS) return it.item }
            val num = tconst.removePrefix("tt").toLongOrNull() ?: return null
            var found: List<Item>? = null
            for (attempt in 0..3) {
                val r = gate.withPermit { runCatching { api.search(title).items } }
                found = r.getOrNull()
                val e = r.exceptionOrNull() ?: break
                if (e is kotlinx.coroutines.CancellationException) throw e
                if ((e as? dev.dt.kinopub.data.api.ApiException)?.code != 429 || attempt == 3) {
                    Log.w(TAG, "recs: search '$title' failed: ${e.message?.take(160)}"); break
                }
                kotlinx.coroutines.delay(4_000L * (attempt + 1)) // docs: wait a few seconds after a 429
            }
            if (found == null) { failedN.incrementAndGet(); return null }
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
            rewatch.map { f -> async { kp(f.tconst, f.title, null)?.let { RecItem(it, "ваша ★${f.my}", (f.hits * f.my).toDouble()) } } }
                .awaitAll().filterNotNull()
        }
        // kino.pub's "similar" for the last few titles watched here (newest first, interleaved).
        val seeds = history.map { it.item }.filterNot { it.type.equals("3D", true) }.distinctBy { it.id }.take(SIMILAR_SEEDS)
        val similarD = async {
            seeds.map { seed ->
                async {
                    val name = seed.title.substringBefore(" / ").trim()
                    gate.withPermit { runCatching { api.similar(seed.id) }.getOrDefault(emptyList()) }
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
                    val info = gate.withPermit { runCatching { api.watching(id) }.getOrNull() } ?: return@async
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
            builtAt = when {
                rows.isEmpty() -> cache.builtAt
                failed > 0 -> t0 - MAX_AGE_MS + 3_600_000
                else -> t0
            },
            rows = rows.ifEmpty { cache.rows },
            map = map, touched = touched, checked = checked,
        )
        runCatching { file.writeText(json.encodeToString(RecsCache.serializer(), cache)) }
    }

    private companion object {
        const val TAG = "denopub"
        const val POOL = 40
        /** Bump when the row rules change, so the TV rebuilds instead of waiting up to 12 h. */
        const val VERSION = 2
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
