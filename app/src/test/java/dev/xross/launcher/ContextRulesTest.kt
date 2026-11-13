package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.theme.BatteryLevel
import dev.xross.launcher.theme.pack.BatteryCondition
import dev.xross.launcher.theme.pack.MotionProfile
import dev.xross.launcher.theme.pack.SurfacePreset
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Theme 2.0, 2b.3: the `rules` context engine (Roadmap §4.3, docs/THEME_RICING_AND_WEB_STUDIO.md
 * §3.4): selectors for category, system (with folder aliases), battery level and time of day;
 * `rules.json` through `imports`; file order with the last matching rule winning; tile shapes and
 * layout patches.
 */
@RunWith(RobolectricTestRunner::class)
class ContextRulesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    private fun pack(id: String, body: String, vararg files: Pair<String, String>) {
        val dir = File(themes, id).apply { mkdirs() }
        File(dir, "theme.json").writeText("""{"schemaVersion": 2, "name": "$id", $body}""")
        for ((name, text) in files) File(dir, name).writeText(text)
    }

    private val palette = """"palette": {"canvas": "#101010", "accent": "#202020", "accentSecondary": "#303030"}"""

    private fun battery(percent: Int, charging: Boolean = false) = BatteryLevel(percent, charging)
    private fun at(h: Int, m: Int = 0) = h * 60 + m

    // region Selectors

    @Test fun timeRangesIncludeTheStartExcludeTheEndAndWrapPastMidnight() {
        val night = TimeRange.parse("21:00..06:00")!!
        assertTrue(night.contains(at(21)))
        assertTrue(night.contains(at(23, 59)))
        assertTrue(night.contains(at(0)))
        assertTrue(night.contains(at(5, 59)))
        assertFalse(night.contains(at(6)))
        assertFalse(night.contains(at(12)))
        assertFalse(night.contains(at(20, 59)))

        val morning = TimeRange.parse("06:00..11:00")!!
        assertTrue(morning.contains(at(6)))
        assertTrue(morning.contains(at(10, 59)))
        assertFalse(morning.contains(at(11)))
        assertFalse(morning.contains(at(5, 59)))

        assertEquals(TimeRange(at(6), at(11)), TimeRange.parse(" 6 .. 11 "))
        assertEquals(TimeRange(at(18, 30), 0), TimeRange.parse("18:30..24:00"))
        assertTrue(TimeRange.parse("00:00..00:00")!!.contains(at(13))) // equal ends: all day
        for (bad in listOf("21:00", "25:00..06:00", "21:60..06:00", "dusk..dawn", "21:00..06:00..07:00")) {
            assertNull(bad, TimeRange.parse(bad))
        }
    }

    @Test fun batterySelectorsCompareTheLevelOrTheCharger() {
        val low = BatteryCondition.parse("<= 20")!!
        assertTrue(low.holds(battery(20)))
        assertTrue(low.holds(battery(3)))
        assertFalse(low.holds(battery(21)))
        assertTrue(BatteryCondition.parse("<20")!!.holds(battery(19)))
        assertFalse(BatteryCondition.parse("<20")!!.holds(battery(20)))
        assertTrue(BatteryCondition.parse(">= 80")!!.holds(battery(80)))
        assertTrue(BatteryCondition.parse("> 50%")!!.holds(battery(51)))
        assertTrue(BatteryCondition.parse("100")!!.holds(battery(100)))
        assertTrue(BatteryCondition.parse("charging")!!.holds(battery(40, charging = true)))
        assertFalse(BatteryCondition.parse("Charging")!!.holds(battery(40)))
        assertTrue(BatteryCondition.parse("discharging")!!.holds(battery(40)))
        assertTrue(BatteryCondition.parse("full")!!.holds(battery(100, charging = true)))
        for (bad in listOf("low", "<= 120", "=< 20", "")) assertNull(bad, BatteryCondition.parse(bad))
    }

    // endregion

    // region Evaluation

    @Test fun categorySystemBatteryAndTimeRulesApplyOnlyWhileTheyHold() {
        pack(
            "ctx", palette + """,
            "rules": [
              {"match": {"category": "music"}, "apply": {"palette": {"accent": "#0000AA"}}},
              {"match": {"system": "dc"}, "apply": {"palette": {"accent": "#FF8C42"}}},
              {"match": {"battery": "<= 20"}, "apply": {"palette": {"accentSecondary": "#FF3344"}}},
              {"match": {"time": "21:00..06:00"}, "apply": {"palette": {"canvas": "#05050A"}}}
            ]""",
        )
        val t = loader.load("ctx")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        assertEquals(4, t.rules.size)
        assertEquals("dreamcast", t.rules[1].system) // folder alias resolved to the platform id
        assertTrue(t.usesBattery)
        assertTrue(t.usesClock)

        val plain = t.look(category = "games", battery = battery(80), minuteOfDay = at(12))
        assertEquals(0xFF202020.toInt(), plain.palette!!.glow)
        assertEquals(0xFF101010.toInt(), plain.palette!!.top)
        assertEquals(emptyList<Int>(), plain.rules)

        assertEquals(0xFF0000AA.toInt(), t.look(category = "music").palette!!.glow)
        assertEquals(0xFFFF8C42.toInt(), t.look(category = "games", system = "dreamcast").palette!!.glow)
        assertEquals(0xFF202020.toInt(), t.look(category = "games", system = "ps2").palette!!.glow)

        assertEquals(0xFFFF3344.toInt(), t.look(battery = battery(20)).palette!!.wave)
        assertEquals(0xFF303030.toInt(), t.look(battery = battery(21)).palette!!.wave)
        // Unknown battery (no reading yet) never matches.
        assertEquals(0xFF303030.toInt(), t.look(battery = null).palette!!.wave)

        assertEquals(0xFF05050A.toInt(), t.look(minuteOfDay = at(22, 30)).palette!!.top)
        assertEquals(0xFF05050A.toInt(), t.look(minuteOfDay = at(5, 59)).palette!!.top)
        assertEquals(0xFF101010.toInt(), t.look(minuteOfDay = at(6)).palette!!.top)
        assertEquals(0xFF101010.toInt(), t.look(minuteOfDay = null).palette!!.top)

        val all = t.look(category = "music", system = "dreamcast", battery = battery(10), minuteOfDay = at(23))
        assertEquals(listOf(0, 1, 2, 3), all.rules)
        // Cached by the rules that hold: another percent with the same answer is the same look.
        assertSame(t.look(battery = battery(12)), t.look(battery = battery(19)))
    }

    @Test fun laterRulesWinAndRulesSitAboveTheVariantAndOverrides() {
        pack(
            "order", palette + """,
            "variants": [{"id": "v", "palette": {"accent": "#AAAAAA", "accentSecondary": "#ABABAB"}, "effects": {"surfaceStyle": "Y2K_GLOSSY_BEZEL"}}],
            "categoryOverrides": {"GAMES": {"palette": {"accent": "#00FF00"}}},
            "systemOverrides": {"gc": {"palette": {"accent": "#0000FF"}}},
            "rules": [
              {"match": {"category": "GAMES"}, "apply": {"palette": {"accent": "#111111"}}},
              {"match": {"category": "GAMES", "system": "gc"}, "apply": {"palette": {"accent": "#222222"}, "effects": {"surfaceStyle": "OLED_TRUE_BLACK"}}}
            ]""",
        )
        val t = loader.load("order")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        // Variant only: its accent.
        assertEquals(0xFFAAAAAA.toInt(), t.look("v").palette!!.glow)
        // The category override, then the first rule over it.
        assertEquals(0xFF111111.toInt(), t.look("v", "games").palette!!.glow)
        // The system override, then both rules: the last one wins, surface included.
        val gc = t.look("v", "games", "gc")
        assertEquals(0xFF222222.toInt(), gc.palette!!.glow)
        assertEquals(SurfacePreset.OLED_TRUE_BLACK.name, gc.surfaceId)
        // Roles no layer above sets keep the variant's.
        assertEquals(0xFFABABAB.toInt(), gc.palette!!.wave)
        // Outside Games the variant's surface stands.
        assertEquals(SurfacePreset.Y2K_GLOSSY_BEZEL.name, t.look("v", "music", "gc").surfaceId)
    }

    @Test fun rulesJsonIsImportedAndItsRulesComeBeforeTheManifests() {
        pack(
            "split", palette + """,
            "imports": ["rules.json"],
            "rules": [{"match": {"category": "MUSIC"}, "apply": {"palette": {"accent": "#BBBBBB"}}}]""",
            "rules.json" to """{"rules": [
                {"match": {"category": "MUSIC"}, "apply": {"palette": {"accent": "#AAAAAA", "accentSecondary": "#A0A0A0"}}},
                {"match": {"category": "PHOTO"}, "apply": {"palette": {"accent": "#CCCCCC"}}}
            ]}""",
        )
        val t = loader.load("split")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        assertEquals(3, t.rules.size) // added up, not replaced
        val music = t.look(category = "music").palette!!
        assertEquals(0xFFBBBBBB.toInt(), music.glow) // theme.json's rule is last, so it wins
        assertEquals(0xFFA0A0A0.toInt(), music.wave) // the imported rule's other role still applies
        assertEquals(0xFFCCCCCC.toInt(), t.look(category = "photo").palette!!.glow)
    }

    @Test fun unreadableRulesAreReportedAndDroppedInsteadOfApplyingEverywhere() {
        pack(
            "bad", palette + """,
            "rules": [
              {"match": {"category": "SHOPPING"}, "apply": {"palette": {"accent": "#111111"}}},
              {"match": {"system": "atari9000"}, "apply": {"palette": {"accent": "#111111"}}},
              {"match": {"battery": "low"}, "apply": {"palette": {"accent": "#111111"}}},
              {"match": {"time": "dusk"}, "apply": {"palette": {"accent": "#111111"}}},
              {"match": {"category": "GAMES"}, "apply": {}},
              {"match": {"category": "GAMES"}, "apply": {"palette": {"accent": "pink", "canvas": "#121212"}, "cardAspectRatio": "wide"}}
            ]""",
        )
        val t = loader.load("bad")
        assertEquals(1, t.rules.size)
        assertEquals(5, t.rules.single().index)
        assertEquals(7, t.problems.size)
        for (part in listOf("SHOPPING", "atari9000", "battery", "time", "changes nothing", "pink", "wide")) {
            assertTrue("$part in ${t.problems}", t.problems.any { part in it })
        }
        // Nothing leaks into other contexts; the good role of the last rule applies in Games.
        assertEquals(0xFF202020.toInt(), t.look(category = "music", battery = battery(5), minuteOfDay = at(20)).palette!!.glow)
        assertEquals(0xFF121212.toInt(), t.look(category = "games").palette!!.top)
        assertEquals(0xFF202020.toInt(), t.look(category = "games").palette!!.glow)
    }

    @Test fun rulesShapeTilesAndPatchTheLayout() {
        pack(
            "shapes", palette + """,
            "rules": [
              {"match": {"category": "MUSIC"}, "apply": {"cardAspectRatio": "1:1"}},
              {"match": {"system": "gba"}, "apply": {"cardAspectRatio": "3:2"}},
              {"match": {"battery": "<= 15"}, "apply": {"layout": {"motionProfile": "INSTANT", "focusedScale": 1.05}}}
            ]""",
        )
        val t = loader.load("shapes")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        // Category and system shapes never change at run time: they are in the pack's layout, and
        // looks keep the pack's own layout instance.
        assertNull(t.look(category = "games", system = "snes").layout)
        assertNull(t.look(category = "music").layout)
        assertEquals(1f, t.layout.aspectFor("music")!!, 1e-4f)
        // A system's tile shape shapes its tiles in any list, focused or not.
        assertEquals(1.5f, t.layout.aspectFor("games", "gba")!!, 1e-4f)
        // Low battery: instant motion and a flatter focus, over the pack's layout.
        val low = t.look(category = "games", battery = battery(10)).layout!!
        assertEquals(MotionProfile.INSTANT, low.motion.profile)
        assertEquals(1.05f, low.focusedScale, 1e-4f)
        assertEquals(t.layout.crosshairX, low.crosshairX)
        assertNull(t.look(category = "games", battery = battery(50)).layout)
        assertEquals(1f, low.aspectFor("music")!!, 1e-4f) // the folded shapes stay under the patch
    }

    @Test fun ruleSwitchesTurnTheColorLayersOnAndOff() {
        pack(
            "switches", """"palette": {"canvas": "#101010", "circadianCurve": true},
            "effects": {"adaptiveGamePalette": false},
            "rules": [{"match": {"battery": "<= 20"}, "apply": {"palette": {"batteryTint": true}, "effects": {"adaptiveGamePalette": true}}}]""",
        )
        val t = loader.load("switches")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        assertEquals(true, t.flags.circadianCurve)
        val calm = t.look(battery = battery(60)).flags
        assertEquals(true, calm.circadianCurve)
        assertEquals(false, calm.adaptiveGamePalette)
        assertNull(calm.batteryTint)
        val low = t.look(battery = battery(9)).flags
        assertEquals(true, low.batteryTint)
        assertEquals(true, low.adaptiveGamePalette)
        // Switches alone don't count as a palette: no canvas problem for a flags-only block.
        pack("flagsOnly", """"palette": {"batteryTint": true}""")
        val f = loader.load("flagsOnly")
        assertTrue(f.problems.toString(), f.problems.isEmpty())
        assertNull(f.palette)
        assertEquals(true, f.flags.batteryTint)
    }

    @Test fun batterySelectorAcceptsTheDottedAndCamelCaseNames() {
        // docs/THEME_RICING_AND_WEB_STUDIO.md §3.4 writes "battery.level"; "batteryLevel" is also read.
        pack(
            "aliases", palette + """,
            "rules": [
              {"match": {"battery.level": "<= 20"}, "apply": {"palette": {"accent": "#AA0000"}}},
              {"match": {"batteryLevel": "charging"}, "apply": {"palette": {"accentSecondary": "#00AA00"}}}
            ]""",
        )
        val t = loader.load("aliases")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        assertEquals(2, t.rules.size)
        assertTrue(t.usesBattery)
        assertEquals(0xFFAA0000.toInt(), t.look(battery = battery(15)).palette!!.glow)
        assertEquals(0xFF202020.toInt(), t.look(battery = battery(60)).palette!!.glow)
        assertEquals(0xFF00AA00.toInt(), t.look(battery = battery(60, charging = true)).palette!!.wave)
        assertEquals(0xFF303030.toInt(), t.look(battery = battery(60)).palette!!.wave)
    }

    @Test fun aCategoryAndSystemTileShapeAppliesAtRunTimeOnlyInThatCategory() {
        pack(
            "both", palette + """,
            "rules": [{"match": {"category": "GAMES", "system": "gba"}, "apply": {"cardAspectRatio": "3:2"}}]""",
        )
        val t = loader.load("both")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        // Not folded into the pack's layout: it depends on the open category.
        assertFalse(t.rules.single().folded)
        assertEquals(16f / 9f, t.layout.aspectFor("games", "gba")!!, 1e-4f) // the pack default
        // In Games it shapes GBA tiles whichever game is focused, and only those.
        for (focused in listOf("gba", "snes", null)) {
            val l = t.look(category = "games", system = focused).layout
            assertNotNull("focused $focused", l)
            assertEquals(1.5f, l!!.aspectFor("games", "gba")!!, 1e-4f)
            assertEquals(t.layout.aspectFor("games", "snes"), l.aspectFor("games", "snes"))
        }
        // Elsewhere the pack's own layout instance stands.
        assertNull(t.look(category = "music", system = "gba").layout)
    }

    @Test fun theDefaultPackHasNoRules() {
        val d = loader.load(ThemePackLoader.DEFAULT_ID)
        assertTrue(d.rules.isEmpty())
        assertFalse(d.usesBattery)
        assertFalse(d.usesClock)
        assertSame(d.look(), d.look(battery = battery(5), minuteOfDay = at(3)))
    }

    // endregion
}
