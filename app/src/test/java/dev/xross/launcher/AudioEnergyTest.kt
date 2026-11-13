package dev.xross.launcher

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import dev.xross.launcher.media.energy.AudioEnergyRing
import dev.xross.launcher.media.energy.BassAnalyzer
import dev.xross.launcher.media.energy.BassTapProcessor
import dev.xross.launcher.media.energy.Biquad
import dev.xross.launcher.media.energy.EnergyAudioSink
import dev.xross.launcher.media.energy.LowPass4
import dev.xross.launcher.media.energy.MusicEnergy
import dev.xross.launcher.media.energy.PlaybackClock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Theme 2.0 2b.4b, audio-reactive uniforms: the 150 Hz low-pass, bass normalization and decay,
 * beat detection, the ring buffer's lookup by heard time, the playout clock, and the whole tap
 * (processor in the sink, buffer anchoring, pause, flush) behind a fake sink.
 */
class AudioEnergyTest {
    private val rate = 48_000

    // region Low-pass filter

    /** Steady-state gain of [process] at [hz], in dB (RMS over the second half of 1 s). */
    private fun gainDb(hz: Double, reset: () -> Unit, process: (Double) -> Double): Double {
        reset()
        var inSq = 0.0
        var outSq = 0.0
        for (n in 0 until rate) {
            val x = sin(2 * PI * hz * n / rate)
            val y = process(x)
            if (n >= rate / 2) {
                inSq += x * x
                outSq += y * y
            }
        }
        return 20 * log10(sqrt(outSq / inSq))
    }

    private fun gainDb(f: Biquad, hz: Double) = gainDb(hz, f::reset, f::process)

    private fun gainDb(f: LowPass4, hz: Double) = gainDb(hz, f::reset, f::process)

    @Test
    fun biquadIsASecondOrderButterworth() {
        val f = Biquad().apply { lowPass(rate, BassAnalyzer.CUTOFF_HZ) }
        assertEquals(0.0, gainDb(f, 40.0), 0.3)
        assertEquals(-3.01, gainDb(f, 150.0), 0.2) // Butterworth corner
        // 12 dB per octave: 3 octaves above the corner is ~-36 dB.
        assertEquals(-36.0, gainDb(f, 1200.0), 1.0)
    }

    @Test
    fun bassFilterPassesBassAndCutsVocals() {
        val f = LowPass4().apply { configure(rate, BassAnalyzer.CUTOFF_HZ) }
        assertEquals(0.0, gainDb(f, 40.0), 0.1)
        assertEquals(0.0, gainDb(f, 80.0), 0.3)
        assertEquals(-3.01, gainDb(f, 150.0), 0.2)
        // 24 dB per octave: one octave up is ~-24 dB, a vocal's 360 Hz ~-30 dB.
        assertEquals(-24.1, gainDb(f, 300.0), 0.5)
        assertTrue(gainDb(f, 360.0) < -29)
        assertTrue(gainDb(f, 1200.0) < -70)
    }

    @Test
    fun lowPassHasUnityDcGainAndResets() {
        val f = Biquad().apply { lowPass(44_100, 150.0) }
        var y = 0.0
        repeat(44_100) { y = f.process(0.5) }
        assertEquals(0.5, y, 1e-6)
        f.reset()
        assertEquals(0.0, f.process(0.0), 0.0)
    }

    // endregion

    // region Analyzer

    private fun tone(hz: Double, amp: Double, seconds: Double, into: BassAnalyzer) {
        val n = (seconds * rate).toInt()
        for (i in 0 until n) into.process((amp * sin(2 * PI * hz * i / rate)).toFloat())
    }

    private fun silence(seconds: Double, into: BassAnalyzer) = repeat((seconds * rate).toInt()) { into.process(0f) }

    /** The ring's entries, in order: time, bass, beat (1/0). */
    private fun entries(ring: AudioEnergyRing, fromUs: Long, toUs: Long, stepUs: Long = 10_000): List<Triple<Long, Float, Float>> {
        val out = FloatArray(2)
        return (fromUs..toUs step stepUs).map { t -> ring.sample(t, out); Triple(t, out[0], out[1]) }
    }

    private fun analyzer(ring: AudioEnergyRing, startUs: Long = 0) =
        BassAnalyzer(ring).apply { configure(rate); anchor(startUs) }

    @Test
    fun entriesAreStampedAtHopCentresFromTheAnchor() {
        val ring = AudioEnergyRing()
        val a = analyzer(ring, startUs = 1_000_000)
        silence(0.1, a)
        assertEquals(10, ring.count)
        val out = FloatArray(2)
        // First hop: frames 0..479, centre 5 ms after the anchor; nothing before the edge.
        assertTrue(ring.sample(1_005_000, out))
        assertFalse(ring.sample(1_005_000 - AudioEnergyRing.EDGE_US - 1, out))
        assertTrue(ring.sample(1_095_000, out))
        assertFalse(ring.sample(1_095_000 + AudioEnergyRing.EDGE_US + 1, out))
    }

    @Test
    fun silenceIsZero() {
        val ring = AudioEnergyRing()
        val a = analyzer(ring)
        silence(1.0, a)
        assertTrue(entries(ring, 0, 1_000_000).all { it.second == 0f && it.third == 0f })
    }

    @Test
    fun steadyBassReadsFull() {
        val ring = AudioEnergyRing()
        val a = analyzer(ring)
        tone(60.0, 0.5, 2.0, a)
        val bass = entries(ring, 1_000_000, 1_990_000)
        assertTrue("${bass.minOf { it.second }}", bass.all { it.second > 0.9f })
    }

    @Test
    fun aVoiceBarelyRegistersAndNeverBeats() {
        // A singer's 360 Hz fundamental at -15 dBFS RMS with no bass under it: 30 dB down
        // through the filter, a sliver of the range at most, and its onset is no beat.
        val ring = AudioEnergyRing()
        val a = analyzer(ring)
        tone(360.0, 0.25, 2.0, a)
        val e = entries(ring, 0, 1_990_000)
        assertTrue(e.all { it.third == 0f })
        assertTrue("${e.maxOf { it.second }}", e.drop(50).all { it.second < 0.2f })
        // Treble (2 kHz) leaks nothing: only the click of the tone starting abruptly shows, a
        // little, and dies away; no beat.
        val ring2 = AudioEnergyRing()
        val b = analyzer(ring2)
        tone(2000.0, 0.5, 2.0, b)
        val t = entries(ring2, 0, 1_990_000)
        assertTrue(t.all { it.second < 0.15f && it.third == 0f })
        assertTrue(entries(ring2, 1_000_000, 1_990_000).all { it.second == 0f })
    }

    @Test
    fun bassIsNormalizedToTheTracksOwnLoudness() {
        // A quiet song (bass -26 dBFS) still reaches the top of the range.
        val ring = AudioEnergyRing()
        val a = analyzer(ring)
        tone(60.0, 0.07, 2.0, a)
        assertTrue(entries(ring, 1_000_000, 1_990_000).all { it.second > 0.9f })
    }

    @Test
    fun bassFallsOffExponentiallyIn200Ms() {
        val ring = AudioEnergyRing()
        val a = analyzer(ring)
        tone(60.0, 0.5, 1.0, a)
        silence(1.5, a)
        val out = FloatArray(2)
        ring.sample(995_000, out)
        val atStop = out[0]
        assertTrue(atStop > 0.9f)
        ring.sample(1_205_000, out) // one time constant later
        assertEquals(atStop * 0.368f, out[0], 0.06f)
        ring.sample(1_405_000, out)
        assertEquals(atStop * 0.135f, out[0], 0.04f)
        ring.sample(2_400_000, out)
        assertEquals(0f, out[0], 0f)
    }

    /** Kicks: [kickS] of 55 Hz at [amp] every [periodS], over a quiet bass bed. */
    private fun kicks(a: BassAnalyzer, seconds: Double, periodS: Double = 0.5, kickS: Double = 0.06, amp: Double = 0.6) {
        val n = (seconds * rate).toInt()
        val period = (periodS * rate).toInt()
        val kick = (kickS * rate).toInt()
        for (i in 0 until n) {
            val inKick = i % period < kick
            val x = 0.04 * sin(2 * PI * 80.0 * i / rate) + if (inKick) amp * sin(2 * PI * 55.0 * i / rate) else 0.0
            a.process(x.toFloat())
        }
    }

    /** Beat onsets (0 → 1 edges) in the ring between [fromUs] and [toUs]. */
    private fun onsets(ring: AudioEnergyRing, fromUs: Long, toUs: Long): List<Long> {
        // Starting from silence, so a beat on the very first entry counts.
        val e = listOf(Triple(fromUs - 1, 0f, 0f)) + entries(ring, fromUs, toUs, stepUs = 5_000)
        return e.zipWithNext().filter { (p, q) -> p.third == 0f && q.third == 1f }.map { it.second.first }
    }

    @Test
    fun kicksAreBeatsHeldFor100Ms() {
        val ring = AudioEnergyRing()
        val a = analyzer(ring)
        kicks(a, 4.0)
        val beats = onsets(ring, 0, 4_000_000)
        assertEquals("one beat per kick at 120 BPM: $beats", 8, beats.size)
        // Each lands on its kick (within a hop or two) ...
        beats.forEachIndexed { i, t -> assertEquals(i * 500_000.0, t.toDouble(), 25_000.0) }
        // ... and lasts BEAT_HOLD_S.
        val out = FloatArray(2)
        val t = beats[3]
        ring.sample(t + 80_000, out)
        assertEquals(1f, out[1], 0f)
        ring.sample(t + 130_000, out)
        assertEquals(0f, out[1], 0f)
    }

    @Test
    fun aSteadyToneIsOneBeatAtItsOnsetOnly() {
        val ring = AudioEnergyRing()
        val a = analyzer(ring)
        tone(60.0, 0.5, 3.0, a)
        val beats = onsets(ring, 0, 3_000_000)
        assertTrue("$beats", beats.size <= 1)
        assertTrue(beats.all { it < 50_000 })
    }

    @Test
    fun beatsRespectTheRefractoryPeriod() {
        // Kicks every 150 ms are faster than 200 BPM: at most one beat per 300 ms.
        val ring = AudioEnergyRing()
        val a = analyzer(ring)
        kicks(a, 3.0, periodS = 0.15, kickS = 0.04)
        val beats = onsets(ring, 0, 3_000_000)
        assertTrue(beats.isNotEmpty())
        beats.zipWithNext().forEach { (p, q) -> assertTrue("$p -> $q", q - p >= 295_000) }
    }

    @Test
    fun resetForgetsTheTrack() {
        val ring = AudioEnergyRing()
        val a = analyzer(ring)
        tone(60.0, 0.5, 1.0, a)
        a.reset()
        a.anchor(5_000_000)
        silence(0.5, a)
        val out = FloatArray(2)
        ring.sample(5_005_000, out)
        assertEquals(0f, out[0], 0f)
    }

    // endregion

    // region Ring buffer

    @Test
    fun ringInterpolatesBassAndStepsTheBeat() {
        val ring = AudioEnergyRing()
        ring.push(1_000, 0.2f, false)
        ring.push(11_000, 0.6f, true)
        ring.push(21_000, 0.4f, false)
        val out = FloatArray(2)
        assertTrue(ring.sample(6_000, out))
        assertArrayEquals(floatArrayOf(0.4f, 0f), out, 1e-6f)
        ring.sample(13_500, out) // a quarter of the way from 0.6 to 0.4; the beat entry still holds
        assertArrayEquals(floatArrayOf(0.55f, 1f), out, 1e-6f)
        ring.sample(11_000, out)
        assertArrayEquals(floatArrayOf(0.6f, 1f), out, 1e-6f)
        ring.sample(21_000, out)
        assertArrayEquals(floatArrayOf(0.4f, 0f), out, 1e-6f)
    }

    @Test
    fun ringHoldsItsEndsBrieflyThenReadsZero() {
        val ring = AudioEnergyRing()
        val out = floatArrayOf(9f, 9f)
        assertFalse(ring.sample(0, out)) // empty
        assertArrayEquals(floatArrayOf(0f, 0f), out, 0f)
        ring.push(100_000, 0.5f, true)
        ring.push(110_000, 0.7f, false)
        assertTrue(ring.sample(100_000 - AudioEnergyRing.EDGE_US, out))
        assertArrayEquals(floatArrayOf(0.5f, 1f), out, 0f)
        assertTrue(ring.sample(110_000 + AudioEnergyRing.EDGE_US, out))
        assertArrayEquals(floatArrayOf(0.7f, 0f), out, 0f)
        // An underrun or a stale reader: nothing to say about that moment.
        assertFalse(ring.sample(110_000 + AudioEnergyRing.EDGE_US + 1, out))
        assertArrayEquals(floatArrayOf(0f, 0f), out, 0f)
        ring.clear()
        assertFalse(ring.sample(105_000, out))
    }

    @Test
    fun ringWrapsAroundKeepingTheNewest() {
        val ring = AudioEnergyRing(capacity = 8)
        for (i in 0 until 20) ring.push(i * 10_000L, i / 20f, false)
        assertEquals(8, ring.count)
        val out = FloatArray(2)
        assertFalse(ring.sample(50_000, out)) // overwritten long ago
        ring.sample(125_000, out)
        assertEquals(12.5f / 20f, out[0], 1e-6f) // oldest kept: 12
        ring.sample(185_000, out)
        assertEquals(18.5f / 20f, out[0], 1e-6f)
    }

    @Test
    fun ringReplacesEntriesWhenTimeGoesBack() {
        val ring = AudioEnergyRing()
        for (i in 0 until 10) ring.push(i * 10_000L, 0.9f, false)
        ring.push(45_000, 0.1f, false) // drops 50..90 ms
        assertEquals(6, ring.count)
        val out = FloatArray(2)
        ring.sample(45_000, out)
        assertEquals(0.1f, out[0], 0f)
        assertFalse(ring.sample(45_000 + AudioEnergyRing.EDGE_US + 1, out))
    }

    // endregion

    // region Playout clock

    @Test
    fun clockExtrapolatesWhilePlayingOnly() {
        val c = PlaybackClock()
        assertEquals(PlaybackClock.NO_POSITION, c.audibleUs(0))
        c.update(2_000_000, atNanos = 1_000_000_000)
        assertEquals(PlaybackClock.NO_POSITION, c.audibleUs(1_000_000_000)) // not playing
        c.setPlaying(true)
        assertEquals(PlaybackClock.NO_POSITION, c.audibleUs(1_000_000_000)) // resumed: wait for a reading
        c.update(2_000_000, atNanos = 1_000_000_000)
        assertEquals(2_016_000, c.audibleUs(1_016_000_000))
        c.setSpeed(2f)
        assertEquals(2_032_000, c.audibleUs(1_016_000_000))
        c.setSpeed(1f)
        // No reading for longer than MAX_EXTRAPOLATION: stalled.
        assertEquals(PlaybackClock.NO_POSITION, c.audibleUs(1_000_000_000 + PlaybackClock.MAX_EXTRAPOLATION_NS + 1))
        c.setPlaying(false)
        assertEquals(PlaybackClock.NO_POSITION, c.audibleUs(1_016_000_000))
        c.setPlaying(true)
        c.update(3_000_000, atNanos = 2_000_000_000)
        c.invalidate()
        assertEquals(PlaybackClock.NO_POSITION, c.audibleUs(2_000_000_000))
    }

    // endregion

    // region The tap behind a fake sink

    /**
     * A stand-in for DefaultAudioSink: [handleBuffer] runs the tap on the buffer (as the real
     * sink's processing pipeline does) unless [accept] is off; the playout position is [positionUs].
     */
    private class FakeSink(val tap: BassTapProcessor) {
        var accept = true
        var positionUs = AudioSink.CURRENT_POSITION_NOT_SET
        var output: ByteBuffer? = null
        val calls = mutableListOf<String>()

        val sink: AudioSink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, m, args ->
            calls += m.name
            when (m.name) {
                "handleBuffer" -> if (!accept) false else {
                    tap.queueInput(args[0] as ByteBuffer)
                    output = tap.output
                    true
                }
                "getCurrentPositionUs" -> positionUs
                else -> when (m.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Long::class.javaPrimitiveType -> 0L
                    Int::class.javaPrimitiveType -> 0
                    Float::class.javaPrimitiveType -> 0f
                    else -> null
                }
            }
        } as AudioSink
    }

    private class Rig {
        var now = 0L
        val energy = MusicEnergy { now }
        val analyzer = BassAnalyzer(energy.ring)
        val tap = BassTapProcessor(analyzer).apply {
            configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
            flush()
        }
        val fake = FakeSink(tap)
        val sink = EnergyAudioSink(fake.sink, analyzer, energy)
        val out = FloatArray(2)

        fun read(): FloatArray = out.also { energy.read(now, it) }
    }

    /** [ms] of stereo 16-bit PCM at 48 kHz: 55 Hz at [amp] (0 = silence), L and R equal. */
    private fun pcm(ms: Int, amp: Double, startFrame: Int = 0): ByteBuffer {
        val frames = 48 * ms
        val b = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val s = (amp * 32767 * sin(2 * PI * 55.0 * (startFrame + i) / 48_000)).toInt().toShort()
            b.putShort(s).putShort(s)
        }
        b.flip()
        return b
    }

    @Test
    fun tapPassesAudioThroughUnchanged() {
        val rig = Rig()
        val input = pcm(20, 0.5)
        val copy = ByteArray(input.remaining()).also { input.duplicate().get(it) }
        assertTrue(rig.sink.handleBuffer(input, 0, 1))
        assertFalse(input.hasRemaining())
        val out = rig.fake.output!!
        val got = ByteArray(out.remaining()).also { out.get(it) }
        assertArrayEquals(copy, got)
    }

    @Test
    fun shaderFollowsWhatIsHeardNotWhatWasDecoded() {
        val rig = Rig()
        val t0 = 1_000_000_000_000L // ExoPlayer's renderer timeline starts here
        rig.sink.play()
        // 1 s of silence, then a 55 Hz tone from t0 + 1 s, decoded in 20 ms buffers.
        var frame = 0
        for (k in 0 until 100) {
            val loud = k >= 50
            assertTrue(rig.sink.handleBuffer(pcm(20, if (loud) 0.5 else 0.0, frame), t0 + k * 20_000L, 1))
            frame += 960
        }
        // The decoder is 1 s ahead; the speaker is 200 ms before the tone.
        rig.fake.positionUs = t0 + 800_000
        rig.now = 5_000_000_000
        rig.sink.getCurrentPositionUs(false)
        assertArrayEquals(floatArrayOf(0f, 0f), rig.read(), 0f)
        // The newest entry (hop centre 1.995 s) is ~1.2 s ahead of the speaker.
        assertEquals(1_195_000L, rig.energy.leadUs(rig.now))
        // 16 ms later, still extrapolated in silence.
        rig.now += 16_000_000
        assertEquals(0f, rig.read()[0], 0f)
        // The tone reaches the speaker: bass up, and its onset is a beat.
        rig.fake.positionUs = t0 + 1_030_000
        rig.sink.getCurrentPositionUs(false)
        val heard = rig.read()
        assertTrue("bass ${heard[0]}", heard[0] > 0.8f)
        assertEquals(1f, heard[1], 0f)
        // Paused: 0 and 0 at once, though the ring is full of tone.
        rig.sink.pause()
        assertArrayEquals(floatArrayOf(0f, 0f), rig.read(), 0f)
        // Resumed: 0 until the sink reports where it is, then the tone again.
        rig.sink.play()
        assertArrayEquals(floatArrayOf(0f, 0f), rig.read(), 0f)
        rig.fake.positionUs = t0 + 1_500_000
        rig.sink.getCurrentPositionUs(false)
        assertTrue(rig.read()[0] > 0.8f)
        // A seek flushes: nothing to show until new audio is analyzed and heard.
        rig.sink.flush()
        assertArrayEquals(floatArrayOf(0f, 0f), rig.read(), 0f)
        rig.sink.getCurrentPositionUs(false)
        assertArrayEquals(floatArrayOf(0f, 0f), rig.read(), 0f)
        // Stopped.
        rig.sink.reset()
        rig.sink.getCurrentPositionUs(false)
        assertArrayEquals(floatArrayOf(0f, 0f), rig.read(), 0f)
    }

    @Test
    fun aBufferTheSinkRefusesIsAnchoredOnceWhenRetried() {
        val rig = Rig()
        val t0 = 2_000_000L
        rig.sink.play()
        assertTrue(rig.sink.handleBuffer(pcm(100, 0.5), t0, 1))
        // The AudioTrack is full: the next buffer is refused twice, then taken.
        val next = pcm(100, 0.5, 4800)
        rig.fake.accept = false
        assertFalse(rig.sink.handleBuffer(next, t0 + 100_000, 1))
        assertFalse(rig.sink.handleBuffer(next, t0 + 100_000, 1))
        rig.fake.accept = true
        // A retry carries the same time; frames since the anchor keep counting from it.
        assertTrue(rig.sink.handleBuffer(next, t0 + 100_000, 1))
        val count = rig.energy.ring.count
        assertEquals(20, count) // 200 ms of 10 ms hops, none lost or doubled
        rig.fake.positionUs = t0 + 195_000
        rig.now = 1_000_000_000
        rig.sink.getCurrentPositionUs(false)
        assertTrue(rig.read()[0] > 0.8f)
    }

    @Test
    fun nonPcmFormatsPassThroughUnread() {
        val ring = AudioEnergyRing()
        val tap = BassTapProcessor(BassAnalyzer(ring).apply { anchor(0) })
        tap.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_24BIT))
        tap.flush()
        val input = ByteBuffer.allocate(48 * 6 * 50)
        tap.queueInput(input)
        assertEquals(48 * 6 * 50, tap.output.remaining())
        assertEquals(0, ring.count)
    }

    @Test
    fun floatPcmIsReadToo() {
        val ring = AudioEnergyRing()
        val analyzer = BassAnalyzer(ring)
        val tap = BassTapProcessor(analyzer)
        tap.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_FLOAT))
        tap.flush()
        analyzer.anchor(0) // the sink does this per buffer
        val frames = 48_000
        val b = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) b.putFloat((0.5 * sin(2 * PI * 55.0 * i / 48_000)).toFloat())
        b.flip()
        tap.queueInput(b)
        assertEquals(100, ring.count)
        val out = FloatArray(2)
        ring.sample(900_000, out)
        assertTrue(out[0] > 0.9f)
    }

    // endregion
}
