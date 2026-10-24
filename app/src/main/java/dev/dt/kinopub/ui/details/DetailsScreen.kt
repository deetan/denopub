package dev.dt.kinopub.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.dt.kinopub.AppContainer
import dev.dt.kinopub.data.ResumeTarget
import dev.dt.kinopub.data.api.Item
import dev.dt.kinopub.data.api.Video
import dev.dt.kinopub.ui.common.ActionMenu
import dev.dt.kinopub.ui.common.MenuAction
import dev.dt.kinopub.ui.common.ProgressLine
import dev.dt.kinopub.ui.common.RateDialog
import dev.dt.kinopub.ui.common.formatMinutes
import dev.dt.kinopub.ui.common.splitTitle
import dev.dt.kinopub.ui.theme.K
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Title card. One primary action (resume/play) focused; next to it the state toggles that sync
 * with kino.pub: wishlist, "watching", watched. Long-press an episode for per-episode actions.
 */
@Composable
fun DetailsScreen(
    c: AppContainer,
    id: Long,
    onPlay: (ResumeTarget) -> Unit,
    onBack: () -> Unit,
    onTrailer: (url: String, title: String) -> Unit,
) {
    var item by remember { mutableStateOf<Item?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var wishlisted by remember { mutableStateOf<Boolean?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<Pair<String, List<MenuAction>>?>(null) }
    var showPlot by remember { mutableStateOf(false) }
    var reloads by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    val trailerFocus = remember { FocusRequester() }

    LaunchedEffect(id, reloads) {
        runCatching { item = c.repository.item(id) }.onFailure { error = it.message }
    }
    LaunchedEffect(id) { wishlisted = runCatching { c.repository.isInWishlist(id) }.getOrNull() }
    // Extra info: English plot + trailers from IMDb, and kino.pub's own trailer.
    var imdb by remember { mutableStateOf<dev.dt.kinopub.data.api.ImdbInfo?>(null) }
    var kpTrailer by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item?.imdb) { item?.imdb?.let { imdb = c.imdb.info(it) } }
    // The owner's own IMDb rating (public profile); the button next to "watched" changes it.
    var myRating by remember { mutableStateOf<Int?>(null) }
    var rating by remember { mutableStateOf(false) }
    LaunchedEffect(item?.imdb) { item?.imdb?.let { myRating = c.rater.myRating(dev.dt.kinopub.data.api.ImdbRater.tconst(it)) } }
    LaunchedEffect(id) { kpTrailer = runCatching { c.api.trailerUrl(id) }.getOrNull() }

    /** Run a kino.pub write, show a confirmation, re-read the item so statuses are real. */
    fun act(done: String, block: suspend () -> Unit) {
        scope.launch {
            message = runCatching { block() }.fold({ done }, { "Не получилось: ${it.message}" })
            reloads++
            delay(2_500)
            message = null
        }
    }

    val it = item ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error ?: "Загрузка…", color = if (error != null) K.badge else K.muted)
        }
        return
    }
    val resume = remember(it) { resumeTarget(it) }
    val fullyWatched = remember(it) { isFullyWatched(it) }
    // "Watching" = on the Continue row: subscribed serial, or any progress that isn't finished.
    val watching = remember(it) {
        val touched = (it.seasons.flatMap { s -> s.episodes } + it.videos).any { v -> resumeSec(v) > 0 || v.watching?.status == 1 }
        (it.isSerial && it.inWatchlist == true) || (touched && !isFullyWatched(it))
    }
    LaunchedEffect(it.id) { runCatching { playFocus.requestFocus() } }

    var season by remember(it.id) { mutableStateOf(resume?.season ?: it.seasons.firstOrNull()?.number) }
    val (titleRu, titleOrig) = splitTitle(it.title)

    menu?.let { (t, actions) -> ActionMenu(t, actions) { menu = null } }
    if (showPlot) PlotDialog(titleRu, it.plot, imdb?.plot) { showPlot = false }
    val imdbId = it.imdb
    if (rating && imdbId != null) RateDialog(c, imdbId, titleRu, myRating) { v -> rating = false; if (v != null) myRating = v }

    fun episodeMenu(s: Int, e: Video) {
        val watched = e.watching?.status == 1
        menu = "S$s·E${e.number} · ${e.title.orEmpty()}" to listOf(
            MenuAction("Смотреть с начала") { onPlay(ResumeTarget(it.id, s, e.number, 0)) },
            if (watched) MenuAction("Снять отметку «просмотрено»") { act("Отметка снята") { c.repository.setEpisodeWatched(it.id, s, e.number, false) } }
            else MenuAction("Отметить просмотренной") { act("Отмечено") { c.repository.setEpisodeWatched(it.id, s, e.number, true) } },
            MenuAction("Отметить все до этой включительно", "предыдущие сезоны тоже") {
                act("Отмечено до S$s·E${e.number}") {
                    it.seasons.filter { x -> x.number < s }.forEach { x -> c.repository.setSeasonWatched(it.id, x.number, true) }
                    it.seasons.first { x -> x.number == s }.episodes.filter { x -> x.number <= e.number && x.watching?.status != 1 }
                        .forEach { x -> c.repository.setEpisodeWatched(it.id, s, x.number, true) }
                }
            },
            MenuAction("Отметить сезон $s просмотренным") { act("Сезон $s отмечен") { c.repository.setSeasonWatched(it.id, s, true) } },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = K.screenPadH, vertical = K.screenPadV),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header: poster + facts + plot
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                AsyncImage(
                    it.posters?.big ?: it.posters?.medium, null,
                    Modifier.width(110.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(K.surface),
                    contentScale = ContentScale.Crop,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(titleRu, style = MaterialTheme.typography.headlineSmall, color = K.text, maxLines = 1)
                    Text(
                        listOfNotNull(
                            titleOrig,
                            it.year?.toString(),
                            it.imdbRating?.let { r -> "IMDb %.1f".format(r) },
                            it.kinopoiskRating?.let { r -> "КП %.1f".format(r) },
                            it.quality?.let { q -> if (q >= 2160) "4K" else "${q}p" },
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.titleMedium, color = K.muted, maxLines = 1,
                    )
                    if (it.genres.isNotEmpty()) {
                        Text(it.genres.mapNotNull { g -> g.title }.joinToString(", "), style = MaterialTheme.typography.labelMedium, color = K.muted)
                    }
                    if (it.plot != null || imdb?.plot != null) {
                        // Truncated here; focus it (↑ from the buttons) and press OK for the full text.
                        androidx.tv.material3.Surface(
                            onClick = { showPlot = true },
                            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                containerColor = Color.Transparent,
                                focusedContainerColor = K.surfaceHi,
                            ),
                            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        ) {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                it.plot?.let { p ->
                                    Text(p, style = MaterialTheme.typography.bodyMedium, color = K.text.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                imdb?.plot?.let { p ->
                                    Text("IMDb · $p", style = MaterialTheme.typography.bodyMedium, color = K.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }

            // Actions: primary first (focused), then toggles that write to kino.pub
            val r = resume
            val hasTrailers = kpTrailer != null || !imdb?.videos.isNullOrEmpty()
            // ↓ from the actions goes to the trailers row (geometry alone skipped it to seasons).
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.focusProperties { if (hasTrailers) down = trailerFocus },
            ) {
                Button(
                    onClick = { onPlay(r ?: ResumeTarget(it.id, it.seasons.firstOrNull()?.number, 1, 0)) },
                    modifier = Modifier.focusRequester(playFocus),
                ) {
                    Text(
                        when {
                            r == null -> "Смотреть"
                            r.season != null && r.startSec > 0 -> "Продолжить S${r.season}·E${r.episode} с ${formatMinutes(r.startSec)}"
                            r.season != null -> "Смотреть S${r.season}·E${r.episode}"
                            r.startSec > 0 -> "Продолжить с ${formatMinutes(r.startSec)}"
                            else -> "Смотреть"
                        },
                    )
                }
                OutlinedButton(onClick = {
                    val on = wishlisted != true
                    act(if (on) "Добавлено в вишлист" else "Убрано из вишлиста") { wishlisted = c.repository.setWishlisted(it.id, on) }
                }) { Text(if (wishlisted == true) "В вишлисте ✓" else "В вишлист") }
                OutlinedButton(onClick = {
                    act(if (watching) "Убрано из «Продолжить»" else "Добавлено в «Продолжить»") {
                        if (watching) c.repository.removeFromContinue(it.id) else c.repository.setWatching(it, true)
                    }
                }) { Text(if (watching) "Смотрю ✓" else "Начал смотреть") }
                OutlinedButton(onClick = {
                    act(if (fullyWatched) "Отметка снята" else "Отмечено просмотренным") { c.repository.setItemWatched(it, !fullyWatched) }
                }) { Text(if (fullyWatched) "Просмотрено ✓" else "Отметить просмотренным") }
                if (imdbId != null) OutlinedButton(onClick = { rating = true }) {
                    Text(myRating?.let { r -> "Моя оценка ★ $r" } ?: "Оценить на IMDb")
                }
            }

            // Trailers: kino.pub's + IMDb's (trailers and teasers, newest first as IMDb lists them).
            val trailers = buildList {
                kpTrailer?.let { add(TrailerChip("Трейлер · kino.pub", null, it)) }
                imdb?.videos?.forEach { v -> add(TrailerChip(v.name, v.seconds, v.url)) }
            }
            if (trailers.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    // Room for the focus zoom: without it the focused chip is clipped by the row.
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    itemsIndexed(trailers, key = { _, t -> t.url }) { i, t ->
                        OutlinedButton(
                            onClick = { onTrailer(t.url, "$titleRu — ${t.name}") },
                            modifier = if (i == 0) Modifier.focusRequester(trailerFocus) else Modifier,
                        ) {
                            Text("▶ ${shortTrailerName(t.name)}" + (t.seconds?.let { s -> " · ${s / 60}:${"%02d".format(s % 60)}" } ?: ""), maxLines = 1)
                        }
                    }
                }
            }

            // Seasons → episodes
            if (it.seasons.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    // Room for the focus zoom: without it the focused chip is clipped by the row.
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    items(it.seasons, key = { s -> s.number }) { s ->
                        val left = s.episodes.count { e -> e.watching?.status != 1 }
                        val label = "Сезон ${s.number}" + if (left == 0) " ✓" else ""
                        // Long-press: mark the whole season watched (or unwatched if it already is).
                        val toggleSeason = {
                            val done = left == 0
                            act(if (done) "Сезон ${s.number}: отметка снята" else "Сезон ${s.number} просмотрен") {
                                c.repository.setSeasonWatched(it.id, s.number, !done)
                            }
                        }
                        if (s.number == season) Button(onClick = { season = s.number }, onLongClick = toggleSeason) { Text(label) }
                        else OutlinedButton(onClick = { season = s.number }, onLongClick = toggleSeason) { Text(label) }
                    }
                }
                val episodes = it.seasons.firstOrNull { s -> s.number == season }?.episodes.orEmpty()
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(season, resume) {
                    val i = episodes.indexOfFirst { e -> resume?.season == season && e.number == resume?.episode }
                    if (i > 0) listState.scrollToItem(i - 1)
                }
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(episodes, key = { e -> e.id }) { e ->
                        EpisodeRow(
                            e,
                            isResume = resume?.season == season && resume?.episode == e.number,
                            onClick = { onPlay(ResumeTarget(it.id, season, e.number, resumeSec(e))) },
                            // Long-press: toggle "просмотрено" right away (no menu).
                            onLongClick = {
                                season?.let { s ->
                                    val watched = e.watching?.status == 1
                                    act(if (watched) "S$s·E${e.number}: отметка снята" else "S$s·E${e.number} просмотрена") {
                                        c.repository.setEpisodeWatched(it.id, s, e.number, !watched)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        message?.let {
            Text(
                it, color = K.text, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                    .background(K.surfaceHi, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

private data class TrailerChip(val name: String, val seconds: Int?, val url: String)

/** "Silo | Official Trailer - Season 2" → "Трейлер S2"; keeps chips short enough to fit a row. */
private fun shortTrailerName(raw: String): String {
    if (raw.contains("kino.pub")) return raw
    val n = raw.substringAfterLast(" | ")
    val season = Regex("Season (\\d+)", RegexOption.IGNORE_CASE).find(n)?.groupValues?.get(1)
    val kind = when {
        n.contains("teaser", true) -> "Тизер"
        n.contains("trailer", true) || n.contains("трейлер", true) -> "Трейлер"
        else -> n.take(18)
    }
    return kind + (season?.let { " S$it" } ?: "")
}

/** kino.pub's marktime stores time without changing status, so "in progress" = time > 0, not finished. */
private fun resumeSec(v: Video): Int = v.watching?.takeIf { it.status != 1 }?.time ?: 0

@Composable
private fun EpisodeRow(e: Video, isResume: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val status = when {
        e.watching?.status == 1 -> 1
        resumeSec(e) > 0 -> 0
        else -> -1
    }
    ListItem(
        selected = isResume,
        onClick = onClick,
        onLongClick = onLongClick,
        headlineContent = {
            Text("${e.number}. ${e.title?.takeIf { it.isNotBlank() } ?: "Серия ${e.number}"}", maxLines = 1)
        },
        supportingContent = {
            Column {
                Text(
                    when (status) {
                        1 -> "просмотрено"
                        0 -> "остановились на ${formatMinutes(e.watching?.time ?: 0)} из ${formatMinutes(e.duration)}"
                        else -> formatMinutes(e.duration)
                    },
                )
                if (status == 0 && e.duration > 0) {
                    ProgressLine((e.watching?.time ?: 0).toFloat() / e.duration, Modifier.padding(top = 4.dp).fillMaxWidth(0.5f), height = 3.dp)
                }
            }
        },
        trailingContent = { if (status == 1) Text("✓", color = K.accent) },
    )
}

private fun isFullyWatched(item: Item): Boolean =
    if (item.seasons.isNotEmpty()) item.seasons.all { s -> s.episodes.all { it.watching?.status == 1 } }
    else item.videos.isNotEmpty() && item.videos.all { it.watching?.status == 1 }

/** Same rule as Home: first unfinished episode at/after the last touched one. */
private fun resumeTarget(item: Item): ResumeTarget? {
    if (item.seasons.isNotEmpty()) {
        val flat = item.seasons.sortedBy { it.number }.flatMap { s -> s.episodes.sortedBy { it.number }.map { s.number to it } }
        val last = flat.indexOfLast { (_, e) -> e.watching?.status == 1 || resumeSec(e) > 0 }
        val next = flat.drop(maxOf(last, 0)).firstOrNull { (_, e) -> e.watching?.status != 1 } ?: return null
        val (s, e) = next
        return ResumeTarget(item.id, s, e.number, resumeSec(e))
    }
    val v = item.videos.firstOrNull { resumeSec(it) > 0 || it.watching?.status == 0 } ?: return null
    return ResumeTarget(item.id, null, v.number, resumeSec(v))
}

/** Full description: kino.pub's Russian text, then IMDb's English one. Back closes. */
@Composable
private fun PlotDialog(title: String, ru: String?, en: String?, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        val scroll = androidx.compose.foundation.rememberScrollState()
        val scope = rememberCoroutineScope()
        Column(
            Modifier
                .width(900.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(K.surface)
                .padding(28.dp)
                .focusRequester(focus)
                .focusable()
                // ↑ ↓ scroll long texts; OK or Back closes.
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(160f) }; true }
                        Key.DirectionUp -> { scope.launch { scroll.animateScrollBy(-160f) }; true }
                        Key.DirectionCenter, Key.Enter -> { onDismiss(); true }
                        else -> false
                    }
                }
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = K.text)
            ru?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = K.text) }
            en?.let { Text("IMDb · $it", style = MaterialTheme.typography.bodyMedium, color = K.muted) }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
