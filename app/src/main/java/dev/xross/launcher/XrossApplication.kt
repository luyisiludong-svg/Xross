package dev.xross.launcher

import android.app.Application
import android.content.ComponentCallbacks2

class XrossApplication : Application() {
    lateinit var container: AppContainer
        private set

    /** True until the first launcher screen has consumed it; drives the startup fade and cue. */
    var coldBootPending = true

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Before any frame: a theme shader that crashed the last process is blocked now (2b.4a).
        container.shaderHealth
    }

    /**
     * Once the launcher's UI is hidden (an emulator is in front) keep the background footprint
     * small: decoded art, pre-blurred copies and cached wallpapers are rebuilt later from disk
     * (what is on screen stays referenced by its composable). Once the process is on the LRU
     * list the styled app icons go too; they are redrawn when the Apps list next shows them.
     */
    @Suppress("DEPRECATION") // the level constants are the documented way to read the callback
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            container.art.trim()
            dev.xross.launcher.ui.FrostedBackdrop.trim()
            container.wallpapers.trim()
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) container.apps.trimIcons()
    }
}
