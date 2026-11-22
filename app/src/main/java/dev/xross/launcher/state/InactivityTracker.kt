package dev.xross.launcher.state

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Says when nobody has touched Xross for [timeoutMs]: no key, D-pad, stick or touch input. While
 * [idle] is set the screen rests (the wave drops to a standby frame rate and the focus glows hold
 * still); the next input wakes it immediately. A null timeout (Settings ▸ System ▸ Performance ▸
 * Standby motion ▸ Never) never rests.
 *
 * [onInput] is cheap enough to call for every input event: it stores a timestamp and only signals
 * the timer when it ends an idle period. [run] is the timer; collect it while the activity is
 * started. [setTimeout] takes effect at once, also while the timer is waiting.
 */
class InactivityTracker(
    timeoutMs: Long? = IDLE_TIMEOUT_MS,
    private val now: () -> Long,
) {
    private val _idle = MutableStateFlow(false)
    val idle: StateFlow<Boolean> = _idle.asStateFlow()

    @Volatile var timeoutMs: Long? = timeoutMs
        private set

    @Volatile private var lastInputMs = now()

    /** Wakes [run]: input ended an idle period, or the timeout changed. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun onInput() {
        lastInputMs = now()
        if (_idle.compareAndSet(expect = true, update = false)) wake.trySend(Unit)
    }

    fun setTimeout(ms: Long?) {
        if (ms == timeoutMs) return
        timeoutMs = ms
        // Resting under the old timeout but not under the new one: wake now.
        if (ms == null || now() - lastInputMs < ms) _idle.value = false
        wake.trySend(Unit)
    }

    suspend fun run() {
        while (true) {
            val timeout = timeoutMs
            val left = if (timeout == null) null else lastInputMs + timeout - now()
            when {
                left == null -> wake.receive()
                left > 0 -> withTimeoutOrNull(left) { wake.receive() }
                else -> {
                    _idle.value = true
                    wake.receive()
                }
            }
        }
    }

    companion object {
        const val IDLE_TIMEOUT_MS = 3_000L
    }
}
