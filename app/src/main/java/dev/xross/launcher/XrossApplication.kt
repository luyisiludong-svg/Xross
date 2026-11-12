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
     * small: decoded art is the largest thing that can be rebuilt later from disk.
     */
    @Suppress("DEPRECATION") // the level constants are the documented way to read the callback
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) container.art.trim()
    }
}
