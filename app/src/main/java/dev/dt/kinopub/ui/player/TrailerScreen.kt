package dev.dt.kinopub.ui.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.dt.kinopub.AppContainer
import dev.dt.kinopub.ui.theme.K
import kotlinx.coroutines.delay

/** Bare full-screen player for trailers (kino.pub or IMDb). OK pause, ← → ±10 s, Back exits. */
@OptIn(UnstableApi::class)
@Composable
fun TrailerScreen(c: AppContainer, url: String, title: String, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(c.http)))
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .build().apply {
                setMediaItem(MediaItem.Builder().setUri(url).apply { if (url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }.build())
                prepare()
                playWhenReady = true
            }
    }
    var showTitle by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val view = LocalView.current
    DisposableEffect(Unit) {
        // Live channels run for hours with no key presses: without this the Google TV
        // screensaver (Ambient mode) kicks in over the picture.
        view.keepScreenOn = true
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_ENDED) onExit() }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) { error = e.errorCodeName }
        }
        player.addListener(l)
        onDispose { view.keepScreenOn = false; player.removeListener(l); player.release() }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() }; delay(3_000); showTitle = false }
    BackHandler { onExit() }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> { if (player.isPlaying) player.pause() else player.play(); showTitle = true; true }
                    Key.DirectionLeft -> { player.seekBack(); true }
                    Key.DirectionRight -> { player.seekForward(); true }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { PlayerView(it).apply { useController = false; isFocusable = false; this.player = player } },
            modifier = Modifier.fillMaxSize(),
        )
        if (showTitle || !player.isPlaying) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = K.text,
                modifier = Modifier.align(Alignment.TopStart).padding(horizontal = K.screenPadH, vertical = 24.dp))
        }
        error?.let { Text("Не воспроизводится: $it", color = K.badge, modifier = Modifier.align(Alignment.Center)) }
    }
}
