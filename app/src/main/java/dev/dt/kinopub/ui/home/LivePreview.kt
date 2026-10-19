package dev.dt.kinopub.ui.home

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient

/**
 * One muted player shared by the Sport row: the focused card shows its channel live.
 * Audio is disabled (not just muted), so it isn't even decoded. Lives as long as Home.
 */
class LivePreview(val player: ExoPlayer) {
    var playing by mutableStateOf<String?>(null)   // channel name being previewed
        private set
    var firstFrame by mutableStateOf(false)         // picture is up: hide the still frame
        private set

    init {
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
        })
    }

    fun show(name: String?, url: String?) {
        if (name == playing) return
        playing = name
        firstFrame = false
        if (name == null || url == null) { player.stop(); player.clearMediaItems(); return }
        player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
        player.prepare()
        player.play()
    }
}

@OptIn(UnstableApi::class)
@Composable
fun rememberLivePreview(http: OkHttpClient): LivePreview {
    val ctx = LocalContext.current
    val preview = remember {
        val p = ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(http)))
            .build().apply {
                volume = 0f
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            }
        LivePreview(p)
    }
    DisposableEffect(Unit) { onDispose { preview.player.release() } }
    return preview
}

/** The video surface for the previewed card; transparent until the first frame is drawn. */
@Composable
fun LivePreviewSurface(preview: LivePreview) {
    AndroidView(
        factory = { TextureView(it).also { tv -> preview.player.setVideoTextureView(tv) } },
        onRelease = { preview.player.clearVideoTextureView(it) },
        modifier = Modifier.fillMaxSize().alpha(if (preview.firstFrame) 1f else 0f),
    )
}
