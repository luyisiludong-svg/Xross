package dev.xross.launcher.state

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Says when nobody has touched Xross for [timeoutMs]: no key, D-pad, stick or touch input. While
 * [idle] is set the screen rests (the wave drops to a standby frame rate and the focus glows hold
 * still); the next input wakes it immediately.
 *
 * [onInput] is cheap enough to call for every input event: it stores a timestamp and only signals
 * the timer when it ends an idle period. [run] is the timer; collect it while the activity is
 * started.
 */
class InactivityTracker(
    private val timeoutMs: Long = IDLE_TIMEOUT_MS,
    private val now: () -> Long,
) {
    private val _idle = MutableStateFlow(false)
    val idle: StateFlow<Boolean> = _idle.asStateFlow()

    @Volatile private var lastInputMs = now()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun onInput() {
        lastInputMs = now()
        if (_idle.compareAndSet(expect = true, update = false)) wake.trySend(Unit)
    }

    suspend fun run() {
        while (true) {
            val left = lastInputMs + timeoutMs - now()
            if (left > 0) {
                delay(left)
            } else {
                _idle.value = true
                wake.receive()
            }
        }
    }

    companion object {
        const val IDLE_TIMEOUT_MS = 3_000L
    }
}
