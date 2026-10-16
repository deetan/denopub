package dev.dt.kinopub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.dt.kinopub.ui.KinoNavHost
import dev.dt.kinopub.ui.theme.KinoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // System splash only bridges process start; Android TV images may not draw its icon, so the
        // branded splash that waits for data is drawn in Compose (ui/SplashOverlay).
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val container = (application as KinopubApp).container
        setContent {
            KinoTheme { KinoNavHost(container) }
        }
    }
}
