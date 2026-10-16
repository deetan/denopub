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

    suspend fun seenWishlistIds(): Set<Long> =
        context.localStore.data.first()[kSeenWishlist].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    suspend fun markWishlistSeen(ids: Collection<Long>) {
        context.localStore.edit { p ->
            p[kSeenWishlist] = p[kSeenWishlist].orEmpty() + ids.map { it.toString() }
        }
    }
}
