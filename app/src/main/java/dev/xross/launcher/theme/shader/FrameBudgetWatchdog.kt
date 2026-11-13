package dev.xross.launcher.theme.shader

import kotlin.math.ceil

/**
 * The frame-budget watchdog's decision (2b.4a), kept free of GL so it can be unit tested. The
 * renderer feeds it the GPU time of each draw of a new program (from `GL_EXT_disjoint_timer_query`,
 * or sampled `glFinish` timings where the driver lacks it) during the first [windowNs] after the
 * program is first drawn. Once the window has passed with at least [minSamples] samples, the
 * program passes when the 90th percentile is within [limitFraction] of [budgetNs] (10 ms of a
 * 16.7 ms frame at 60 Hz) and trips otherwise. A program far over budget trips early, so the
 * screen isn't a slideshow for 2 s: after [HOPELESS_SAMPLES] samples all over
 * [HOPELESS_FACTOR]× the budget (a 6000-iteration test shader drew at 344 ms a frame on a Galaxy
 * A55), or after [EARLY_SAMPLES] with a median over twice the budget.
 *
 * The first [skipFirst] samples are ignored: they carry the driver's first-use work (the
 * renderer's 1×1 warm-up draw takes most of it).
 */
class FrameBudgetWatchdog(
    val budgetNs: Long,
    val windowNs: Long = WINDOW_NS,
    val limitFraction: Float = LIMIT_FRACTION,
    val minSamples: Int = MIN_SAMPLES,
    val skipFirst: Int = SKIP_FIRST,
) {
    enum class Verdict { MEASURING, PASSED, TRIPPED }

    /** The p90 a program must stay under. */
    val limitNs: Long = budgetNs * kotlin.math.round(limitFraction * 1000f).toLong() / 1000

    private val samples = LongArray(MAX_SAMPLES)
    private var count = 0
    private var skipped = 0
    private var startNs = -1L

    var verdict: Verdict = Verdict.MEASURING
        private set

    /** The 90th percentile the verdict was reached on (0 until then; the median for a median-based early trip). */
    var p90Ns: Long = 0
        private set

    val sampleCount: Int get() = count

    /** One draw that took [durationNs] of GPU time, measured at [nowNs] (any monotonic clock). */
    fun record(durationNs: Long, nowNs: Long): Verdict {
        if (verdict != Verdict.MEASURING) return verdict
        if (durationNs < 0) return verdict
        if (skipped < skipFirst) {
            skipped++
            return verdict
        }
        if (startNs < 0) startNs = nowNs
        if (count < MAX_SAMPLES) samples[count++] = durationNs
        if (count == HOPELESS_SAMPLES) {
            val fastest = percentile(samples, count, 0)
            if (fastest > HOPELESS_FACTOR * budgetNs) {
                p90Ns = percentile(samples, count, 90)
                verdict = Verdict.TRIPPED
                return verdict
            }
        }
        if (count == EARLY_SAMPLES) {
            val median = percentile(samples, count, 50)
            if (median > 2 * budgetNs) {
                p90Ns = median
                verdict = Verdict.TRIPPED
                return verdict
            }
        }
        if (nowNs - startNs >= windowNs && count >= minSamples) {
            p90Ns = percentile(samples, count, 90)
            verdict = if (p90Ns > limitNs) Verdict.TRIPPED else Verdict.PASSED
        }
        return verdict
    }

    /** `p90 12.4 ms > 10.0 ms`, for the toast and the log. */
    fun describe(): String = "p90 %.1f ms > %.1f ms".format(p90Ns / 1e6, limitNs / 1e6)

    companion object {
        const val WINDOW_NS = 2_000_000_000L
        const val LIMIT_FRACTION = 0.6f
        const val MIN_SAMPLES = 12
        const val SKIP_FIRST = 1
        const val HOPELESS_SAMPLES = 3
        const val HOPELESS_FACTOR = 4
        const val EARLY_SAMPLES = 6
        private const val MAX_SAMPLES = 512

        /** Nearest-rank [p]th percentile of the first [n] of [values]. */
        fun percentile(values: LongArray, n: Int, p: Int): Long {
            require(n > 0)
            val sorted = values.copyOf(n).also { it.sort() }
            val rank = ceil(p / 100.0 * n).toInt().coerceIn(1, n)
            return sorted[rank - 1]
        }
    }
}
