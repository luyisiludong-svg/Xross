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
import dev.xross.launcher.theme.PACK_THEME
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.pack.SurfacePreset
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.platformIdOf
import dev.xross.launcher.theme.paletteFor
import dev.xross.launcher.ui.WaveRenderer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Theme 2.0 slice 3 (Roadmap Appendix A.4): `variants`, `categoryOverrides` and
 * `systemOverrides`, the precedence cascade (Default → pack → variant → category → system → the
 * user's settings), the saved variant, and the Theme variant row.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeVariantsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    private fun pack(id: String, body: String) {
        val dir = File(themes, id).apply { mkdirs() }
        File(dir, "theme.json").writeText("""{"schemaVersion": 2, "name": "$id", $body}""")
    }

    private val cyber = """
        "palette": {"canvas": "#1B1829", "accent": "#FFB5C5", "accentSecondary": "#C8B6FF"},
        "variants": [
          {"id": "cyber-lilac", "name": "Cyber Lilac", "palette": {"accent": "#AA00FF"}},
          {"id": "bios-matrix", "name": "BIOS Matrix",
           "palette": {"canvas": "#2A242B", "accent": "#F4D03F"}, "effects": {"surfaceStyle": "EINK_HIGH_CONTRAST", "shaderPreset": "ASCII_MATRIX"}}
        ],
        "categoryOverrides": {
          "GAMES": {"palette": {"accent": "#00FF00"}, "effects": {"surfaceStyle": "Y2K_GLOSSY_BEZEL"}},
          "MUSIC": {"cardAspectRatio": "1:1"}
        },
        "systemOverrides": {
          "dreamcast": {"palette": {"accent": "#FF8C42"}, "effects": {"surfaceStyle": "OLED_TRUE_BLACK"}, "cardAspectRatio": "2:1"},
          "gc": {"palette": {"accentSecondary": "#123456"}}
        }
    """

    // region Variants

    @Test fun variantsAreReadAndPatchThePalette() {
        pack("cyber", cyber)
        val t = loader.load("cyber")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        assertEquals(listOf("cyber-lilac" to "Cyber Lilac", "bios-matrix" to "BIOS Matrix"), t.variants.map { it.id to it.name })

        val base = t.look()
        assertEquals(0xFFFFB5C5.toInt(), base.palette!!.glow)
        assertEquals(t.palette, base.palette)
        assertEquals(SurfacePreset.FLAT_GLASS.name, base.surfaceId)

        // A variant patches only the roles it sets: lilac keeps canvas and the ribbon color.
        val lilac = t.look("cyber-lilac").palette!!
        assertEquals(0xFFAA00FF.toInt(), lilac.glow)
        assertEquals(0xFF1B1829.toInt(), lilac.top)
        assertEquals(0xFFC8B6FF.toInt(), lilac.wave)

        val bios = t.look("bios-matrix")
        assertEquals(0xFF2A242B.toInt(), bios.palette!!.top)
        assertEquals(SurfacePreset.EINK_HIGH_CONTRAST.name, bios.surfaceId)
        assertEquals("bios-matrix", bios.variantId)
    }

    @Test fun anUnknownOrBlankVariantFallsBackToTheBaseLook() {
        pack("cyber", cyber)
        val t = loader.load("cyber")
        assertEquals(t.look(), t.look("no-such-variant"))
        assertEquals(t.look(), t.look(""))
        assertEquals("", t.look("no-such-variant").variantId)
        assertNull(t.variant("Cyber-Lilac")) // ids are exact, as saved
        // Cached: a focus change asks for the same look again and gets it back without work.
        assertSame(t.look("cyber-lilac", "games", "dreamcast"), t.look("cyber-lilac", "games", "dreamcast"))
    }

    @Test fun aVariantMayBringThePacksOnlyPalette() {
        pack(
            "plain",
            """"variants": [
                 {"id": "night", "palette": {"canvas": "#000000", "accent": "#FFFFFF"}},
                 {"id": "half", "palette": {"accent": "#FF0000"}},
                 {"id": "bad id!"}, {"id": "night"}
               ]""",
        )
        val t = loader.load("plain")
        assertNull(t.palette)
        assertNull(t.look().palette)
        assertEquals(0xFF000000.toInt(), t.look("night").palette!!.top)
        assertEquals("night", t.variants.first().name) // a missing name shows the id
        // No canvas anywhere: reported, and the user's color theme stays.
        assertNull(t.look("half").palette)
        assertEquals(listOf("night", "half"), t.variants.map { it.id })
        assertEquals(3, t.problems.size)
        assertTrue(t.problems.toString(), t.problems.any { "canvas" in it } && t.problems.any { "bad id" in it } && t.problems.any { "twice" in it })
    }

    @Test fun badColorsAndSurfacesInPatchesAreDroppedOnTheirOwn() {
        pack(
            "typo",
            """"palette": {"canvas": "#101010", "accent": "#202020"},
               "variants": [{"id": "v", "palette": {"accent": "pink", "accentSecondary": "#303030"}, "effects": {"surfaceStyle": "nope"}}],
               "categoryOverrides": {"GAMES": {"palette": {"canvas": "#12"}}, "SHOPPING": {"cardAspectRatio": "1:1"}}""",
        )
        val t = loader.load("typo")
        val v = t.look("v")
        // The bad accent is dropped, the good role still applies, and the surface stays the pack's.
        assertEquals(0xFF202020.toInt(), v.palette!!.glow)
        assertEquals(0xFF303030.toInt(), v.palette!!.wave)
        assertEquals(SurfacePreset.FLAT_GLASS.name, v.surfaceId)
        assertEquals(t.look("v"), t.look("v", "games"))
        assertEquals(4, t.problems.size)
        assertTrue(t.problems.toString(), t.problems.any { "SHOPPING" in it })
    }

    // endregion

    // region Precedence cascade (A.4)

    @Test fun precedenceRunsPackVariantCategorySystemThenUser() {
        pack("cyber", cyber)
        val t = loader.load("cyber")

        // Pack base → variant.
        assertEquals(0xFFAA00FF.toInt(), t.look("cyber-lilac", "music").palette!!.glow)
        // → category override (while Games is open), over the variant.
        val games = t.look("cyber-lilac", "games")
        assertEquals(0xFF00FF00.toInt(), games.palette!!.glow)
        assertEquals(SurfacePreset.Y2K_GLOSSY_BEZEL.name, games.surfaceId)
        // → system override (a Dreamcast game focused), over the category.
        val dc = t.look("cyber-lilac", "games", "dreamcast")
        assertEquals(0xFFFF8C42.toInt(), dc.palette!!.glow)
        assertEquals(SurfacePreset.OLED_TRUE_BLACK.name, dc.surfaceId)
        // Roles nobody patched come from below: the variant's canvas, the pack's ribbon.
        assertEquals(0xFF1B1829.toInt(), dc.palette!!.top)
        assertEquals(0xFFC8B6FF.toInt(), dc.palette!!.wave)
        // The variant's surface sits under the category's.
        assertEquals(SurfacePreset.Y2K_GLOSSY_BEZEL.name, t.look("bios-matrix", "games").surfaceId)
        assertEquals(SurfacePreset.EINK_HIGH_CONTRAST.name, t.look("bios-matrix", "music").surfaceId)
        // A system without an override, or one focused outside its category, changes nothing more.
        assertEquals(games, t.look("cyber-lilac", "games", "snes"))
        val gc = t.look("", "games", "gc").palette!!
        assertEquals(0xFF123456.toInt(), gc.wave)
        assertEquals(0xFF00FF00.toInt(), gc.glow)

        // → the user's settings.json: a fixed color theme wins over every pack layer…
        assertEquals(XrossTheme.CRIMSON.palette(), paletteFor(XrossTheme.CRIMSON.name, dc.palette))
        assertEquals(dc.palette, paletteFor(PACK_THEME, dc.palette))
        // …and so does a chosen surface style; empty means the look's.
        assertEquals(SurfacePreset.EINK_HIGH_CONTRAST.name, t.surfaceFor(dc, SurfacePreset.EINK_HIGH_CONTRAST.name).id)
        assertEquals(SurfacePreset.OLED_TRUE_BLACK.name, t.surfaceFor(dc, "").id)
        assertEquals(SurfacePreset.OLED_TRUE_BLACK.name, t.surfaceFor(dc, "gone").id)
    }

    @Test fun overridesOnlyPatchAPaletteThatExists() {
        pack(
            "nocolors",
            """"categoryOverrides": {"GAMES": {"palette": {"canvas": "#000000", "accent": "#FF0000"}}},
               "systemOverrides": {"dc": {"palette": {"canvas": "#000000"}, "effects": {"surfaceStyle": "OLED_TRUE_BLACK"}}}""",
        )
        val t = loader.load("nocolors")
        assertNull(t.look("", "games", "dreamcast").palette)
        // Effects still apply without a palette.
        assertEquals(SurfacePreset.OLED_TRUE_BLACK.name, t.look("", "games", "dreamcast").surfaceId)
    }

    @Test fun systemOverridesAcceptFolderAliases() {
        assertEquals("dreamcast", platformIdOf("dc"))
        assertEquals("dreamcast", platformIdOf("Dreamcast"))
        assertEquals("ps2", platformIdOf("PlayStation2"))
        assertEquals("gc", platformIdOf("gamecube"))
        assertNull(platformIdOf("toaster"))
        assertNull(platformIdOf(" "))

        pack(
            "alias",
            """"palette": {"canvas": "#000000", "accent": "#111111"},
               "systemOverrides": {
                 "dc": {"palette": {"accent": "#222222"}},
                 "dreamcast": {"palette": {"accent": "#333333"}},
                 "ps2": {"cardAspectRatio": "3:4"},
                 "toaster": {"palette": {"accent": "#444444"}}
               }""",
        )
        val t = loader.load("alias")
        assertEquals(listOf("dreamcast"), t.systemPatches.keys.toList())
        // The exact id beats the alias, whichever comes first.
        assertEquals(0xFF333333.toInt(), t.look("", "games", "dreamcast").palette!!.glow)
        assertTrue(t.problems.single().startsWith("systemOverrides.toaster"))
        // A system's tile aspect shapes its games; other systems keep the category's.
        assertEquals(0.75f, t.layout.aspectFor("games", "ps2")!!, 1e-6f)
        assertEquals(16f / 9f, t.layout.aspectFor("games", "snes")!!, 1e-6f)
        assertEquals(16f / 9f, t.layout.aspectFor("games", null)!!, 1e-6f)
    }

    @Test fun categoryAndSystemAspectsLayer() {
        pack("cyber", cyber)
        val l = loader.load("cyber").layout
        assertEquals(1f, l.aspectFor("music")!!, 1e-6f)
        assertEquals(2f, l.aspectFor("games", "dreamcast")!!, 1e-6f)
        assertEquals(16f / 9f, l.aspectFor("games", "gc")!!, 1e-6f)
    }

    @Test fun theDefaultPackHasNoVariantsOrOverrides() {
        val t = loader.load(ThemePackLoader.DEFAULT_ID)
        assertTrue(t.variants.isEmpty() && t.categoryPatches.isEmpty() && t.systemPatches.isEmpty())
        assertNull(t.look("anything", "games", "dreamcast").palette)
        assertEquals(t.surfaceId, t.look("anything", "games", "dreamcast").surfaceId)
    }

    // endregion

    // region Settings

    @Test fun theVariantIsSavedInSettingsJson() {
        val s = XrossSettings(themePack = "cyber", themeVariant = "bios-matrix")
        val json = ConfigCodec.encodeSettings(s)
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        val display = root["display"]!!.jsonObject
        assertEquals("bios-matrix", display["themeVariant"]!!.jsonPrimitive.content)
        assertEquals("bios-matrix", ConfigCodec.decodeSettings(XrossSettings(), json).themeVariant)
        // Older files without the key load the pack's base look.
        val old = kotlinx.serialization.json.JsonObject(root + ("display" to kotlinx.serialization.json.JsonObject(display - "themeVariant"))).toString()
        assertFalse(old.contains("themeVariant"))
        assertEquals("", ConfigCodec.decodeSettings(XrossSettings(), old).themeVariant)
    }

    @Test fun theVariantRowShowsOnlyForPacksWithVariants() {
        fun rows(variant: String, variants: List<Pair<String, String>>) = MenuTree(
            MenuSnapshot(
                settings = XrossSettings(themeVariant = variant),
                themes = ThemeSetup(activeId = "cyber", activeName = "Cyber", variants = variants),
            ),
        ).list(MenuTree.DISPLAY)

        val none = rows("", emptyList())
        assertFalse(none.any { it.id == "disp:variant" })
        assertEquals("disp:import", none.last().id)
        assertEquals(NodeAction.Run(Command.ImportThemeArchive), none.last().action)

        val two = listOf("cyber-lilac" to "Cyber Lilac", "bios-matrix" to "BIOS Matrix")
        val with = rows("bios-matrix", two)
        assertEquals(listOf("disp:pack", "disp:variant"), with.take(2).map { it.id })
        val row = with[1]
        assertEquals("BIOS Matrix · 3 looks in Cyber", row.subtitle)
        assertEquals(NodeAction.Run(Command.CycleThemeVariant), row.action)
        assertEquals(
            listOf(Command.SetThemeVariant(""), Command.SetThemeVariant("cyber-lilac"), Command.SetThemeVariant("bios-matrix")),
            row.menu.map { it.command },
        )
        assertEquals(listOf(false, false, true), row.menu.map { it.selected })
        // A saved id the pack doesn't have shows (and means) the base look.
        val stale = rows("gone", two)[1]
        assertTrue(stale.subtitle!!.startsWith("Standard"))
        assertTrue(stale.menu[0].selected)
    }

    // endregion

    @Test fun waveColorsCrossfadeAtAnyFrameRate() {
        assertEquals(0f, WaveRenderer.colorStep(0f))
        // ~95% of the way in 300 ms, whether that is 18 frames at 60 fps or 6 at the 20 fps standby.
        fun after(frames: Int, dt: Float): Float {
            val shown = floatArrayOf(0f, 0f, 0f)
            repeat(frames) { WaveRenderer.easeColor(shown, floatArrayOf(1f, 0.5f, 0f), WaveRenderer.colorStep(dt)) }
            return shown[0]
        }
        assertEquals(after(18, 1f / 60f), after(6, 1f / 20f), 1e-4f)
        assertTrue(after(18, 1f / 60f) > 0.94f)
        // It lands exactly on the target instead of creeping forever.
        assertEquals(1f, after(200, 1f / 60f))
    }
}
