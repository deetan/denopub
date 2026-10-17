package dev.dt.kinopub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.dt.kinopub.system.WatchNext
import dev.dt.kinopub.ui.KinoNavHost
import dev.dt.kinopub.ui.theme.KinoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // System splash only bridges process start; Android TV images may not draw its icon, so the
        // branded splash that waits for data is drawn in Compose (ui/SplashOverlay).
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val container = (application as KinopubApp).container
        container.pendingPlay.value = WatchNext.parse(intent?.data)
        setContent {
            KinoTheme { KinoNavHost(container) }
        }
    }

    // singleTask: a deep link while running arrives here.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        WatchNext.parse(intent.data)?.let { (application as KinopubApp).container.pendingPlay.value = it }
    }
}
