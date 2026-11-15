package dev.dt.kinopub.system

import android.service.dreams.DreamService
import android.view.KeyEvent
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.dt.kinopub.KinopubApp
import dev.dt.kinopub.ui.art.ArtCommand
import dev.dt.kinopub.ui.art.ArtShow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The paintings screensaver. Google TV's settings only list Google's Ambient mode, so it is switched on
 * with adb once: `scripts/tv.sh screensaver` (writes `secure screensaver_components`).
 * Remote: ← → previous/next painting, ↑ ↓ hide/show the caption, any other key wakes the TV up.
 */
class ArtDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private val commands = MutableSharedFlow<ArtCommand>(extraBufferCapacity = 4)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = true
        val catalog = (application as KinopubApp).container.art
        val view = ComposeView(this).apply { setContent { ArtShow(catalog, (application as KinopubApp).container.weather, commands) } }
        window.decorView.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
            it.setViewTreeViewModelStoreOwner(this)
        }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDreamingStopped() {
        registry.currentState = Lifecycle.State.CREATED
        super.onDreamingStopped()
    }

    override fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val cmd = artCommandFor(event.keyCode)
        if (cmd == null) { finish(); return true }
        if (event.action == KeyEvent.ACTION_DOWN) commands.tryEmit(cmd)
        return true
    }
}

/** Remote keys shared by the screensaver and the in-app gallery; null = leave. */
fun artCommandFor(keyCode: Int): ArtCommand? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> ArtCommand.NEXT
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> ArtCommand.PREV
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> ArtCommand.TOGGLE_CAPTION
    else -> null
}
