package dev.dt.kinopub.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.dt.kinopub.ui.theme.K

private val cardShape = RoundedCornerShape(8.dp)

@Composable
private fun focusBorder() = CardDefaults.border(
    focusedBorder = Border(BorderStroke(3.dp, K.accent), shape = cardShape),
)

/** Small pill: "НОВОЕ", "+3", quality. */
@Composable
fun Badge(text: String, color: Color = K.badge, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
    )
}

/** 2:3 poster with title under it. */
@Composable
fun PosterCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    width: Dp = 150.dp,
) {
    Column(modifier.width(width)) {
        Card(
            onClick = onClick,
            shape = CardDefaults.shape(cardShape),
            border = focusBorder(),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
        ) {
            Box(Modifier.fillMaxSize().background(K.surface)) {
                AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                badge?.let { Badge(it, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) }
            }
        }
        Text(
            title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, color = K.text,
            modifier = Modifier.padding(top = 8.dp),
        )
        subtitle?.let {
            Text(it, maxLines = 1, style = MaterialTheme.typography.labelMedium, color = K.muted)
        }
    }
}

/**
 * 16:9 "continue" card: everything needed to decide in one glance — what, which episode,
 * how far in. OK resumes playback directly; long-press opens details.
 */
@Composable
fun ContinueCard(
    title: String,
    image: String?,
    episodeLabel: String?,
    meta: String,
    progress: Float,
    newBadge: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 300.dp,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = CardDefaults.shape(cardShape),
        border = focusBorder(),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        modifier = modifier.width(width).aspectRatio(16f / 9f),
    ) {
        Box(Modifier.fillMaxSize().background(K.surface)) {
            AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f)),
                ),
            )
            newBadge?.let { Badge(it, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) }
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(
                    title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium, color = K.text,
                )
                Text(
                    listOfNotNull(episodeLabel, meta).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium, color = K.muted,
                )
            }
            ProgressLine(progress, Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(modifier.fillMaxWidth().height(height).background(Color.White.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(K.accent))
    }
}

fun formatMinutes(sec: Int): String {
    val m = sec / 60
    return if (m >= 60) "${m / 60} ч ${m % 60} мин" else "$m мин"
}
