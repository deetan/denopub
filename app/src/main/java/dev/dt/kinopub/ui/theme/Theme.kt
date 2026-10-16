package dev.dt.kinopub.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/** Design tokens. See docs/design.md — function over decoration, readable from the couch. */
object K {
    val bg = Color(0xFF0E0F12)
    val surface = Color(0xFF1A1C21)
    val surfaceHi = Color(0xFF262931)
    val accent = Color(0xFFF2B33D)      // focus + primary action
    val onAccent = Color(0xFF14161A)
    val text = Color(0xFFEDEEF0)
    val muted = Color(0xFF9AA0A6)
    val badge = Color(0xFFE5484D)       // "new" markers

    val screenPadH = 48.dp              // TV overscan-safe horizontal inset
    val screenPadV = 27.dp
    val rowGap = 28.dp
    val cardGap = 16.dp
}

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 15.sp),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun KinoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = K.accent,
            onPrimary = K.onAccent,
            background = K.bg,
            surface = K.surface,
            onSurface = K.text,
            surfaceVariant = K.surfaceHi,
            onSurfaceVariant = K.muted,
            border = K.surfaceHi,
            borderVariant = K.surfaceHi,
        ),
        typography = typography,
        content = content,
    )
}
