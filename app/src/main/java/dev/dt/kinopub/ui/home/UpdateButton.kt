package dev.dt.kinopub.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.dt.kinopub.system.Updater
import dev.dt.kinopub.ui.theme.K
import kotlinx.coroutines.launch

/**
 * «Обновление» next to «Поиск», only while a newer release exists. Checks on Home start and on
 * every return to the foreground (the updater throttles to once per few hours). Lives on Home
 * only, so nothing is checked, downloaded or installed during playback.
 */
@Composable
fun UpdateButton(updater: Updater, modifier: Modifier = Modifier) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { updater.checkForUpdate() }
    }
    val state by updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    // Compact (a round ⬆ / percentage) so it doesn't cover the hero's episode info; the full
    // label shows only while focused.
    val (short, long) = when (val s = state) {
        is Updater.State.Idle -> return
        is Updater.State.Ready -> "⬆" to "Обновить до ${s.update.versionName}"
        is Updater.State.NeedsPermission -> "⬆" to "Разрешите установку и нажмите ещё раз"
        is Updater.State.Downloading -> "${s.percent}%" to "Загрузка ${s.percent} %"
        is Updater.State.Installing -> "…" to "Установка…"
        is Updater.State.Failed -> "!" to "Не удалось: ${s.message.take(60)} — ещё раз"
    }
    Surface(
        onClick = { scope.launch { updater.install() } },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = K.surfaceHi, contentColor = K.accent,
            focusedContainerColor = K.accent, focusedContentColor = K.bg,
        ),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        modifier = modifier.height(40.dp).widthIn(min = 40.dp).onFocusChanged { focused = it.isFocused },
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = if (focused) 16.dp else 8.dp), contentAlignment = Alignment.Center) {
            Text(if (focused) long else short, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
