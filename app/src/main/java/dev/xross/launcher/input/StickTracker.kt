package dev.xross.launcher.input

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Converts analog stick / hat samples into discrete directions with a radial and an optional
 * axial deadzone, and an anti-snapback window: when a released stick springs past centre, the
 * brief opposite-direction sample it produces is ignored.
 *
 * Deadzones, applied in this order:
 *  - **Axial** ([axialDeadzone]): each axis on its own; a component smaller than the threshold is
 *    treated as zero. Removes off-axis drift so a slightly crooked push still reads as straight
 *    up/down/left/right, and stops a worn stick from drifting along one axis. 0 disables it.
 *  - **Radial** ([deadzone]): the length of the (axial-filtered) vector must reach the threshold,
 *    so the dead area is a circle and diagonals need the same travel as cardinal directions.
 *    0 turns it off ("hardware only"): any sample outside the driver's flat zone, which
 *    [GamepadHandler] has already zeroed, counts. An exactly centred stick never does.
 *
 * Pure and time-injected so it can be unit-tested.
 */
class StickTracker(
    var deadzone: Float = 0.35f,
    private val snapbackWindowMs: Long = 70,
    var axialDeadzone: Float = 0f,
) {
    var current: NavAction? = null
        private set

    private var lastReleased: NavAction? = null
    private var releasedAtMs: Long = Long.MIN_VALUE

    /**
     * Feeds one sample. Returns the new direction if it changed to a non-null direction
     * (i.e. a fresh "press"), otherwise null. Read [current] for the held direction.
     */
    fun update(x: Float, y: Float, nowMs: Long): NavAction? {
        val next = direction(x, y, deadzone, axialDeadzone)
        if (next == current) return null

        if (next == null) {
            lastReleased = current
            releasedAtMs = nowMs
            current = null
            return null
        }

        val isSnapback = current == null &&
            next == lastReleased?.opposite() &&
            nowMs - releasedAtMs <= snapbackWindowMs
        if (isSnapback) return null

        current = next
        return next
    }

    fun reset() {
        current = null
        lastReleased = null
    }

    companion object {
        fun direction(x: Float, y: Float, deadzone: Float, axialDeadzone: Float = 0f): NavAction? {
            val ax = applyAxial(x, axialDeadzone)
            val ay = applyAxial(y, axialDeadzone)
            if (hypot(ax, ay) < deadzone) return null
            if (ax == 0f && ay == 0f) return null
            return if (abs(ax) > abs(ay)) {
                if (ax > 0) NavAction.RIGHT else NavAction.LEFT
            } else {
                if (ay > 0) NavAction.DOWN else NavAction.UP
            }
        }

        /** Zeroes a component inside the axial deadzone; leaves it untouched otherwise. */
        internal fun applyAxial(value: Float, axialDeadzone: Float): Float =
            if (axialDeadzone > 0f && abs(value) < axialDeadzone) 0f else value
    }
}
