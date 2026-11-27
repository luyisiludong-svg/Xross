package dev.xross.launcher.ui

/**
 * What `XmbScreen` reports about itself, for the Activity rather than the view model: how it was
 * drawn and laid out, not what the user asked for (that is an `XmbEvent`).
 */
sealed interface ScreenSignal {

    /** Opaque hero art, a video snap or an opaque wallpaper covers the screen: the wave can stop rendering. */
    data class BackdropCover(val covers: Boolean) : ScreenSignal

    /** A UI filter (2b.4f) couldn't be built: announced once per [key] with [text]. */
    data class UiFilterError(val key: String, val text: String) : ScreenSignal

    /**
     * The focused item's centre over the screen as laid out, both 0–1 with y down: [x] pans the UI
     * sounds (2b.5a), and both drive shaders' `uFocus` (Live backgrounds § 1). In the XMB this is
     * the crosshair, where the focused item always sits.
     */
    data class Focus(val x: Float, val y: Float) : ScreenSignal

    /**
     * The menu's list transition (Menu styles slice 3): 0 as a category switch or drill-down
     * begins, 1 when it has settled. Sent every frame while it runs, for scenes' `transition`
     * driver; the receiver must not recompose on it.
     */
    data class MenuTransition(val progress: Float) : ScreenSignal
}
