package dev.xross.launcher

import android.content.Context
import android.text.format.DateFormat
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.state.StatusBarSource
import dev.xross.launcher.state.StatusBarState
import dev.xross.launcher.theme.BatteryLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.Locale

/** 2b.7-prep: the status bar's own state slice produces the text the bar drew before it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StatusBarStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Exactly what `StatusBar` built in its composition before the slice. */
    private fun todaysText(now: Date, battery: BatteryLevel?): String {
        val locale = Locale.getDefault()
        val date = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "Md"), now)
        val time = DateFormat.getTimeFormat(context).format(now)
        return "$date  $time" + (battery?.let { "  ${it.percent}%" } ?: "")
    }

    private fun StatusBarState.text() = clock + (batteryText?.let { "  $it" } ?: "")

    /** 2026-11-14 21:41:30 local time. */
    private val start = java.util.Calendar.getInstance().apply { set(2026, 10, 14, 21, 41, 30); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis

    @Test fun minuteTickBatteryChangeAndChargingMatchTodaysText() = runTest {
        val battery = MutableStateFlow<BatteryLevel?>(BatteryLevel(87, charging = false))
        val source = StatusBarSource(context, backgroundScope, battery, clock = { start + testScheduler.currentTime })
        // The bar's first frame already has the time and battery, before anything collects.
        assertEquals(todaysText(Date(start), battery.value), source.state.value.text())

        backgroundScope.launch { source.state.collect {} }
        runCurrent()
        assertEquals(todaysText(Date(start), battery.value), source.state.value.text())

        // The next minute: ticks right after the boundary (30 s + 50 ms from the start).
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("Not before the boundary", todaysText(Date(start), battery.value), source.state.value.text())
        advanceTimeBy(100)
        runCurrent()
        val nextMinute = Date(start + testScheduler.currentTime)
        assertEquals(todaysText(nextMinute, battery.value), source.state.value.text())
        assertNotEquals(todaysText(Date(start), battery.value), source.state.value.text())

        // A battery change redraws the figure; charging changes the gauge's color, not the text.
        battery.value = BatteryLevel(12, charging = false)
        runCurrent()
        assertEquals(todaysText(nextMinute, battery.value), source.state.value.text())
        assertTrue(source.state.value.batteryLow)
        battery.value = BatteryLevel(12, charging = true)
        runCurrent()
        assertEquals(todaysText(nextMinute, BatteryLevel(12, charging = false)), source.state.value.text())
        assertFalse("Charging: the bars stay white", source.state.value.batteryLow)
    }

    @Test fun noBatteryDrawsTheClockAlone() {
        val s = StatusBarState(clock = StatusBarState.clockText(Date(start), Locale.getDefault(), DateFormat.getTimeFormat(context)))
        assertEquals(todaysText(Date(start), null), s.text())
        assertNull(s.batteryText)
        assertEquals(0, s.batteryBars)
        assertFalse(s.batteryLow)
    }

    @Test fun gaugeBarsAndLowWarningKeepTodaysThresholds() {
        fun bars(p: Int) = StatusBarState(battery = BatteryLevel(p, false)).batteryBars
        assertEquals(listOf(0, 1, 1, 2, 2, 3, 3), listOf(5, 6, 33, 34, 66, 67, 100).map(::bars))
        assertTrue(StatusBarState(battery = BatteryLevel(15, false)).batteryLow)
        assertFalse(StatusBarState(battery = BatteryLevel(16, false)).batteryLow)
        assertEquals("100%", StatusBarState(battery = BatteryLevel(100, true)).batteryText)
    }

    @Test fun ticksWakeJustAfterEachMinuteBoundary() {
        assertEquals(60_050, StatusBarState.untilNextMinute(0))
        assertEquals(60, StatusBarState.untilNextMinute(59_990))
        assertEquals(30_050, StatusBarState.untilNextMinute(90_000))
    }
}
