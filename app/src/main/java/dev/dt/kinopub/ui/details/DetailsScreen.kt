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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import dev.dt.kinopub.ui.common.ProgressLine
import dev.dt.kinopub.ui.common.formatMinutes
import dev.dt.kinopub.ui.theme.K
import kotlinx.coroutines.launch

/** Title page: one primary action (resume/play) focused, then seasons → episodes. */
@Composable
fun DetailsScreen(c: AppContainer, id: Long, onPlay: (ResumeTarget) -> Unit, onBack: () -> Unit) {
    var item by remember { mutableStateOf<Item?>(null) }
    var resume by remember { mutableStateOf<ResumeTarget?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var wishlisted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(id) {
        runCatching {
            val it = c.repository.item(id)
            item = it
            resume = resumeTarget(it)
        }.onFailure { error = it.message }
    }

    val it = item ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error ?: "Загрузка…", color = if (error != null) K.badge else K.muted)
        }
        return
    }
    LaunchedEffect(it.id) { runCatching { playFocus.requestFocus() } }

    var season by remember(it.id) {
        mutableStateOf(resume?.season ?: it.seasons.firstOrNull()?.number)
    }

    Row(Modifier.fillMaxSize().padding(horizontal = K.screenPadH, vertical = K.screenPadV)) {
        // Left: poster + facts + actions
        Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AsyncImage(
                it.posters?.big ?: it.posters?.medium, null,
                Modifier.width(200.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(K.surface),
                contentScale = ContentScale.Crop,
            )
            Text(it.title, style = MaterialTheme.typography.headlineSmall, color = K.text, maxLines = 2)
            Text(
                listOfNotNull(
                    it.year?.toString(),
                    it.imdbRating?.let { r -> "IMDb %.1f".format(r) },
                    it.kinopoiskRating?.let { r -> "КП %.1f".format(r) },
                    it.quality?.let { q -> if (q >= 2160) "4K" else "${q}p" },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium, color = K.muted,
            )
            val r = resume
            Button(
                onClick = { onPlay(r ?: ResumeTarget(it.id, it.seasons.firstOrNull()?.number, 1, 0)) },
                modifier = Modifier.focusRequester(playFocus),
            ) {
                Text(
                    when {
                        r == null -> "Смотреть"
                        r.season != null && r.startSec > 0 -> "Продолжить S${r.season}·E${r.episode}"
                        r.season != null -> "Смотреть S${r.season}·E${r.episode}"
                        r.startSec > 0 -> "Продолжить с ${formatMinutes(r.startSec)}"
                        else -> "Смотреть"
                    },
                )
            }
            OutlinedButton(
                onClick = {
                    scope.launch { runCatching { c.repository.addToWishlist(it.id) }.onSuccess { wishlisted = true } }
                },
            ) { Text(if (wishlisted) "В вишлисте ✓" else "В вишлист") }
        }

        Spacer(Modifier.width(36.dp))

        // Right: plot + seasons + episodes
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            it.plot?.let { p ->
                Text(p, style = MaterialTheme.typography.bodyMedium, color = K.muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (it.seasons.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(it.seasons, key = { s -> s.number }) { s ->
                        val selected = s.number == season
                        if (selected) Button(onClick = { season = s.number }) { Text("Сезон ${s.number}") }
                        else OutlinedButton(onClick = { season = s.number }) { Text("Сезон ${s.number}") }
                    }
                }
                val episodes = it.seasons.firstOrNull { s -> s.number == season }?.episodes.orEmpty()
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(episodes, key = { e -> e.id }) { e ->
                        EpisodeRow(e, isResume = resume?.season == season && resume?.episode == e.number) {
                            val start = e.watching?.takeIf { w -> w.status == 0 }?.time ?: 0
                            onPlay(ResumeTarget(it.id, season, e.number, start))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(e: Video, isResume: Boolean, onClick: () -> Unit) {
    val status = e.watching?.status ?: -1
    ListItem(
        selected = isResume,
        onClick = onClick,
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

/** Same rule as Home: first unfinished episode at/after the last touched one. */
private fun resumeTarget(item: Item): ResumeTarget? {
    if (item.seasons.isNotEmpty()) {
        val flat = item.seasons.sortedBy { it.number }.flatMap { s -> s.episodes.sortedBy { it.number }.map { s.number to it } }
        val last = flat.indexOfLast { (_, e) -> (e.watching?.status ?: -1) >= 0 }
        val next = flat.drop(maxOf(last, 0)).firstOrNull { (_, e) -> e.watching?.status != 1 } ?: return null
        val (s, e) = next
        return ResumeTarget(item.id, s, e.number, if (e.watching?.status == 0) e.watching?.time ?: 0 else 0)
    }
    val v = item.videos.firstOrNull { it.watching?.status == 0 } ?: return null
    return ResumeTarget(item.id, null, v.number, v.watching?.time ?: 0)
}
