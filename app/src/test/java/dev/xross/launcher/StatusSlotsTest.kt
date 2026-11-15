package dev.xross.launcher

import android.content.Context
import android.text.format.DateFormat
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.state.MediaVolume
import dev.xross.launcher.state.NetworkKind
import dev.xross.launcher.state.NetworkStatus
import dev.xross.launcher.state.StatusBarSource
import dev.xross.launcher.state.StatusBarState
import dev.xross.launcher.state.StatusLevels
import dev.xross.launcher.theme.BatteryLevel
import dev.xross.launcher.theme.pack.BatteryDisplayMode
import dev.xross.launcher.theme.pack.ClockFormat
import dev.xross.launcher.theme.pack.StatusNeeds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 2b.7a: what each status-bar slot shows (clock skeletons, battery modes, Wi-Fi and volume levels) and what the source watches. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class StatusSlotsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Saturday 2026-11-14 21:41. */
    private val now = Calendar.getInstance().apply { set(2026, 10, 14, 21, 41, 30) }.time
    private val us = Locale.US
    private val twelve = java.text.SimpleDateFormat("h:mm a", us)
    private val twentyFour = java.text.SimpleDateFormat("HH:mm", us)

    // region Clock

    @Test fun clockSkeletonKeepsOnlyItsFields() {
        assertEquals("yMdEHhma" to "", ClockFormat.filter("yMdEHhma"))
        assertEquals("MdHm" to "sZ", ClockFormat.filter("Md Hm sZ"))
        assertEquals("" to "Q'-", ClockFormat.filter("Q'-"))
        val problems = ArrayList<String>()
        assertEquals("HHmm", ClockFormat.check("theme", "HHmmss", problems))
        assertTrue(problems.single().contains("dropped \"ss\""))
    }

    @Test fun clockTextPerSkeleton() {
        // Empty is today's clock, whatever the system's time format.
        assertEquals(StatusBarState.clockText(now, us, twelve), StatusBarState.clockText(now, us, twelve, ""))
        assertEquals("11/14  9:41 PM", StatusBarState.clockText(now, us, twelve, ""))
        // H and h pick 24 h and 12 h; date and time two spaces apart.
        assertEquals("11/14  21:41", StatusBarState.clockText(now, us, twelve, "MdHm"))
        assertEquals("11/14  9:41 PM", StatusBarState.clockText(now, us, twentyFour, "Mdhma"))
        // m alone follows the system's 12/24 h.
        assertEquals("9:41 PM", StatusBarState.clockText(now, us, twelve, "m"))
        assertEquals("21:41", StatusBarState.clockText(now, us, twentyFour, "m"))
        assertEquals("Sat, Nov 14  21:41", StatusBarState.clockText(now, us, twentyFour, "EMMMdm"))
        // Date only, and letters outside the set dropped.
        assertEquals("11/14/2026", StatusBarState.clockText(now, us, twelve, "yMd"))
        assertEquals("11/14  21:41", StatusBarState.clockText(now, us, twelve, "MdHmss"))
        // The presets all format.
        for ((skeleton, _) in ClockFormat.PRESETS) assertTrue(skeleton, StatusBarState.clockText(now, us, twelve, skeleton).isNotBlank())
    }

    // endregion

    // region Battery

    @Test fun batteryTextPerMode() {
        val s = StatusBarState(battery = BatteryLevel(87, charging = false))
        assertNull(s.batteryText(BatteryDisplayMode.CAPSULE_ICON))
        assertEquals("87%", s.batteryText(BatteryDisplayMode.PERCENTAGE))
        assertEquals("87%", s.batteryText(BatteryDisplayMode.BOTH))
        assertNull(StatusBarState().batteryText(BatteryDisplayMode.PERCENTAGE))
    }

    @Test fun timeRemainingWhileChargingElsePercentage() {
        val charging = StatusBarState(battery = BatteryLevel(40, charging = true), chargeTimeMs = 80 * 60_000L)
        assertEquals("1 h 20 m to full", charging.batteryText(BatteryDisplayMode.TIME_REMAINING))
        assertEquals("45 m to full", charging.copy(chargeTimeMs = 44 * 60_000L + 1).batteryText(BatteryDisplayMode.TIME_REMAINING))
        assertEquals("2 h to full", charging.copy(chargeTimeMs = 120 * 60_000L).batteryText(BatteryDisplayMode.TIME_REMAINING))
        assertEquals("1 m to full", charging.copy(chargeTimeMs = 5_000L).batteryText(BatteryDisplayMode.TIME_REMAINING))
        // Charging but unknown (−1 is dropped by the source, so null), or zero: the percentage.
        val unknown = charging.copy(chargeTimeMs = null)
        assertEquals(unknown.batteryText(BatteryDisplayMode.PERCENTAGE), unknown.batteryText(BatteryDisplayMode.TIME_REMAINING))
        assertEquals("40%", charging.copy(chargeTimeMs = 0).batteryText(BatteryDisplayMode.TIME_REMAINING))
        // Discharging: exactly PERCENTAGE, even with a stale estimate.
        val discharging = charging.copy(battery = BatteryLevel(40, charging = false))
        assertEquals(discharging.batteryText(BatteryDisplayMode.PERCENTAGE), discharging.batteryText(BatteryDisplayMode.TIME_REMAINING))
    }

    @Test fun sourceReadsTheChargeTimeOnlyForTimeRemainingWhileCharging() = runTest {
        val battery = MutableStateFlow<BatteryLevel?>(BatteryLevel(50, charging = false))
        val needs = MutableStateFlow(StatusNeeds(chargeTime = true))
        var reads = 0
        var answer = 4_800_000L
        val source = StatusBarSource(
            context, backgroundScope, battery, needs, clock = { 0L },
            network = { error("not watched") }, volume = { error("not watched") },
            chargeTime = { reads++; answer },
        )
        backgroundScope.launch { source.state.collect {} }
        runCurrent()
        assertEquals(0, reads)
        assertNull(source.state.value.chargeTimeMs)
        battery.value = BatteryLevel(51, charging = true)
        runCurrent()
        assertEquals("1 h 20 m to full", source.state.value.batteryText(BatteryDisplayMode.TIME_REMAINING))
        // −1: unknown, the percentage.
        answer = -1
        battery.value = BatteryLevel(52, charging = true)
        runCurrent()
        assertNull(source.state.value.chargeTimeMs)
        assertEquals("52%", source.state.value.batteryText(BatteryDisplayMode.TIME_REMAINING))
        // Another mode: never read.
        val before = reads
        needs.value = StatusNeeds()
        battery.value = BatteryLevel(53, charging = true)
        runCurrent()
        assertEquals(before, reads)
    }

    // endregion

    // region Wi-Fi and volume

    @Test fun wifiLevelFromRssiMatchesWifiManager() {
        // API 29+ maps signalStrength with WifiManager.calculateSignalLevel(rssi, 4)'s AOSP formula
        // (-100…-55 dBm over 4 levels), the mapping API 28 gets from WifiManager itself.
        // (Robolectric stubs calculateSignalLevel, so the formula's values are checked directly.)
        for (rssi in -120..-30) {
            val aosp = when {
                rssi <= -100 -> 0
                rssi >= -55 -> 3
                else -> ((rssi + 100).toFloat() * 3 / 45).toInt()
            }
            assertEquals("rssi $rssi", aosp, StatusLevels.wifiLevel(rssi))
        }
        assertEquals(0, StatusLevels.wifiLevel(-100))
        assertEquals(0, StatusLevels.wifiLevel(-90))
        assertEquals(1, StatusLevels.wifiLevel(-80))
        assertEquals(2, StatusLevels.wifiLevel(-70))
        assertEquals(3, StatusLevels.wifiLevel(-55))
        assertEquals(3, StatusLevels.wifiLevel(-40))
        assertEquals(0, StatusLevels.wifiLevel(Int.MIN_VALUE))
    }

    @Test fun volumeLevelsAndMuteAtZero() {
        assertEquals(0, StatusLevels.volumeLevel(0, 15))
        assertEquals(1, StatusLevels.volumeLevel(1, 15))
        assertEquals(1, StatusLevels.volumeLevel(5, 15))
        assertEquals(2, StatusLevels.volumeLevel(6, 15))
        assertEquals(2, StatusLevels.volumeLevel(10, 15))
        assertEquals(3, StatusLevels.volumeLevel(11, 15))
        assertEquals(3, StatusLevels.volumeLevel(15, 15))
        assertEquals(0, StatusLevels.volumeLevel(9, 15, muted = true))
        assertEquals(0, StatusLevels.volumeLevel(5, 0))
    }

    @Test fun networkAndVolumeAreWatchedOnlyWhileASlotShowsThem() = runTest {
        var networkStarts = 0
        var volumeStarts = 0
        val volume = MutableStateFlow(MediaVolume(8, 15))
        val needs = MutableStateFlow(StatusNeeds())
        val source = StatusBarSource(
            context, backgroundScope, MutableStateFlow(null), needs, clock = { 0L },
            network = { flow { networkStarts++; emit(NetworkStatus(NetworkKind.WIFI, 2)) } },
            volume = { volumeStarts++; volume },
            chargeTime = { -1 },
        )
        backgroundScope.launch { source.state.collect {} }
        runCurrent()
        assertEquals(0, networkStarts)
        assertEquals(0, volumeStarts)
        assertNull(source.state.value.network)
        assertNull(source.state.value.volume)

        needs.value = StatusNeeds(network = true, volume = true)
        runCurrent()
        assertEquals(1, networkStarts)
        assertEquals(1, volumeStarts)
        assertEquals(NetworkStatus(NetworkKind.WIFI, 2), source.state.value.network)
        assertEquals(2, source.state.value.volume!!.level)
        assertEquals(0, source.state.value.volume!!.changes)
        // A change counts even when the level stays (8 and 9 of 15 are both 2); the same reading again doesn't.
        volume.value = MediaVolume(9, 15)
        runCurrent()
        assertEquals(2, source.state.value.volume!!.level)
        assertEquals(1, source.state.value.volume!!.changes)
        volume.value = MediaVolume(0, 15)
        runCurrent()
        assertTrue(source.state.value.volume!!.muted)
        assertEquals(2, source.state.value.volume!!.changes)
        volume.value = MediaVolume(0, 15)
        runCurrent()
        assertEquals(2, source.state.value.volume!!.changes)
        // The mute switch alone mutes.
        assertEquals(0, MediaVolume(9, 15, muted = true).level)

        needs.value = StatusNeeds()
        runCurrent()
        assertNull(source.state.value.network)
        assertNull(source.state.value.volume)
    }

    @Test fun theClockFollowsTheSkeletonFromTheFirstFrame() = runTest {
        val at = Calendar.getInstance().apply { set(2026, 10, 14, 21, 41, 30); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val needs = MutableStateFlow(StatusNeeds(clock = "MdHm"))
        val source = StatusBarSource(context, backgroundScope, MutableStateFlow(null), needs, clock = { at })
        val fmt = DateFormat.getTimeFormat(context)
        assertEquals(StatusBarState.clockText(Date(at), Locale.getDefault(), fmt, "MdHm"), source.state.value.clock)
        backgroundScope.launch { source.state.collect {} }
        runCurrent()
        needs.value = StatusNeeds()
        runCurrent()
        assertEquals(StatusBarState.clockText(Date(at), Locale.getDefault(), fmt), source.state.value.clock)
    }

    // endregion
}
