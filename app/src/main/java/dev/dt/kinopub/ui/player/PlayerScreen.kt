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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.tv.material3.FilterChipDefaults
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
 *   OK / play-pause  → pause/resume + OSD     ← / → → −10 s / +10 s (hold to keep seeking)
 *   ↓                → tracks panel           ↑      → OSD
 *   audio-track key  → next voiceover         CC key → subtitles on/off
 *   Back             → close tracks panel, else exit (progress is saved)
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
    // Outside the tracks panel the root owns focus, so the D-pad always works — including the
    // first seconds with the OSD up and right after the panel closes (its chips took focus with them).
    LaunchedEffect(overlay) {
        if (overlay != Overlay.TRACKS) repeat(10) {
            if (runCatching { rootFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(30)
        }
    }
    LaunchedEffect(overlay, lastInput) {
        if (overlay == Overlay.OSD) {
            delay(4_000)
            if (vm.player.isPlaying) overlay = Overlay.NONE
        }
    }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var panelHasFocus by remember { mutableStateOf(false) }
    var panelFocusTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(seekHint, lastInput) { if (seekHint != null) { delay(1_200); seekHint = null } }
    /** Holding ← / → speeds up: 10 s → 30 s → 1 min → 2 min per step. */
    fun seek(forward: Boolean, repeat: Int = 0) {
        val step = seekStepMs(repeat)
        val p = vm.player
        p.seekTo((p.currentPosition + if (forward) step else -step).coerceIn(0, maxOf(p.duration, 0)))
        seekHint = (if (forward) "+" else "−") + stepLabel(step)
        overlay = if (overlay == Overlay.TRACKS) Overlay.TRACKS else Overlay.OSD
    }

    BackHandler {
        // Only the tracks panel is "modal"; the OSD is informational, so Back exits right away.
        if (overlay == Overlay.TRACKS) overlay = Overlay.OSD else onExit()
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
                if (overlay == Overlay.TRACKS) {
                    // Panel handles its own D-pad — unless focus never made it into the panel
                    // (then the root still holds it and every key would be lost). Pull it in.
                    if (!panelHasFocus && e.key in DPAD_KEYS) { panelFocusTick++; return@onPreviewKeyEvent true }
                    return@onPreviewKeyEvent false
                }
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
                    Key.DirectionLeft, Key.MediaRewind -> { seek(forward = false, e.nativeKeyEvent.repeatCount); true }
                    Key.DirectionRight, Key.MediaFastForward -> { seek(forward = true, e.nativeKeyEvent.repeatCount); true }
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
                    // PlayerView handles D-pad itself and could steal focus from the Compose root,
                    // which made ↓ / ← / → randomly do nothing on the TV.
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
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
                Osd(vm, ui, seekHint, seekable = overlay == Overlay.TRACKS)
                if (overlay == Overlay.TRACKS) TracksPanel(vm, ui, panelFocusTick) { panelHasFocus = it }
            }
        }
    }
}

/**
 * Title, seek bar, times, hint and a stats line (resolution · codec/HDR · bitrate · network · buffer).
 *
 * With the tracks panel open the seek bar is focusable (↑ from the first chip row) and works as a
 * scrubber: ← / → move a marker (accelerating when held) with a preview frame of that moment
 * above it; OK jumps there; leaving the bar cancels.
 */
@Composable
private fun Osd(vm: PlayerController, ui: PlayerUi, seekHint: String?, seekable: Boolean) {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var stats by remember { mutableStateOf("") }
    var barFocused by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var scrubHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            pos = vm.player.currentPosition
            dur = vm.player.duration.coerceAtLeast(0)
            stats = vm.stats()
            delay(500)
        }
    }
    DisposableEffect(Unit) { onDispose { vm.releasePreview() } }

    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(ui.title, style = MaterialTheme.typography.headlineSmall, color = K.text)
        ui.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = K.muted) }
    }

    // Preview frame above the marker while scrubbing.
    scrubMs?.let { s ->
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val frac = if (dur > 0) (s.toFloat() / dur).coerceIn(0f, 1f) else 0f
            val w = 320.dp
            val x = (maxWidth - w) * frac
            Column(Modifier.padding(start = x), horizontalAlignment = Alignment.CenterHorizontally) {
                AndroidView(
                    factory = { ctx ->
                        android.view.TextureView(ctx).also { tv -> vm.previewPlayer()?.setVideoTextureView(tv) }
                    },
                    modifier = Modifier.width(w).height(180.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black),
                )
                Text(clock(s), style = MaterialTheme.typography.titleMedium, color = K.accent)
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .then(
                if (seekable) Modifier
                    .onFocusChanged {
                        barFocused = it.isFocused
                        if (!it.isFocused) { scrubMs = null; vm.releasePreview() }
                    }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft, Key.DirectionRight -> {
                                val step = seekStepMs(e.nativeKeyEvent.repeatCount)
                                val fwd = e.key == Key.DirectionRight
                                val next = ((scrubMs ?: pos) + if (fwd) step else -step).coerceIn(0, dur)
                                scrubMs = next
                                scrubHint = (if (fwd) "+" else "−") + stepLabel(step)
                                vm.previewAt(next)
                                true
                            }
                            Key.DirectionCenter, Key.Enter -> {
                                val s = scrubMs
                                if (s != null) { vm.player.seekTo(s); scrubMs = null; vm.releasePreview() }
                                else if (vm.player.isPlaying) vm.player.pause() else vm.player.play()
                                true
                            }
                            Key.Back -> if (scrubMs != null) { scrubMs = null; vm.releasePreview(); true } else false
                            else -> false
                        }
                    }
                    .focusable()
                else Modifier,
            ),
    ) {
        ProgressLine(if (dur > 0) pos.toFloat() / dur else 0f, height = if (barFocused) 8.dp else 4.dp)
        scrubMs?.let { s ->
            // Marker at the scrub position.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                val frac = if (dur > 0) (s.toFloat() / dur).coerceIn(0f, 1f) else 0f
                Box(Modifier.padding(start = (maxWidth - 4.dp) * frac).width(4.dp).height(16.dp).background(Color.White))
            }
        }
    }
    Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(clock(pos), style = MaterialTheme.typography.labelMedium, color = K.muted)
        (if (scrubMs != null) scrubHint else seekHint)?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = K.accent) }
        Text("−" + clock((dur - pos).coerceAtLeast(0)), style = MaterialTheme.typography.labelMedium, color = K.muted)
    }
    Text(
        when {
            scrubMs != null -> "← →  выбрать момент  ·  OK — перейти  ·  ↓ — отмена"
            barFocused -> "← →  показать кадр и выбрать момент  ·  OK — пауза"
            seekable -> "← →  ±10 с, удерживайте — быстрее  ·  ↑ на полосу — перемотка с превью"
            else -> "← →  ±10 с, удерживайте — быстрее  ·  ↓ — озвучка, субтитры, качество"
        },
        style = MaterialTheme.typography.labelMedium, color = K.muted,
    )
    if (stats.isNotEmpty()) Text(stats, style = MaterialTheme.typography.labelMedium, color = K.muted.copy(alpha = 0.8f))
}

/** Accelerating step while a seek key is held (repeatCount from the key event). */
private fun seekStepMs(repeat: Int): Long = when {
    repeat < 4 -> 10_000L
    repeat < 12 -> 30_000L
    repeat < 24 -> 60_000L
    else -> 120_000L
}

private fun stepLabel(ms: Long): String = if (ms >= 60_000) "${ms / 60_000} мин" else "${ms / 1000} с"

/**
 * Every option of every kind on screen at once: one horizontal row per track type.
 * Current choice gets focus when the panel opens, so ← → OK is the whole interaction.
 */
@Composable
private fun TracksPanel(vm: PlayerController, ui: PlayerUi, focusTick: Int, onFocusInside: (Boolean) -> Unit) {
    val audioFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    // Focus the current voiceover (its row scrolls to it first); fall back to the first chip.
    LaunchedEffect(focusTick) {
        repeat(15) {
            if (runCatching { audioFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            if (it > 5 && runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }

    Column(
        Modifier
            .onFocusChanged { onFocusInside(it.hasFocus) }
            .clip(RoundedCornerShape(10.dp)).background(K.surface.copy(alpha = 0.92f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TrackRow("Озвучка", ui.audio, audioFocus, firstFocus) { i, _ -> vm.selectAudio(i) }
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
    firstFocus: FocusRequester? = null,
    onPick: (Int, TrackOption) -> Unit,
) {
    if (options.isEmpty()) return
    val selectedIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    // An off-screen chip isn't composed and can't take focus: bring the selected one into view.
    LaunchedEffect(focus, selectedIndex) { if (focus != null && selectedIndex > 0) rowState.scrollToItem(selectedIndex) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = K.muted, modifier = Modifier.width(96.dp))
        LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
            itemsIndexed(options, key = { _, o -> o.key }) { i, o ->
                FilterChip(
                    selected = o.selected,
                    onClick = { onPick(i, o) },
                    // No zoom on focus: scaled chips overlapped their neighbours on the TV.
                    scale = FilterChipDefaults.scale(focusedScale = 1f),
                    colors = FilterChipDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = K.muted,
                        selectedContainerColor = K.surfaceHi,
                        selectedContentColor = K.text,
                        focusedContainerColor = K.accent,
                        focusedContentColor = K.onAccent,
                        focusedSelectedContainerColor = K.accent,
                        focusedSelectedContentColor = K.onAccent,
                    ),
                    modifier = Modifier
                        .then(if (focus != null && i == selectedIndex) Modifier.focusRequester(focus) else Modifier)
                        .then(if (firstFocus != null && i == 0) Modifier.focusRequester(firstFocus) else Modifier),
                ) {
                    Text(listOfNotNull(o.label, o.detail).joinToString(" · "), maxLines = 1)
                }
            }
        }
    }
}

private val DPAD_KEYS = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter)

private fun clock(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
