package dev.dt.kinopub.system

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.dt.kinopub.MainActivity

/**
 * "Media mode": when the dongle wakes up (TV powered on → CEC → Chromecast awake) and the
 * Google TV home is in front, bring denopub up on its Home ("Продолжить").
 *
 * It only tracks which package owns the foreground window; it reads no screen content.
 * Enabled by the user once: Settings → System → Accessibility → denopub.
 */
class WakeService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var foreground: String? = null

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            // The launcher redraws itself right after wake; give it a moment, then check.
            handler.removeCallbacks(openIfOnLauncher)
            handler.postDelayed(openIfOnLauncher, 2_000)
        }
    }

    private val openIfOnLauncher = Runnable {
        val fg = foreground
        Log.i(TAG, "screen on, foreground=$fg")
        if (fg == null || fg in LAUNCHERS) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        }
    }

    override fun onServiceConnected() {
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { foreground = it }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOn) }
        handler.removeCallbacks(openIfOnLauncher)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "denopub"
        private val LAUNCHERS = setOf(
            "com.google.android.apps.tv.launcherx", // Google TV home
            "com.google.android.tvlauncher",        // Android TV home
            "com.android.systemui",                 // screensaver/ambient just dismissed
            "com.google.android.apps.tv.dreamx",    // Google TV ambient mode
        )
    }
}
