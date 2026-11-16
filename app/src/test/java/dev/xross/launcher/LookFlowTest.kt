package dev.xross.launcher

import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.state.LookFlow
import dev.xross.launcher.state.LookFocus
import dev.xross.launcher.theme.BatteryLevel
import dev.xross.launcher.theme.QuickProfile
import dev.xross.launcher.theme.ThemeLookResolver
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.TestAssets
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalTime

/**
 * Architecture Assessment §7.4: the theme cascade's inputs as flows ([LookFlow]), settled the way
 * the menu shows them, with no Compose and no Robolectric (the Default pack loads from the app's
 * assets through the engine's file-backed test platform).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LookFlowTest {
    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { TestAssets.loader { themes } }

    /** A pack with a system override and one rule each on the battery and the clock. */
    private fun rulesPack(): LoadedTheme {
        val dir = File(themes, "ctx").apply { mkdirs() }
        File(dir, "theme.json").writeText(
            """{"schemaVersion": 2, "name": "ctx",
            "palette": {"canvas": "#101010", "accent": "#202020", "accentSecondary": "#303030"},
            "systemOverrides": {"dc": {"palette": {"accent": "#FF8C42"}}},
            "rules": [
              {"match": {"battery": "<= 20"}, "apply": {"palette": {"accentSecondary": "#FF3344"}}},
              {"match": {"time": "21:00..06:00"}, "apply": {"palette": {"canvas": "#05050A"}}}
            ]}""",
        )
        return loader.load("ctx").also { assertTrue(it.problems.toString(), it.problems.isEmpty()) }
    }

    private fun battery(percent: Int) = BatteryLevel(percent, charging = false)

    /** Collects [flow] in the background of this test; the list fills as virtual time runs. */
    private fun <T> TestScope.record(flow: kotlinx.coroutines.flow.Flow<T>): MutableList<T> {
        val out = ArrayList<T>()
        backgroundScope.launch { flow.collect { out += it } }
        runCurrent()
        return out
    }

    @Test fun aNewSystemWaitsForTheCursorToRestButANewCategoryTakesItsSystemAtOnce() = runTest {
        val focus = MutableStateFlow(LookFocus("games", "snes"))
        val seen = record(LookFlow.settle(focus))
        assertEquals(listOf(LookFocus("games", "snes")), seen)

        // Scrolling through a mixed list: only the system the cursor rests on comes through.
        focus.value = LookFocus("games", "dreamcast")
        advanceTimeBy(100)
        focus.value = LookFocus("games", "gc")
        advanceTimeBy(LookFlow.SYSTEM_LOOK_DELAY_MS - 1)
        runCurrent()
        assertEquals(1, seen.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(LookFocus("games", "gc"), seen.last())

        // A category switch is never held back, so the old system's override never shows in it.
        focus.value = LookFocus("music", null)
        runCurrent()
        assertEquals(LookFocus("music", null), seen.last())

        // Back to where it was before the delay ran out: nothing new.
        focus.value = LookFocus("music", "psx")
        advanceTimeBy(50)
        focus.value = LookFocus("music", null)
        advanceTimeBy(LookFlow.SYSTEM_LOOK_DELAY_MS * 2)
        runCurrent()
        assertEquals(3, seen.size)
    }

    @Test fun theBatteryIsPassedOnOnlyWhenTheRulesThatHoldChange() = runTest {
        val pack = rulesPack()
        val level = MutableStateFlow<BatteryLevel?>(battery(80))
        val seen = record(LookFlow.ruleBattery(pack, level))
        for (p in listOf(79, 60, 21)) {
            level.value = battery(p)
            runCurrent()
        }
        assertEquals(listOf(battery(80)), seen)
        level.value = battery(20)
        runCurrent()
        level.value = battery(5)
        runCurrent()
        assertEquals(listOf(battery(80), battery(20)), seen)

        // A pack with no battery rule never reads it.
        val plain = loader.load("default")
        assertEquals(listOf(null), LookFlow.ruleBattery(plain, level).toList())
    }

    @Test fun theClockTicksOnceAMinuteButOnlyChangesTheLookAtARuleBoundary() = runTest {
        val pack = rulesPack()
        val start = LocalTime.of(20, 58, 30)
        val clock = { start.plusNanos(testScheduler.currentTime * 1_000_000) }
        val seen = record(LookFlow.ruleMinute(pack, clock))
        assertEquals(listOf(20 * 60 + 58), seen)
        advanceTimeBy(60_000) // 20:59: the night rule still doesn't hold
        runCurrent()
        assertEquals(1, seen.size)
        advanceTimeBy(60_000) // 21:00: it does
        runCurrent()
        assertEquals(listOf(20 * 60 + 58, 21 * 60), seen)
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals(2, seen.size)

        assertEquals(listOf(null), LookFlow.ruleMinute(loader.load("default"), clock).take(1).toList())
    }

    @Test fun settingsFocusBatteryAndClockAddUpToTheResolvedLook() = runTest {
        val pack = rulesPack()
        val settings = MutableStateFlow(XrossSettings())
        val focus = MutableStateFlow(LookFocus("games", null))
        val level = MutableStateFlow<BatteryLevel?>(battery(90))
        val clock = { LocalTime.NOON }
        val inputs = record(LookFlow.inputs(flowOf(pack), settings, focus, level, clock))
        val base = ThemeLookResolver.resolve(inputs.last())
        assertEquals(0xFF202020.toInt(), base.look.palette!!.glow)
        assertEquals(90, inputs.last().battery?.percent)
        assertEquals(12 * 60, inputs.last().minuteOfDay)

        // The system override, once the cursor rests.
        focus.value = LookFocus("games", "dreamcast")
        runCurrent()
        assertNull(inputs.last().system)
        advanceTimeBy(LookFlow.SYSTEM_LOOK_DELAY_MS + 1)
        runCurrent()
        assertEquals(0xFFFF8C42.toInt(), ThemeLookResolver.resolve(inputs.last()).look.palette!!.glow)

        // The battery rule.
        level.value = battery(15)
        runCurrent()
        assertEquals(0xFFFF3344.toInt(), ThemeLookResolver.resolve(inputs.last()).look.palette!!.wave)

        // The user's picks and the quick profile come from the settings.
        settings.value = XrossSettings(quickProfile = QuickProfile.MONOCHROME_EINK.name)
        runCurrent()
        val eink = ThemeLookResolver.resolve(inputs.last())
        assertEquals(QuickProfile.MONOCHROME_EINK, eink.profile)
        assertTrue(eink.stillMotion)

        // An unchanged moment is not a new input.
        val count = inputs.size
        settings.value = settings.value.copy()
        level.value = battery(14)
        runCurrent()
        assertEquals(count, inputs.size)
        assertEquals(LookFlow.of(pack, settings.value, focus.value, battery(15), 12 * 60), inputs.last())
    }

    @Test fun aPackWithoutRulesReadsNeitherTheBatteryNorTheClock() = runTest {
        val pack = loader.load("default")
        val inputs = record(LookFlow.inputs(flowOf(pack), flowOf(XrossSettings()), flowOf(LookFocus()), flowOf(battery(10)), { LocalTime.MIDNIGHT }))
        assertNull(inputs.single().battery)
        assertNull(inputs.single().minuteOfDay)
    }
}
