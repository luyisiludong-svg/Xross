package dev.xross.launcher.state

import android.content.Context
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.runtime.Immutable
import dev.xross.launcher.theme.BatteryLevel
import dev.xross.launcher.theme.pack.BatteryDisplayMode
import dev.xross.launcher.theme.pack.ClockFormat
import dev.xross.launcher.theme.pack.StatusNeeds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.withIndex
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** What carries the default network (2b.7a `WIFI` slot). */
enum class NetworkKind { WIFI, CELLULAR, ETHERNET, OFFLINE }

/** The default network: its kind and, for Wi-Fi, its strength in 4 levels (0 weakest … 3). */
@Immutable
data class NetworkStatus(val kind: NetworkKind, val level: Int = 0) {
    companion object {
        val OFFLINE = NetworkStatus(NetworkKind.OFFLINE)
    }
}

/**
 * The system media volume (`STREAM_MUSIC`, 2b.7a `VOLUME` slot) in 4 levels, 0 being muted.
 * [changes] counts changes of the volume itself (not only of its level) since the bar started
 * watching, so the slot knows when to show itself.
 */
@Immutable
data class VolumeStatus(val level: Int, val changes: Int = 0) {
    val muted: Boolean get() = level == 0
}

/**
 * What the status bar shows, as one slice read by `StatusBar` alone (Assessment #9): a minute
 * tick, a battery change, a network change or a volume change recomposes the bar and nothing else.
 */
@Immutable
data class StatusBarState(
    /** Date and time in the components' clock format ([ClockFormat]); "11/14  9:41 PM" by default. */
    val clock: String = "",
    /** Null until the first reading, or on a device without a battery. */
    val battery: BatteryLevel? = null,
    /** Time to a full charge in ms, while charging and only for `TIME_REMAINING`; null when unknown. */
    val chargeTimeMs: Long? = null,
    /** Null while no slot shows the network. */
    val network: NetworkStatus? = null,
    /** Null while no slot shows the volume. */
    val volume: VolumeStatus? = null,
) {
    /** The battery figure, drawn in the pack's mono font ("87%"). */
    val batteryText: String? get() = battery?.let { "${it.percent}%" }

    /**
     * The `BATTERY` slot's text in [mode]: "87%" for `PERCENTAGE` and `BOTH`, none for
     * `CAPSULE_ICON`, and for `TIME_REMAINING` "1 h 20 m to full" while charging when Android
     * knows, else the percentage exactly as `PERCENTAGE` draws it.
     */
    fun batteryText(mode: BatteryDisplayMode): String? = when (mode) {
        BatteryDisplayMode.CAPSULE_ICON -> null
        BatteryDisplayMode.PERCENTAGE, BatteryDisplayMode.BOTH -> batteryText
        BatteryDisplayMode.TIME_REMAINING -> {
            val ms = chargeTimeMs
            if (battery?.charging == true && ms != null && ms > 0) chargeText(ms) else batteryText
        }
    }

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

        /**
         * The clock in [skeleton] ([ClockFormat]): its date letters through the locale's best
         * pattern, two spaces, its time letters (`m` alone: the system's 12/24 h, [timeFormat]).
         * Empty is [clockText]'s clock.
         */
        fun clockText(now: Date, locale: Locale, timeFormat: java.text.DateFormat, skeleton: String): String {
            val s = ClockFormat.filter(skeleton).first
            if (s.isEmpty()) return clockText(now, locale, timeFormat)
            val dateFields = ClockFormat.dateFields(s)
            val timeFields = ClockFormat.timeFields(s)
            // ICU patterns, formatted by ICU (android.text.format.DateFormat reads "y" as two digits).
            fun icu(fields: String) = android.icu.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, fields), locale).format(now)
            val date = dateFields.takeIf { it.isNotEmpty() }?.let(::icu)
            val time = when {
                timeFields.isEmpty() -> null
                'H' in timeFields || 'h' in timeFields -> icu(timeFields)
                else -> timeFormat.format(now)
            }
            return listOfNotNull(date, time).joinToString("  ")
        }

        /** "1 h 20 m to full", "45 m to full"; minutes rounded up so it never says 0. */
        fun chargeText(ms: Long): String {
            val minutes = ceil(ms / 60_000.0).toLong().coerceAtLeast(1)
            val h = minutes / 60
            val m = minutes % 60
            val left = when {
                h == 0L -> "$m m"
                m == 0L -> "$h h"
                else -> "$h h $m m"
            }
            return "$left to full"
        }

        /** Delay from [nowMs] until just after the next minute boundary. */
        fun untilNextMinute(nowMs: Long): Long = 60_000 - nowMs % 60_000 + 50
    }
}

/** Level mappings for the `WIFI` and `VOLUME` slots (2b.7a). */
object StatusLevels {
    /** Levels of both slots: 0–3. */
    const val LEVELS = 4

    private const val MIN_RSSI = -100
    private const val MAX_RSSI = -55

    /**
     * A Wi-Fi RSSI (dBm) in [LEVELS] levels: `WifiManager.calculateSignalLevel(rssi, 4)`'s
     * mapping, used for `NetworkCapabilities.signalStrength` on API 29+. Unknown (`Int.MIN_VALUE`) is 0.
     */
    fun wifiLevel(rssi: Int): Int = when {
        rssi <= MIN_RSSI -> 0
        rssi >= MAX_RSSI -> LEVELS - 1
        else -> ((rssi - MIN_RSSI).toFloat() * (LEVELS - 1) / (MAX_RSSI - MIN_RSSI)).toInt()
    }

    /** [volume] of [max] in [LEVELS] levels: 0 only when muted (0 or [muted]), else 1–3. */
    fun volumeLevel(volume: Int, max: Int, muted: Boolean = false): Int {
        if (muted || volume <= 0 || max <= 0) return 0
        return ceil(volume.coerceAtMost(max) * (LEVELS - 1).toDouble() / max).toInt().coerceIn(1, LEVELS - 1)
    }
}

/**
 * Produces [StatusBarState]: the clock ticks right after each minute boundary and the battery
 * follows [battery]; the network, the volume and the time to full are watched only while [needs]
 * asks for them (a slot that shows them). Runs only while the bar is collected (the screen is
 * started), so a game in front of Xross gets no wakeups from it; a new collector reads the time,
 * locale and 12/24 h setting afresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusBarSource(
    context: Context,
    scope: CoroutineScope,
    private val battery: StateFlow<BatteryLevel?>,
    private val needs: StateFlow<StatusNeeds> = MutableStateFlow(StatusNeeds()),
    private val clock: () -> Long = System::currentTimeMillis,
    private val network: () -> Flow<NetworkStatus> = { NetworkMonitor(context).status() },
    private val volume: () -> Flow<MediaVolume> = { MediaVolumeMonitor(context).volumes() },
    private val chargeTime: () -> Long = { chargeTimeOf(context) },
) {
    private val appContext = context.applicationContext

    private fun minutes(skeleton: String): Flow<String> = flow {
        while (true) {
            emit(clockNow(skeleton))
            delay(StatusBarState.untilNextMinute(clock()))
        }
    }

    /** Every change of the volume counts, even one that keeps the glyph's level (14 → 12 of 15 are both 3). */
    private fun volumes(): Flow<VolumeStatus> =
        volume().distinctUntilChanged().withIndex().map { (i, v) -> VolumeStatus(v.level, changes = i) }

    /** Starts with the current time and battery, so the bar's first frame is never blank. */
    val state: StateFlow<StatusBarState> = needs.flatMapLatest { n ->
        val net: Flow<NetworkStatus?> = if (n.network) network().distinctUntilChanged() else flowOf(null)
        val vol: Flow<VolumeStatus?> = if (n.volume) volumes() else flowOf(null)
        combine(minutes(n.clock), battery, net.onStart { emit(null) }, vol.onStart { emit(null) }) { c, b, w, v ->
            val toFull = if (n.chargeTime && b?.charging == true) chargeTime().takeIf { it >= 0 } else null
            StatusBarState(c, b, toFull, w, v)
        }.distinctUntilChanged()
    }.onEach(::log).stateIn(scope, SharingStarted.WhileSubscribed(), StatusBarState(clockNow(needs.value.clock), battery.value))

    private var logged: String? = null

    /** Debug builds: one `XrossStatus` line per change of what the slots show (not per minute tick). */
    private fun log(s: StatusBarState) {
        if (!dev.xross.launcher.BuildConfig.PERF_LOG) return
        val line = "network=${s.network?.let { "${it.kind}/${it.level}" } ?: "-"} volume=${s.volume?.let { "${it.level}#${it.changes}" } ?: "-"} " +
            "battery=${s.battery?.let { "${it.percent}${if (it.charging) "+" else ""}" } ?: "-"} toFull=${s.chargeTimeMs ?: "-"}"
        if (line != logged) {
            logged = line
            runCatching { android.util.Log.i("XrossStatus", line) }
        }
    }

    private fun clockNow(skeleton: String): String =
        StatusBarState.clockText(Date(clock()), Locale.getDefault(), DateFormat.getTimeFormat(appContext), skeleton)

    companion object {
        /** `BatteryManager.computeChargeTimeRemaining()` (API 28+): ms to full, or −1 when unknown. */
        fun chargeTimeOf(context: Context): Long =
            runCatching { context.getSystemService(BatteryManager::class.java)?.computeChargeTimeRemaining() }.getOrNull() ?: -1L
    }
}
