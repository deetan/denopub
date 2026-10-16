package dev.dt.kinopub.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.dt.kinopub.AppContainer
import dev.dt.kinopub.data.ContinueEntry
import dev.dt.kinopub.data.ResumeTarget
import dev.dt.kinopub.data.api.Item
import dev.dt.kinopub.ui.common.Badge
import dev.dt.kinopub.ui.common.ContinueCard
import dev.dt.kinopub.ui.common.PosterCard
import dev.dt.kinopub.ui.common.ProgressLine
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import dev.dt.kinopub.ui.common.formatMinutes
import dev.dt.kinopub.ui.theme.K

/**
 * Home = three jobs, top to bottom, in order of how often they happen:
 *  1. Continue what I'm watching (first card focused on launch: OK = play).
 *  2. Wishlist, with "new" markers for things added from the phone since last look.
 *  3. What's new on kinopub.
 */
@Composable
fun HomeScreen(c: AppContainer, onPlay: (ResumeTarget) -> Unit, onOpen: (Long) -> Unit) {
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(c.repository) } })
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    val firstFocus = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    val data = state.data

    if (data == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                state.error?.let { "Не загрузилось: $it" } ?: "Загрузка…",
                color = if (state.error != null) K.badge else K.muted,
            )
        }
        return
    }

    LaunchedEffect(data) {
        // Lazy rows compose a frame late; retry briefly until the first card exists.
        repeat(20) {
            if (focusedOnce) return@LaunchedEffect
            if (runCatching { firstFocus.requestFocus() }.isSuccess) focusedOnce = true
            else kotlinx.coroutines.delay(50)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = K.screenPadV),
        verticalArrangement = Arrangement.spacedBy(K.rowGap),
    ) {
        if (data.continueWatching.isNotEmpty()) item("continue") {
            // "Continue" owns the top half of the screen: a hero describing the focused
            // title, then larger cards. This is the main use case of the app.
            var focused by remember { mutableStateOf(data.continueWatching.first()) }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ContinueHero(focused)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = K.screenPadH),
                    horizontalArrangement = Arrangement.spacedBy(K.cardGap),
                ) {
                    itemsIndexed(data.continueWatching, key = { _, e -> e.itemId }) { i, e ->
                        ContinueCard(
                            title = e.title,
                            image = e.poster,
                            episodeLabel = e.episodeLabel,
                            meta = continueMeta(e),
                            progress = e.progress,
                            newBadge = e.newEpisodes.takeIf { it > 0 }?.let { "+$it" },
                            onClick = { onPlay(e.target) },
                            onLongClick = { onOpen(e.itemId) },
                            width = 340.dp,
                            modifier = Modifier
                                .onFocusChanged { if (it.isFocused) focused = e }
                                .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier),
                        )
                    }
                }
            }
        }

        if (data.wishlist.isNotEmpty()) item("wishlist") {
            Section("Вишлист", badge = data.newInWishlist.takeIf { it > 0 }?.let { "$it новых" }) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = K.screenPadH),
                    horizontalArrangement = Arrangement.spacedBy(K.cardGap),
                ) {
                    items(data.wishlist, key = { it.item.id }) { w ->
                        PosterCard(
                            title = w.item.title,
                            image = w.item.posters?.medium,
                            subtitle = w.item.year?.toString(),
                            badge = if (w.isNew) "НОВОЕ" else null,
                            onClick = { onOpen(w.item.id) },
                            modifier = Modifier
                                .onFocusChanged { if (it.isFocused) vm.markSeen(w.item.id) }
                                .then(if (data.continueWatching.isEmpty() && w == data.wishlist.first()) Modifier.focusRequester(firstFocus) else Modifier),
                        )
                    }
                }
            }
        }

        posterRow("Новые фильмы", data.freshMovies, onOpen)
        posterRow("Новые сериалы", data.freshSerials, onOpen)
        posterRow("Популярное", data.hot, onOpen)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.posterRow(
    title: String, items: List<Item>, onOpen: (Long) -> Unit,
) {
    if (items.isEmpty()) return
    item(title) {
        Section(title) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = K.screenPadH),
                horizontalArrangement = Arrangement.spacedBy(K.cardGap),
            ) {
                items(items, key = { it.id }) { it ->
                    PosterCard(
                        title = it.title,
                        image = it.posters?.medium,
                        subtitle = listOfNotNull(it.year?.toString(), it.imdbRating?.let { r -> "IMDb %.1f".format(r) })
                            .joinToString(" · "),
                        onClick = { onOpen(it.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, badge: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.padding(horizontal = K.screenPadH),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = K.text)
            badge?.let { Badge(it) }
        }
        content()
    }
}

private fun continueMeta(e: ContinueEntry): String {
    val left = (e.durationSec - e.target.startSec).coerceAtLeast(0)
    return when {
        e.target.startSec > 0 -> "осталось ${formatMinutes(left)}"
        e.isSerial -> "следующая серия"
        else -> "сначала"
    }
}

/** Big description of the focused "continue" card: answers "what was this, where am I". */
@Composable
private fun ContinueHero(e: ContinueEntry) {
    Box(Modifier.fillMaxWidth().height(230.dp)) {
        AsyncImage(
            e.poster, null,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to K.bg, 0.55f to K.bg.copy(alpha = 0.75f), 1f to Color.Transparent),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to K.bg)))
        Column(
            Modifier.align(Alignment.BottomStart).padding(horizontal = K.screenPadH).width(560.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Продолжить", style = MaterialTheme.typography.labelMedium, color = K.accent)
            Text(e.title, style = MaterialTheme.typography.displaySmall, color = K.text, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(e.episodeLabel, continueMeta(e)).joinToString("  ·  "),
                    style = MaterialTheme.typography.titleMedium, color = K.text,
                )
                if (e.newEpisodes > 0) Badge("+${e.newEpisodes} новых")
            }
            if (e.isSerial && e.remainingEpisodes > 1) {
                Text("ещё ${e.remainingEpisodes} серий впереди", style = MaterialTheme.typography.bodyMedium, color = K.muted)
            }
            ProgressLine(e.progress, Modifier.width(320.dp))
        }
    }
}
