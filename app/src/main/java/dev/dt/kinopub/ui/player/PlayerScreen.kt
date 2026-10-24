package dev.dt.kinopub.ui.player

import android.app.Application
import android.view.KeyEvent as AKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.dt.kinopub.AppContainer
import dev.dt.kinopub.data.ResumeTarget
import dev.dt.kinopub.ui.common.ProgressLine
import dev.dt.kinopub.ui.common.RateDialog
import dev.dt.kinopub.ui.common.splitTitle
import dev.dt.kinopub.ui.theme.K
import kotlinx.coroutines.delay

private enum class Overlay { NONE, FINE, OSD, TRACKS }

/** Fine stepping by how long the key has been held: 0.5 s → 1 s → 2 s → 5 s per step. */
private fun fineStepMs(heldMs: Long) = when {
    heldMs < 600 -> 500L
    heldMs < 1_500 -> 1_000L
    heldMs < 3_000 -> 2_000L
    else -> 5_000L
}

/**
 * Remote map:
 *   ↑ (from video)   → FINE mode: top bar, ← → step 0.5 s (held: 1 s, 2 s, 5 s), pauses. The mode stays
 *                      until ↓, OK (play/pause) or Back; the bar hides after 3 s idle and returns on the next ← →
 *   Rule everywhere: ↓ reveals the next level, ↑ hides the current one
 *   ↓ once           → OSD: seek bar with position marker; ← → scrub with a preview frame, commits
 *                      ~1 s after release or on OK
 *   ↓ again          → TRACKS: voiceover / subtitles / quality panel; ↑ from its top row → OSD
 *   ← / → (video)    → quick jump: a bare seek bar at the bottom, no text. Steps grow over a burst
 *                      of presses / a hold: 3 s ×3 → 5 s ×3 → 10 s ×4 → 30 s ×4 → 1 min ×4 → 2 min ×4 → 3 min
 *   OK / play-pause  → pause/resume; a big pause sign fades into a small corner badge
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
    var rootFocused by remember { mutableStateOf(false) }
    val view = LocalView.current

    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var wantsPlay by remember { mutableStateOf(true) }
    var stats by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            pos = vm.player.currentPosition
            dur = vm.player.duration.coerceAtLeast(0)
            wantsPlay = vm.player.playWhenReady
            stats = vm.stats()
            delay(250)
        }
    }

    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var scrubHint by remember { mutableStateOf<String?>(null) }
    var scrubReleaseTick by remember { mutableIntStateOf(0) }
    var fineLabel by remember { mutableStateOf("0,5 с") }
    // FINE is a mode you leave yourself (↓ or Back); only its bar hides when idle.
    var fineBar by remember { mutableStateOf(true) }
    // When the current ← / → hold started: speed grows with hold time, not with press count.
    var holdStart by remember { mutableLongStateOf(0L) }
    fun held(repeat: Int): Long {
        val now = System.currentTimeMillis()
        if (repeat == 0) holdStart = now
        return now - holdStart
    }
    var panelHasFocus by remember { mutableStateOf(false) }
    var panelFocusTick by remember { mutableIntStateOf(0) }

    // Pause sign: big for a moment after the user pauses, then a small badge. Pauses made by
    // frame stepping show nothing — that's a still frame you're looking at.
    var userPaused by remember { mutableStateOf(false) }
    var bigPause by remember { mutableStateOf(false) }
    var pauseTick by remember { mutableIntStateOf(0) }
    fun userPause() { vm.player.pause(); userPaused = true; pauseTick++ }
    fun togglePause() { if (vm.player.playWhenReady) userPause() else vm.player.play() }
    // Play/pause always ends fine mode: you're back to plain watching, no top bar.
    fun leaveFine() { if (overlay == Overlay.FINE) overlay = Overlay.NONE }

    // Quick jump (← / → with no overlay): seeks right away, shows only a bare seek bar.
    var jumpSteps by remember { mutableIntStateOf(0) }
    var jumpForward by remember { mutableStateOf(true) }
    var jumpAt by remember { mutableLongStateOf(0L) }
    var jumpVisible by remember { mutableStateOf(false) }
    fun quickSeek(forward: Boolean, repeat: Int) {
        val now = System.currentTimeMillis()
        // Auto-repeat fires every ~50 ms: one step per 220 ms while held.
        if (repeat > 0 && now - jumpAt < 220) return
        if (!(jumpVisible && jumpForward == forward && now - jumpAt < 1_500)) jumpSteps = 0
        jumpSteps++; jumpForward = forward; jumpAt = now
        val step = jumpStepMs(jumpSteps)
        val from = vm.player.currentPosition
        val to = (from + if (forward) step else -step).coerceIn(0, maxOf(dur, 0))
        jumpVisible = true
        vm.player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        vm.player.seekTo(to)
        pos = to
    }
    fun cancelScrub() { scrubMs = null; vm.releasePreview() }
    fun commitScrub() {
        val s = scrubMs ?: return
        vm.player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT)
        vm.player.seekTo(s)
        cancelScrub()
    }
    fun scrub(forward: Boolean, repeat: Int) {
        val step = seekStepMs(held(repeat))
        val next = ((scrubMs ?: pos) + if (forward) step else -step).coerceIn(0, maxOf(dur, 0))
        scrubMs = next
        scrubHint = (if (forward) "+" else "−") + stepLabel(step)
    }
    fun fine(forward: Boolean, repeat: Int) {
        fineBar = true
        if (vm.player.playWhenReady) vm.player.pause()
        userPaused = false; bigPause = false
        vm.player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        val step = fineStepMs(held(repeat))
        fineLabel = (if (forward) "+" else "−") + stepLabelFine(step)
        vm.player.seekTo((vm.player.currentPosition + if (forward) step else -step).coerceIn(0, maxOf(dur, 0)))
    }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false; vm.saveProgress(); vm.releasePreview() }
    }
    // Outside the tracks panel the root owns focus, so the D-pad always works.
    LaunchedEffect(overlay) {
        // requestFocus() "succeeds" while a panel card is still focused (the panel is fading out),
        // and focus then vanished with the card: the remote went dead after picking an episode.
        // Keep asking until the root really holds focus.
        if (overlay != Overlay.TRACKS) repeat(40) {
            runCatching { rootFocus.requestFocus() }
            delay(30)
            if (rootFocused && it >= 10) return@LaunchedEffect
        }
    }
    // Auto-hide: OSD after 4 s, the fine bar after 3 s of no input (not while scrubbing).
    LaunchedEffect(overlay, lastInput, scrubMs == null) {
        when (overlay) {
            Overlay.OSD -> if (scrubMs == null) { delay(4_000); if (vm.player.isPlaying) overlay = Overlay.NONE }
            Overlay.FINE -> { fineBar = true; delay(3_000); fineBar = false }
            else -> Unit
        }
    }
    LaunchedEffect(pauseTick) {
        if (pauseTick == 0) return@LaunchedEffect
        bigPause = true
        delay(1_200)
        bigPause = false
    }
    LaunchedEffect(wantsPlay) { if (wantsPlay) { userPaused = false; bigPause = false } }
    LaunchedEffect(jumpAt) {
        delay(1_500)
        jumpVisible = false
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
            overlay == Overlay.FINE -> overlay = Overlay.NONE
            scrubMs != null -> cancelScrub()
            // Leaving a movie in its credits: ask for the IMDb rating first (once per title).
            vm.offerRating() -> Unit
            else -> onExit()
        }
    }
    val ratePrompt by vm.rate.collectAsState()
    ratePrompt?.let { p -> RateDialog(c, p.imdbId, splitTitle(p.title).first, p.current) { onExit() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onFocusChanged { rootFocused = it.isFocused }
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
                    Key.MediaPlayPause, Key.Spacebar -> { leaveFine(); togglePause(); true }
                    Key.MediaPlay -> { leaveFine(); vm.player.play(); true }
                    Key.MediaPause -> { leaveFine(); userPause(); true }
                    Key.MediaRewind -> { quickSeek(false, repeat); true }
                    Key.MediaFastForward -> { quickSeek(true, repeat); true }
                    else -> when (overlay) {
                        Overlay.FINE -> when (e.key) {
                            Key.DirectionLeft -> { fine(false, repeat); true }
                            Key.DirectionRight -> { fine(true, repeat); true }
                            // Sticky: only ↓, OK (play/pause) or Back leave; ↑ just brings the bar back.
                            Key.DirectionUp -> { fineBar = true; true }
                            Key.DirectionDown -> { overlay = Overlay.NONE; true }
                            Key.DirectionCenter, Key.Enter -> { leaveFine(); togglePause(); true }
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
                            Key.DirectionLeft -> { quickSeek(false, repeat); true }
                            Key.DirectionRight -> { quickSeek(true, repeat); true }
                            // Just pause/resume: the pause sign is enough, no seek bar.
                            Key.DirectionCenter, Key.Enter -> { togglePause(); true }
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

        // Just paused: big sign over a dimmed picture, fading out after a moment…
        AnimatedVisibility(
            visible = bigPause && !wantsPlay && ui.error == null,
            enter = fadeIn(tween(150)), exit = fadeOut(tween(700)),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                Text("❚❚", fontSize = 120.sp, color = Color.White.copy(alpha = 0.9f))
            }
        }
        // …leaving a small badge in the corner so the still picture stays clean.
        AnimatedVisibility(
            visible = userPaused && !bigPause && !wantsPlay && overlay == Overlay.NONE && ui.error == null,
            enter = fadeIn(tween(500)), exit = fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 36.dp),
        ) {
            Text(
                "❚❚", fontSize = 26.sp, color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        // Quick jump: just the seek bar at the bottom, no words — enough to see roughly where you are.
        AnimatedVisibility(
            visible = jumpVisible && overlay == Overlay.NONE,
            enter = fadeIn(tween(120)), exit = fadeOut(tween(400)),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))))
                    .padding(horizontal = K.screenPadH, vertical = 20.dp),
            ) {
                SeekBar(pos, dur, null, emphasized = false)
            }
        }

        ui.error?.let {
            Text("Ошибка воспроизведения: $it", color = K.badge, modifier = Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
            visible = overlay == Overlay.FINE && fineBar,
            enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(180)) { -it } + fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = K.screenPadH, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SeekBar(pos, dur, null, emphasized = true)
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(clockMs(pos), style = MaterialTheme.typography.titleMedium, color = K.accent)
                    Text(fineLabel, style = MaterialTheme.typography.titleMedium, color = K.muted)
                }
            }
        }

        AnimatedVisibility(
            visible = overlay == Overlay.OSD || overlay == Overlay.TRACKS,
            enter = slideInVertically(tween(240)) { it } + fadeIn(tween(240)),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = K.screenPadH, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(ui.title, style = MaterialTheme.typography.headlineSmall, color = K.text)
                    ui.episodeLabel?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = K.muted) }
                }
                // Target time above the scrub marker (no preview frame: the owner wants just the bar).
                scrubMs?.let { s ->
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val frac = if (dur > 0) (s.toFloat() / dur).coerceIn(0f, 1f) else 0f
                        val w = 120.dp
                        Box(Modifier.padding(start = (maxWidth - w) * frac).width(w), contentAlignment = Alignment.Center) {
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
                    if (stats.isNotEmpty()) Text(stats, style = MaterialTheme.typography.labelMedium, color = K.muted.copy(alpha = 0.8f))
                }
                AnimatedVisibility(
                    visible = overlay == Overlay.TRACKS,
                    // One motion: the panel grows/shrinks and the bar rides with it (an outer
                    // animateContentSize used to lag behind, so the bar "fell" after the panel vanished).
                    enter = expandVertically(tween(200), expandFrom = Alignment.Top),
                    exit = shrinkVertically(tween(200), shrinkTowards = Alignment.Top),
                ) {
                    TracksPanel(
                        vm, ui, panelFocusTick, hasFocus = { panelHasFocus }, onFocusInside = { panelHasFocus = it },
                        onExitUp = { overlay = Overlay.OSD }, onEpisodePicked = { overlay = Overlay.NONE },
                    )
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

/** Scrub step by hold time: 10 s → 30 s → 1 min → 2 min → 5 min. */
private fun seekStepMs(heldMs: Long): Long = when {
    heldMs < 600 -> 10_000L
    heldMs < 1_500 -> 30_000L
    heldMs < 3_000 -> 60_000L
    heldMs < 5_000 -> 120_000L
    else -> 300_000L
}

private fun stepLabel(ms: Long): String = if (ms >= 60_000) "${ms / 60_000} мин" else "${ms / 1000} с"

/**
 * Quick-jump step by its number in the burst, kept gentle (5 min after ~10 presses was too much):
 * 3 s ×3 → 5 s ×3 → 10 s ×4 → 30 s ×4 → 1 min ×4 → 2 min ×4 → 3 min max.
 */
private fun jumpStepMs(n: Int): Long = when {
    n <= 3 -> 3_000L
    n <= 6 -> 5_000L
    n <= 10 -> 10_000L
    n <= 14 -> 30_000L
    n <= 18 -> 60_000L
    n <= 22 -> 120_000L
    else -> 180_000L
}

/**
 * Every option of every kind on screen at once: the previous / next episode on top, then one
 * horizontal row per track type. The next episode gets focus when the panel opens (else the
 * previous one, else the current voiceover), so ← → OK is the whole interaction.
 * Long-press on an episode card swaps that row for every episode of the title.
 */
@Composable
private fun TracksPanel(
    vm: PlayerController,
    ui: PlayerUi,
    focusTick: Int,
    hasFocus: () -> Boolean,
    onFocusInside: (Boolean) -> Unit,
    onExitUp: () -> Unit,
    onEpisodePicked: () -> Unit,
) {
    val audioFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    var picking by remember { mutableStateOf(false) }
    // The picker opens while OK is still held (long-press): ignore that press's repeats and
    // release, or letting go would play the focused episode. Arm on a fresh press.
    var armed by remember { mutableStateOf(true) }
    val hasEpisodes = ui.prev != null || ui.next != null
    // Focus the next episode (or the current voiceover; its row scrolls to it first); fall back
    // to the first chip. requestFocus() can "succeed" before the panel is laid out (it is still
    // expanding), which used to cost an extra key press: keep asking until focus is really inside.
    // Opening / closing the picker swaps the focused card out, so insist for the first ~200 ms
    // even if focus looks inside (it can still sit on the card that just left).
    LaunchedEffect(focusTick, picking) {
        if (picking) armed = false
        repeat(30) {
            if (it >= 5 && hasFocus()) return@LaunchedEffect
            runCatching {
                when {
                    hasEpisodes && it < 10 -> episodeFocus.requestFocus()
                    it < 10 -> audioFocus.requestFocus()
                    else -> firstFocus.requestFocus()
                }
            }
            delay(40)
        }
    }
    val play: (EpisodeCard) -> Unit = { e -> picking = false; vm.playEpisode(e.season, e.episode); onEpisodePicked() }
    // ↑ from the top row leaves the panel and brings the seek bar back.
    val exitUp = Modifier.onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { onExitUp(); true } else false
    }

    Column(
        Modifier
            .onFocusChanged { onFocusInside(it.hasFocus) }
            .onPreviewKeyEvent { e ->
                // Back: picker → episode row, else panel → seek bar. Caught as a key here: with a
                // card focused, Back never reached the BackHandler — it only cleared focus.
                if (e.key == Key.Back) {
                    if (e.type == KeyEventType.KeyUp) { if (picking) picking = false else onExitUp() }
                    return@onPreviewKeyEvent true
                }
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (!ok || armed) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) { armed = true; false } else true
            }
            .clip(RoundedCornerShape(10.dp)).background(K.surface.copy(alpha = 0.92f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (hasEpisodes) {
            Box(exitUp) {
                if (picking) EpisodePicker(ui.episodes, episodeFocus, play)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Серии", style = MaterialTheme.typography.labelMedium, color = K.muted, modifier = Modifier.width(96.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                        ui.prev?.let { e ->
                            EpisodeTile(e, "‹ Предыдущая", if (ui.next == null) episodeFocus else null, { play(e) }, { picking = true })
                        }
                        ui.next?.let { e -> EpisodeTile(e, "Следующая ›", episodeFocus, { play(e) }, { picking = true }) }
                    }
                }
            }
        }
        Box(if (hasEpisodes) Modifier else exitUp) {
            TrackRow("Озвучка", ui.audio, audioFocus, firstFocus) { i, _ -> vm.selectAudio(i) }
        }
        TrackRow("Субтитры", ui.subtitles, null) { i, _ -> vm.selectSubtitle(if (i == 0) null else i - 1) }
        TrackRow("Качество", ui.qualities, null) { _, o -> o.key.removePrefix("q").toIntOrNull()?.let(vm::selectQuality) }
    }
}

/** Every episode of the title in one row, the current one focused; OK plays, Back returns. */
@Composable
private fun EpisodePicker(episodes: List<EpisodeCard>, focus: FocusRequester, onPick: (EpisodeCard) -> Unit) {
    val at = episodes.indexOfFirst { it.current }.coerceAtLeast(0)
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    // An off-screen card isn't composed and can't take focus: scroll to the current one first.
    LaunchedEffect(Unit) { rowState.scrollToItem((at - 1).coerceAtLeast(0)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Все серии", style = MaterialTheme.typography.labelMedium, color = K.muted, modifier = Modifier.width(96.dp))
        LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
            itemsIndexed(episodes, key = { _, e -> "${e.season}-${e.episode}" }) { i, e ->
                EpisodeTile(e, if (e.current) "Сейчас" else null, if (i == at) focus else null, { onPick(e) }, null)
            }
        }
    }
}

/** 16:9 episode still with "S2·E5 · title" and how far it was watched. */
@Composable
private fun EpisodeTile(e: EpisodeCard, caption: String?, focus: FocusRequester?, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(8.dp)
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = CardDefaults.shape(shape),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, K.accent), shape = shape)),
        // No zoom: a scaled card would be clipped by the panel.
        scale = CardDefaults.scale(focusedScale = 1f),
        modifier = Modifier.width(208.dp).aspectRatio(16f / 9f)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
    ) {
        Box(Modifier.fillMaxSize().background(K.surfaceHi)) {
            AsyncImage(e.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f))))
            caption?.let {
                Text(
                    it, style = MaterialTheme.typography.labelMedium, color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                Text(
                    listOfNotNull(e.label, e.title).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium, color = K.text,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                )
                if (e.progress > 0f) ProgressLine(e.progress, height = 3.dp)
            }
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
