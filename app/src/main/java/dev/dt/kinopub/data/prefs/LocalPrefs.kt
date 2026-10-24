package dev.dt.kinopub.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.localStore by preferencesDataStore("local")

/**
 * What the user picked last time for a title. Carried to the next episode so a serial keeps
 * its voiceover and subtitles without touching the remote.
 */
@Serializable
data class TrackChoice(
    val audioLang: String? = null,
    val audioAuthor: String? = null,   // studio, e.g. "LostFilm" — the strongest signal on kinopub
    val audioType: String? = null,     // voiceover type
    val subtitleLang: String? = null,  // null = subtitles off
    val maxHeight: Int? = null,        // quality cap; null = best available
)

/** Device-local state that has no home in the kinopub API. */
class LocalPrefs(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val kSeenWishlist = stringSetPreferencesKey("wishlist_seen")
    private val kDefaultTracks = stringPreferencesKey("tracks_default")
    private fun kTracks(itemId: Long) = stringPreferencesKey("tracks_$itemId")

    suspend fun trackChoice(itemId: Long): TrackChoice? {
        val p = context.localStore.data.first()
        val raw = p[kTracks(itemId)] ?: p[kDefaultTracks] ?: return null
        return runCatching { json.decodeFromString<TrackChoice>(raw) }.getOrNull()
    }

    /** Saved per title and as the global default for titles never opened before. */
    suspend fun saveTrackChoice(itemId: Long, choice: TrackChoice) {
        val raw = json.encodeToString(TrackChoice.serializer(), choice)
        context.localStore.edit {
            it[kTracks(itemId)] = raw
            it[kDefaultTracks] = raw
        }
    }

    private val kKids = stringSetPreferencesKey("kids_ids")
    private val kAdult = stringSetPreferencesKey("adult_ids")

    /** Persistent kids/adult classification by item id (genres never change). null = unknown. */
    suspend fun kidsFlags(): Map<Long, Boolean> {
        val p = context.localStore.data.first()
        return p[kKids].orEmpty().mapNotNull { it.toLongOrNull() }.associateWith { true } +
            p[kAdult].orEmpty().mapNotNull { it.toLongOrNull() }.associateWith { false }
    }

    suspend fun saveKidsFlags(flags: Map<Long, Boolean>) {
        context.localStore.edit { p ->
            p[kKids] = p[kKids].orEmpty() + flags.filterValues { it }.keys.map { it.toString() }
            p[kAdult] = p[kAdult].orEmpty() + flags.filterValues { !it }.keys.map { it.toString() }
        }
    }

    private val kStarted = stringSetPreferencesKey("started_at") // "id:epochMs"

    /** Last local activity per title: "Начал смотреть" or starting playback here. Sort key for Continue. */
    suspend fun startedAt(): Map<Long, Long> =
        context.localStore.data.first()[kStarted].orEmpty().mapNotNull {
            val (id, ts) = it.split(':').takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            (id.toLongOrNull() ?: return@mapNotNull null) to (ts.toLongOrNull() ?: 0L)
        }.toMap()

    private val kPlayed = stringSetPreferencesKey("played_at") // "id:epochMs", last playback here

    /** When each title was last played in this app. Cleared only by "Убрать из «Продолжить»". */
    suspend fun playedAt(): Map<Long, Long> =
        context.localStore.data.first()[kPlayed].orEmpty().mapNotNull {
            val (id, ts) = it.split(':').takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            (id.toLongOrNull() ?: return@mapNotNull null) to (ts.toLongOrNull() ?: 0L)
        }.toMap()

    suspend fun markPlayed(id: Long, on: Boolean = true) {
        context.localStore.edit { p ->
            val rest = p[kPlayed].orEmpty().filterNot { it.startsWith("$id:") }.toSet()
            p[kPlayed] = if (on) rest + "$id:${System.currentTimeMillis()}" else rest
        }
    }

    suspend fun markStarted(id: Long, on: Boolean) {
        if (!on) android.util.Log.i("denopub", "startedAt cleared for $id")
        context.localStore.edit { p ->
            val rest = p[kStarted].orEmpty().filterNot { it.startsWith("$id:") }.toSet()
            p[kStarted] = if (on) rest + "$id:${System.currentTimeMillis()}" else rest
        }
    }

    private val kRateKey = stringPreferencesKey("imdb_rate_key")
    private val kRateAsked = stringSetPreferencesKey("imdb_rate_asked")

    /** Device key for the movies service's IMDb rating API (got once with a pairing code). */
    suspend fun rateKey(): String? = context.localStore.data.first()[kRateKey]

    suspend fun saveRateKey(key: String?) {
        context.localStore.edit { if (key == null) it.remove(kRateKey) else it[kRateKey] = key }
    }

    /** Titles the "rate it on IMDb" prompt was already shown for — it comes once per title. */
    suspend fun rateAsked(id: Long): Boolean = context.localStore.data.first()[kRateAsked].orEmpty().contains(id.toString())

    suspend fun markRateAsked(id: Long) {
        context.localStore.edit { it[kRateAsked] = it[kRateAsked].orEmpty() + id.toString() }
    }

    suspend fun seenWishlistIds(): Set<Long> =
        context.localStore.data.first()[kSeenWishlist].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    suspend fun markWishlistSeen(ids: Collection<Long>) {
        context.localStore.edit { p ->
            p[kSeenWishlist] = p[kSeenWishlist].orEmpty() + ids.map { it.toString() }
        }
    }
}
