package dev.dt.kinopub.ui.art

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import dev.dt.kinopub.data.ArtCatalog
import dev.dt.kinopub.data.Painting
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

enum class ArtCommand { NEXT, PREV, TOGGLE_CAPTION }

private class Slide(val painting: Painting, val image: ImageBitmap, val serial: Int) {
    /** From the bitmap itself: catalog w/h only pick the download size, a wrong one must not distort. */
    val aspect = image.width.toFloat() / image.height
    val panorama = aspect > 2.3f
}

/** How long one painting stays (the caption shows during the middle part). */
private const val SLIDE_MS = 60_000L
private const val FADE_MS = 2_500

/**
 * Full-screen paintings: a slow Ken Burns drift (wide scrolls pan end to end), cross-fades between works,
 * and a caption that rises after a few seconds — titles RU/EN, artist with life dates, date, museum, then
 * a short story — and leaves the picture clean again for the last seconds. Used by the system screensaver
 * ([dev.dt.kinopub.system.ArtDream]) and by [ArtActivity].
 */
@Composable
fun ArtShow(catalog: ArtCatalog, commands: Flow<ArtCommand>) {
    val context = LocalContext.current
    val loader = remember { SingletonImageLoader.get(context) }
    var slide by remember { mutableStateOf<Slide?>(null) }
    var serial by remember { mutableIntStateOf(0) }
    var captionOn by remember { mutableStateOf(true) }
    var phase by remember { mutableIntStateOf(0) }   // of the slide on screen, see SlideView
    val history = remember { ArrayList<Painting>() }   // shown paintings, for ←
    var pos by remember { mutableIntStateOf(-1) }
    var all by remember { mutableStateOf<List<Painting>>(emptyList()) }
    val scope = rememberCoroutineScope()

    suspend fun fetch(p: Painting): ImageBitmap? {
        val url = p.imageUrl() ?: return null
        val req = ImageRequest.Builder(context).data(url)
            .httpHeaders(NetworkHeaders.Builder().set("User-Agent", ArtCatalog.USER_AGENT).build())
            .size(if (p.panorama) Size.ORIGINAL else Size(2400, 1350))
            .allowHardware(false)
            .build()
        val r = loader.execute(req)
        if (r !is SuccessResult) { Log.w(ArtCatalog.TAG, "art: ${p.id} failed: $url"); return null }
        return r.image.toBitmap().asImageBitmap()
    }

    /** Shows history[target] (dealing new paintings past the end); skips works whose image won't load. */
    suspend fun show(forward: Boolean) {
        repeat(6) {
            val target = if (forward) pos + 1 else pos - 1
            if (target < 0) return
            if (target >= history.size) history.add(catalog.deal(all) ?: return)
            val p = history[target]
            val img = fetch(p)
            if (img == null) { history.removeAt(target); if (!forward) pos--; return@repeat }
            pos = target
            serial++
            slide = Slide(p, img, serial)
            Log.i(ArtCatalog.TAG, "art: ${p.id}")
            return
        }
    }

    LaunchedEffect(Unit) {
        all = catalog.load()
        launch { catalog.refresh() }
        show(forward = true)
    }
    // Auto-advance; any manual move restarts the timer (keyed on serial).
    LaunchedEffect(serial) {
        if (serial == 0) return@LaunchedEffect
        delay(SLIDE_MS)
        show(forward = true)
    }
    LaunchedEffect(Unit) {
        commands.collect {
            when (it) {
                ArtCommand.NEXT -> scope.launch { show(forward = true) }
                ArtCommand.PREV -> scope.launch { show(forward = false) }
                ArtCommand.TOGGLE_CAPTION -> captionOn = !captionOn
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = slide, animationSpec = tween(FADE_MS), label = "art") { s ->
            if (s != null) SlideView(s, captionOn) { if (s === slide) phase = it }
        }
        // The clock shows only while no caption is up (a long story beside a tall painting reaches the top).
        AnimatedVisibility(
            visible = slide != null && (phase == 0 || !captionOn),
            enter = fadeIn(tween(1200)), exit = fadeOut(tween(600)),
            modifier = Modifier.align(Alignment.TopStart).padding(top = 28.dp, start = 64.dp),
        ) { Clock(Modifier) }
    }
}

@Composable
private fun SlideView(s: Slide, captionOn: Boolean, onPhase: (Int) -> Unit) {
    val p = s.painting
    val rnd = remember(s.serial) { Random(p.id.hashCode() + s.serial) }
    val drift = remember(s.serial) { Animatable(0f) }
    LaunchedEffect(s.serial) {
        drift.animateTo(1f, tween((SLIDE_MS + FADE_MS).toInt(), easing = if (s.panorama) FastOutSlowInEasing else LinearEasing))
    }
    // Caption timeline: clean picture → titles → story → clean picture before the next one.
    var phase by remember(s.serial) { mutableIntStateOf(0) }
    LaunchedEffect(s.serial) {
        onPhase(0)
        delay(5_000); phase = 1; onPhase(1)
        delay(5_000); phase = 2; onPhase(2)
        delay(SLIDE_MS - 22_000); phase = 0; onPhase(0)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenAspect = maxWidth / maxHeight
        if (s.panorama) {
            // Full height, panned from one end to the other.
            val imgW = maxHeight * s.aspect
            val travel = with(LocalDensity.current) { (imgW - maxWidth).toPx() }
            Image(
                s.image, p.titleRu, contentScale = ContentScale.FillBounds,
                modifier = Modifier.align(Alignment.CenterStart).requiredSize(imgW, maxHeight)
                    .graphicsLayer {
                        // requiredSize centres the oversized image, so its left edge starts at -travel/2.
                        translationX = travel / 2 - travel * drift.value
                    },
            )
        } else {
            val fillsScreen = kotlin.math.abs(s.aspect / screenAspect - 1f) < 0.08f
            // Tall works leave room beside them: the painting moves right, the caption stands to its left.
            // (A 1080p TV is 960×540 dp.)
            val paintH = maxHeight - 32.dp
            val paintW = paintH * s.aspect
            val sideW = maxWidth - paintW - 48.dp - 64.dp - 36.dp
            val side = !fillsScreen && sideW >= 300.dp
            if (!fillsScreen) {
                // The painting's own colours, blurred and dimmed, fill the space around it.
                Image(
                    s.image, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.15f; scaleY = 1.15f; alpha = 0.8f }.blur(48.dp),
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (side) 0.45f else 0.3f)))
            }
            val ox = remember(s.serial) { 0.25f + rnd.nextFloat() * 0.5f }
            val oy = remember(s.serial) { 0.25f + rnd.nextFloat() * 0.5f }
            val zoomIn = remember(s.serial) { rnd.nextBoolean() }
            val kenBurns = Modifier.graphicsLayer {
                val z = (if (side) 0.04f else 0.08f) * if (zoomIn) drift.value else 1f - drift.value
                scaleX = 1f + z; scaleY = 1f + z
                transformOrigin = TransformOrigin(ox, oy)
            }
            if (side) {
                Image(
                    s.image, p.titleRu, contentScale = ContentScale.Fit,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 48.dp).requiredSize(paintW, paintH).then(kenBurns),
                )
                Caption(p, phase, captionOn, side = true, Modifier.align(Alignment.CenterStart).padding(start = 64.dp).widthIn(max = sideW.coerceAtMost(480.dp)))
            } else {
                Image(
                    s.image, p.titleRu,
                    contentScale = if (fillsScreen) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(if (fillsScreen) 0.dp else 24.dp).then(kenBurns),
                )
            }
            if (side) return@BoxWithConstraints
        }
        Caption(p, phase, captionOn, side = false, Modifier.align(Alignment.BottomStart))
    }
}

private val shadow = Shadow(Color.Black.copy(alpha = 0.85f), blurRadius = 12f)
private val serif = FontFamily.Serif

@Composable
private fun Caption(p: Painting, phase: Int, on: Boolean, side: Boolean, modifier: Modifier) {
    AnimatedVisibility(
        visible = on && phase >= 1, modifier = modifier,
        enter = fadeIn(tween(1200)) + slideInVertically(tween(1200)) { it / 6 },
        exit = fadeOut(tween(1500)),
    ) {
        // Beside a tall painting the dimmed backdrop is calm enough; over a picture a scrim keeps it readable.
        Box(
            if (side) Modifier else Modifier.fillMaxWidth().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.85f)),
            ),
        ) {
            Column(if (side) Modifier else Modifier.padding(start = 64.dp, end = 64.dp, top = 96.dp, bottom = 44.dp).widthIn(max = 1150.dp)) {
                BasicText(p.titleRu, style = TextStyle(color = Color.White, fontSize = 40.sp, fontFamily = serif, fontWeight = FontWeight.Medium, shadow = shadow))
                if (p.titleEn.isNotBlank() && p.titleEn != p.titleRu) {
                    BasicText(p.titleEn, style = TextStyle(color = Color.White.copy(alpha = 0.72f), fontSize = 21.sp, fontFamily = serif, fontStyle = FontStyle.Italic, shadow = shadow))
                }
                Spacer(Modifier.height(12.dp))
                val artist = buildString {
                    append(p.artistRu)
                    if (p.artistLife.isNotBlank()) append(" (").append(p.artistLife).append(')')
                    if (p.date.isNotBlank()) append("  ·  ").append(p.date)
                }
                BasicText(artist, style = TextStyle(color = Color.White, fontSize = 20.sp, shadow = shadow))
                val sub = listOf(p.artistEn, p.museumRu).filter { it.isNotBlank() }.joinToString("  ·  ")
                if (sub.isNotBlank()) BasicText(sub, style = TextStyle(color = Color.White.copy(alpha = 0.65f), fontSize = 16.sp, shadow = shadow))
                AnimatedVisibility(
                    visible = phase >= 2 && p.story.isNotBlank(),
                    enter = fadeIn(tween(1500)) + expandVertically(tween(1200)),
                    exit = fadeOut(tween(800)) + shrinkVertically(tween(800)),
                ) {
                    BasicText(
                        p.story, Modifier.padding(top = 16.dp),
                        style = TextStyle(color = Color.White.copy(alpha = 0.92f), fontSize = 19.sp, lineHeight = 28.sp, shadow = shadow),
                    )
                }
            }
        }
    }
}

@Composable
private fun Clock(modifier: Modifier) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(10_000) } }
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    BasicText(fmt.format(now), modifier, style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 30.sp, fontWeight = FontWeight.Light, shadow = shadow))
}
