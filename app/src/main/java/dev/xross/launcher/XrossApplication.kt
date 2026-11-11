package dev.xross.launcher

import android.app.Application

class XrossApplication : Application() {
    lateinit var container: AppContainer
        private set

    /** True until the first launcher screen has consumed it; drives the startup fade and cue. */
    var coldBootPending = true

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
