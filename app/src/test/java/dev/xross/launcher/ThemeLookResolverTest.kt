package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.ThemeSetup
import dev.xross.launcher.theme.BatteryLevel
import dev.xross.launcher.theme.LookInputs
import dev.xross.launcher.theme.PACK_THEME
import dev.xross.launcher.theme.ProfileField
import dev.xross.launcher.theme.QuickProfile
import dev.xross.launcher.theme.ThemeLookResolver
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.pack.EffectOverrides
import dev.xross.launcher.theme.pack.MotionProfile
import dev.xross.launcher.theme.shader.ShaderPreset
import dev.xross.launcher.theme.pack.SurfacePreset
import dev.xross.launcher.theme.pack.ThemePackLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The theme cascade outside Compose (Phase 2b.5-prep): pack → variant → category → system →
 * rules → the user's picks → the quick profile, and the profile's field table as Settings shows it.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeLookResolverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    private fun load(body: String) = run {
        val dir = File(themes, "cyber").apply { mkdirs() }
        File(dir, "theme.json").writeText("""{"schemaVersion": 2, "name": "cyber", $body}""")
        loader.load("cyber")
    }

    private val cyber = """
        "palette": {"canvas": "#1B1829", "accent": "#FFB5C5", "accentSecondary": "#C8B6FF"},
        "effects": {"surfaceStyle": "FLAT_GLASS", "shaderPreset": "WAVE"},
        "variants": [
          {"id": "bios", "name": "BIOS", "effects": {"surfaceStyle": "EINK_HIGH_CONTRAST", "shaderPreset": "ASCII_MATRIX"}}
        ],
        "categoryOverrides": {"GAMES": {"effects": {"surfaceStyle": "Y2K_GLOSSY_BEZEL"}}},
        "systemOverrides": {"dreamcast": {"effects": {"surfaceStyle": "OLED_TRUE_BLACK"}}},
        "rules": [{"match": {"battery": "<= 20"}, "apply": {"effects": {"shaderPreset": "BOKEH"}}}]
    """

    @Test fun eachLayerOfTheCascadeAppliesInOrder() {
        val t = load(cyber)
        fun surface(i: LookInputs) = ThemeLookResolver.resolve(i).surface.id
        fun shader(i: LookInputs) = ThemeLookResolver.resolve(i).look.shader.preset

        val base = LookInputs(pack = t)
        assertEquals(SurfacePreset.FLAT_GLASS.name, surface(base))
        assertEquals(SurfacePreset.EINK_HIGH_CONTRAST.name, surface(base.copy(variant = "bios")))
        assertEquals(ShaderPreset.ASCII_MATRIX, shader(base.copy(variant = "bios")))
        assertEquals(SurfacePreset.Y2K_GLOSSY_BEZEL.name, surface(base.copy(category = "games")))
        // The system override beats the category's.
        assertEquals(SurfacePreset.OLED_TRUE_BLACK.name, surface(base.copy(category = "games", system = "dreamcast")))
        // A rule on the battery, only while it holds.
        assertEquals(ShaderPreset.BOKEH, shader(base.copy(battery = BatteryLevel(10, charging = false))))
        assertEquals(ShaderPreset.WAVE, shader(base.copy(battery = BatteryLevel(80, charging = false))))
        // The user's picks beat the pack in every context ...
        assertEquals(ShaderPreset.CRT, shader(base.copy(variant = "bios", effects = EffectOverrides(shaderPreset = "CRT"))))
        assertEquals(SurfacePreset.EINK_HIGH_CONTRAST.name, surface(base.copy(system = "dreamcast", category = "games", surfaceStyle = "EINK_HIGH_CONTRAST")))
        // ... and the quick profile beats the user's surface.
        assertEquals(
            SurfacePreset.Y2K_GLOSSY_BEZEL.name,
            surface(base.copy(surfaceStyle = "EINK_HIGH_CONTRAST", profile = QuickProfile.Y2K_CYBER_PASTEL)),
        )
    }

    @Test fun noProfileIsTheIdentity() {
        val t = load(cyber)
        val r = ThemeLookResolver.resolve(LookInputs(pack = t, surfaceStyle = "EINK_HIGH_CONTRAST", circadian = true, adaptive = false))
        assertEquals(t.look(), r.look)
        assertEquals(t.layout, r.layout)
        assertEquals(SurfacePreset.EINK_HIGH_CONTRAST.name, r.surface.id)
        assertTrue(r.switches.circadian)
        assertFalse(r.switches.adaptive)
        assertFalse(r.stillMotion)
        assertTrue(QuickProfile.NONE.fields.isEmpty())
    }

    /** Every profile, every field: set (the profile's value applies) or untouched (the user's does). */
    @Test fun eachProfileTakesOverExactlyItsFields() {
        val expected = mapOf(
            QuickProfile.NONE to emptySet(),
            QuickProfile.OLED_TRUE_BLACK to setOf(ProfileField.THEME_COLOR, ProfileField.SURFACE),
            QuickProfile.Y2K_CYBER_PASTEL to setOf(ProfileField.THEME_COLOR, ProfileField.SURFACE),
            QuickProfile.MONOCHROME_EINK to ProfileField.entries.toSet(),
        )
        assertEquals(QuickProfile.entries.toSet(), expected.keys)
        val t = load(cyber)
        val user = LookInputs(pack = t, surfaceStyle = "EINK_HIGH_CONTRAST", circadian = true, batteryTint = true, adaptive = true)
        for ((profile, fields) in expected) {
            assertEquals(profile.name, fields, profile.fields)
            val r = ThemeLookResolver.resolve(user.copy(profile = profile))
            val surface = if (ProfileField.SURFACE in fields) profile.surface!!.name else SurfacePreset.EINK_HIGH_CONTRAST.name
            assertEquals(profile.name, surface, r.surface.id)
            assertEquals(profile.name, ProfileField.CIRCADIAN !in fields, r.switches.circadian)
            assertEquals(profile.name, ProfileField.BATTERY_TINT !in fields, r.switches.batteryTint)
            assertEquals(profile.name, ProfileField.ADAPTIVE_COLORS !in fields, r.switches.adaptive)
            assertEquals(profile.name, ProfileField.MOTION in fields, r.stillMotion)
            if (ProfileField.MOTION in fields) assertEquals(MotionProfile.INSTANT, r.layout.motion.profile)
        }
    }

    @Test fun rowsTheProfileOverridesSaySoAndShowTheUsersValue() {
        fun rows(profile: QuickProfile) = MenuTree(
            MenuSnapshot(settings = XrossSettings(theme = "CRIMSON", quickProfile = profile.name)),
        ).themeRows().associateBy { it.id }

        val none = rows(QuickProfile.NONE)
        assertEquals("Crimson", none["disp:theme"]!!.subtitle)
        assertFalse(none["disp:surface"]!!.subtitle!!.contains("Set by"))

        val oled = rows(QuickProfile.OLED_TRUE_BLACK)
        assertEquals("Set by OLED True Black · yours: Crimson", oled["disp:theme"]!!.subtitle)
        assertTrue(oled["disp:surface"]!!.subtitle!!.startsWith("Set by OLED True Black · yours: Theme pack"))
        assertEquals("Set by OLED True Black · yours: Crimson", oled[MenuTree.DISPLAY_COLORS]!!.subtitle)
        assertTrue(oled["disp:profile"]!!.subtitle!!.endsWith("sets Theme color, Surface style"))
        // Rows a profile doesn't list keep the user's value, unlabelled.
        assertFalse(oled["disp:circadian"]!!.subtitle!!.contains("set by"))
        assertFalse(oled["disp:fx:shader"]!!.subtitle!!.contains("Set by"))

        val eink = rows(QuickProfile.MONOCHROME_EINK)
        assertTrue(eink["disp:wavesettings"]!!.subtitle!!.startsWith("Still in Monochrome E-Ink · "))
        assertTrue(eink["disp:battery"]!!.subtitle!!.startsWith("Off (set by Monochrome E-Ink) · yours: "))
    }

    @Test fun themeColorOptionsListEveryColorAndThePacksOwn() {
        fun row(theme: String, packPalette: Boolean) = MenuTree(
            MenuSnapshot(settings = XrossSettings(theme = theme), themes = ThemeSetup(activeName = "Sakura", packPalette = packPalette)),
        ).list(MenuTree.DISPLAY_COLORS).single { it.id == "disp:theme" }

        val plain = row("TEAL", packPalette = false)
        assertEquals(XrossTheme.entries.map { Command.SetTheme(it.name) }, plain.menu.map { it.command })
        assertEquals("Teal", plain.menu.single { it.selected }.label)
        assertEquals("Changes every month", plain.menu.single { it.label == "Original" }.detail)

        val withPack = row(PACK_THEME, packPalette = true)
        assertEquals(Command.SetTheme(PACK_THEME), withPack.menu.first().command)
        assertEquals("Sakura", withPack.menu.first().detail)
        assertTrue(withPack.menu.first().selected)
    }
}
