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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import dev.dt.kinopub.ui.common.splitTitle
import dev.dt.kinopub.ui.common.plural
import dev.dt.kinopub.ui.common.ActionMenu
import dev.dt.kinopub.ui.common.MenuAction
import androidx.compose.ui.input.key.Key
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.tv.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
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
fun HomeScreen(
    c: AppContainer,
    onPlay: (ResumeTarget) -> Unit,
    onOpen: (Long) -> Unit,
    onSearch: () -> Unit,
    onLive: (url: String, title: String) -> Unit,
) {
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(c.repository, c.watchNext) } })
    remember { vm.refresh() } // once per visit, before the first read of state
    val state by vm.state.collectAsState()
    val sport by c.sport.state.collectAsState()
    // Channels, guide and still frames refresh only while Home is on screen (never during playback).
    LaunchedEffect(Unit) { c.sport.run() }
    var minute by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); minute = System.currentTimeMillis() } }

    val firstFocus = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Pair<String, List<MenuAction>>?>(null) }
    val data = state.data

    menu?.let { (title, actions) -> ActionMenu(title, actions) { menu = null } }

    fun itemMenu(id: Long, title: String, inWishlist: Boolean) {
        menu = title to listOfNotNull(
            MenuAction("Открыть карточку") { onOpen(id) },
            if (inWishlist) MenuAction("Убрать из вишлиста") { vm.setWishlisted(id, false) }
            else MenuAction("Добавить в вишлист") { vm.setWishlisted(id, true) },
            MenuAction("Начал смотреть", "добавить в «Продолжить»") { vm.setWatching(id) },
            MenuAction("Отметить просмотренным") { vm.setItemWatched(id, true) },
        )
    }

    fun continueMenu(e: ContinueEntry) {
        menu = splitTitle(e.title).first to listOfNotNull(
            MenuAction("Открыть карточку") { onOpen(e.itemId) },
            e.episodeLabel?.let { ep ->
                MenuAction("Отметить $ep просмотренной", "продолжить со следующей") {
                    vm.setEpisodeWatched(e.itemId, e.target.season, e.target.episode)
                }
            },
            MenuAction(if (e.isSerial) "Отметить весь сериал просмотренным" else "Отметить просмотренным") {
                vm.setItemWatched(e.itemId, true)
            },
            MenuAction("Убрать из «Продолжить»", "очистить историю на kino.pub") { vm.removeFromContinue(e.itemId) },
            MenuAction("Добавить в вишлист") { vm.setWishlisted(e.itemId, true) },
        )
    }

    LaunchedEffect(data != null, state.error) {
        if (data != null || state.error != null) c.uiReady.value = true
    }

    if (data == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                state.error?.let { "Не загрузилось: $it" } ?: "Загрузка…",
                color = if (state.error != null) K.badge else K.muted,
            )
        }
        return
    }

    val continueRow = rememberLazyListState()
    val column = rememberLazyListState()
    // Focus the first card as soon as anything is on screen (cached data), and again when the
    // fresh load lands — unless the user has already started moving around.
    var userMoved by remember { mutableStateOf(false) }
    // Coming back from a title card / search / player: return focus to the card you left from.
    // Rows keep their scroll position (saveable lazy states), so that card is on screen.
    val restoreFocus = remember { FocusRequester() }
    val restoreKey = remember { vm.lastFocusKey }
    val track: Modifier.(String) -> Modifier = { key ->
        this.onFocusChanged { if (it.isFocused) vm.lastFocusKey = key }
            .then(if (key == restoreKey) Modifier.focusRequester(restoreFocus) else Modifier)
    }
    LaunchedEffect(state.loading, data != null) {
        if (data == null || (focusedOnce && (userMoved || state.loading))) return@LaunchedEffect
        if (!focusedOnce && restoreKey != null) {
            repeat(20) {
                if (runCatching { restoreFocus.requestFocus() }.isSuccess) {
                    focusedOnce = true; userMoved = true; return@LaunchedEffect
                }
                kotlinx.coroutines.delay(50)
            }
        }
        column.scrollToItem(0)
        continueRow.scrollToItem(0)
        repeat(20) {
            if (runCatching { firstFocus.requestFocus() }.isSuccess) { focusedOnce = true; return@LaunchedEffect }
            kotlinx.coroutines.delay(50)
        }
    }

    // Back on Home: first go to the first "continue" card; there, a second Back within 2.5 s exits.
    val firstKey = (data.continueSerials + data.continueMovies + data.continueKids).firstOrNull()?.let { "c:${it.itemId}" }
        ?: data.wishlist.firstOrNull()?.let { "w:${it.item.id}" }
    var exitArmed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(exitArmed) { if (exitArmed) { kotlinx.coroutines.delay(2_500); exitArmed = false } }
    BackHandler {
        when {
            firstKey != null && vm.lastFocusKey != firstKey -> scope.launch {
                exitArmed = false
                column.animateScrollToItem(0)
                continueRow.scrollToItem(0)
                runCatching { firstFocus.requestFocus() }
            }
            exitArmed -> activity?.finish()
            else -> exitArmed = true
        }
    }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown) userMoved = true
            if (it.type == KeyEventType.KeyDown && it.key == Key.Search) { onSearch(); true } else false
        },
    ) {
    LazyColumn(
        Modifier.fillMaxSize(),
        state = column,
        contentPadding = PaddingValues(bottom = K.screenPadV),
        verticalArrangement = Arrangement.spacedBy(K.rowGap),
    ) {
        if (data.continueWatching.isNotEmpty()) item("continue") {
            // "Continue" owns the top of the screen: a hero describing the focused title, then
            // two compact rows — grown-up titles first, kids' (cartoons) second, so a child can
            // find theirs without wading through the rest.
            var focused by remember { mutableStateOf(data.continueWatching.first()) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ContinueHero(focused)
                @Composable
                fun card(e: ContinueEntry, first: Boolean) = ContinueCard(
                    title = splitTitle(e.title).first,
                    image = e.poster,
                    episodeLabel = e.episodeLabel,
                    meta = continueMeta(e),
                    progress = e.progress,
                    newBadge = e.newEpisodes.takeIf { it > 0 }?.let { "+$it" },
                    onClick = { onPlay(e.target) },
                    onLongClick = { continueMenu(e) },
                    width = 160.dp,
                    modifier = Modifier
                        .onFocusChanged { if (it.isFocused) focused = e }
                        .track("c:${e.itemId}")
                        .then(if (first) Modifier.focusRequester(firstFocus) else Modifier),
                )

                // Three rows, each newest first (last watched or "Начал смотреть"):
                // serials, movies, kids. The first card of the first non-empty row gets focus.
                val rows = listOf(
                    "Сериалы" to data.continueSerials,
                    "Фильмы" to data.continueMovies,
                    "Детям" to data.continueKids,
                ).filter { it.second.isNotEmpty() }
                rows.forEachIndexed { r, (label, entries) ->
                    Text(
                        label, style = MaterialTheme.typography.labelMedium, color = K.muted,
                        modifier = Modifier.padding(horizontal = K.screenPadH, vertical = 0.dp),
                    )
                    LazyRow(
                        state = if (r == 0) continueRow else rememberLazyListState(),
                        contentPadding = PaddingValues(horizontal = K.screenPadH),
                        horizontalArrangement = Arrangement.spacedBy(K.cardGap),
                    ) {
                        itemsIndexed(entries, key = { _, e -> e.itemId }) { i, e -> card(e, first = r == 0 && i == 0) }
                    }
                }
            }
        }

        if (sport.channels.isNotEmpty()) item("sport") {
            Section("Спорт") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = K.screenPadH),
                    horizontalArrangement = Arrangement.spacedBy(K.cardGap),
                ) {
                    items(sport.channels, key = { it.name }) { ch ->
                        val now = sport.now(ch, minute)
                        val progress = now?.let { ((minute - it.start).toFloat() / (it.stop - it.start)).coerceIn(0f, 1f) } ?: 0f
                        dev.dt.kinopub.ui.common.SportCard(
                            channel = ch.title,
                            image = sport.snaps[ch.name]?.let { "file://$it" } ?: ch.logo,
                            programme = now?.title,
                            until = now?.let { hhmm(it.stop) },
                            progress = progress,
                            onClick = { onLive(ch.stream, ch.title) },
                            modifier = Modifier.track("s:${ch.name}"),
                        )
                    }
                }
            }
        }

        if (data.wishlist.isNotEmpty()) item("wishlist") {
            Section("Вишлист", badge = data.newInWishlist.takeIf { it > 0 }?.let { "$it ${plural(it, "новое", "новых", "новых")}" }) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = K.screenPadH),
                    horizontalArrangement = Arrangement.spacedBy(K.cardGap),
                ) {
                    items(data.wishlist, key = { it.item.id }) { w ->
                        PosterCard(
                            title = splitTitle(w.item.title).first,
                            image = w.item.posters?.medium,
                            subtitle = w.item.year?.toString(),
                            badge = if (w.isNew) "НОВОЕ" else null,
                            onClick = { onOpen(w.item.id) },
                            onLongClick = { itemMenu(w.item.id, splitTitle(w.item.title).first, inWishlist = true) },
                            modifier = Modifier
                                .onFocusChanged { if (it.isFocused) vm.onWishlistFocus(w.item.id) else vm.onWishlistBlur(w.item.id) }
                                .track("w:${w.item.id}")
                                .then(if (data.continueWatching.isEmpty() && w == data.wishlist.first()) Modifier.focusRequester(firstFocus) else Modifier),
                        )
                    }
                }
            }
        }

        val onMenu: (Item) -> Unit = { itemMenu(it.id, splitTitle(it.title).first, inWishlist = false) }
        posterRow("Новые фильмы", data.freshMovies, onOpen, onMenu, track)
        posterRow("Новые сериалы", data.freshSerials, onOpen, onMenu, track)
        posterRow("Популярное", data.hot, onOpen, onMenu, track)
    }
        // Always-visible search entry, top-right; ↑ from the first row reaches it.
        OutlinedButton(
            onClick = onSearch,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = K.screenPadV, end = K.screenPadH)
                // The button floats over the hero with no focusable neighbours, so the default
                // focus search can get stuck here. ↓ / ← always go back to the first card.
                .onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (it.key) {
                        Key.DirectionDown, Key.DirectionLeft ->
                            runCatching { firstFocus.requestFocus() }.isSuccess
                        else -> false
                    }
                },
        ) { Text("Поиск") }
        if (exitArmed) {
            Text(
                "Нажмите «Назад» ещё раз, чтобы выйти", color = K.text, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                    .background(K.surfaceHi, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        state.message?.let {
            Text(
                it, color = K.text, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                    .background(K.surfaceHi, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.posterRow(
    title: String, items: List<Item>, onOpen: (Long) -> Unit, onMenu: (Item) -> Unit,
    track: Modifier.(String) -> Modifier,
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
                        title = splitTitle(it.title).first,
                        image = it.posters?.medium,
                        subtitle = listOfNotNull(it.year?.toString(), it.imdbRating?.let { r -> "IMDb %.1f".format(r) })
                            .joinToString(" · "),
                        onClick = { onOpen(it.id) },
                        onLongClick = { onMenu(it) },
                        modifier = Modifier.track("$title:${it.id}"),
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
        e.target.startSec > 0 -> "ещё ${formatMinutes(left)}"
        e.isSerial -> "след. серия"
        else -> "сначала"
    }
}

/** Big description of the focused "continue" card: answers "what was this, where am I". */
@Composable
private fun ContinueHero(e: ContinueEntry) {
    // Compact, two columns: what it is (left) · where you are (right). ~92dp tall so 3.5
    // Continue rows fit under it.
    Box(Modifier.fillMaxWidth().height(92.dp)) {
        AsyncImage(e.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.Center)
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to K.bg, 0.6f to K.bg.copy(alpha = 0.85f), 1f to K.bg.copy(alpha = 0.35f)),
            ),
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = K.screenPadH, end = 200.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            val (ru, original) = splitTitle(e.title)
            Column(Modifier.width(420.dp)) {
                Text(ru, style = MaterialTheme.typography.headlineSmall, color = K.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                original?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = K.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(e.episodeLabel, continueMeta(e)).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium, color = K.text, maxLines = 1,
                    )
                    if (e.newEpisodes > 0) Badge("+${e.newEpisodes} ${plural(e.newEpisodes, "новая", "новые", "новых")}")
                }
                if (e.isSerial && e.remainingEpisodes > 1) {
                    Text("ещё ${e.remainingEpisodes} ${plural(e.remainingEpisodes, "серия", "серии", "серий")} впереди", style = MaterialTheme.typography.labelMedium, color = K.muted)
                }
                ProgressLine(e.progress, Modifier.width(260.dp), height = 3.dp)
            }
        }
    }
}

private fun hhmm(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
