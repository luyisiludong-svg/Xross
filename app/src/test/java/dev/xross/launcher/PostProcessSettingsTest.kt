package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.ThemeSetup
import dev.xross.launcher.theme.pack.BackgroundFilter
import dev.xross.launcher.theme.pack.Cosmetics
import dev.xross.launcher.theme.pack.EffectOverrides
import dev.xross.launcher.theme.pack.EffectSlot
import dev.xross.launcher.theme.pack.FocusFrameStyle
import dev.xross.launcher.theme.pack.IconMask
import dev.xross.launcher.theme.pack.ParticlePreset
import dev.xross.launcher.theme.pack.ThemeDefaults
import dev.xross.launcher.theme.pack.ThemeLook
import dev.xross.launcher.theme.pack.ThemeManifest
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.UiFilter
import dev.xross.launcher.theme.pack.UiFilterRef
import dev.xross.launcher.theme.shader.ShaderPreset
import dev.xross.launcher.theme.shader.ShaderRef
import dev.xross.launcher.ui.ScreenFilterPass
import dev.xross.launcher.ui.UiFilterSupport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
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
 * Theme 2.0, 2b.4f: `effects.backgroundFilter` (the GL-side pass and its pixel snapping),
 * `effects.uiFilter` / `uiFilterPath` (AGSL on Android 13+, resting rules, the uniforms a custom
 * file declares), the `effects` schema, the user's Settings ▸ Theme overrides (`display.effects`
 * in settings.json, their place in the cascade, the rows and Reset to theme defaults).
 */
@RunWith(RobolectricTestRunner::class)
class PostProcessSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    private fun pack(id: String, body: String, files: Map<String, String> = emptyMap()): File {
        val dir = File(themes, id).apply { mkdirs() }
        File(dir, "theme.json").writeText("""{"schemaVersion": 2, "name": "$id", $body}""")
        for ((name, text) in files) File(dir, name).apply { parentFile?.mkdirs() }.writeText(text)
        return dir
    }

    private fun effects(json: String): ThemeManifest.Effects =
        ThemeManifest.JSON.decodeFromString(ThemeManifest.serializer(), """{"schemaVersion": 2, "name": "x", "effects": $json}""").effects!!

    private val agsl = "uniform shader content;\nhalf4 main(float2 p) { return content.eval(p); }\n"

    // region Manifest and cascade

    @Test fun filtersDecodeWithAliases() {
        val e = effects("""{"backgroundFilter": "scanlines", "postProcess": "bloom"}""")
        val problems = ArrayList<String>()
        val c = Cosmetics.of("theme", e, problems)!!
        assertEquals(BackgroundFilter.SCANLINES, c.backgroundFilter)
        assertEquals(UiFilterRef(UiFilter.BLOOM), c.uiFilter)
        assertTrue(problems.isEmpty())
        val lcd = Cosmetics.of("theme", effects("""{"backgroundFilter": "lcd-grid", "uiFilter": "Scanlines UI"}"""), problems)!!
        assertEquals(BackgroundFilter.LCD_GRID, lcd.backgroundFilter)
        assertEquals(UiFilter.SCANLINES_UI, lcd.uiFilter?.filter)
    }

    @Test fun uiFilterPathAloneMeansCustomAndStaysInThePack() {
        val problems = ArrayList<String>()
        assertEquals(UiFilterRef(UiFilter.CUSTOM, "fx/glow.agsl"), UiFilterRef.of("theme", null, "fx/glow.agsl", problems))
        assertEquals(UiFilterRef(UiFilter.CUSTOM, "postprocess.agsl"), UiFilterRef.of("theme", "CUSTOM", null, problems))
        assertTrue(problems.isEmpty())
        assertNull(UiFilterRef.of("theme", "CUSTOM", "../evil.agsl", problems))
        assertNull(UiFilterRef.of("theme", "CUSTOM", "fx/glow.frag", problems))
        assertNull(UiFilterRef.of("theme", "SEPIA", null, problems))
        assertEquals(3, problems.size)
        assertTrue(problems.last(), problems.last().contains("SCANLINES_UI"))
        // A path next to a built-in filter is reported and ignored.
        problems.clear()
        assertEquals(UiFilterRef(UiFilter.BLOOM), UiFilterRef.of("theme", "BLOOM", "x.agsl", problems))
        assertEquals(1, problems.size)
    }

    @Test fun unknownFilterNamesAreReportedAndLeftUnset() {
        val problems = ArrayList<String>()
        assertNull(Cosmetics.of("theme", effects("""{"backgroundFilter": "VHS"}"""), problems))
        assertTrue(problems.single().contains("backgroundFilter: VHS"))
    }

    @Test fun filtersCascadeThroughVariantsOverridesAndRules() {
        pack(
            "fx",
            """"palette": {"canvas": "#101010"}, "effects": {"backgroundFilter": "SCANLINES"},
               "variants": [{"id": "tube", "effects": {"backgroundFilter": "CRT", "uiFilter": "BLOOM"}}],
               "categoryOverrides": {"MUSIC": {"effects": {"backgroundFilter": "NONE"}}},
               "rules": [{"match": {"category": "VIDEO"}, "apply": {"effects": {"uiFilter": "SCANLINES_UI"}}}]""",
        )
        val t = loader.load("fx")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        assertEquals(BackgroundFilter.SCANLINES, t.look().cosmetics.bgFilter)
        assertEquals(UiFilter.NONE, t.look().cosmetics.screenFilter.filter)
        assertEquals(BackgroundFilter.CRT, t.look("tube").cosmetics.bgFilter)
        assertEquals(UiFilter.BLOOM, t.look("tube").cosmetics.screenFilter.filter)
        assertEquals(BackgroundFilter.NONE, t.look("tube", category = "music").cosmetics.bgFilter)
        assertEquals(UiFilter.SCANLINES_UI, t.look(category = "video").cosmetics.screenFilter.filter)
    }

    @Test fun customUiFilterIsReadAtLoad() {
        pack("own", """"effects": {"uiFilter": "CUSTOM"}, "variants": [{"id": "b", "effects": {"uiFilterPath": "fx/b.agsl"}}]""", mapOf("postprocess.agsl" to agsl))
        val t = loader.load("own")
        assertEquals(agsl, t.customUiFilters["postprocess.agsl"])
        assertTrue(t.problems.any { it.contains("UI filter fx/b.agsl not found") })
        assertEquals(UiFilterRef(UiFilter.CUSTOM, "fx/b.agsl"), t.look("b").cosmetics.uiFilter)
    }

    @Test fun unknownEffectsKeysAreReportedEverywhere() {
        pack(
            "typo",
            """"effects": {"shaderPrest": "BOKEH", "particles": "SNOW"},
               "variants": [{"id": "v", "effects": {"focusFrame": "STATIC_NEON", "glow": 2}}],
               "systemOverrides": {"snes": {"effects": {"scanlines": true}}},
               "rules": [{"match": {"category": "GAMES"}, "apply": {"effects": {"uiFliter": "BLOOM"}}}]""",
        )
        val p = loader.load("typo").problems
        assertTrue(p.toString(), p.contains("theme.effects.shaderPrest isn't an effects property (see docs/THEME_SPECIFICATION.md, effects reference)"))
        assertTrue(p.toString(), p.any { it.startsWith("variants.v.effects.glow") })
        assertTrue(p.toString(), p.any { it.startsWith("systemOverrides.snes.effects.scanlines") })
        assertTrue(p.toString(), p.any { it.startsWith("rules[0].effects.uiFliter") })
        assertFalse("aliases are known", p.any { it.contains("effects.particles") || it.contains("effects.focusFrame ") })
    }

    @Test fun everyEffectsFieldIsInTheSchema() {
        // A new Effects property must be added to KEYS too, or packs using it would be told it's unknown.
        val encoded = Json { encodeDefaults = true }.encodeToJsonElement(ThemeManifest.Effects.serializer(), ThemeManifest.Effects()).jsonObject
        for (key in encoded.keys) assertTrue("$key missing from Effects.KEYS", key in ThemeManifest.Effects.KEYS)
    }

    // endregion

    // region GL background filter

    @Test fun filterPitchesSnapToWholeRenderPixels() {
        // A 2400×1080 phone at 2.625 dp: rendered at 1280×576 (0.533), 1.40 render px per dp.
        val ppd = 2.625f * 1280f / 2400f
        assertEquals(2, ScreenFilterPass.linePitchPx(ppd))
        assertEquals(1, ScreenFilterPass.stripePx(ppd))
        assertEquals(4, ScreenFilterPass.cellPx(ppd))
        // A 640×480 handheld at 1.0: native, lines never closer than 2 px, cells never under 3.
        assertEquals(2, ScreenFilterPass.linePitchPx(1f))
        assertEquals(3, ScreenFilterPass.cellPx(0.5f))
        // A dense tablet: 3 dp at 2.0 render px/dp.
        assertEquals(3, ScreenFilterPass.linePitchPx(2f))
        assertEquals(6, ScreenFilterPass.cellPx(2f))
        assertEquals(listOf(0, 1, 2, 3), BackgroundFilter.entries.map(ScreenFilterPass::mode))
    }

    @Test fun filterShaderShipsWithItsOwnVersion() {
        val src = context.assets.open("shaders/screen_filter.frag").bufferedReader().readText()
        assertTrue(src.trimStart().startsWith("#version 300 es"))
        for (u in listOf("uResolution", "uPxPerDp", "uMode", "uStrength")) assertTrue(u, src.contains("uniform") && src.contains(u))
        val crt = context.assets.open("shaders/crt.frag").bufferedReader().readText()
        assertTrue("CRT sizes its lines from the density", crt.contains("uniform float uPxPerDp"))
    }

    // endregion

    // region AGSL UI filter

    @Test fun uiFilterNeedsAndroid13() {
        val bloom = UiFilterRef(UiFilter.BLOOM)
        assertEquals(UiFilterRef.OFF, UiFilterSupport.active(bloom, 32))
        assertEquals(UiFilterRef.OFF, UiFilterSupport.active(bloom, 28))
        assertEquals(bloom, UiFilterSupport.active(bloom, 33))
        assertEquals(UiFilterSupport.State.UNSUPPORTED, UiFilterSupport.state(bloom, 32, failed = false, animates = false, running = false))
        assertEquals(UiFilterSupport.State.OFF, UiFilterSupport.state(UiFilterRef.OFF, 36, false, false, false))
    }

    @Test fun builtInFiltersNeverAnimateSoTheyRest() {
        for (f in listOf(UiFilter.SCANLINES_UI, UiFilter.BLOOM)) {
            assertFalse(UiFilterSupport.animates(f, null))
            assertEquals(UiFilterSupport.State.STATIC, UiFilterSupport.state(UiFilterRef(f), 34, false, animates = false, running = false))
        }
    }

    @Test fun customFilterAnimatesOnlyWhileTheScreenIsInUse() {
        val timed = "uniform shader content;\nuniform float uTime;\nhalf4 main(float2 p) { return content.eval(p) * half(0.9 + 0.1 * sin(uTime)); }"
        assertTrue(UiFilterSupport.animates(UiFilter.CUSTOM, timed))
        assertFalse(UiFilterSupport.animates(UiFilter.CUSTOM, agsl))
        assertTrue(UiFilterSupport.running(true, started = true, idle = false, stillMotion = false))
        assertFalse("idle", UiFilterSupport.running(true, started = true, idle = true, stillMotion = false))
        assertFalse("stopped", UiFilterSupport.running(true, started = false, idle = false, stillMotion = false))
        assertFalse("E-Ink", UiFilterSupport.running(true, started = true, idle = false, stillMotion = true))
        val ref = UiFilterRef(UiFilter.CUSTOM, "postprocess.agsl")
        assertEquals(UiFilterSupport.State.RESTING, UiFilterSupport.state(ref, 35, false, animates = true, running = false))
        assertEquals(UiFilterSupport.State.ANIMATING, UiFilterSupport.state(ref, 35, false, animates = true, running = true))
        assertEquals(UiFilterSupport.State.FAILED, UiFilterSupport.state(ref, 35, true, animates = true, running = true))
    }

    @Test fun onlyDeclaredUniformsOfTheRightTypeAreSet() {
        // Setting an undeclared uniform aborts the process on Android 13+, so this must be exact.
        val u = UiFilterSupport.uniformsOf(
            """
            uniform shader content;
            // uniform float uTime;   (commented out: not declared)
            /* uniform float2 uResolution; */
            uniform vec2 uResolution;
            uniform float3 uPxPerDp;
            half4 main(float2 p) { return content.eval(p); }
            """.trimIndent(),
        )
        assertEquals(UiFilterSupport.Uniforms(resolution = true, time = false, pxPerDp = false), u)
        assertEquals(UiFilterSupport.Uniforms(true, true, true), UiFilterSupport.uniformsOf("uniform half2 uResolution; uniform half uTime;uniform float uPxPerDp ;"))
    }

    @Test fun customFilterMustDeclareContent() {
        assertNull(UiFilterSupport.precheck(agsl))
        assertTrue(UiFilterSupport.precheck("uniform shader image; half4 main(float2 p) { return image.eval(p); }")!!.contains("uniform shader content;"))
        assertTrue(UiFilterSupport.precheck("// uniform shader content;\nhalf4 main(float2 p) { return half4(1); }") != null)
        assertTrue(UiFilterSupport.precheck(null)!!.contains("can't be read"))
    }

    @Test fun scanlinePitchIsWholePixels() {
        assertEquals(4, UiFilterSupport.scanlinePitchPx(2.625f))
        assertEquals(2, UiFilterSupport.scanlinePitchPx(1f))
        assertEquals(2, UiFilterSupport.scanlinePitchPx(0.75f))
    }

    @Test fun uiFilterSourcesShipInTheApk() {
        val scan = context.assets.open("shaders/ui/scanlines_ui.agsl").bufferedReader().readText()
        val bloom = context.assets.open("shaders/ui/bloom_bright.agsl").bufferedReader().readText()
        assertNull(UiFilterSupport.precheck(scan))
        assertNull(UiFilterSupport.precheck(bloom))
        assertTrue(scan.contains("uniform float uPitch;") && scan.contains("uniform float uStrength;"))
        assertTrue(bloom.contains("uniform float uThreshold;") && bloom.contains("uniform float uGain;"))
    }

    // endregion

    // region User overrides

    @Test fun overridesWinOverThePackInEveryContext() {
        pack(
            "fx",
            """"effects": {"shaderPreset": "BOKEH", "iconMask": "SQUIRCLE", "foregroundParticles": "SNOW"},
               "categoryOverrides": {"MUSIC": {"effects": {"shaderPreset": "STARFIELD", "focusFrameStyle": "GOLD_BEZEL"}}}""",
        )
        val t = loader.load("fx")
        val user = EffectOverrides(shaderPreset = "CRT", iconMask = "NONE", focusFrameStyle = "STATIC_NEON")
        for (category in listOf(null, "music")) {
            val look = user.applyTo(t.look(category = category))
            assertEquals(ShaderRef(ShaderPreset.CRT), look.shader)
            assertEquals("System beats the pack's mask", IconMask.NONE, look.cosmetics.mask)
            assertEquals(FocusFrameStyle.STATIC_NEON, look.cosmetics.frame)
            assertEquals("unset values stay the pack's", ParticlePreset.SNOW, look.cosmetics.particleField)
        }
        val look = t.look()
        assertSame("no overrides: the pack's look itself", look, EffectOverrides.NONE.applyTo(look))
    }

    @Test fun userShaderJoinsTheAheadOfTimeLibrary() {
        pack("fx", """"effects": {"shaderPreset": "BOKEH"}""")
        val t = loader.load("fx")
        assertSame(t.shaderLibrary, t.shaderLibrary(null))
        assertSame(t.shaderLibrary, t.shaderLibrary(ShaderPreset.BOKEH))
        val withCrt = t.shaderLibrary(ShaderPreset.CRT)
        assertNotSame(t.shaderLibrary, withCrt)
        assertEquals(listOf("WAVE", "BOKEH", "CRT"), withCrt.specs.map { it.key })
        assertEquals("CRT", withCrt.keyOf(ShaderRef(ShaderPreset.CRT)))
    }

    @Test fun overridesNormalizeAndDropTyposAndCustom() {
        val o = EffectOverrides(shaderPreset = "ascii-matrix", uiFilter = "custom", iconMask = "Y2K chrome frame", foregroundParticles = "rain", backgroundFilter = " crt ")
            .normalized()
        assertEquals(EffectOverrides(shaderPreset = "ASCII_MATRIX", iconMask = "Y2K_CHROME_FRAME", backgroundFilter = "CRT"), o)
        assertNull("CUSTOM is a pack's own code", EffectOverrides(shaderPreset = "CUSTOM").shader)
        assertTrue(EffectOverrides(shaderPreset = "PLASMA").empty)
    }

    @Test fun overridesRoundTripThroughSettingsJson() {
        val s = XrossSettings(effectOverrides = EffectOverrides(shaderPreset = "BOKEH", backgroundFilter = "SCANLINES", uiFilter = "BLOOM", iconMask = "NONE"))
        val text = ConfigCodec.encodeSettings(s)
        val display = Json.parseToJsonElement(text).jsonObject["display"]!!.jsonObject
        assertEquals("BOKEH", display["effects"]!!.jsonObject["shaderPreset"]!!.toString().trim('"'))
        assertEquals(s.effectOverrides, ConfigCodec.decodeSettings(XrossSettings(), text).effectOverrides)
        // A hand edit with a typo follows the pack for that value only.
        val edited = text.replace("\"BLOOM\"", "\"BLOOOM\"")
        assertEquals(EffectOverrides(shaderPreset = "BOKEH", backgroundFilter = "SCANLINES", iconMask = "NONE"), ConfigCodec.decodeSettings(XrossSettings(), edited).effectOverrides)
        // Older files without the block: no overrides.
        assertEquals(EffectOverrides.NONE, ConfigCodec.decodeSettings(XrossSettings(), """{"display": {"theme": "CRIMSON"}}""").effectOverrides)
    }

    @Test fun rowsCycleThePackFirstThenEachChoice() {
        assertEquals("WAVE", EffectSlot.SHADER.next(""))
        assertEquals("CRT", EffectSlot.SHADER.next("STARFIELD"))
        assertEquals("", EffectSlot.SHADER.next("CRT"))
        assertEquals("SNOW", EffectSlot.PARTICLES.next("none"))
        assertEquals("System", EffectSlot.ICON_MASK.label("NONE"))
        assertEquals("Matrix", EffectSlot.SHADER.label("ASCII_MATRIX"))
        val look = ThemeLook(null, "FLAT_GLASS", shader = ShaderRef(ShaderPreset.CUSTOM, "shader.frag"))
        assertEquals("Theme pack (the pack's shader)", EffectSlot.SHADER.packLabel(look))
        assertEquals("Theme pack (Breathing halo)", EffectSlot.FOCUS_FRAME.packLabel(look))
    }

    @Test fun settingsThemeListsTheEffectRowsAndReset() {
        val look = ThemeLook(null, "FLAT_GLASS", shader = ShaderRef(ShaderPreset.BOKEH), cosmetics = Cosmetics(particles = ParticlePreset.SNOW))
        val setup = ThemeSetup(activeName = "Sakura", looks = mapOf("" to look))
        val s = XrossSettings(effectOverrides = EffectOverrides(iconMask = "CIRCLE"), customWallpaper = "content://x/1")
        val rows = MenuTree(MenuSnapshot(settings = s, themes = setup)).list(MenuTree.DISPLAY)
        val ids = rows.map { it.id }
        val fx = EffectSlot.entries.map { "disp:fx:" + it.name.lowercase() }
        assertEquals(fx, ids.filter { it.startsWith("disp:fx:") })
        assertEquals(listOf("disp:battery") + fx + listOf("disp:wallpaper", "disp:reset", "disp:import"), ids.subList(ids.indexOf("disp:battery"), ids.size))
        val byId = rows.associateBy { it.id }
        assertTrue(byId["disp:fx:shader"]!!.subtitle!!.startsWith("Theme pack (Bokeh)"))
        assertTrue(byId["disp:fx:particles"]!!.subtitle!!.startsWith("Theme pack (Snow)"))
        assertTrue(byId["disp:fx:icon_mask"]!!.subtitle!!.startsWith("Circle"))
        val mask = byId["disp:fx:icon_mask"]!!
        assertEquals(listOf("Theme pack (System)", "System", "Squircle", "Circle", "Chrome"), mask.menu.map { it.label })
        assertEquals("Circle", mask.menu.single { it.selected }.label)
        assertEquals(NodeAction.Run(Command.CycleEffect(EffectSlot.ICON_MASK)), mask.action)
        assertEquals("Clears 1 effect, wallpaper", byId["disp:reset"]!!.subtitle)
        val wall = byId["disp:wallpaper"]!!
        assertEquals(NodeAction.Run(Command.ResetXrossWallpaper), wall.action)
        assertEquals(listOf("Pick custom…", "Reset to theme (none)"), wall.menu.map { it.label })
        val clean = MenuTree(MenuSnapshot(themes = setup)).list(MenuTree.DISPLAY).associateBy { it.id }
        assertEquals("Nothing overrides Sakura", clean["disp:reset"]!!.subtitle)
        assertEquals(NodeAction.Run(Command.PickXrossWallpaper), clean["disp:wallpaper"]!!.action)
    }

    @Test fun resetToThemeDefaultsKeepsThePackAndWave() {
        val s = XrossSettings(
            themePack = "sakura", themeVariant = "night", theme = "CRIMSON", quickProfile = "OLED_TRUE_BLACK", waveSpeed = 1.4f,
            effectOverrides = EffectOverrides(shaderPreset = "CRT", uiFilter = "BLOOM"), surfaceStyle = "OLED_TRUE_BLACK",
            customWallpaper = "content://x/1", circadianCurve = true, adaptiveGamePalette = false,
        )
        val r = ThemeDefaults.reset(s)
        assertEquals(EffectOverrides.NONE, r.effectOverrides)
        assertEquals("", r.surfaceStyle)
        assertEquals("", r.customWallpaper)
        assertNull(r.circadianCurve)
        assertNull(r.adaptiveGamePalette)
        assertEquals(listOf("sakura", "night", "CRIMSON", "OLED_TRUE_BLACK"), listOf(r.themePack, r.themeVariant, r.theme, r.quickProfile))
        assertEquals(1.4f, r.waveSpeed)
        assertTrue(ThemeDefaults.isDefault(r))
        assertFalse(ThemeDefaults.isDefault(s))
    }

    // endregion
}
