package dev.xross.launcher.input

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Converts analog stick / hat samples into discrete directions with a radial deadzone and an
 * anti-snapback window: when a released stick springs past centre, the brief opposite-direction
 * sample it produces is ignored.
 *
 * Pure and time-injected so it can be unit-tested.
 */
class StickTracker(
    var deadzone: Float = 0.35f,
    private val snapbackWindowMs: Long = 70,
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
        val next = direction(x, y, deadzone)
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
        fun direction(x: Float, y: Float, deadzone: Float): NavAction? {
            if (hypot(x, y) < deadzone) return null
            return if (abs(x) > abs(y)) {
                if (x > 0) NavAction.RIGHT else NavAction.LEFT
            } else {
                if (y > 0) NavAction.DOWN else NavAction.UP
            }
        }
    }
}
