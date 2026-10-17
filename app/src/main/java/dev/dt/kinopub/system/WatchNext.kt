package dev.dt.kinopub.system

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import dev.dt.kinopub.data.ContinueEntry
import dev.dt.kinopub.data.ResumeTarget
import dev.dt.kinopub.ui.common.splitTitle

/**
 * Mirrors our "Продолжить" into the system Watch Next row ("Continue watching" on the Google TV
 * home). Each tile deep-links to denopub://play, which resumes at the exact second.
 * Google TV may hide rows from sideloaded apps — this is best-effort.
 */
class WatchNext(private val context: Context) {

    fun sync(entries: List<ContinueEntry>) {
        runCatching {
            val resolver = context.contentResolver
            // We only see our own rows; replace them all.
            resolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, arrayOf("_id"), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    resolver.delete(TvContractCompat.buildWatchNextProgramUri(c.getLong(0)), null, null)
                }
            }
            val now = System.currentTimeMillis()
            entries.take(MAX).forEachIndexed { i, e ->
                val (title, _) = splitTitle(e.title)
                val p = WatchNextProgram.Builder()
                    .setType(if (e.isSerial) TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                    .setWatchNextType(
                        if (e.target.startSec > 0) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                        else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT,
                    )
                    // Keep our order: most recent first.
                    .setLastEngagementTimeUtcMillis(now - i * 60_000L)
                    .setTitle(title)
                    .setEpisodeTitle(e.episodeLabel)
                    .setPosterArtUri(e.poster?.let(Uri::parse))
                    .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                    .setLastPlaybackPositionMillis(e.target.startSec * 1000)
                    .setDurationMillis(e.durationSec * 1000)
                    .setInternalProviderId("${e.itemId}")
                    .setIntentUri(deepLink(e.target))
                    .build()
                resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, p.toContentValues())
            }
            Log.i("denopub", "watch next synced: ${minOf(entries.size, MAX)}")
        }.onFailure { Log.w("denopub", "watch next sync failed", it) }
    }

    companion object {
        private const val MAX = 10

        fun deepLink(t: ResumeTarget): Uri = Uri.parse(
            "denopub://play?id=${t.itemId}&episode=${t.episode}&start=${t.startSec}" +
                (t.season?.let { "&season=$it" } ?: ""),
        )

        fun parse(uri: Uri?): ResumeTarget? {
            if (uri?.scheme != "denopub" || uri.host != "play") return null
            val id = uri.getQueryParameter("id")?.toLongOrNull() ?: return null
            return ResumeTarget(
                itemId = id,
                season = uri.getQueryParameter("season")?.toIntOrNull(),
                episode = uri.getQueryParameter("episode")?.toIntOrNull() ?: 1,
                startSec = uri.getQueryParameter("start")?.toIntOrNull() ?: 0,
            )
        }
    }
}
