package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.LayoutEditor
import dev.xross.launcher.state.LayoutEditorState
import dev.xross.launcher.state.LayoutRow
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.ThemeSetup
import dev.xross.launcher.theme.BatteryLevel
import dev.xross.launcher.theme.LookInputs
import dev.xross.launcher.theme.QuickProfile
import dev.xross.launcher.theme.ThemeLookResolver
import dev.xross.launcher.theme.pack.CrosshairPreset
import dev.xross.launcher.theme.pack.EffectOverrides
import dev.xross.launcher.theme.pack.LayoutOverrides
import dev.xross.launcher.theme.pack.MotionProfile
import dev.xross.launcher.theme.pack.ThemeLayout
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.UserLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import dev.xross.launcher.data.writeTo

/** Phase 2b.8a: the user's layout values over the pack (`display.layout`), the cascade slot and the Layout panel. */
@RunWith(RobolectricTestRunner::class)
class LayoutOverridesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    // region settings.json

    private fun decode(layout: String) = ConfigCodec.decodeSettings(
        XrossSettings(),
        """{"display": {"theme": "LAVENDER", "layout": $layout, "surfaceStyle": "OLED_TRUE_BLACK"}}""",
    )

    @Test fun badValuesDropOnTheirOwn() {
        val s = decode("""{"preset": "SIDEWAYS", "uiScale": "big", "itemSpacingDp": 99, "focusedScale": "1.2"}""")
        assertEquals(LayoutOverrides(itemSpacingDp = 32f, focusedScale = 1.2f), s.layoutOverrides)
        // The rest of the file survives.
        assertEquals("LAVENDER", s.theme)
        assertEquals("OLED_TRUE_BLACK", s.surfaceStyle)

        assertEquals(LayoutOverrides.NONE, decode("5").layoutOverrides)
        assertEquals(LayoutOverrides.NONE, decode("null").layoutOverrides)
        assertEquals(LayoutOverrides.NONE, decode("""{"preset": {"x": 1}, "uiScale": [1], "focusedScale": true, "itemSpacingDp": "NaN"}""").layoutOverrides)
        assertEquals("LAVENDER", decode("5").theme)
    }

    @Test fun numbersClampToTheLayoutLimits() {
        assertEquals(
            LayoutOverrides(uiScale = 0.5f, itemSpacingDp = 0f, focusedScale = 1.35f),
            decode("""{"uiScale": 0.1, "itemSpacingDp": -4, "focusedScale": 2}""").layoutOverrides,
        )
        assertEquals(LayoutOverrides(uiScale = 1.5f), LayoutOverrides(uiScale = 9f).normalized())
        assertEquals(LayoutOverrides.NONE, LayoutOverrides(uiScale = Float.NaN, focusedScale = Float.POSITIVE_INFINITY).normalized())
    }

    @Test fun presetByNameAndAlias() {
        assertEquals(CrosshairPreset.PSP_CENTER.name, decode("""{"preset": "PSP_CENTRE"}""").layoutOverrides.preset)
        assertEquals(CrosshairPreset.PSP_CENTER, LayoutOverrides(preset = "psp centre").crosshair)
        assertEquals(CrosshairPreset.MODERN_WIDESCREEN, LayoutOverrides(preset = "Modern-Widescreen").crosshair)
        assertEquals("CLASSIC_PS3", LayoutOverrides(preset = " classic_ps3 ").normalized().preset)
        assertNull(LayoutOverrides(preset = "SIDEWAYS").normalized().preset)
    }

    @Test fun roundTripsThroughSettingsJson() {
        val all = LayoutOverrides(preset = "MODERN_WIDESCREEN", uiScale = 1.3f, itemSpacingDp = 6f, focusedScale = 1.05f)
        val s = XrossSettings(layoutOverrides = all)
        assertEquals(all, ConfigCodec.decodeSettings(XrossSettings(), ConfigCodec.encodeSettings(s)).layoutOverrides)
        // Unset values are written as null (= the pack's).
        assertTrue(ConfigCodec.encodeSettings(XrossSettings()).contains("\"uiScale\": null"))
    }

    // endregion

    // region The cascade

    private fun load(body: String) = run {
        val dir = File(themes, "mixer").apply { mkdirs() }
        File(dir, "theme.json").writeText("""{"schemaVersion": 2, "name": "Mixer", $body}""")
        loader.load("mixer")
    }

    /** Every layer that can set the UI scale sets a different one; the user's wins in each context. */
    private val layered = """
        "palette": {"canvas": "#101018", "accent": "#FFB5C5"},
        "layout": {"preset": "CLASSIC_PS3", "uiScale": 0.9, "itemSpacingDp": 20, "focusedScale": 1.2},
        "variants": [{"id": "night", "name": "Night", "palette": {"accent": "#334455"}}],
        "categoryOverrides": {"GAMES": {"cardAspectRatio": "3:4"}},
        "rules": [
          {"match": {"category": "MUSIC"}, "apply": {"layout": {"uiScale": 0.7, "crosshairX": 0.4, "focusedScale": 1.05}}},
          {"match": {"battery": "<= 15"}, "apply": {"layout": {"uiScale": 1.4, "preset": "PSP_CENTER", "motionProfile": "BOUNCY"}}}
        ]
    """

    @Test fun theUsersValuesWinInEveryContext() {
        val t = load(layered)
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        fun layout(i: LookInputs) = ThemeLookResolver.resolve(i).layout
        val base = LookInputs(pack = t)
        val low = BatteryLevel(10, charging = false)
        val contexts = listOf(
            base, base.copy(variant = "night"), base.copy(category = "games"), base.copy(category = "music"),
            base.copy(battery = low), base.copy(category = "music", battery = low),
        )
        // The pack's own values differ by context ...
        assertEquals(listOf(0.9f, 0.9f, 0.9f, 0.7f, 1.4f, 1.4f), contexts.map { layout(it).uiScale })

        val user = UserLook(layout = LayoutOverrides(preset = "MODERN_WIDESCREEN", uiScale = 1.1f, itemSpacingDp = 6f, focusedScale = 1.3f))
        for (c in contexts) {
            val l = layout(c.copy(user = user))
            assertEquals("$c", 1.1f, l.uiScale)
            assertEquals("$c", 6f, l.itemSpacingDp)
            assertEquals("$c", 1.3f, l.focusedScale)
            // The preset replaces both fractions, the rule's crosshairX and preset included.
            assertEquals("$c", CrosshairPreset.MODERN_WIDESCREEN.x, l.crosshairX)
            assertEquals("$c", CrosshairPreset.MODERN_WIDESCREEN.y, l.crosshairY)
            // ... and the rest stays the pack's in that context.
            val pack = layout(c)
            assertEquals("$c", pack.motion, l.motion)
            assertEquals("$c", pack.aspectFor("games"), l.aspectFor("games"))
        }

        // Only what the user set: the rest follows the pack's context.
        val scaleOnly = UserLook(layout = LayoutOverrides(uiScale = 1.1f))
        val music = layout(base.copy(category = "music", user = scaleOnly))
        assertEquals(1.1f, music.uiScale)
        assertEquals(0.4f, music.crosshairX)
        assertEquals(1.05f, music.focusedScale)
    }

    @Test fun theQuickProfileStillSetsOnlyMotion() {
        val t = load(layered)
        val user = UserLook(layout = LayoutOverrides(preset = "PSP_CENTER", uiScale = 1.2f, itemSpacingDp = 6f, focusedScale = 1.0f))
        val l = ThemeLookResolver.resolve(LookInputs(pack = t, user = user, profile = QuickProfile.MONOCHROME_EINK)).layout
        assertEquals(MotionProfile.INSTANT, l.motion.profile)
        assertEquals(1.2f, l.uiScale)
        assertEquals(6f, l.itemSpacingDp)
        assertEquals(1.0f, l.focusedScale)
        assertEquals(0.5f, l.crosshairX)
        // A profile without motion leaves the user's layout alone.
        assertEquals(
            ThemeLookResolver.resolve(LookInputs(pack = t, user = user)).layout,
            ThemeLookResolver.resolve(LookInputs(pack = t, user = user, profile = QuickProfile.OLED_TRUE_BLACK)).layout,
        )
    }

    @Test fun noOverridesKeepThePacksInstance() {
        val l = ThemeLayout(uiScale = 0.8f)
        assertSame(l, LayoutOverrides.NONE.applyTo(l))
        assertSame(l, LayoutOverrides(uiScale = 0.8f).applyTo(l))
    }

    // endregion

    // region The Layout panel

    private val pack = ThemeLayout(crosshairX = 0.22f, crosshairY = 0.25f, uiScale = 1f, itemSpacingDp = 12f, focusedScale = 1.32f)

    private fun steps(row: LayoutRow, direction: Int, from: LayoutOverrides = LayoutOverrides.NONE, p: ThemeLayout = pack): List<String> {
        val seen = mutableListOf<String>()
        var u = from
        repeat(20) {
            val next = LayoutEditor.adjust(row, u, p, direction) ?: return seen
            u = next
            seen += LayoutEditor.value(row, u, p)
        }
        return seen
    }

    @Test fun uiScaleStepsFromThePacksValueAndThemePackSitsBelowTheMinimum() {
        assertEquals(listOf("110%", "120%", "130%", "140%", "150%"), steps(LayoutRow.UI_SCALE, +1))
        assertEquals(
            listOf("100%", "90%", "80%", "70%", "60%", "50%", "Theme pack · 100%"),
            steps(LayoutRow.UI_SCALE, -1, LayoutOverrides(uiScale = 1.1f)),
        )
        // Both ends hold, so a held key stops: 150% on the right, the pack on the left.
        assertNull(LayoutEditor.adjust(LayoutRow.UI_SCALE, LayoutOverrides(uiScale = 1.5f), pack, +1))
        assertNull(LayoutEditor.adjust(LayoutRow.UI_SCALE, LayoutOverrides.NONE, pack, -1))
        assertTrue(steps(LayoutRow.UI_SCALE, -1).isEmpty())
        assertEquals(LayoutOverrides.NONE, LayoutEditor.adjust(LayoutRow.UI_SCALE, LayoutOverrides(uiScale = 0.5f), pack, -1))
        // A hand-edited value off the grid steps to the next one.
        assertEquals(1.3f, LayoutEditor.adjust(LayoutRow.UI_SCALE, LayoutOverrides(uiScale = 1.23f), pack, +1)!!.uiScale)
    }

    @Test fun focusedScaleStepsByFiveHundredths() {
        assertEquals(listOf("1.35×"), steps(LayoutRow.FOCUSED_SCALE, +1))
        assertEquals(
            listOf("1.30×", "1.25×", "1.20×", "1.15×", "1.10×", "1.05×", "1.00×", "Theme pack · 1.32×"),
            steps(LayoutRow.FOCUSED_SCALE, -1, LayoutOverrides(focusedScale = 1.35f)),
        )
        assertEquals(LayoutEditor.FOCUS_SCALES.first(), 1f)
        assertEquals(LayoutEditor.FOCUS_SCALES.last(), 1.35f)
    }

    @Test fun spacingAndCrosshairChoices() {
        assertEquals(listOf("Spacious (20 dp)"), steps(LayoutRow.SPACING, +1))
        assertEquals(listOf("Standard (12 dp)", "Tight (6 dp)", "Theme pack · Standard (12 dp)"), steps(LayoutRow.SPACING, -1, LayoutOverrides(itemSpacingDp = 20f)))
        assertTrue(steps(LayoutRow.SPACING, -1).isEmpty())
        assertEquals("15 dp", LayoutEditor.value(LayoutRow.SPACING, LayoutOverrides(itemSpacingDp = 15f), pack))

        assertEquals(listOf("Classic PS3", "PSP centre", "Modern widescreen"), steps(LayoutRow.CROSSHAIR, +1))
        assertEquals(
            listOf("PSP centre", "Classic PS3", "Theme pack · 22% × 25%"),
            steps(LayoutRow.CROSSHAIR, -1, LayoutOverrides(preset = "MODERN_WIDESCREEN")),
        )
        assertNull(LayoutEditor.adjust(LayoutRow.CROSSHAIR, LayoutOverrides.NONE, pack, -1))
        // A pack on a preset's anchor names it.
        assertEquals("Theme pack · Classic PS3", LayoutEditor.value(LayoutRow.CROSSHAIR, LayoutOverrides.NONE, ThemeLayout(crosshairX = 0.30f, crosshairY = 0.35f)))
    }

    @Test fun panelInput() {
        val user = LayoutOverrides(uiScale = 1.2f, preset = "PSP_CENTER")
        val top = LayoutEditorState(0)
        assertTrue(LayoutEditor.onAction(top, NavAction.UP, user, pack).blocked)
        assertEquals(LayoutRow.UI_SCALE, LayoutEditor.onAction(top, NavAction.DOWN, user, pack).state.row)
        assertTrue(LayoutEditor.onAction(LayoutEditorState(LayoutRow.RESET.ordinal), NavAction.DOWN, user, pack).blocked)
        assertTrue(LayoutEditor.onAction(top, NavAction.BACK, user, pack).close)
        // Left/Right step and save.
        assertEquals(1.3f, LayoutEditor.onAction(LayoutEditorState(LayoutRow.UI_SCALE.ordinal), NavAction.RIGHT, user, pack).overrides!!.uiScale)
        // Confirm puts the row back to the pack; then there is nothing to put back.
        val back = LayoutEditor.onAction(top, NavAction.CONFIRM, user, pack).overrides!!
        assertEquals(LayoutOverrides(uiScale = 1.2f), back)
        assertTrue(LayoutEditor.onAction(top, NavAction.CONFIRM, back, pack).blocked)
        // Reset layout clears the panel's values only.
        val reset = LayoutEditorState(LayoutRow.RESET.ordinal)
        assertEquals("2 values", LayoutEditor.value(LayoutRow.RESET, user, pack))
        assertEquals(LayoutOverrides.NONE, LayoutEditor.onAction(reset, NavAction.CONFIRM, user, pack).overrides)
        assertTrue(LayoutEditor.onAction(reset, NavAction.CONFIRM, LayoutOverrides.NONE, pack).blocked)
        assertTrue(LayoutEditor.onAction(reset, NavAction.RIGHT, user, pack).blocked)
    }

    @Test fun sliders() {
        assertEquals(0.5f, LayoutEditor.fraction(LayoutRow.UI_SCALE, LayoutOverrides.NONE, pack)!!, 1e-6f)
        assertEquals(1f, LayoutEditor.fraction(LayoutRow.FOCUSED_SCALE, LayoutOverrides(focusedScale = 1.35f), pack)!!, 1e-6f)
        assertNull(LayoutEditor.fraction(LayoutRow.SPACING, LayoutOverrides.NONE, pack))
        assertEquals(0.7f, LayoutEditor.setFraction(LayoutRow.UI_SCALE, LayoutOverrides.NONE, 0.21f).uiScale)
        assertEquals(1.35f, LayoutEditor.setFraction(LayoutRow.FOCUSED_SCALE, LayoutOverrides.NONE, 1.4f).focusedScale)
    }

    // endregion

    // region Settings ▸ Theme

    private fun tree(user: UserLook) =
        MenuTree(MenuSnapshot(settings = user.writeTo(XrossSettings()), themes = ThemeSetup(activeName = "Mixer")))

    @Test fun layoutRowAfterComponentsWithWhatIsOverridden() {
        val rows = tree(UserLook.NONE).list(MenuTree.DISPLAY)
        val ids = rows.map { it.id }
        assertEquals(ids.indexOf(MenuTree.DISPLAY_COMPONENTS) + 1, ids.indexOf("disp:layout"))
        val row = rows.first { it.id == "disp:layout" }
        assertEquals("Layout", row.title)
        assertEquals("From Mixer · Crosshair, UI scale, list spacing, focused scale", row.subtitle)

        val set = tree(UserLook(layout = LayoutOverrides(preset = "PSP_CENTER", focusedScale = 1.1f), effects = EffectOverrides(iconMask = "CIRCLE")))
        val display = set.list(MenuTree.DISPLAY)
        assertEquals("Crosshair PSP centre · Focused 1.10×", display.first { it.id == "disp:layout" }.subtitle)
        assertEquals("Clears 2 layout values, 1 effect", display.first { it.id == "disp:reset" }.subtitle)
    }

    // endregion
}
