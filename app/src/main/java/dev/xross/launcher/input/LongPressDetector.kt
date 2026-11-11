package dev.xross.launcher.input

/**
 * Detects a key held for [thresholdMs] and fires once per hold.
 *
 * Android normally synthesizes key repeats while a key is held, and the first repeat past the
 * threshold fires. Some controllers' Guide/Home buttons never repeat; for those the release
 * fires instead, if the key was held long enough. Either way a hold fires exactly once, and a
 * short tap never does.
 *
 * Pure and time-injected (KeyEvent event/down times) so it can be unit-tested.
 */
class LongPressDetector(private val thresholdMs: Long = DEFAULT_THRESHOLD_MS) {
    private var downAtMs = NOT_DOWN
    private var fired = false

    /** A key-down event (fresh press or repeat). Returns true when it completes a long press. */
    fun onDown(eventTimeMs: Long, downTimeMs: Long, repeatCount: Int): Boolean {
        if (repeatCount == 0 || downAtMs == NOT_DOWN) {
            downAtMs = downTimeMs
            fired = false
        }
        if (!fired && eventTimeMs - downAtMs >= thresholdMs) {
            fired = true
            return true
        }
        return false
    }

    /** The key was released. Returns true when the hold was long enough and nothing fired yet. */
    fun onUp(eventTimeMs: Long): Boolean {
        val fire = downAtMs != NOT_DOWN && !fired && eventTimeMs - downAtMs >= thresholdMs
        reset()
        return fire
    }

    fun reset() {
        downAtMs = NOT_DOWN
        fired = false
    }

    companion object {
        const val DEFAULT_THRESHOLD_MS = 500L
        private const val NOT_DOWN = Long.MIN_VALUE
    }
}
