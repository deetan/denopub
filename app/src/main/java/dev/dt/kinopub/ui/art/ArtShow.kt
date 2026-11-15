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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
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
private const val SLIDE_MS = 40_000L   // owner 2026-11-15: 60 s too slow, 30 s too short for the child to read
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

    fun request(p: Painting, url: String) = ImageRequest.Builder(context).data(url)
        .httpHeaders(NetworkHeaders.Builder().set("User-Agent", ArtCatalog.USER_AGENT).build())
        .size(if (p.panorama) Size.ORIGINAL else Size(2400, 1350))
        .allowHardware(false)
        .build()

    suspend fun fetch(p: Painting): ImageBitmap? {
        val url = p.imageUrl() ?: return null
        val r = loader.execute(request(p, url))
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
            // Deal the next one now and warm Coil's cache, so the switch happens when the bar ends.
            if (pos == history.lastIndex) catalog.deal(all)?.let { n ->
                history.add(n)
                n.imageUrl()?.let { loader.enqueue(request(n, it)) }
            }
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
        // Thin bar along the bottom edge: how long until the next painting (restarts on ← →).
        val progress = remember { Animatable(0f) }
        LaunchedEffect(serial) {
            if (serial == 0) return@LaunchedEffect
            progress.snapTo(0f)
            progress.animateTo(1f, tween(SLIDE_MS.toInt(), easing = LinearEasing))
        }
        if (slide != null) Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.value).height(4.dp)
                .background(Color.White.copy(alpha = 0.55f)),
        )
    }
}

/** Where the caption goes: beside the painting, under it, or over it (when neither leaves it big enough). */
private enum class Place { SIDE, BOTTOM, OVER }

private class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun lerp(o: Rect, t: Float) = Rect(x + (o.x - x) * t, y + (o.y - y) * t, w + (o.w - w) * t, h + (o.h - h) * t)
}

/** The largest rect of [aspect] inside the box, centred. */
private fun fit(aspect: Float, x: Float, y: Float, w: Float, h: Float): Rect {
    val fw = if (aspect > w / h) w else h * aspect
    val fh = fw / aspect
    return Rect(x + (w - fw) / 2, y + (h - fh) / 2, fw, fh)
}

// Caption sizes (sp ≈ dp at the TV's font scale). Kept small so the painting can keep its room.
private const val TITLE_SP = 26f
private const val EN_SP = 16f
private const val ARTIST_SP = 15f
private const val SUB_SP = 13f
private const val STORY_SP = 15f
private const val STORY_LINE = 22f
private const val SIDE_TEXT_W = 330f
private const val TEXT_MARGIN = 56f

private fun artistLine(p: Painting) = buildString {
    append(p.artistRu)
    if (p.artistLife.isNotBlank()) append(" (").append(p.artistLife).append(')')
    if (p.date.isNotBlank()) append("  ·  ").append(p.date)
}

private fun subLine(p: Painting) = listOf(p.artistEn, p.museumRu).filter { it.isNotBlank() }.joinToString("  ·  ")

/** Rough height (dp) of the caption in a column [width] dp wide — enough to reserve room for it. */
private fun captionHeight(p: Painting, width: Float): Float {
    fun lines(text: String, sp: Float) =
        if (text.isBlank()) 0 else kotlin.math.ceil(text.length * sp * 0.56f / width).toInt().coerceAtLeast(1)
    var h = lines(p.titleRu, TITLE_SP) * TITLE_SP * 1.35f
    if (p.titleEn.isNotBlank() && p.titleEn != p.titleRu) h += lines(p.titleEn, EN_SP) * EN_SP * 1.35f
    h += 8f + lines(artistLine(p), ARTIST_SP) * ARTIST_SP * 1.35f + lines(subLine(p), SUB_SP) * SUB_SP * 1.35f
    if (p.story.isNotBlank()) h += 10f + (lines(p.story, STORY_SP) + 1) * STORY_LINE
    return h
}

/** Positions a child at an absolute rect (dp), larger than the parent if need be. */
private fun Modifier.placeAt(r: Rect) = layout { m, c ->
    val pl = m.measure(Constraints.fixed(r.w.dp.roundToPx().coerceAtLeast(1), r.h.dp.roundToPx().coerceAtLeast(1)))
    layout(c.maxWidth, c.maxHeight) { pl.place(r.x.dp.roundToPx(), r.y.dp.roundToPx()) }
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
        delay(2_000); phase = 1; onPhase(1)
        delay(2_000); phase = 2; onPhase(2)
        delay(SLIDE_MS - 8_000); phase = 0; onPhase(0)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Everything in dp (a 1080p TV is 960×540 dp).
        val W = maxWidth.value
        val H = maxHeight.value
        val a = s.aspect
        val m = 24f
        val fillsScreen = kotlin.math.abs(a / (W / H) - 1f) < 0.08f
        val full = if (fillsScreen) fit(a, 0f, 0f, W, H) else fit(a, m, m, W - 2 * m, H - 2 * m)
        val sideText = captionHeight(p, SIDE_TEXT_W)
        val bottomBand = captionHeight(p, W - 2 * TEXT_MARGIN) + 28f
        // The painting makes room for the caption: beside it or above it, whichever keeps it bigger;
        // if both would shrink it too much, the caption goes over it on a scrim.
        val sideX = TEXT_MARGIN + SIDE_TEXT_W + 32f
        val side = if (sideText <= H - 80f) fit(a, sideX, m, W - sideX - 40f, H - 2 * m) else null
        val bottom = fit(a, m, 16f, W - 2 * m, H - bottomBand - 24f)
        val place = remember(s.serial, W, H) {
            val best = maxOf(side?.w ?: 0f, bottom.w)
            when {
                s.panorama -> Place.BOTTOM
                best < 0.58f * full.w -> Place.OVER
                side != null && side.w >= bottom.w -> Place.SIDE
                else -> Place.BOTTOM
            }
        }
        // ↑ ↓ hide the caption: the painting grows back to full size, smoothly.
        val t by animateFloatAsState(if (captionOn && place != Place.OVER) 1f else 0f, tween(900), label = "room")

        // The painting's own colours, blurred and dimmed, fill the space around it.
        Image(
            s.image, null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.15f; scaleY = 1.15f; alpha = 0.8f }.blur(48.dp),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))

        if (s.panorama) {
            // Full width of the scroll at the available height, panned from one end to the other.
            val h = H + ((H - bottomBand - 16f) - H) * t
            val w = h * a
            Image(
                s.image, p.titleRu, contentScale = ContentScale.FillBounds,
                modifier = Modifier.placeAt(Rect(-(w - W).coerceAtLeast(0f) * drift.value, 0f, w, h)),
            )
        } else {
            val ox = remember(s.serial) { 0.25f + rnd.nextFloat() * 0.5f }
            val oy = remember(s.serial) { 0.25f + rnd.nextFloat() * 0.5f }
            val zoomIn = remember(s.serial) { rnd.nextBoolean() }
            val target = when (place) { Place.SIDE -> side!!; Place.BOTTOM -> bottom; Place.OVER -> full }
            Image(
                s.image, p.titleRu, contentScale = ContentScale.FillBounds,
                modifier = Modifier.placeAt(full.lerp(target, t)).graphicsLayer {
                    val z = (0.08f - 0.05f * t) * if (zoomIn) drift.value else 1f - drift.value
                    scaleX = 1f + z; scaleY = 1f + z
                    transformOrigin = TransformOrigin(ox, oy)
                },
            )
        }
        when (place) {
            Place.SIDE -> Caption(p, phase, captionOn, place, Modifier.align(Alignment.CenterStart).padding(start = TEXT_MARGIN.dp).width(SIDE_TEXT_W.dp))
            else -> Caption(p, phase, captionOn, place, Modifier.align(Alignment.BottomStart))
        }
    }
}

private val shadow = Shadow(Color.Black.copy(alpha = 0.85f), blurRadius = 10f)
private val serif = FontFamily.Serif

@Composable
private fun Caption(p: Painting, phase: Int, on: Boolean, place: Place, modifier: Modifier) {
    AnimatedVisibility(
        visible = on && phase >= 1, modifier = modifier,
        enter = fadeIn(tween(1000)) + slideInVertically(tween(1000)) { it / 8 },
        exit = fadeOut(tween(1200)),
    ) {
        // Over the picture a scrim keeps it readable; beside or under it the dimmed backdrop is enough.
        Box(
            when (place) {
                Place.SIDE -> Modifier
                Place.BOTTOM -> Modifier.fillMaxWidth()
                Place.OVER -> Modifier.fillMaxWidth().background(
                    Brush.verticalGradient(0f to Color.Transparent, 0.3f to Color.Black.copy(alpha = 0.5f), 1f to Color.Black.copy(alpha = 0.8f)),
                )
            },
        ) {
            Column(
                if (place == Place.SIDE) Modifier
                else Modifier.padding(start = TEXT_MARGIN.dp, end = TEXT_MARGIN.dp, top = if (place == Place.OVER) 64.dp else 0.dp, bottom = 22.dp),
            ) {
                BasicText(p.titleRu, style = TextStyle(color = Color.White, fontSize = TITLE_SP.sp, fontFamily = serif, fontWeight = FontWeight.Medium, shadow = shadow))
                if (p.titleEn.isNotBlank() && p.titleEn != p.titleRu) {
                    BasicText(p.titleEn, style = TextStyle(color = Color.White.copy(alpha = 0.72f), fontSize = EN_SP.sp, fontFamily = serif, fontStyle = FontStyle.Italic, shadow = shadow))
                }
                Spacer(Modifier.height(8.dp))
                BasicText(artistLine(p), style = TextStyle(color = Color.White, fontSize = ARTIST_SP.sp, shadow = shadow))
                val sub = subLine(p)
                if (sub.isNotBlank()) BasicText(sub, style = TextStyle(color = Color.White.copy(alpha = 0.65f), fontSize = SUB_SP.sp, shadow = shadow))
                AnimatedVisibility(
                    visible = phase >= 2 && p.story.isNotBlank(),
                    enter = fadeIn(tween(1200)) + expandVertically(tween(1000)),
                    exit = fadeOut(tween(800)) + shrinkVertically(tween(800)),
                ) {
                    BasicText(
                        p.story, Modifier.padding(top = 10.dp),
                        style = TextStyle(color = Color.White.copy(alpha = 0.92f), fontSize = STORY_SP.sp, lineHeight = STORY_LINE.sp, shadow = shadow),
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
