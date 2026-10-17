package dev.dt.kinopub.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.dt.kinopub.R
import dev.dt.kinopub.ui.theme.K
import kotlinx.coroutines.delay

/**
 * Branded splash over the app until the first screen has content ([ready]); 5 s at most.
 * Covers the "Загрузка…" state so launch goes logo → Home with focus on the first card.
 */
@Composable
fun SplashOverlay(ready: Boolean) {
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(5_000); timedOut = true }
    AnimatedVisibility(visible = !ready && !timedOut, exit = fadeOut(tween(250))) {
        // Full-frame brand splash (docs/brand), cropped to fill any TV aspect.
        Image(
            painterResource(R.drawable.splash_screen), null,
            Modifier.fillMaxSize().background(K.bg),
            contentScale = ContentScale.Crop,
        )
    }
}
