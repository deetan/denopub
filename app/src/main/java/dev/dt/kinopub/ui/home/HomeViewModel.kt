package dev.dt.kinopub.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dt.kinopub.data.HomeData
import dev.dt.kinopub.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class HomeState(val data: HomeData? = null, val loading: Boolean = true, val error: String? = null)

class HomeViewModel(private val repo: Repository) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    /** Called every time Home becomes visible: progress changes after each playback. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            _state.value = runCatching { repo.home() }.fold(
                { HomeState(it, loading = false) },
                { _state.value.copy(loading = false, error = it.message) },
            )
        }
    }

    fun markSeen(id: Long) {
        val d = _state.value.data ?: return
        if (d.wishlist.none { it.item.id == id && it.isNew }) return
        viewModelScope.launch { repo.markWishlistSeen(listOf(id)) }
        // Keep the badge on screen until the next refresh so the row does not jump.
    }
}
