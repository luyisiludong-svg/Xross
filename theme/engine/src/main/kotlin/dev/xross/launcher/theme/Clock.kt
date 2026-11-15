package dev.xross.launcher.theme

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

// The clock arithmetic behind the date- and minute-driven looks (the composables that wait on it
// are in :app's RememberThemePalette.kt).

/** Local minutes since midnight, 0–1439. */
fun minuteOfDay(now: LocalTime = LocalTime.now()): Int = now.hour * 60 + now.minute

/** Time until just after the next whole minute (never less than a second). */
fun millisUntilNextMinute(now: LocalTime): Long =
    (60_000L - (now.second * 1_000L + now.nano / 1_000_000L) + 50L).coerceAtLeast(1_000L)

/** Time until a moment just after the next local midnight (never less than a second). */
fun millisUntilNextDay(now: LocalDateTime): Long {
    val next = now.toLocalDate().plusDays(1).atStartOfDay().plusSeconds(1)
    return Duration.between(now, next).toMillis().coerceAtLeast(1_000L)
}
