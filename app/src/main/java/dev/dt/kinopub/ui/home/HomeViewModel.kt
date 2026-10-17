package dev.dt.kinopub.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dt.kinopub.data.HomeData
import dev.dt.kinopub.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class HomeState(
    val data: HomeData? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,   // short confirmation after an action
)

class HomeViewModel(private val repo: Repository) : ViewModel() {
    // Start from the on-disk copy of the last Home: instant launch, fresh data replaces it.
    private val _state = MutableStateFlow(HomeState(data = repo.cachedHome()))
    val state: StateFlow<HomeState> = _state

    /** Called every time Home becomes visible: progress changes after each playback. */
    fun refresh() {
        // Synchronous, so the screen never treats cached data as fresh.
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            _state.value = runCatching { repo.home() }.fold(
                { HomeState(it, loading = false) },
                { _state.value.copy(loading = false, error = it.message) },
            )
            classifyNewTitles()
        }
    }

    /** First sighting of a title: look up its genres once, then move kids' titles to their row. */
    private suspend fun classifyNewTitles() {
        val d = _state.value.data ?: return
        val unknown = runCatching { repo.unclassified(d.continueWatching) }.getOrDefault(emptyList())
        if (unknown.isEmpty()) return
        val kids = runCatching { repo.classifyKids(unknown) }.getOrDefault(emptySet())
        if (kids.isEmpty()) return
        val cur = _state.value.data ?: return
        _state.value = _state.value.copy(
            data = cur.copy(continueWatching = cur.continueWatching.map { if (it.itemId in kids) it.copy(isKids = true) else it }),
        )
    }

    // ---------- actions: write to kino.pub, confirm, reload ----------

    private fun act(done: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            val msg = runCatching { block() }.fold({ done }, { "Не получилось: ${it.message}" })
            _state.value = _state.value.copy(message = msg)
            refreshKeepingMessage()
            kotlinx.coroutines.delay(2_500)
            _state.value = _state.value.copy(message = null)
        }
    }

    private suspend fun refreshKeepingMessage() {
        runCatching { repo.home() }.onSuccess { _state.value = _state.value.copy(data = it, loading = false) }
    }

    fun setEpisodeWatched(id: Long, season: Int?, episode: Int) =
        act("Отмечено просмотренным") { repo.setEpisodeWatched(id, season, episode, true) }

    fun setItemWatched(id: Long, watched: Boolean) =
        act(if (watched) "Отмечено просмотренным" else "Отметка снята") { repo.setItemWatched(repo.item(id), watched) }

    fun removeFromContinue(id: Long) = act("Убрано из «Продолжить»") { repo.removeFromContinue(id) }

    fun setWishlisted(id: Long, on: Boolean) =
        act(if (on) "Добавлено в вишлист" else "Убрано из вишлиста") { repo.setWishlisted(id, on) }

    fun setWatching(id: Long) = act("Добавлено в «Продолжить»") { repo.setWatching(repo.item(id), true) }

    private val dwell = mutableMapOf<Long, kotlinx.coroutines.Job>()

    /** A wishlist item counts as seen after its card held focus for a moment, not on fly-by. */
    fun onWishlistFocus(id: Long) {
        val d = _state.value.data ?: return
        if (d.wishlist.none { it.item.id == id && it.isNew }) return
        dwell[id]?.cancel()
        dwell[id] = viewModelScope.launch {
            kotlinx.coroutines.delay(1_500)
            repo.markWishlistSeen(listOf(id))
            // Badge stays until the next refresh so the row does not jump under the cursor.
        }
    }

    fun onWishlistBlur(id: Long) {
        dwell.remove(id)?.cancel()
    }
}
