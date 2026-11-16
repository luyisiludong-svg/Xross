package dev.xross.launcher.audio

/**
 * A video snap's sound ramps in from silence over [MS] once its first frame is up. The media
 * player ([dev.xross.launcher.media.SnapPlayer]) applies it and the backdrop
 * ([dev.xross.launcher.ui.BackdropTiming]) times its fade by it, so it lives with the mixing rules.
 */
object SnapRamp {
    const val MS = 300L

    /** Volume [elapsedMs] into the ramp towards [target]. */
    fun volume(elapsedMs: Long, target: Float): Float =
        target.coerceIn(0f, 1f) * (elapsedMs.toFloat() / MS).coerceIn(0f, 1f)
}
