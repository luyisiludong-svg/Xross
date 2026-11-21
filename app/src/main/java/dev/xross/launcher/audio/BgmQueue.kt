package dev.xross.launcher.audio

import kotlin.random.Random

/**
 * The order a theme music list plays in (0.2.2.0 § 5), pure. [next] gives the index of the next
 * track: in order, wrapping; or from a shuffle bag, so no track repeats until all have played and
 * a new round never starts with the track that just ended. The position is kept while the queue
 * lives, so music coming back after a game continues the list instead of starting it over.
 */
class BgmQueue(val size: Int, val shuffle: Boolean, private val random: Random = Random.Default) {
    private val bag = ArrayDeque<Int>()

    /** The index last handed out; -1 before the first. */
    var current: Int = -1
        private set

    fun next(): Int {
        if (size <= 0) return -1
        if (!shuffle) {
            current = (current + 1) % size
            return current
        }
        if (bag.isEmpty()) refill()
        current = bag.removeFirst()
        return current
    }

    private fun refill() {
        val order = (0 until size).shuffled(random).toMutableList()
        if (size > 1 && order.first() == current) {
            order[0] = order[order.lastIndex].also { order[order.lastIndex] = order[0] }
        }
        bag.addAll(order)
    }

    companion object {
        /** Tracks crossfade over this. */
        const val CROSSFADE_MS = 1_500L
    }
}
