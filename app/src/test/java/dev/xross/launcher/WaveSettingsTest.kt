package dev.xross.launcher

import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.ThemeSetup
import dev.xross.launcher.state.WaveEditor
import dev.xross.launcher.state.WaveEditorState
import dev.xross.launcher.state.WaveRow
import dev.xross.launcher.theme.PACK_THEME
import dev.xross.launcher.theme.ThemePalette
import dev.xross.launcher.theme.WaveColors
import dev.xross.launcher.theme.WaveTint
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.paletteFor
import dev.xross.launcher.theme.pack.ThemeManifest
import dev.xross.launcher.theme.wavePalette
import dev.xross.launcher.ui.BackdropTiming
import dev.xross.launcher.ui.WaveParams
import dev.xross.launcher.ui.WaveRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Wave shader uniforms in settings.json, the Wave settings panel's logic, color presets, the
 * Theme settings rows, and the focused-game background's timing.
 */
@RunWith(RobolectricTestRunner::class)
class WaveSettingsTest {

    @Test fun waveUniformsRoundTripThroughSettingsJson() {
        val s = XrossSettings(
            waveSpeed = 1.7f, waveLineCount = 9, waveTurbulence = 0.4f, waveThickness = 2.2f,
            waveTint = WaveTint.CUSTOM.name, waveColors = WaveColors("#101010", "#202020", "#F0F0F0"),
            themePack = "sakura", theme = PACK_THEME,
        )
        val json = ConfigCodec.encodeSettings(s)
        for (key in listOf("\"waveSpeed\": 1.7", "\"waveLines\": 9", "\"waveTurbulence\": 0.4", "\"waveThickness\": 2.2", "\"waveTint\": \"CUSTOM\"", "\"themePack\": \"sakura\"", "\"top\": \"#101010\"")) {
            assertTrue("$key in\n$json", json.contains(key))
        }
        assertEquals(s, ConfigCodec.decodeSettings(XrossSettings(), json))
    }

    @Test fun handEditedWaveValuesAreClampedPerField() {
        val s = ConfigCodec.decodeSettings(
            XrossSettings(),
            """{"display": {"waveSpeed": 0.1, "waveLines": 99, "waveTurbulence": -3, "waveThickness": 9,
                "waveTint": "plaid", "waveColors": {"top": "#123456", "bottom": "blue", "wave": "#ABC"},
                "themePack": "  "}}""",
        )
        assertEquals(0.5f, s.waveSpeed)
        assertEquals(12, s.waveLineCount)
        assertEquals(0f, s.waveTurbulence)
        assertEquals(3f, s.waveThickness)
        assertEquals("THEME", s.waveTint)
        assertEquals("#123456", s.waveColors.top)
        assertEquals(WaveColors().bottom, s.waveColors.bottom) // only the broken color resets
        assertEquals("#ABC", s.waveColors.wave)
        assertEquals("default", s.themePack)
        // Older files without the new keys keep the defaults.
        assertEquals(XrossSettings(), ConfigCodec.decodeSettings(XrossSettings(), """{"display": {"wave": true}}"""))
    }

    @Test fun editorMovesRowsAndStepsValuesWithoutDrift() {
        var s = XrossSettings()
        var st = WaveEditorState(index = WaveRow.SPEED.ordinal)
        repeat(3) {
            val o = WaveEditor.onAction(st, NavAction.RIGHT, s, null)
            s = o.settings!!
            st = o.state
        }
        assertEquals(1.3f, s.waveSpeed) // 1.0 + 3 × 0.1, no 1.3000001
        repeat(20) { WaveEditor.onAction(st, NavAction.RIGHT, s, null).settings?.let { s = it } }
        assertEquals(2f, s.waveSpeed)
        assertTrue("at the end: blocked (system_ng)", WaveEditor.onAction(st, NavAction.RIGHT, s, null).blocked)

        st = WaveEditor.onAction(st, NavAction.DOWN, s, null).state
        assertEquals(WaveRow.LINES, st.row)
        s = WaveEditor.onAction(st, NavAction.LEFT, s, null).settings!!
        assertEquals(4, s.waveLineCount)
        assertTrue(WaveEditor.onAction(WaveEditorState(0), NavAction.UP, s, null).blocked)
        assertTrue(WaveEditor.onAction(st, NavAction.BACK, s, null).close)
    }

    @Test fun tintCyclesAndResetUsesThePacksDefaults() {
        var s = XrossSettings(waveTint = "THEME")
        val tint = WaveEditorState(index = WaveRow.TINT.ordinal)
        s = WaveEditor.onAction(tint, NavAction.CONFIRM, s, null).settings!!
        assertEquals(WaveTint.AURORA.name, s.waveTint)
        s = WaveEditor.onAction(tint, NavAction.LEFT, s, null).settings!!
        s = WaveEditor.onAction(tint, NavAction.LEFT, s, null).settings!!
        assertEquals(WaveTint.CUSTOM.name, s.waveTint) // wraps around

        val defaults = ThemeManifest.WaveDefaults(speed = 0.8f, lineCount = 7, turbulence = 1.2f, ribbonThickness = 1.4f)
        val reset = WaveEditor.onAction(WaveEditorState(WaveRow.RESET.ordinal), NavAction.CONFIRM, s.copy(waveEnabled = false), defaults).settings!!
        assertEquals(listOf(0.8f, 1.2f, 1.4f), listOf(reset.waveSpeed, reset.waveTurbulence, reset.waveThickness))
        assertEquals(7, reset.waveLineCount)
        assertEquals("THEME", reset.waveTint)
        assertTrue(reset.waveEnabled)
        assertTrue("already at defaults", WaveEditor.onAction(WaveEditorState(WaveRow.RESET.ordinal), NavAction.CONFIRM, reset, defaults).blocked)
    }

    @Test fun slidersMapToFractionsBothWays() {
        val s = XrossSettings(waveSpeed = 1.25f, waveLineCount = 6)
        assertEquals(0.5f, WaveEditor.fraction(WaveRow.SPEED, s)!!, 1e-6f)
        assertEquals(0.5f, WaveEditor.fraction(WaveRow.LINES, s)!!, 1e-6f)
        assertNull(WaveEditor.fraction(WaveRow.TINT, s))
        assertEquals(2f, WaveEditor.setFraction(WaveRow.SPEED, s, 1.4f).waveSpeed)
        assertEquals(12, WaveEditor.setFraction(WaveRow.LINES, s, 1f).waveLineCount)
        assertEquals(0.5f, WaveEditor.setFraction(WaveRow.THICKNESS, s, 0f).waveThickness)
        assertEquals("1.3×", WaveEditor.value(WaveRow.SPEED, XrossSettings(waveSpeed = 1.25f + 0.01f)))
        assertEquals("Custom (settings.json)", WaveEditor.value(WaveRow.TINT, XrossSettings(waveTint = "CUSTOM")))
    }

    @Test fun presetsReplaceTheGradientButKeepTheGlow() {
        val base = XrossTheme.CRIMSON.palette()
        assertEquals(base, wavePalette(base, WaveTint.THEME, WaveColors()))
        val ocean = wavePalette(base, WaveTint.OCEAN, WaveColors())
        assertEquals(WaveTint.OCEAN.palette!!.wave, ocean.wave)
        assertEquals(base.glow, ocean.glow)
        val custom = wavePalette(base, WaveTint.CUSTOM, WaveColors("#010203", "#040506", "#070809"))
        assertEquals(0xFF010203.toInt(), custom.top)
        assertEquals(base, wavePalette(base, WaveTint.CUSTOM, WaveColors("nope", "#000", "#000")))
        val pack = ThemePalette(1, 2, 3)
        assertEquals(pack, paletteFor(PACK_THEME, pack))
        assertEquals(XrossTheme.LAVENDER.palette(), paletteFor(PACK_THEME, null))
    }

    @Test fun rendererParamsCarryTheUniforms() {
        val p = WaveParams(speed = 1.5f, lineCount = 12, turbulence = 0.3f, thickness = 2.5f)
        assertEquals(12, p.lineCount)
        assertEquals(WaveRenderer.MAX_LINES, p.lineCount)
        assertEquals(0.3f, p.turbulence)
        assertEquals(2.5f, p.thickness)
    }

    @Test fun themeSettingsOfferPacksAndTheWavePanel() {
        val tree = MenuTree(
            MenuSnapshot(
                settings = XrossSettings(theme = PACK_THEME, waveSpeed = 1.5f, waveLineCount = 7),
                themes = ThemeSetup(packs = listOf("default" to "Default", "sakura" to "Sakura"), activeId = "sakura", activeName = "Sakura", activeAuthor = "me", packPalette = true),
            ),
        )
        val rows = tree.list(MenuTree.DISPLAY)
        assertEquals(listOf("disp:pack", "disp:theme", "disp:wave", "disp:wavesettings", "disp:avatar", "disp:hints", "disp:allgames", "disp:surface", "disp:profile", "disp:adaptive", "disp:circadian", "disp:battery",
            "disp:fx:shader", "disp:fx:background_filter", "disp:fx:ui_filter", "disp:fx:focus_frame", "disp:fx:particles", "disp:fx:icon_mask",
            "disp:wallpaper", "disp:reset", "disp:import"), rows.map { it.id })
        val pack = rows[0]
        assertEquals("Sakura by me · 2 packs", pack.subtitle)
        assertEquals(NodeAction.Run(Command.CycleThemePack), pack.action)
        assertEquals(listOf(Command.SetThemePack("default"), Command.SetThemePack("sakura"), Command.ReloadThemePacks), pack.menu.map { it.command })
        assertTrue(pack.menu[1].selected)
        assertEquals("Theme pack · Sakura", rows[1].subtitle)
        assertEquals("1.5× · 7 lines · Theme colors", rows[3].subtitle)
        assertEquals(NodeAction.Run(Command.OpenWaveSettings), rows[3].action)
        assertFalse(MenuTree(MenuSnapshot()).list(MenuTree.DISPLAY).any { it.id == "disp:speed" })
    }

    @Test fun focusedGameBackgroundTiming() {
        assertEquals(250L, BackdropTiming.HERO_DELAY_MS)
        assertEquals(1_200L, BackdropTiming.VIDEO_DELAY_MS)
        assertEquals(300L, BackdropTiming.AUDIO_RAMP_MS)
        assertEquals(0f, BackdropTiming.rampVolume(0, 0.8f))
        assertEquals(0.4f, BackdropTiming.rampVolume(150, 0.8f), 1e-6f)
        assertEquals(0.8f, BackdropTiming.rampVolume(300, 0.8f))
        assertEquals(0.8f, BackdropTiming.rampVolume(5_000, 0.8f))
        assertFalse(BackdropTiming.covers(0.9f, 0f))
        assertTrue(BackdropTiming.covers(1f, 0f))
        assertTrue(BackdropTiming.covers(0f, 1f))
    }
}
