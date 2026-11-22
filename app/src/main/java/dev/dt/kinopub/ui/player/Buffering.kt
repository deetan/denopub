package dev.dt.kinopub.ui.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Buffering for kino.pub VOD, which sometimes stalls for a few seconds (owner 2026-11-22: short
 * freezes). Stock ExoPlayer keeps 50 s or ~130 MB ahead — only ~25-30 s of a 4K file at the
 * pinned highest bitrate — and the shared client waited 30 s on a stalled read before retrying.
 */
@OptIn(UnstableApi::class)
object Buffering {
    /** Up to 3 min ahead, capped at 256 MB (≈ 50 s of 4K at 40 Mbit/s, the full 3 min of 1080p). */
    private const val AHEAD_MS = 180_000
    private const val MAX_BYTES = 256 * 1024 * 1024

    /** After a stall, wait for 10 s (stock: 5 s) so one slow server gives one pause, not several. */
    fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(AHEAD_MS, AHEAD_MS, 2_500, 10_000)
        .setTargetBufferBytes(MAX_BYTES)
        .build()

    /** Media source over the short-timeout media client, retrying a failed segment 8 times (stock 3). */
    fun sourceFactory(media: OkHttpClient): DefaultMediaSourceFactory =
        DefaultMediaSourceFactory(OkHttpDataSource.Factory(media))
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))

    /**
     * `buffer:` log lines: every stall during playback (buffered ahead, network estimate, how long
     * it lasted), every failed segment load, and segment loads slower than 4 s.
     */
    fun log(player: ExoPlayer, bandwidth: DefaultBandwidthMeter) {
        var stallAt = 0L
        var seekAt = 0L
        fun ahead() = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0) / 1000
        fun net() = "%.0f".format(bandwidth.bitrateEstimate / 1e6)
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) seekAt = System.currentTimeMillis()
            }

            override fun onPlaybackStateChanged(state: Int) {
                val now = System.currentTimeMillis()
                when {
                    state == Player.STATE_BUFFERING && player.playWhenReady && stallAt == 0L -> {
                        stallAt = now
                        // Buffering right after a seek is expected; only a stall in plain playback is a freeze.
                        if (now - seekAt > 1_500) {
                            Log.i(TAG, "buffer: stall at ${player.currentPosition / 1000}s, ahead ${ahead()}s, net ${net()} Mbit/s")
                        } else {
                            stallAt = -now // seek buffering: timed, logged as such
                        }
                    }
                    state == Player.STATE_READY && stallAt != 0L -> {
                        val seek = stallAt < 0
                        val ms = now - kotlin.math.abs(stallAt)
                        if (!seek || ms > 3_000) {
                            Log.i(TAG, "buffer: ${if (seek) "seek" else "stall"} resumed after ${ms} ms, net ${net()} Mbit/s")
                        }
                        stallAt = 0L
                    }
                    state != Player.STATE_BUFFERING -> stallAt = 0L
                }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                info: LoadEventInfo,
                data: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean,
            ) {
                Log.w(TAG, "buffer: load error ${info.uri.host} ${kind(data)}: ${error.javaClass.simpleName} ${error.message}, ahead ${ahead()}s")
            }

            override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
                if (info.loadDurationMs < 4_000) return
                val len = if (data.mediaEndTimeMs != C.TIME_UNSET && data.mediaStartTimeMs != C.TIME_UNSET) {
                    " (${(data.mediaEndTimeMs - data.mediaStartTimeMs) / 1000}s of media)"
                } else ""
                Log.i(TAG, "buffer: slow ${kind(data)} ${info.uri.host} ${info.loadDurationMs} ms, ${info.bytesLoaded / 1024} KB$len, ahead ${ahead()}s")
            }
        })
    }

    private fun kind(data: MediaLoadData) = when (data.trackType) {
        C.TRACK_TYPE_VIDEO -> "video"
        C.TRACK_TYPE_AUDIO -> "audio"
        C.TRACK_TYPE_TEXT -> "subs"
        else -> if (data.dataType == C.DATA_TYPE_MANIFEST) "playlist" else "media"
    }

    private const val TAG = "denopub"
}
