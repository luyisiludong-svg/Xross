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

    /** The crosshair's X over the screen width (0–1) as laid out, for panning the UI sounds (2b.5a). */
    data class CrosshairX(val x: Float) : ScreenSignal
}
