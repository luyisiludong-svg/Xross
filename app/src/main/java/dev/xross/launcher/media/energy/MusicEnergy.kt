@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.xross.launcher.media.energy

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/** Music levels for the background shader (`uAudioBass`, `uAudioBeat`), read on the GL thread. */
fun interface AudioLevels {
    /** `out[0]` = bass 0–1, `out[1]` = beat 0 or 1, as heard at [nowNanos] (elapsed realtime). */
    fun read(nowNanos: Long, out: FloatArray)

    /** Debug: how far the newest analysis runs ahead of what is heard, in µs, or null. */
    fun leadUs(nowNanos: Long): Long? = null
}

/** Elapsed-realtime nanoseconds: the GL thread's frame clock. Injected so tests can drive it. */
fun interface NanoClock {
    fun now(): Long
}

/**
 * Audio-reactive uniforms from Xross's own music player (Theme 2.0, 2b.4b), with no
 * `RECORD_AUDIO`: a pass-through [BassTapProcessor] in the music ExoPlayer's [DefaultAudioSink]
 * feeds a [BassAnalyzer], whose entries go into [ring] by media time; [EnergyAudioSink] around
 * that sink stamps each decoded buffer with its presentation time and samples the sink's own
 * playout clock ([AudioSink.getCurrentPositionUs], what ExoPlayer derives `currentPosition` from).
 *
 * The GL thread asks for the moment being heard, extrapolated from that clock ([PlaybackClock]),
 * not the newest analysis, which leads the speaker by the AudioTrack buffer. ExoPlayer itself may
 * only be called on its application thread, so the GL thread never touches the player.
 * Paused, stopped, buffering or never started: 0 and 0.
 */
class MusicEnergy(private val clock: NanoClock = NanoClock { SystemClock.elapsedRealtimeNanos() }) : AudioLevels {
    internal val ring = AudioEnergyRing()
    internal val playback = PlaybackClock()

    /** Builds the music player's sink with the tap in front of the default processors. */
    fun buildSink(builder: DefaultAudioSink.Builder): AudioSink {
        val analyzer = BassAnalyzer(ring)
        return EnergyAudioSink(builder.setAudioProcessors(arrayOf(BassTapProcessor(analyzer))).build(), analyzer, this)
    }

    override fun read(nowNanos: Long, out: FloatArray) {
        val heard = playback.audibleUs(nowNanos)
        if (heard == PlaybackClock.NO_POSITION) {
            out[0] = 0f
            out[1] = 0f
        } else {
            ring.sample(heard, out)
        }
    }

    override fun leadUs(nowNanos: Long): Long? {
        val heard = playback.audibleUs(nowNanos)
        val newest = ring.newestUs()
        return if (heard == PlaybackClock.NO_POSITION || newest == null) null else newest - heard
    }

    internal fun now(): Long = clock.now()
}

/**
 * The sink's playout position, sampled on the playback thread each time the audio renderer asks
 * for it (every ~10 ms while playing), and extrapolated for the GL thread.
 */
class PlaybackClock {
    private val lock = Any()
    private var positionUs = 0L
    private var atNanos = 0L
    private var known = false
    private var playing = false
    private var speed = 1f

    fun update(positionUs: Long, atNanos: Long) = synchronized(lock) {
        this.positionUs = positionUs
        this.atNanos = atNanos
        known = true
    }

    /** Playing or not; a resume waits for the next [update] (the old reading is from before the pause). */
    fun setPlaying(playing: Boolean) = synchronized(lock) {
        if (playing && !this.playing) known = false
        this.playing = playing
    }

    fun setSpeed(speed: Float) = synchronized(lock) { this.speed = speed.coerceAtLeast(0f) }

    /** A flush or reset: the timeline starts over. */
    fun invalidate() = synchronized(lock) { known = false }

    /** The media time being heard at [nowNanos], or [NO_POSITION]. */
    fun audibleUs(nowNanos: Long): Long = synchronized(lock) {
        if (!playing || !known) return NO_POSITION
        val dt = nowNanos - atNanos
        // Readings stop when the renderer stops asking (stalled): don't run on past what was played.
        if (dt > MAX_EXTRAPOLATION_NS) return NO_POSITION
        positionUs + (dt.coerceAtLeast(0) / 1000.0 * speed).toLong()
    }

    companion object {
        const val NO_POSITION = Long.MIN_VALUE

        /** Longest gap since the last reading that is still extrapolated. */
        const val MAX_EXTRAPOLATION_NS = 250_000_000L
    }
}

/**
 * Pass-through [BaseAudioProcessor] that reads each PCM buffer in place into a [BassAnalyzer]
 * (16-bit or float, any channel count, averaged to mono) on ExoPlayer's playback thread. The
 * output is the input unchanged; [replaceOutputBuffer] reuses its buffer, so nothing allocates
 * per buffer.
 */
class BassTapProcessor(private val analyzer: BassAnalyzer) : BaseAudioProcessor() {
    private var encoding = C.ENCODING_INVALID
    private var channels = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = inputAudioFormat

    override fun onFlush() {
        // configure()'s format takes effect here.
        val format = inputAudioFormat
        val readable = format.encoding == C.ENCODING_PCM_16BIT || format.encoding == C.ENCODING_PCM_FLOAT
        encoding = if (readable && format.channelCount > 0) format.encoding else C.ENCODING_INVALID
        channels = format.channelCount
        analyzer.configure(if (encoding == C.ENCODING_INVALID) 0 else format.sampleRate)
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        channels = 0
        analyzer.reset()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val start = inputBuffer.position()
        val end = inputBuffer.limit()
        if (start == end) return
        when (encoding) {
            C.ENCODING_PCM_16BIT -> read16(inputBuffer, start, end)
            C.ENCODING_PCM_FLOAT -> readFloat(inputBuffer, start, end)
        }
        replaceOutputBuffer(end - start).put(inputBuffer).flip()
    }

    // Android PCM is little-endian; read the bytes so a buffer's order flag doesn't matter.
    private fun read16(buffer: ByteBuffer, start: Int, end: Int) {
        val ch = channels
        val frameBytes = 2 * ch
        val scale = 1f / (32768f * ch)
        var i = start
        while (i + frameBytes <= end) {
            var sum = 0
            for (c in 0 until ch) {
                val at = i + 2 * c
                sum += ((buffer.get(at).toInt() and 0xFF) or (buffer.get(at + 1).toInt() shl 8)).toShort().toInt()
            }
            analyzer.process(sum * scale)
            i += frameBytes
        }
    }

    private fun readFloat(buffer: ByteBuffer, start: Int, end: Int) {
        val ch = channels
        val frameBytes = 4 * ch
        val scale = 1f / ch
        var i = start
        while (i + frameBytes <= end) {
            var sum = 0f
            for (c in 0 until ch) {
                val at = i + 4 * c
                val bits = (buffer.get(at).toInt() and 0xFF) or ((buffer.get(at + 1).toInt() and 0xFF) shl 8) or
                    ((buffer.get(at + 2).toInt() and 0xFF) shl 16) or (buffer.get(at + 3).toInt() shl 24)
                sum += Float.fromBits(bits)
            }
            analyzer.process((sum * scale).coerceIn(-1f, 1f))
            i += frameBytes
        }
    }
}

/**
 * The music sink with timing taps: each new decoded buffer anchors [analyzer] to its
 * presentation time (the tap reads that buffer next, inside [handleBuffer]), and every playout
 * position the renderer reads is handed to the [MusicEnergy.playback] clock. Both are in the
 * sink's own timeline, so no conversion to the player's position is needed. Playback thread.
 */
class EnergyAudioSink(
    sink: AudioSink,
    private val analyzer: BassAnalyzer,
    private val energy: MusicEnergy,
) : ForwardingAudioSink(sink) {
    /** The buffer handed in last wasn't fully taken: the next call is the same one again. */
    private var holding = false

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!holding) analyzer.anchor(presentationTimeUs)
        val taken = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        holding = !taken
        return taken
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (position != AudioSink.CURRENT_POSITION_NOT_SET) energy.playback.update(position, energy.now())
        return position
    }

    override fun play() {
        super.play()
        energy.playback.setPlaying(true)
    }

    override fun pause() {
        super.pause()
        energy.playback.setPlaying(false)
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        super.setPlaybackParameters(playbackParameters)
        energy.playback.setSpeed(playbackParameters.speed)
    }

    /** A seek or a new media list: the timeline restarts, so the analysis does too. */
    override fun flush() {
        super.flush()
        startOver()
    }

    override fun reset() {
        super.reset()
        energy.playback.setPlaying(false)
        startOver()
    }

    private fun startOver() {
        holding = false
        analyzer.reset()
        energy.ring.clear()
        energy.playback.invalidate()
    }
}
