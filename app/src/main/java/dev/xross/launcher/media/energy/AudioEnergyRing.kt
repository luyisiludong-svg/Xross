package dev.xross.launcher.media.energy

/**
 * The last few seconds of [BassAnalyzer] output, by media time. The analyzer runs ahead of the
 * speaker by the whole AudioTrack buffer (100–250 ms), so the GL thread asks for the moment being
 * heard ([sample]), not the newest entry.
 *
 * One writer (the playback thread) and one reader (the GL thread). The lock is held for a few
 * array reads; nothing allocates.
 */
class AudioEnergyRing(capacity: Int = DEFAULT_CAPACITY) {
    private val times = LongArray(capacity)
    private val bass = FloatArray(capacity)
    private val beats = BooleanArray(capacity)
    private var head = 0 // next write
    private var size = 0
    private val lock = Any()

    val count: Int get() = synchronized(lock) { size }

    /**
     * Adds an entry. The ring is a timeline: an entry at or before newer ones (the clock went
     * back without a flush) replaces them, so times always increase.
     */
    fun push(timeUs: Long, bassLevel: Float, beat: Boolean) = synchronized(lock) {
        while (size > 0 && times[index(size - 1)] >= timeUs) {
            size--
            head = (head - 1 + times.size) % times.size
        }
        times[head] = timeUs
        bass[head] = bassLevel
        beats[head] = beat
        head = (head + 1) % times.size
        if (size < times.size) size++
    }

    fun clear() = synchronized(lock) { size = 0 }

    /** The newest entry's time, or null when empty. */
    fun newestUs(): Long? = synchronized(lock) { if (size == 0) null else times[index(size - 1)] }

    /**
     * The levels heard at [timeUs]: `out[0]` = bass (linear between the entries around it),
     * `out[1]` = beat (1 or 0, from the entry at or before it). Within [EDGE_US] past either end
     * the end entry holds; further out, or with nothing yet, both are 0 and it returns false.
     */
    fun sample(timeUs: Long, out: FloatArray): Boolean = synchronized(lock) {
        if (size == 0) return zero(out)
        val first = times[index(0)]
        val last = times[index(size - 1)]
        if (timeUs < first - EDGE_US || timeUs > last + EDGE_US) return zero(out)
        if (timeUs <= first) return copy(0, out)
        if (timeUs >= last) return copy(size - 1, out)
        // The last entry at or before timeUs.
        var lo = 0
        var hi = size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (times[index(mid)] <= timeUs) lo = mid else hi = mid
        }
        val a = index(lo)
        val b = index(hi)
        val span = (times[b] - times[a]).toFloat()
        val f = if (span <= 0f) 0f else (timeUs - times[a]) / span
        out[0] = bass[a] + (bass[b] - bass[a]) * f
        out[1] = if (beats[a]) 1f else 0f
        true
    }

    private fun index(logical: Int): Int = (head - size + logical + times.size) % times.size

    private fun copy(logical: Int, out: FloatArray): Boolean {
        val i = index(logical)
        out[0] = bass[i]
        out[1] = if (beats[i]) 1f else 0f
        return true
    }

    private fun zero(out: FloatArray): Boolean {
        out[0] = 0f
        out[1] = 0f
        return false
    }

    companion object {
        /** 512 hops of 10 ms: ~5 s, far more than any output latency. */
        const val DEFAULT_CAPACITY = 512

        /** How far past the ends an end entry still answers (a few hops). */
        const val EDGE_US = 50_000L
    }
}
