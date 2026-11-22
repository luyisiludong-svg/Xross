package dev.xross.launcher

import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.SlowShaders
import dev.xross.launcher.data.StandbyMotion
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.InactivityTracker
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings ▸ System ▸ Performance: standby motion, background frame rate, slow shaders. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PerformanceSettingsTest {

    private fun rows(settings: XrossSettings = XrossSettings()) =
        MenuTree(MenuSnapshot(settings = settings)).list(MenuTree.PERFORMANCE)

    @Test fun defaultsKeepTheOldBehaviour() {
        val s = XrossSettings()
        assertEquals(StandbyMotion.AFTER_3S, s.standbyMotion)
        assertEquals(3_000L, s.standbyMotion.timeoutMs)
        assertEquals(10_000L, s.standbyMotion.particleTimeoutMs)
        assertFalse(s.fullFrameRate)
        assertEquals(SlowShaders.RETIRE, s.slowShaders)
    }

    @Test fun particlesRestNoSoonerThanTenSecondsAndNeverWithNever() {
        assertEquals(30_000L, StandbyMotion.AFTER_30S.particleTimeoutMs)
        assertNull(StandbyMotion.NEVER.timeoutMs)
        assertNull(StandbyMotion.NEVER.particleTimeoutMs)
    }

    @Test fun systemListsThePerformanceFolder() {
        val folder = MenuTree(MenuSnapshot()).list(MenuTree.SYSTEM).single { it.id == MenuTree.PERFORMANCE }
        assertEquals("Performance", folder.title)
        assertEquals("Rests after 3 s · ~60 fps · slow shaders switch to the wave", folder.subtitle)
    }

    @Test fun folderHasItsFourRows() {
        assertEquals(listOf("perf:standby", "perf:fps", "perf:slow", "perf:retry"), rows().map { it.id })
        val byId = rows().associateBy { it.id }
        assertEquals(NodeAction.Run(Command.CycleStandbyMotion), byId.getValue("perf:standby").action)
        assertEquals(NodeAction.Run(Command.ToggleFullFrameRate), byId.getValue("perf:fps").action)
        assertEquals(NodeAction.Run(Command.CycleSlowShaders), byId.getValue("perf:slow").action)
        assertEquals(NodeAction.Run(Command.RetrySlowShaders), byId.getValue("perf:retry").action)
    }

    @Test fun optionsListEveryChoiceAndMarkTheCurrentOne() {
        val standby = rows(XrossSettings(standbyMotion = StandbyMotion.NEVER)).single { it.id == "perf:standby" }
        assertEquals(StandbyMotion.entries.map { it.label }, standby.menu.map { it.label })
        assertEquals("Never", standby.menu.single { it.selected }.label)
        val slow = rows(XrossSettings(slowShaders = SlowShaders.WARN)).single { it.id == "perf:slow" }
        assertEquals(Command.SetSlowShaders(SlowShaders.WARN), slow.menu.single { it.selected }.command)
    }

    @Test fun choicesCycle() {
        assertEquals(StandbyMotion.AFTER_30S, StandbyMotion.AFTER_3S.next())
        assertEquals(StandbyMotion.AFTER_3S, StandbyMotion.NEVER.next())
        assertEquals(SlowShaders.WARN, SlowShaders.RETIRE.next())
    }

    @Test fun settingsFileRoundTripsAndToleratesJunk() {
        val s = XrossSettings(standbyMotion = StandbyMotion.AFTER_30S, fullFrameRate = true, slowShaders = SlowShaders.WARN)
        assertEquals(s, ConfigCodec.decodeSettings(XrossSettings(), ConfigCodec.encodeSettings(s)))
        val old = ConfigCodec.decodeSettings(XrossSettings(), """{"display": {"theme": "OCEAN"}}""")
        assertEquals(StandbyMotion.AFTER_3S, old.standbyMotion)
        assertEquals(SlowShaders.RETIRE, old.slowShaders)
        val junk = ConfigCodec.decodeSettings(XrossSettings(), """{"performance": {"standbyMotion": "soon", "slowShaders": "warn"}}""")
        assertEquals(StandbyMotion.AFTER_3S, junk.standbyMotion)
        assertEquals(SlowShaders.WARN, junk.slowShaders)
    }

    @Test fun neverTimeoutNeverRests() = runTest {
        val tracker = InactivityTracker(timeoutMs = null) { testScheduler.currentTime }
        val job = launch { tracker.run() }
        advanceTimeBy(600_000)
        assertFalse(tracker.idle.value)
        job.cancel()
    }

    @Test fun longerTimeoutWakesARestingScreen() = runTest {
        val tracker = InactivityTracker(timeoutMs = 3_000) { testScheduler.currentTime }
        val job = launch { tracker.run() }
        advanceTimeBy(5_000)
        assertTrue(tracker.idle.value)
        tracker.setTimeout(30_000)
        assertFalse(tracker.idle.value)
        runCurrent()
        // 5 s gone of the new 30 s.
        advanceTimeBy(24_999)
        assertFalse(tracker.idle.value)
        advanceTimeBy(2)
        assertTrue(tracker.idle.value)
        job.cancel()
    }

    @Test fun shorterTimeoutTakesEffectWhileWaiting() = runTest {
        val tracker = InactivityTracker(timeoutMs = 30_000) { testScheduler.currentTime }
        val job = launch { tracker.run() }
        advanceTimeBy(1_000)
        tracker.setTimeout(3_000)
        runCurrent()
        advanceTimeBy(1_999)
        assertFalse(tracker.idle.value)
        advanceTimeBy(2)
        assertTrue(tracker.idle.value)
        job.cancel()
    }

    @Test fun switchingToNeverWakesARestingScreen() = runTest {
        val tracker = InactivityTracker(timeoutMs = 3_000) { testScheduler.currentTime }
        val job = launch { tracker.run() }
        advanceTimeBy(4_000)
        assertTrue(tracker.idle.value)
        tracker.setTimeout(null)
        assertFalse(tracker.idle.value)
        advanceTimeBy(600_000)
        assertFalse(tracker.idle.value)
        job.cancel()
    }
}
