package dev.dt.kinopub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.dt.kinopub.ui.KinoNavHost
import dev.dt.kinopub.ui.theme.KinoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as KinopubApp).container
        setContent {
            KinoTheme { KinoNavHost(container) }
        }
    }
}
