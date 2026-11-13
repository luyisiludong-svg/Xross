package dev.xross.launcher.media.energy

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-order IIR filter in transposed direct form II, with the RBJ cookbook low-pass
 * coefficients. State is in doubles: at 150 Hz over 48 kHz the poles sit close to 1, where float
 * state drifts.
 */
class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    /** Low-pass at [cutoffHz] for [sampleRateHz]; Q 0.707 is maximally flat (Butterworth). */
    fun lowPass(sampleRateHz: Int, cutoffHz: Double, q: Double = BUTTERWORTH_Q) {
        val w0 = 2 * PI * (cutoffHz / sampleRateHz).coerceIn(1e-5, 0.49)
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha
        b0 = (1 - cw) / 2 / a0
        b1 = (1 - cw) / a0
        b2 = b0
        a1 = -2 * cw / a0
        a2 = (1 - alpha) / a0
        reset()
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    companion object {
        const val BUTTERWORTH_Q = 0.7071067811865476
    }
}

/**
 * 4th-order Butterworth low-pass: two [Biquad]s with the Butterworth pole pair Qs, -3 dB at the
 * corner and 24 dB per octave above it, so the 200–400 Hz of vocals and snare stay out of the bass.
 */
class LowPass4 {
    private val first = Biquad()
    private val second = Biquad()

    fun configure(sampleRateHz: Int, cutoffHz: Double) {
        first.lowPass(sampleRateHz, cutoffHz, Q1)
        second.lowPass(sampleRateHz, cutoffHz, Q2)
    }

    fun process(x: Double): Double = second.process(first.process(x))

    fun reset() {
        first.reset()
        second.reset()
    }

    companion object {
        /** 1 / (2 cos(π/8)) and 1 / (2 cos(3π/8)). */
        const val Q1 = 0.5411961001461970
        const val Q2 = 1.3065629648763766
    }
}

/**
 * Bass energy and beats of the music playing, without an FFT (Theme 2.0, 2b.4b). Fed one mono
 * sample at a time on ExoPlayer's playback thread by [BassTapProcessor]; every [HOP_S] it pushes
 * `(time, bass, beat)` into [ring], stamped with the media time the hop will be heard at.
 *
 *  - Bass: RMS of the signal through a [CUTOFF_HZ] low-pass ([LowPass4]), as a running mean
 *    square over [SMOOTH_S] (longer than a bass period, so a steady tone reads steady), over a
 *    slowly decaying peak (the track's own loudness, so a quiet song pulses as much as a loud one;
 *    [PEAK_FLOOR] keeps near-silence from being blown up), then held with an exponential release
 *    of [RELEASE_S]: attacks are instant, the fall is smooth.
 *  - Beat: the hop's bass energy (RMS²) against the mean of the last [WINDOW_S]; over
 *    [BEAT_RATIO] times the mean (and over a floor) starts a beat, held for [BEAT_HOLD_S] so even a
 *    frame at the standby rate sees it, with no new beat for [REFRACTORY_S].
 *
 * Not thread-safe: one writer thread. Allocation-free after [configure].
 */
class BassAnalyzer(private val ring: AudioEnergyRing) {
    private val filter = LowPass4()
    private var sampleRate = 0
    private var hopFrames = 0
    private var hopUs = 0.0
    private var smoothing = 0.0
    private var releasePerHop = 0f
    private var peakDecayPerHop = 0f

    // Time base: the media time of the first frame after the last anchor.
    private var anchorUs = NO_ANCHOR
    private var framesSinceAnchor = 0L

    // The hop under way, and the running mean square of the filtered signal.
    private var hopCount = 0
    private var meanSquare = 0.0

    private var peak = PEAK_FLOOR
    private var bass = 0f

    // Beat detection: the last WINDOW_S of hop energies.
    private var history = FloatArray(0)
    private var historyNext = 0
    private var historySize = 0
    private var historySum = 0.0
    private var sinceBeatUs = Double.MAX_VALUE

    /**
     * A new stream format. Restarts the filter and the current hop; the loudness history stays.
     * 0: a format the tap can't read, so nothing is analyzed until the next one.
     */
    fun configure(sampleRateHz: Int) {
        if (sampleRateHz <= 0) {
            sampleRate = 0
            hopFrames = 0
            return
        }
        if (sampleRateHz != sampleRate) {
            sampleRate = sampleRateHz
            hopFrames = (sampleRateHz * HOP_S).toInt().coerceAtLeast(1)
            hopUs = hopFrames * 1e6 / sampleRateHz
            val hopS = hopFrames.toDouble() / sampleRateHz
            releasePerHop = exp(-hopS / RELEASE_S).toFloat()
            peakDecayPerHop = exp(-hopS / PEAK_RELEASE_S).toFloat()
            smoothing = 1 - exp(-1.0 / (sampleRateHz * SMOOTH_S))
            val size = (WINDOW_S / hopS).toInt().coerceAtLeast(2)
            if (history.size != size) {
                history = FloatArray(size)
                clearHistory()
            }
            filter.configure(sampleRateHz, CUTOFF_HZ)
        }
        restart()
    }

    /** The next sample passed to [process] is heard at [timeUs] (the buffer's presentation time). */
    fun anchor(timeUs: Long) {
        anchorUs = timeUs
        framesSinceAnchor = 0
    }

    /** One frame, mono, -1..1. */
    fun process(sample: Float) {
        if (hopFrames == 0) return
        val y = filter.process(sample.toDouble())
        meanSquare += (y * y - meanSquare) * smoothing
        framesSinceAnchor++
        if (++hopCount >= hopFrames) endHop()
    }

    /** The filter and the hop under way start over (a flush or a new format); levels stay. */
    fun restart() {
        filter.reset()
        hopCount = 0
        meanSquare = 0.0
    }

    /** Everything back to silence (a seek, a stop, a new track list). */
    fun reset() {
        restart()
        anchorUs = NO_ANCHOR
        framesSinceAnchor = 0
        peak = PEAK_FLOOR
        bass = 0f
        clearHistory()
    }

    private fun clearHistory() {
        history.fill(0f)
        historyNext = 0
        historySize = 0
        historySum = 0.0
        sinceBeatUs = Double.MAX_VALUE
    }

    private fun endHop() {
        val rms = sqrt(meanSquare).toFloat()
        hopCount = 0

        peak = maxOf(rms, peak * peakDecayPerHop, PEAK_FLOOR)
        val level = if (rms < GATE) 0f else (rms / peak).coerceIn(0f, 1f)
        bass = maxOf(level, bass * releasePerHop)
        if (bass < 1e-3f) bass = 0f

        // Beat: this hop's energy against the recent mean (before this hop joins it).
        val energy = rms * rms
        val mean = if (historySize == 0) 0.0 else historySum / historySize
        sinceBeatUs += hopUs
        if (energy > BEAT_RATIO * mean && energy > BEAT_MIN_ENERGY && level > BEAT_MIN_LEVEL && sinceBeatUs >= REFRACTORY_S * 1e6) {
            sinceBeatUs = 0.0
        }
        val beat = sinceBeatUs < BEAT_HOLD_S * 1e6
        if (historySize == history.size) historySum -= history[historyNext] else historySize++
        history[historyNext] = energy
        historySum = (historySum + energy).coerceAtLeast(0.0) // float rounding never goes negative
        historyNext = (historyNext + 1) % history.size

        if (anchorUs == NO_ANCHOR) return // no clock to place it on (not reached in playback)
        // The hop's middle, on the media timeline.
        val endUs = anchorUs + framesSinceAnchor * 1e6 / sampleRate
        ring.push((endUs - hopUs / 2).toLong(), bass, beat)
    }

    companion object {
        private const val NO_ANCHOR = Long.MIN_VALUE

        /** Low-pass corner: kick drum and bass line, not the vocals. */
        const val CUTOFF_HZ = 150.0

        /** Analysis step: one ring entry per 10 ms. */
        const val HOP_S = 0.010

        /** Time constant of the running mean square: over a period of 50 Hz, with little lag. */
        const val SMOOTH_S = 0.020

        /** Bass release time constant (the decay falloff). */
        const val RELEASE_S = 0.200

        /** How fast the loudness reference forgets a loud passage. */
        const val PEAK_RELEASE_S = 4.0

        /** Lowest loudness reference (bass RMS ≈ -28 dBFS): quieter stays below 1. */
        const val PEAK_FLOOR = 0.04f

        /** Bass RMS under this (≈ -50 dBFS) is silence; so is what treble leaks through the filter. */
        const val GATE = 0.003f

        /** Window of the beat detector's mean energy. */
        const val WINDOW_S = 1.0

        /** A hop over this many times the window's mean energy is a beat. */
        const val BEAT_RATIO = 1.6

        /** Energy floor of a beat (bass RMS ≈ -40 dBFS), so a hiss over silence never beats. */
        const val BEAT_MIN_ENERGY = 1e-4f

        /** A beat also needs this much of the track's own bass loudness. */
        const val BEAT_MIN_LEVEL = 0.35f

        /** `uAudioBeat` stays 1 this long after a beat starts (three frames at 20 fps). */
        const val BEAT_HOLD_S = 0.100

        /** No new beat this soon after one (caps detection at 200 BPM). */
        const val REFRACTORY_S = 0.300
    }
}
