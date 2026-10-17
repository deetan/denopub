package dev.dt.kinopub.ui.player

import android.app.Application
import android.view.KeyEvent as AKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.sp
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

private enum class Overlay { NONE, FINE, OSD, TRACKS }

/** Fine stepping: a tap = 0.5 s; holding speeds up to 1 s, then 3 s per step. */
private fun fineStepMs(repeat: Int) = when {
    repeat < 3 -> 500L
    repeat < 10 -> 1_000L
    else -> 3_000L
}

/**
 * Remote map:
 *   ↑ (from video)   → FINE: thin top bar, ← → step 0.5 s (held: 1 s, 3 s), pauses; hides in 3 s
 *   Rule everywhere: ↓ reveals the next level, ↑ hides the current one
 *   ↓ once           → OSD: seek bar with position marker; ← → scrub with a preview frame, commits
 *                      ~1 s after release or on OK
 *   ↓ again          → TRACKS: voiceover / subtitles / quality panel; ↑ from its top row → OSD
 *   ← / → (video)    → opens OSD and starts scrubbing (accelerates while held)
 *   OK / play-pause  → pause/resume; paused = full-screen dim with a pause sign
 *   audio-track key  → next voiceover         CC key → subtitles on/off
 *   Back             → panel → OSD, scrub → cancel, otherwise exit (progress is saved)
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

    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var stats by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            pos = vm.player.currentPosition
            dur = vm.player.duration.coerceAtLeast(0)
            playing = vm.player.isPlaying || vm.player.playbackState == androidx.media3.common.Player.STATE_BUFFERING
            stats = vm.stats()
            delay(250)
        }
    }

    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var scrubHint by remember { mutableStateOf<String?>(null) }
    var scrubReleaseTick by remember { mutableIntStateOf(0) }
    var fineLabel by remember { mutableStateOf("0,5 с") }
    var panelHasFocus by remember { mutableStateOf(false) }
    var panelFocusTick by remember { mutableIntStateOf(0) }

    fun togglePause() { if (vm.player.isPlaying) vm.player.pause() else vm.player.play() }
    fun cancelScrub() { scrubMs = null; vm.releasePreview() }
    fun commitScrub() {
        val s = scrubMs ?: return
        vm.player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT)
        vm.player.seekTo(s)
        cancelScrub()
    }
    fun scrub(forward: Boolean, repeat: Int) {
        val step = seekStepMs(repeat)
        val next = ((scrubMs ?: pos) + if (forward) step else -step).coerceIn(0, maxOf(dur, 0))
        scrubMs = next
        scrubHint = (if (forward) "+" else "−") + stepLabel(step)
        vm.previewAt(next)
    }
    fun fine(forward: Boolean, repeat: Int) {
        if (vm.player.isPlaying) vm.player.pause()
        vm.player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        val step = fineStepMs(repeat)
        fineLabel = (if (forward) "+" else "−") + stepLabelFine(step)
        vm.player.seekTo((vm.player.currentPosition + if (forward) step else -step).coerceIn(0, maxOf(dur, 0)))
    }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false; vm.saveProgress(); vm.releasePreview() }
    }
    // Outside the tracks panel the root owns focus, so the D-pad always works.
    LaunchedEffect(overlay) {
        if (overlay != Overlay.TRACKS) repeat(10) {
            if (runCatching { rootFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(30)
        }
    }
    // Auto-hide: OSD after 4 s, the fine bar after 3 s of no input (not while scrubbing).
    LaunchedEffect(overlay, lastInput, scrubMs == null) {
        when (overlay) {
            Overlay.OSD -> if (scrubMs == null) { delay(4_000); if (vm.player.isPlaying) overlay = Overlay.NONE }
            Overlay.FINE -> { delay(3_000); overlay = Overlay.NONE }
            else -> Unit
        }
    }
    // Releasing ← / → while scrubbing: jump there after a beat (OK jumps at once).
    LaunchedEffect(scrubReleaseTick) {
        if (scrubReleaseTick == 0) return@LaunchedEffect
        delay(900)
        commitScrub()
    }

    BackHandler {
        when {
            overlay == Overlay.TRACKS -> overlay = Overlay.OSD
            scrubMs != null -> cancelScrub()
            else -> onExit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                val dpadLR = e.key == Key.DirectionLeft || e.key == Key.DirectionRight
                if (e.type == KeyEventType.KeyUp) {
                    if (dpadLR && overlay == Overlay.OSD && scrubMs != null) scrubReleaseTick++
                    return@onPreviewKeyEvent false
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastInput = System.currentTimeMillis()
                if (overlay == Overlay.TRACKS) {
                    // The panel handles its own D-pad — unless focus never made it in.
                    if (!panelHasFocus && e.key in DPAD_KEYS) { panelFocusTick++; return@onPreviewKeyEvent true }
                    return@onPreviewKeyEvent false
                }
                when (e.nativeKeyEvent.keyCode) {
                    AKeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { vm.cycleAudio(); overlay = Overlay.OSD; return@onPreviewKeyEvent true }
                    AKeyEvent.KEYCODE_CAPTIONS -> { vm.toggleSubtitles(); overlay = Overlay.OSD; return@onPreviewKeyEvent true }
                    AKeyEvent.KEYCODE_MEDIA_NEXT -> { vm.playNext(); return@onPreviewKeyEvent true }
                }
                val repeat = e.nativeKeyEvent.repeatCount
                when (e.key) {
                    Key.MediaPlayPause, Key.Spacebar -> { togglePause(); true }
                    Key.MediaPlay -> { vm.player.play(); true }
                    Key.MediaPause -> { vm.player.pause(); true }
                    Key.MediaRewind -> { overlay = Overlay.OSD; scrub(false, repeat); scrubReleaseTick++; true }
                    Key.MediaFastForward -> { overlay = Overlay.OSD; scrub(true, repeat); scrubReleaseTick++; true }
                    else -> when (overlay) {
                        Overlay.FINE -> when (e.key) {
                            Key.DirectionLeft -> { fine(false, repeat); true }
                            Key.DirectionRight -> { fine(true, repeat); true }
                            // ↓ reveals, ↑ hides — the same rule on every level.
                            Key.DirectionUp -> { overlay = Overlay.NONE; true }
                            Key.DirectionDown -> { overlay = Overlay.OSD; true }
                            Key.DirectionCenter, Key.Enter -> { togglePause(); true }
                            else -> false
                        }
                        Overlay.OSD -> when (e.key) {
                            Key.DirectionLeft -> { scrub(false, repeat); true }
                            Key.DirectionRight -> { scrub(true, repeat); true }
                            Key.DirectionDown -> { cancelScrub(); overlay = Overlay.TRACKS; true }
                            Key.DirectionUp -> { cancelScrub(); overlay = Overlay.NONE; true }
                            Key.DirectionCenter, Key.Enter -> { if (scrubMs != null) commitScrub() else togglePause(); true }
                            else -> false
                        }
                        else -> when (e.key) { // NONE
                            Key.DirectionUp -> { overlay = Overlay.FINE; true }
                            Key.DirectionDown -> { overlay = Overlay.OSD; true }
                            Key.DirectionLeft -> { overlay = Overlay.OSD; scrub(false, repeat); true }
                            Key.DirectionRight -> { overlay = Overlay.OSD; scrub(true, repeat); true }
                            Key.DirectionCenter, Key.Enter -> { togglePause(); overlay = Overlay.OSD; true }
                            else -> false
                        }
                    }
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                // PlayerView defaults to SurfaceView: required for HDR/Dolby Vision output.
                PlayerView(ctx).apply {
                    useController = false
                    // PlayerView handles D-pad itself and could steal focus from the Compose root.
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

        // Paused: dim the picture and show a big pause sign (not while stepping frames).
        if (!playing && overlay != Overlay.FINE && ui.error == null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Text("❚❚", fontSize = 120.sp, color = Color.White.copy(alpha = 0.9f))
            }
        }

        ui.error?.let {
            Text("Ошибка воспроизведения: $it", color = K.badge, modifier = Modifier.align(Alignment.Center))
        }

        if (overlay == Overlay.FINE) {
            Column(
                Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = K.screenPadH, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SeekBar(pos, dur, null, emphasized = true)
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(clockMs(pos), style = MaterialTheme.typography.titleMedium, color = K.accent)
                    Text(
                        "покадрово: ← → по 0,5 с (удерживайте — быстрее) $fineLabel  ·  OK пауза  ·  ↑ скрыть",
                        style = MaterialTheme.typography.labelMedium, color = K.muted,
                    )
                }
            }
        }

        if (overlay == Overlay.OSD || overlay == Overlay.TRACKS) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = K.screenPadH, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(ui.title, style = MaterialTheme.typography.headlineSmall, color = K.text)
                    ui.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = K.muted) }
                }
                // Preview frame above the scrub marker.
                scrubMs?.let { s ->
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val frac = if (dur > 0) (s.toFloat() / dur).coerceIn(0f, 1f) else 0f
                        val w = 320.dp
                        Column(Modifier.padding(start = (maxWidth - w) * frac), horizontalAlignment = Alignment.CenterHorizontally) {
                            AndroidView(
                                factory = { ctx -> android.view.TextureView(ctx).also { tv -> vm.previewPlayer()?.setVideoTextureView(tv) } },
                                modifier = Modifier.width(w).height(180.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black),
                            )
                            Text(clock(s), style = MaterialTheme.typography.titleMedium, color = K.accent)
                        }
                    }
                }
                SeekBar(pos, dur, scrubMs, emphasized = overlay == Overlay.OSD)
                Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(clock(pos), style = MaterialTheme.typography.labelMedium, color = K.muted)
                    if (scrubMs != null) scrubHint?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = K.accent) }
                    Text("−" + clock((dur - pos).coerceAtLeast(0)), style = MaterialTheme.typography.labelMedium, color = K.muted)
                }
                if (overlay == Overlay.OSD) {
                    Text(
                        if (scrubMs != null) "← →  выбрать момент (удерживайте — быстрее)  ·  отпустите или OK — перейти  ·  Back — отмена"
                        else "← →  перемотка с превью, удерживайте — быстрее  ·  ↓ — озвучка, субтитры, качество  ·  ↑ — покадрово",
                        style = MaterialTheme.typography.labelMedium, color = K.muted,
                    )
                    if (stats.isNotEmpty()) Text(stats, style = MaterialTheme.typography.labelMedium, color = K.muted.copy(alpha = 0.8f))
                }
                if (overlay == Overlay.TRACKS) {
                    TracksPanel(vm, ui, panelFocusTick, onFocusInside = { panelHasFocus = it }, onExitUp = { overlay = Overlay.OSD })
                }
            }
        }
    }
}

/** Track + played part + a vertical marker at the current position (and at the scrub point). */
@Composable
private fun SeekBar(pos: Long, dur: Long, scrubMs: Long?, emphasized: Boolean) {
    val h = if (emphasized) 8.dp else 4.dp
    BoxWithConstraints(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        ProgressLine(frac, height = h)
        Box(Modifier.padding(start = (maxWidth - 4.dp) * frac).width(4.dp).height(22.dp).background(Color.White))
        scrubMs?.let { s ->
            val sf = if (dur > 0) (s.toFloat() / dur).coerceIn(0f, 1f) else 0f
            Box(Modifier.padding(start = (maxWidth - 4.dp) * sf).width(4.dp).height(22.dp).background(K.accent))
        }
    }
}

private fun stepLabelFine(ms: Long) = if (ms < 1000) "0,5 с" else "${ms / 1000} с"

private fun clockMs(ms: Long): String {
    val tenths = (ms % 1000) / 100
    return clock(ms) + ",$tenths"
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
private fun TracksPanel(
    vm: PlayerController,
    ui: PlayerUi,
    focusTick: Int,
    onFocusInside: (Boolean) -> Unit,
    onExitUp: () -> Unit,
) {
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
        // ↑ from the top row leaves the panel and brings the seek bar back.
        Box(Modifier.onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { onExitUp(); true } else false
        }) {
            TrackRow("Озвучка", ui.audio, audioFocus, firstFocus) { i, _ -> vm.selectAudio(i) }
        }
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
