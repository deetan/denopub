package dev.dt.kinopub.ui.player

import android.app.Application
import android.view.KeyEvent as AKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.dt.kinopub.AppContainer
import dev.dt.kinopub.data.ResumeTarget
import dev.dt.kinopub.ui.common.ProgressLine
import dev.dt.kinopub.ui.theme.K
import kotlinx.coroutines.delay

private enum class Overlay { NONE, OSD, TRACKS }

/**
 * Remote map (overlay hidden):
 *   OK / play-pause  → pause/resume + OSD     ← / →  → −10 s / +30 s
 *   ↓                → tracks panel           ↑      → OSD
 *   audio-track key  → next voiceover         CC key → subtitles on/off
 *   Back             → close overlay, else exit (progress is saved)
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(c: AppContainer, start: ResumeTarget, onExit: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: PlayerController = viewModel(
        key = "player-${start.itemId}",
        factory = viewModelFactory { initializer { PlayerController(app, c, start) } },
    )
    val ui by vm.ui.collectAsState()
    var overlay by remember { mutableStateOf(Overlay.OSD) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val rootFocus = remember { FocusRequester() }
    val view = LocalView.current

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false; vm.saveProgress() }
    }
    // OSD auto-hides; the tracks panel stays until Back so you can read it.
    LaunchedEffect(overlay, lastInput) {
        if (overlay == Overlay.OSD) {
            delay(4_000)
            if (vm.player.isPlaying) overlay = Overlay.NONE
        }
        if (overlay == Overlay.NONE) runCatching { rootFocus.requestFocus() }
    }

    BackHandler {
        if (overlay != Overlay.NONE) overlay = Overlay.NONE else onExit()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastInput = System.currentTimeMillis()
                if (overlay == Overlay.TRACKS) return@onPreviewKeyEvent false // panel handles its own D-pad
                when (e.nativeKeyEvent.keyCode) {
                    AKeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { vm.cycleAudio(); overlay = Overlay.OSD; return@onPreviewKeyEvent true }
                    AKeyEvent.KEYCODE_CAPTIONS -> { vm.toggleSubtitles(); overlay = Overlay.OSD; return@onPreviewKeyEvent true }
                    AKeyEvent.KEYCODE_MEDIA_NEXT -> { vm.playNext(); return@onPreviewKeyEvent true }
                }
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause, Key.Spacebar -> {
                        if (vm.player.isPlaying) vm.player.pause() else vm.player.play()
                        overlay = Overlay.OSD; true
                    }
                    Key.MediaPlay -> { vm.player.play(); true }
                    Key.MediaPause -> { vm.player.pause(); overlay = Overlay.OSD; true }
                    Key.DirectionLeft, Key.MediaRewind -> { vm.player.seekBack(); overlay = Overlay.OSD; true }
                    Key.DirectionRight, Key.MediaFastForward -> { vm.player.seekForward(); overlay = Overlay.OSD; true }
                    Key.DirectionDown -> { overlay = Overlay.TRACKS; true }
                    Key.DirectionUp -> { overlay = Overlay.OSD; true }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                // PlayerView defaults to SurfaceView: required for HDR/Dolby Vision output.
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    player = vm.player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        ui.error?.let {
            Text("Ошибка воспроизведения: $it", color = K.badge, modifier = Modifier.align(Alignment.Center))
        }

        if (overlay != Overlay.NONE) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = K.screenPadH, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Osd(vm, ui)
                if (overlay == Overlay.TRACKS) TracksPanel(vm, ui)
            }
        }
    }
}

@Composable
private fun Osd(vm: PlayerController, ui: PlayerUi) {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            pos = vm.player.currentPosition
            dur = vm.player.duration.coerceAtLeast(0)
            delay(500)
        }
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(ui.title, style = MaterialTheme.typography.headlineSmall, color = K.text)
        ui.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = K.muted) }
    }
    ProgressLine(if (dur > 0) pos.toFloat() / dur else 0f)
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(clock(pos), style = MaterialTheme.typography.labelMedium, color = K.muted)
        Text("−" + clock((dur - pos).coerceAtLeast(0)), style = MaterialTheme.typography.labelMedium, color = K.muted)
    }
}

/**
 * Every option of every kind on screen at once: one horizontal row per track type.
 * Current choice gets focus when the panel opens, so ← → OK is the whole interaction.
 */
@Composable
private fun TracksPanel(vm: PlayerController, ui: PlayerUi) {
    val audioFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { if (runCatching { audioFocus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(50) } }

    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).background(K.surface.copy(alpha = 0.92f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TrackRow("Озвучка", ui.audio, audioFocus) { i, _ -> vm.selectAudio(i) }
        TrackRow("Субтитры", ui.subtitles, null) { i, _ -> vm.selectSubtitle(if (i == 0) null else i - 1) }
        TrackRow("Качество", ui.qualities, null) { _, o -> o.key.removePrefix("q").toIntOrNull()?.let(vm::selectQuality) }
        if (ui.hasNext) {
            TrackRow("Дальше", listOf(TrackOption("next", "Следующая серия ›", null, false)), null) { _, _ -> vm.playNext() }
        }
    }
}

@Composable
private fun TrackRow(
    title: String,
    options: List<TrackOption>,
    focus: FocusRequester?,
    onPick: (Int, TrackOption) -> Unit,
) {
    if (options.isEmpty()) return
    val selectedIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = K.muted, modifier = Modifier.width(96.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 24.dp)) {
            itemsIndexed(options, key = { _, o -> o.key }) { i, o ->
                FilterChip(
                    selected = o.selected,
                    onClick = { onPick(i, o) },
                    modifier = if (focus != null && i == selectedIndex) Modifier.focusRequester(focus) else Modifier,
                ) {
                    Text(listOfNotNull(o.label, o.detail).joinToString(" · "), maxLines = 1)
                }
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
