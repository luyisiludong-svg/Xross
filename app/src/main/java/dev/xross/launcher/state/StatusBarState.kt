package dev.xross.launcher.state

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Immutable
import dev.xross.launcher.theme.BatteryLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Date
import java.util.Locale

/**
 * What the status bar shows, as one slice read by `StatusBar` alone (Assessment #9): a minute
 * tick or a battery change recomposes the bar and nothing else. 2b.7a adds the network and the
 * system volume here.
 */
@Immutable
data class StatusBarState(
    /** Date and time, `"Md"` skeleton and the system's 12/24 h time ("11/14  9:41 PM"). */
    val clock: String = "",
    /** Null until the first reading, or on a device without a battery. */
    val battery: BatteryLevel? = null,
) {
    /** The battery figure, drawn in the pack's mono font ("87%"). */
    val batteryText: String? get() = battery?.let { "${it.percent}%" }

    /** Bars of the three-bar gauge: 1–33% → 1, 34–66% → 2, 67%+ → 3; 5% or less → none. */
    val batteryBars: Int
        get() = battery?.percent?.let {
            when {
                it > 66 -> 3
                it > 33 -> 2
                it > 5 -> 1
                else -> 0
            }
        } ?: 0

    /** The gauge's bars turn red at 15% or less, unless the phone is charging. */
    val batteryLow: Boolean get() = battery?.let { it.percent <= 15 && !it.charging } ?: false

    companion object {
        /** Today's clock text: the locale's `"Md"` date, two spaces, the time in [timeFormat]. */
        fun clockText(now: Date, locale: Locale, timeFormat: java.text.DateFormat): String {
            val date = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "Md"), now)
            return "$date  ${timeFormat.format(now)}"
        }

        /** Delay from [nowMs] until just after the next minute boundary. */
        fun untilNextMinute(nowMs: Long): Long = 60_000 - nowMs % 60_000 + 50
    }
}

/**
 * Produces [StatusBarState]: the clock ticks right after each minute boundary and the battery
 * follows [battery]. Runs only while the bar is collected (the screen is started), so a game in
 * front of Xross gets no wakeups from it; a new collector reads the time, locale and 12/24 h
 * setting afresh.
 */
class StatusBarSource(
    context: Context,
    scope: CoroutineScope,
    private val battery: StateFlow<BatteryLevel?>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext

    private val minutes: Flow<String> = flow {
        while (true) {
            emit(clockNow())
            delay(StatusBarState.untilNextMinute(clock()))
        }
    }

    /** Starts with the current time and battery, so the bar's first frame is never blank. */
    val state: StateFlow<StatusBarState> = combine(minutes, battery) { c, b -> StatusBarState(c, b) }
        .stateIn(scope, SharingStarted.WhileSubscribed(), StatusBarState(clockNow(), battery.value))

    private fun clockNow(): String =
        StatusBarState.clockText(Date(clock()), Locale.getDefault(), DateFormat.getTimeFormat(appContext))
}
