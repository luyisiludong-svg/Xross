package dev.xross.launcher

import android.content.Intent
import android.os.BatteryManager
import dev.xross.launcher.state.BatteryMonitor
import dev.xross.launcher.theme.BatteryLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatteryMonitorTest {
    private fun broadcast(
        level: Int,
        present: Boolean? = true,
        voltage: Int? = 4000,
        health: Int? = BatteryManager.BATTERY_HEALTH_GOOD,
        status: Int = BatteryManager.BATTERY_STATUS_DISCHARGING,
    ) = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
        putExtra(BatteryManager.EXTRA_LEVEL, level)
        putExtra(BatteryManager.EXTRA_SCALE, 100)
        putExtra(BatteryManager.EXTRA_STATUS, status)
        present?.let { putExtra(BatteryManager.EXTRA_PRESENT, it) }
        voltage?.let { putExtra(BatteryManager.EXTRA_VOLTAGE, it) }
        health?.let { putExtra(BatteryManager.EXTRA_HEALTH, it) }
    }

    @Test
    fun aPhoneBatteryReads() {
        assertEquals(BatteryLevel(87, charging = false), BatteryMonitor.parse(broadcast(87)))
        assertEquals(BatteryLevel(100, charging = true), BatteryMonitor.parse(broadcast(100, status = BatteryManager.BATTERY_STATUS_FULL)))
        // A real battery at 0% still has a voltage, so it still shows.
        assertEquals(BatteryLevel(0, charging = false), BatteryMonitor.parse(broadcast(0, voltage = 3300)))
        // Older broadcasts without the extras keep showing.
        assertEquals(BatteryLevel(50, charging = false), BatteryMonitor.parse(broadcast(50, present = null, voltage = null, health = null)))
    }

    @Test
    fun aTvWithoutABatteryReadsAsNone() {
        // The TV says so.
        assertNull(BatteryMonitor.parse(broadcast(0, present = false, voltage = 0, health = BatteryManager.BATTERY_HEALTH_UNKNOWN)))
        assertNull(BatteryMonitor.parse(broadcast(100, present = false)))
        // The TV claims a battery but reports an empty stand-in.
        assertNull(BatteryMonitor.parse(broadcast(0, voltage = 0, health = BatteryManager.BATTERY_HEALTH_UNKNOWN)))
        assertNull(BatteryMonitor.parse(broadcast(0, voltage = null, health = null)))
    }
}
