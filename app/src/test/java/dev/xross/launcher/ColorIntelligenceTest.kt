package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.state.ColorSwitch
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.ThemeSetup
import dev.xross.launcher.theme.AmbientState
import dev.xross.launcher.theme.BatteryLevel
import dev.xross.launcher.theme.BatteryTint
import dev.xross.launcher.theme.Circadian
import dev.xross.launcher.theme.ColorMath
import dev.xross.launcher.theme.ColorPipeline
import dev.xross.launcher.theme.ColorSwitches
import dev.xross.launcher.theme.GameColors
import dev.xross.launcher.theme.PACK_THEME
import dev.xross.launcher.theme.QuickProfile
import dev.xross.launcher.theme.SwatchSet
import dev.xross.launcher.theme.ThemePalette
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.pack.ColorFlags
import dev.xross.launcher.theme.pack.MotionProfile
import dev.xross.launcher.theme.pack.SurfacePreset
import dev.xross.launcher.theme.pack.TextOverflowMode
import dev.xross.launcher.theme.pack.ThemeLayout
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.Motion
import dev.xross.launcher.theme.paletteFor
import dev.xross.launcher.ui.WaveLoop
import dev.xross.launcher.ui.WaveRenderer
import dev.xross.launcher.ui.waveLoop
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.math.abs

/**
 * Theme 2.0, 2b.3 color intelligence (Roadmap Appendix A.4, A.7): the circadian curve, the
 * battery tint, the game-art palette mapping, quick profiles, the full precedence order and the
 * Settings ▸ Theme rows.
 */
@RunWith(RobolectricTestRunner::class)
class ColorIntelligenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    private val base = ThemePalette(top = 0xFF202040.toInt(), bottom = 0xFF5050A0.toInt(), wave = 0xFFD0D0FF.toInt(), glow = 0xFFFFFFFF.toInt())

    /** Largest per-channel difference between two colors, 0–255. */
    private fun distance(a: Int, b: Int): Int =
        listOf(16, 8, 0).maxOf { abs(((a shr it) and 0xFF) - ((b shr it) and 0xFF)) }

    private fun hue(argb: Int) = ColorMath.hsv(argb)[0]

    // region Circadian curve

    @Test fun theCircadianCurveHasFourPhasesAtTheSpecifiedHours() {
        assertEquals(Circadian.Phase.MORNING, Circadian.phaseAt(6 * 60))
        assertEquals(Circadian.Phase.MORNING, Circadian.phaseAt(10 * 60 + 59))
        assertEquals(Circadian.Phase.DAY, Circadian.phaseAt(11 * 60))
        assertEquals(Circadian.Phase.DAY, Circadian.phaseAt(17 * 60 + 59))
        assertEquals(Circadian.Phase.SUNSET, Circadian.phaseAt(18 * 60))
        assertEquals(Circadian.Phase.SUNSET, Circadian.phaseAt(20 * 60 + 59))
        assertEquals(Circadian.Phase.NIGHT, Circadian.phaseAt(21 * 60))
        assertEquals(Circadian.Phase.NIGHT, Circadian.phaseAt(3 * 60))
        assertEquals(Circadian.Phase.NIGHT, Circadian.phaseAt(5 * 60 + 59))

        // Inside each phase its own color, with the hue the spec names.
        assertEquals(Circadian.Phase.MORNING.palette, Circadian.paletteAt(8 * 60 + 30))
        assertEquals(Circadian.Phase.NIGHT.palette, Circadian.paletteAt(1 * 60))
        assertTrue(hue(Circadian.paletteAt(8 * 60).bottom) in 180f..200f) // cyan / sky
        assertTrue(hue(Circadian.paletteAt(14 * 60).bottom) in 205f..225f) // azure / cobalt
        assertTrue(hue(Circadian.paletteAt(19 * 60 + 30).bottom) in 10f..35f) // amber / rose gold
        assertTrue(hue(Circadian.paletteAt(2 * 60).bottom) in 245f..265f) // indigo / midnight
    }

    @Test fun theCircadianCurveBlendsSmoothlyAcrossEveryBoundary() {
        // Never a visible jump from one minute to the next, all day round.
        for (m in 0 until 24 * 60) {
            val a = Circadian.paletteAt(m)
            val b = Circadian.paletteAt((m + 1) % (24 * 60))
            for ((x, y) in listOf(a.top to b.top, a.bottom to b.bottom, a.wave to b.wave)) {
                assertTrue("minute $m: ${distance(x, y)}", distance(x, y) <= 8)
            }
        }
        // Halfway at the boundary itself; settled 45 minutes either side.
        val dusk = Circadian.paletteAt(18 * 60)
        assertNotEquals(Circadian.Phase.DAY.palette, dusk)
        assertNotEquals(Circadian.Phase.SUNSET.palette, dusk)
        assertEquals(Circadian.Phase.DAY.palette, Circadian.paletteAt(18 * 60 - Circadian.BLEND_MINUTES))
        assertEquals(Circadian.Phase.SUNSET.palette, Circadian.paletteAt(18 * 60 + Circadian.BLEND_MINUTES))
        // Midnight wraps without a seam.
        assertEquals(Circadian.paletteAt(0), Circadian.paletteAt(24 * 60))
    }

    @Test fun theCircadianCurveTintsTheThemeWithoutReplacingIt() {
        val noon = Circadian.apply(base, 14 * 60)
        assertNotEquals(base, noon)
        // Half way between the theme and the phase on the canvas, a lighter touch on the glow.
        assertEquals(ColorMath.lerp(base.top, Circadian.Phase.DAY.palette.top, Circadian.CANVAS_WEIGHT), noon.top)
        assertEquals(ColorMath.lerp(base.glow, Circadian.Phase.DAY.palette.glow, Circadian.GLOW_WEIGHT), noon.glow)
    }

    // endregion

    // region Battery tint

    @Test fun theBatteryTintShiftsTowardAmberThenRedAndGreenWhileCharging() {
        assertEquals(base, BatteryTint.apply(base, BatteryLevel(50, charging = false)))
        assertEquals(base, BatteryTint.apply(base, BatteryLevel(21, charging = false)))
        assertEquals(base, BatteryTint.apply(base, null))

        val low = BatteryTint.apply(base, BatteryLevel(20, charging = false))
        val critical = BatteryTint.apply(base, BatteryLevel(5, charging = false))
        assertNotEquals(base.glow, low.glow)
        assertTrue(hue(low.glow) in 20f..45f) // amber
        assertTrue(hue(critical.glow) < hue(low.glow)) // toward red
        assertTrue(distance(critical.glow, base.glow) > distance(low.glow, base.glow)) // and stronger
        assertEquals(BatteryTint.RED, BatteryTint.warning(1))
        // The canvas top stays the theme's: it's a tint, not a new theme.
        assertEquals(base.top, low.top)

        val charging = BatteryTint.apply(base, BatteryLevel(10, charging = true))
        assertTrue(hue(charging.glow) in 100f..170f) // green, even when low
        assertFalse(BatteryTint.low(BatteryLevel(10, charging = true)))

        val on = ColorSwitches(batteryTint = true)
        assertTrue(ColorPipeline.chargePulse(on, AmbientState(battery = BatteryLevel(60, charging = true))))
        assertFalse(ColorPipeline.chargePulse(on, AmbientState(battery = BatteryLevel(60, charging = false))))
        assertFalse(ColorPipeline.chargePulse(ColorSwitches(batteryTint = false), AmbientState(battery = BatteryLevel(60, charging = true))))
        // The GL pulse is subtle and never dims the sheets.
        for (t in 0..48) assertTrue(WaveRenderer.chargePulse(t * 0.05) in 1f..1.18f)
    }

    // endregion

    // region Game-art palette

    @Test fun swatchesMapToPaletteRoles() {
        val s = SwatchSet(
            dominant = 0xFF3060A0.toInt(),
            vibrant = 0xFFFF6020.toInt(),
            lightVibrant = 0xFFFFC080.toInt(),
            muted = 0xFF806050.toInt(),
            darkMuted = 0xFF201810.toInt(),
        )
        val c = GameColors.from(s)!!
        assertEquals(0xFFFF6020.toInt(), c.accent) // vibrant
        assertEquals(0xFFFFC080.toInt(), c.accentSecondary) // light vibrant
        assertEquals(0xFF201810.toInt(), c.canvas) // dark muted
        val p = c.toPalette()
        assertEquals(hue(c.canvas), hue(p.top), 3f)
        assertTrue(ColorMath.hsv(p.top)[2] < 0.2f)
        assertTrue(ColorMath.contrast(p.glow, p.top) >= GameColors.MIN_CONTRAST)

        // Fallbacks: dominant for the accent, muted for the secondary, a darkened dominant canvas.
        val sparse = GameColors.from(SwatchSet(dominant = 0xFF20C060.toInt(), muted = 0xFF608070.toInt()))!!
        assertEquals(0xFF20C060.toInt(), sparse.accent)
        assertEquals(0xFF608070.toInt(), sparse.accentSecondary)
        assertTrue(ColorMath.hsv(sparse.canvas)[2] <= GameColors.MAX_CANVAS_VALUE + 0.01f)
        assertEquals(hue(0xFF20C060.toInt()), hue(sparse.canvas), 2f)
    }

    @Test fun artWithoutUsableColorFallsBackToTheTheme() {
        assertNull(GameColors.from(SwatchSet()))
        // All grey (a black-and-white logo): nothing to take.
        assertNull(GameColors.from(SwatchSet(dominant = 0xFF808080.toInt(), lightMuted = 0xFFC0C0C0.toInt(), darkMuted = 0xFF202020.toInt())))
        // Without art the pipeline keeps the theme.
        val themed = ColorPipeline.resolve(base, QuickProfile.NONE, ColorSwitches(), AmbientState(), art = null)
        assertEquals(base, themed)
    }

    // endregion

    // region Precedence (Appendix A.4)

    @Test fun precedenceRunsPackVariantRuleUserThenGameArt() {
        File(themes, "stack").apply { mkdirs() }.resolve("theme.json").writeText(
            """{"schemaVersion": 2, "name": "Stack",
               "palette": {"canvas": "#111111", "accent": "#A1A1A1", "accentSecondary": "#B1B1B1"},
               "variants": [{"id": "v", "palette": {"canvas": "#222222", "accent": "#A2A2A2"}}],
               "rules": [{"match": {"category": "GAMES"}, "apply": {"palette": {"canvas": "#333333"}}}]}""",
        )
        val t = loader.load("stack")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        // 1. pack base → 2. variant → 3. rule
        assertEquals(0xFF111111.toInt(), t.look().palette!!.top)
        assertEquals(0xFF222222.toInt(), t.look("v").palette!!.top)
        val ruled = t.look("v", "games")
        assertEquals(0xFF333333.toInt(), ruled.palette!!.top)
        assertEquals(0xFFA2A2A2.toInt(), ruled.palette!!.glow) // the variant's accent under the rule
        assertEquals(0xFFB1B1B1.toInt(), ruled.palette!!.wave) // the pack's own role under both

        // 4. the user's settings: "Theme pack" shows the look, any other theme color wins over it.
        assertEquals(ruled.palette, paletteFor(PACK_THEME, ruled.palette))
        val user = paletteFor(XrossTheme.EMERALD.name, ruled.palette)
        assertEquals(XrossTheme.EMERALD.palette(), user)
        // ... and the user's quick profile over that.
        val profiled = ColorPipeline.resolve(user, QuickProfile.Y2K_CYBER_PASTEL, ColorSwitches(adaptive = false), AmbientState(), null)
        assertEquals(QuickProfile.Y2K_CYBER_PASTEL.palette, profiled)

        // 5. game art over everything, when adaptive colors are on.
        val art = GameColors(accent = 0xFFFF4020.toInt(), accentSecondary = 0xFFFFB080.toInt(), canvas = 0xFF301008.toInt())
        val on = ColorPipeline.resolve(user, QuickProfile.NONE, ColorSwitches(adaptive = true), AmbientState(), art)
        assertEquals(art.toPalette(), on)
        val off = ColorPipeline.resolve(user, QuickProfile.NONE, ColorSwitches(adaptive = false), AmbientState(), art)
        assertEquals(user, off)
    }

    @Test fun colorSwitchesFollowTheUserThenThePackThenTheDefaults() {
        val none = ColorFlags.NONE
        assertEquals(ColorSwitches(circadian = false, batteryTint = false, adaptive = true), ColorSwitches.resolve(none, null, null, null, QuickProfile.NONE))
        val pack = ColorFlags(circadianCurve = true, batteryTint = true, adaptiveGamePalette = false)
        assertEquals(ColorSwitches(true, true, false), ColorSwitches.resolve(pack, null, null, null, QuickProfile.NONE))
        assertEquals(ColorSwitches(false, false, true), ColorSwitches.resolve(pack, false, false, true, QuickProfile.NONE))
        // E-Ink turns every color layer off whatever the settings say.
        assertEquals(ColorSwitches(false, false, false), ColorSwitches.resolve(pack, true, true, true, QuickProfile.MONOCHROME_EINK))
    }

    @Test fun ambientLayersSitBetweenTheProfileAndTheArt() {
        val switches = ColorSwitches(circadian = true, batteryTint = true, adaptive = true)
        val night = AmbientState(minuteOfDay = 2 * 60, battery = BatteryLevel(10, charging = false))
        val p = ColorPipeline.resolve(base, QuickProfile.NONE, switches, night, art = null)
        assertEquals(BatteryTint.apply(Circadian.apply(base, 2 * 60), night.battery), p)
        // Game art replaces the curve, but a low battery still shows over it.
        val art = GameColors(accent = 0xFF20A0FF.toInt(), accentSecondary = 0xFF80D0FF.toInt(), canvas = 0xFF081830.toInt())
        val withArt = ColorPipeline.resolve(base, QuickProfile.NONE, switches, night, art)
        assertEquals(BatteryTint.apply(art.toPalette(), night.battery), withArt)
    }

    // endregion

    // region Quick profiles

    @Test fun oledTrueBlackKeepsItsBlackCanvasUnderGameArt() {
        val oled = QuickProfile.OLED_TRUE_BLACK
        assertEquals(SurfacePreset.OLED_TRUE_BLACK, oled.surface)
        val p = ColorPipeline.resolve(base, oled, ColorSwitches(), AmbientState(), null)
        assertEquals(0xFF000000.toInt(), p.top)
        assertEquals(0xFF000000.toInt(), p.bottom)
        val art = GameColors(accent = 0xFFFF4020.toInt(), accentSecondary = 0xFFFFB080.toInt(), canvas = 0xFF301008.toInt())
        val withArt = ColorPipeline.resolve(base, oled, ColorSwitches(), AmbientState(), art)
        assertEquals(0xFF000000.toInt(), withArt.top)
        assertEquals(0xFF000000.toInt(), withArt.bottom)
        assertEquals(art.toPalette().glow, withArt.glow) // the accent follows the art
        // The quick profile's surfaces exist in every pack (they're the Default pack's presets).
        val d = loader.load(ThemePackLoader.DEFAULT_ID)
        for (profile in QuickProfile.entries) profile.surface?.let { assertNotNull(it.name, d.surfaces[it.name]) }
    }

    @Test fun y2kCyberPastelUsesTheSpecifiedColorsAndBezels() {
        val y2k = QuickProfile.Y2K_CYBER_PASTEL
        assertEquals(0xFF1B1829.toInt(), y2k.palette!!.top) // lavender canvas
        assertEquals(0xFFC8B6FF.toInt(), y2k.palette!!.wave) // lilac
        assertEquals(0xFFFFB5C5.toInt(), y2k.palette!!.glow) // strawberry
        assertEquals(SurfacePreset.Y2K_GLOSSY_BEZEL, y2k.surface)
        assertFalse(y2k.stillMotion)
    }

    @Test fun monochromeEinkHaltsEveryContinuousAnimationAndTheWaveLoop() {
        val eink = QuickProfile.MONOCHROME_EINK
        assertTrue(eink.stillMotion)
        assertEquals(SurfacePreset.EINK_HIGH_CONTRAST, eink.surface)
        // Motion profile INSTANT, and no marquee scrolling.
        val layout = eink.layout(ThemeLayout(textOverflow = TextOverflowMode.MARQUEE, motion = Motion(MotionProfile.BOUNCY)))
        assertEquals(MotionProfile.INSTANT, layout.motion.profile)
        assertTrue(layout.motion.instant)
        assertEquals(TextOverflowMode.ELLIPSIS, layout.textOverflow)
        // Pure black and white.
        val p = ColorPipeline.resolve(XrossTheme.CRIMSON.palette(), eink, ColorSwitches.resolve(ColorFlags.NONE, true, true, true, eink), AmbientState(battery = BatteryLevel(5, false)), null)
        assertEquals(eink.palette, p)
        for (c in listOf(p.top, p.wave, p.glow)) assertTrue(c == 0xFF000000.toInt() || c == 0xFFFFFFFF.toInt())

        // The GL wave never runs a render loop: one frame on request, at any refresh rate, at rest or not.
        for (stride in 1..6) {
            assertEquals(WaveLoop.SINGLE_FRAME, waveLoop(paused = false, resumed = true, stride = stride, frozen = true))
        }
        assertEquals(WaveLoop.STOPPED, waveLoop(paused = true, resumed = true, stride = 1, frozen = true))
        // Without the profile the loop runs as before.
        assertEquals(WaveLoop.CONTINUOUS, waveLoop(paused = false, resumed = true, stride = 1, frozen = false))
        assertEquals(WaveLoop.PACED, waveLoop(paused = false, resumed = true, stride = 2, frozen = false))
        // Other profiles keep the pack's motion.
        val pack = ThemeLayout(textOverflow = TextOverflowMode.MARQUEE)
        assertEquals(pack, QuickProfile.OLED_TRUE_BLACK.layout(pack))
        assertEquals(pack, QuickProfile.NONE.layout(pack))
    }

    // endregion

    // region Settings

    @Test fun theNewSettingsRoundTripThroughSettingsJson() {
        val s = XrossSettings(quickProfile = QuickProfile.MONOCHROME_EINK.name, adaptiveGamePalette = false, circadianCurve = true, batteryTint = null)
        val text = ConfigCodec.encodeSettings(s)
        val display = ConfigCodec.json.parseToJsonElement(text).jsonObject["display"]!!.jsonObject
        assertEquals("MONOCHROME_EINK", display["quickProfile"]!!.jsonPrimitive.content)
        assertEquals("false", display["adaptiveGamePalette"]!!.jsonPrimitive.content)
        assertEquals("null", display["batteryTint"].toString()) // "follow the pack" is written out, editable
        val back = ConfigCodec.decodeSettings(XrossSettings(), text)
        assertEquals(s.quickProfile, back.quickProfile)
        assertEquals(false, back.adaptiveGamePalette)
        assertEquals(true, back.circadianCurve)
        assertNull(back.batteryTint)
        // Hand edits: any case reads; an unknown profile means none.
        val edited = text.replace("\"MONOCHROME_EINK\"", "\"oled_true_black\"")
        assertEquals("OLED_TRUE_BLACK", ConfigCodec.decodeSettings(XrossSettings(), edited).quickProfile)
        assertEquals("NONE", ConfigCodec.decodeSettings(XrossSettings(), text.replace("\"MONOCHROME_EINK\"", "\"VAPORWAVE\"")).quickProfile)
        // Defaults: no profile, everything follows the pack.
        val d = XrossSettings()
        assertEquals("NONE", d.quickProfile)
        assertNull(d.adaptiveGamePalette)
        assertNull(d.circadianCurve)
        assertNull(d.batteryTint)
    }

    @Test fun themeSettingsListTheQuickProfileAndColorRows() {
        fun rows(settings: XrossSettings, flags: ColorFlags = ColorFlags.NONE) =
            MenuTree(MenuSnapshot(settings = settings, themes = ThemeSetup(packFlags = flags))).list(MenuTree.DISPLAY)

        val list = rows(XrossSettings())
        val ids = list.map { it.id }
        val at = ids.indexOf("disp:profile")
        assertEquals(listOf("disp:profile", "disp:adaptive", "disp:circadian", "disp:battery"), ids.subList(at, at + 4))
        assertEquals("disp:import", ids.last())

        val profile = list.first { it.id == "disp:profile" }
        assertEquals("Quick profile", profile.title)
        assertTrue(profile.subtitle!!.startsWith("None"))
        assertEquals(NodeAction.Run(Command.CycleQuickProfile), profile.action)
        assertEquals(QuickProfile.entries.map { Command.SetQuickProfile(it.name) }, profile.menu.map { it.command })

        val adaptive = list.first { it.id == "disp:adaptive" }
        assertEquals("Adaptive colors", adaptive.title)
        assertTrue(adaptive.subtitle!!, adaptive.subtitle!!.startsWith("On (theme pack)"))
        assertEquals(NodeAction.Run(Command.ToggleColorSwitch(ColorSwitch.ADAPTIVE)), adaptive.action)
        assertEquals(
            listOf(Command.SetColorSwitch(ColorSwitch.ADAPTIVE, true), Command.SetColorSwitch(ColorSwitch.ADAPTIVE, false), Command.SetColorSwitch(ColorSwitch.ADAPTIVE, null)),
            adaptive.menu.map { it.command },
        )
        assertEquals("Circadian curve", list.first { it.id == "disp:circadian" }.title)
        assertTrue(list.first { it.id == "disp:circadian" }.subtitle!!.startsWith("Off (theme pack)"))
        assertEquals("Battery alert tint", list.first { it.id == "disp:battery" }.title)

        // The pack's default and the user's own choice read differently.
        val packOn = rows(XrossSettings(), ColorFlags(circadianCurve = true)).first { it.id == "disp:circadian" }
        assertTrue(packOn.subtitle!!.startsWith("On (theme pack)"))
        val userOff = rows(XrossSettings(circadianCurve = false), ColorFlags(circadianCurve = true)).first { it.id == "disp:circadian" }
        assertTrue(userOff.subtitle!!, userOff.subtitle!!.startsWith("Off ·"))
        assertTrue(userOff.menu[1].selected)

        // Under E-Ink the color rows say they're held off.
        val eink = rows(XrossSettings(quickProfile = QuickProfile.MONOCHROME_EINK.name))
        assertTrue(eink.first { it.id == "disp:profile" }.subtitle!!.startsWith("Monochrome E-Ink"))
        assertEquals("Off in Monochrome E-Ink", eink.first { it.id == "disp:adaptive" }.subtitle)
    }

    // endregion
}
