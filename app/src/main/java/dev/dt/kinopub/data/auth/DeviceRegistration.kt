package dev.dt.kinopub.data.auth

import android.os.Build
import dev.dt.kinopub.BuildConfig
import dev.dt.kinopub.data.api.KinopubApi

/**
 * After the device-code login: name the device in the kinopub cabinet and switch on the
 * stream features this TV can play. Without these settings kinopub serves only H.264 1080p.
 */
class DeviceRegistration(private val api: KinopubApi) {
    suspend fun afterLogin() {
        runCatching {
            api.notifyDevice(
                title = "Kino TV (${Build.MODEL})",
                hardware = "${Build.MANUFACTURER} ${Build.MODEL}",
                software = "Android ${Build.VERSION.RELEASE} / kino ${BuildConfig.VERSION_NAME}",
            )
        }
        runCatching {
            val device = api.deviceInfo()
            // Map hls4 to its id from the live reference list instead of hardcoding it.
            val hls4 = api.streamingTypes().firstOrNull { it.code == "hls4" }?.id
            val settings = STREAM_SETTINGS + (hls4?.let { mapOf("streamingType" to "$it") } ?: emptyMap())
            api.saveDeviceSettings(device.id, settings)
        }
    }

    companion object {
        // Field names per docs/kinopub-api.md. hls4 = one manifest with every audio track,
        // so ExoPlayer switches voiceovers without reloading the stream.
        val STREAM_SETTINGS = mapOf(
            "supportSsl" to "1",
            "supportHevc" to "1",
            "supportHdr" to "1",
            "support4k" to "1",
            "mixedPlaylist" to "1",
        )
    }
}
