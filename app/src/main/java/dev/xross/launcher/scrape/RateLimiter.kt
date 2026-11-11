package dev.xross.launcher.scrape

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spaces requests to one service at least [minIntervalMs] apart, and backs off after the
 * service says to slow down ([penalize]). Callers queue fairly on a mutex, so concurrent
 * scrapes can't burst. Clock and sleep are injected for tests.
 */
class RateLimiter(
    private val minIntervalMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var nextAt = 0L

    /** Suspends until a request may go out, and reserves that slot. */
    suspend fun acquire() = mutex.withLock {
        val now = clock()
        if (now < nextAt) sleep(nextAt - now)
        nextAt = maxOf(clock(), nextAt) + minIntervalMs
    }

    /** No request before [ms] from now (HTTP 429, "too many threads"). */
    suspend fun penalize(ms: Long) = mutex.withLock {
        nextAt = maxOf(nextAt, clock() + ms)
    }
}
