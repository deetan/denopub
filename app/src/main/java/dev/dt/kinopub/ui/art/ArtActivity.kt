package dev.dt.kinopub.ui.art

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.dt.kinopub.KinopubApp
import dev.dt.kinopub.system.artCommandFor
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The paintings show as a normal screen (from Home's «Картины» button, or
 * `adb shell am start -n dev.dt.kinopub/.ui.art.ArtActivity` for testing). Same remote keys as the
 * screensaver; Back leaves; OK also hides/shows the caption.
 */
class ArtActivity : ComponentActivity() {
    private val commands = MutableSharedFlow<ArtCommand>(extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val catalog = (application as KinopubApp).container.art
        setContent { ArtShow(catalog, (application as KinopubApp).container.weather, commands) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val cmd = artCommandFor(event.keyCode)
            ?: if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) ArtCommand.TOGGLE_CAPTION else null
            ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) commands.tryEmit(cmd)
        return true
    }
}
