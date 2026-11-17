package dev.xross.launcher.theme.pack

import dev.xross.launcher.theme.PACK_THEME
import dev.xross.launcher.theme.WaveColors
import dev.xross.launcher.theme.WaveTint
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.pack.ComponentOverrides
import dev.xross.launcher.theme.pack.EffectOverrides
import dev.xross.launcher.theme.pack.ExportScope
import dev.xross.launcher.theme.pack.ExportSource
import dev.xross.launcher.theme.pack.LayoutOverrides
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.LookExport
import dev.xross.launcher.theme.pack.LookExporter
import dev.xross.launcher.theme.pack.LookPicks
import dev.xross.launcher.theme.pack.ThemeManifest
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.UserLook
import dev.xross.launcher.theme.pack.WaveValues
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/** 2b.8b: what each export scope writes into `theme.json` and which files it copies. */
class LookExporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { TestAssets.loader { themes } }
    private val date = LocalDate.of(2026, 11, 15)

    private fun stress(): LoadedTheme {
        StressPack.write(themes)
        return loader.load(StressPack.ID).also { assertEquals(StressPack.ID, it.id) }
    }

    private fun default() = loader.load(ThemePackLoader.DEFAULT_ID)

    private fun LookExport.paths() = files.map { it.path }.toSet()
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private val identity = setOf("schemaVersion", "name", "author", "version", "description")

    /** Every scope: one theme.json, schema v2, no imports, decodable with no schema problems. */
    private fun checkShape(e: LookExport) {
        assertEquals(2, e.manifest.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertFalse(e.manifest.containsKey("imports"))
        val m = ThemeManifest.JSON.decodeFromJsonElement(ThemeManifest.serializer(), e.manifest)
        assertEquals(emptyList<String>(), ThemeManifest.schemaProblems(e.manifest))
        assertTrue(m.name.isNotBlank())
        assertTrue(e.files.none { f -> f.path.split('/').any { it.startsWith(".") } })
    }

    // region Whole pack

    @Test fun wholePackIsTheSourceJsonWithItsIdentityAndEveryConventionFile() {
        val pack = stress()
        val all = loader.packFiles(pack.info)
        val picks = LookPicks(user = UserLook(effects = EffectOverrides(shaderPreset = "BOKEH"), surfaceStyle = "OLED_TRUE_BLACK"), colorTheme = "CRIMSON")
        val e = LookExporter.export(pack, ExportScope.WholePack, "", date, all, picks)
        checkShape(e)
        // Apart from its identity, the pack as it ships: variants, overrides and rules, no user pick.
        assertEquals(JsonObject(pack.sourceJson - "imports" - identity), JsonObject(e.manifest - identity))
        assertEquals(2, (e.manifest.getValue("variants") as JsonArray).size)
        assertEquals(setOf("MUSIC", "GAMES"), e.manifest.obj("categoryOverrides").keys)
        assertEquals(2, (e.manifest.getValue("rules") as JsonArray).size)
        assertEquals("Stress", e.name)
        assertEquals("Xross tests", e.manifest.text("author"))
        assertEquals("2.3.0", e.manifest.text("version"))
        assertEquals(
            "Every pack file in every place · Exported from Stress 2.3.0 (whole pack) by Xross on 2026-11-15. ${LookExporter.LICENCE_NOTE}",
            e.manifest.text("description"),
        )
        // Everything the loader read (a non-selected variant's and a rule's files too), and the
        // every other data file the pack ships (2b.8c: the import takes any file a manifest could
        // name, so art/orphan.png too); never dot-files or the JSON merged into theme.json.
        val paths = e.paths()
        assertTrue(paths.containsAll(StressPack.USED))
        assertTrue("art/night.webp" in paths && "art/late.jpg" in paths)
        for (p in listOf("wallpaper.png", "bgm.ogg", "sounds/cursor.wav", "icons/music.svg", "icons/not_a_glyph.svg", "notes.txt", "art/orphan.png")) assertTrue(p, p in paths)
        for (p in listOf(".git/config", ".nomedia", "theme.json", "surfaces.json", "rules.json")) assertFalse(p, p in paths)
        assertTrue(e.files.all { it.source == ExportSource.Pack(it.path) })
    }

    @Test fun wholePackOfTheBuiltInPackFromItsAssets() {
        val pack = default()
        val e = LookExporter.export(pack, ExportScope.WholePack, "", date, loader.packFiles(pack.info))
        checkShape(e)
        assertEquals("Default", e.name)
        assertEquals("Xross", e.manifest.text("author"))
        // surfaces.json is merged in, and the pack keeps everything it ships, power.svg included,
        // and CREDITS.md (the Phosphor icons' MIT notice travels with a shared copy).
        assertTrue(e.manifest.obj("surfaces").containsKey("FLAT_GLASS"))
        val sounds = TestAssets.platform.listAssets("${ThemePackLoader.DEFAULT_DIR}/sounds")!!.map { "sounds/$it" }
        val icons = TestAssets.platform.listAssets("${ThemePackLoader.DEFAULT_DIR}/icons")!!.map { "icons/$it" }
        assertEquals((sounds + icons + "CREDITS.md").toSet(), e.paths())
        assertTrue("icons/power.svg" in e.paths())
    }

    // endregion

    // region One variant

    @Test fun aVariantIsFlattenedIntoTheBaseWithOverridesAndRulesKept() {
        val pack = stress()
        val e = LookExporter.export(pack, ExportScope.Variant("night"), "", date)
        checkShape(e)
        val m = e.manifest
        assertEquals("Stress · Night", e.name)
        assertEquals("1.0.0", m.text("version"))
        assertEquals("Xross tests", m.text("author"))
        assertEquals("Exported from Stress 2.3.0 (Night variant) by Xross on 2026-11-15. ${LookExporter.LICENCE_NOTE}", m.text("description"))
        assertFalse(m.containsKey("variants"))
        // The night canvas over the base's accents.
        assertEquals(mapOf("canvas" to "#000000", "accent" to "#7A5CFF", "accentSecondary" to "#3AE0C8"), m.obj("palette").mapValues { it.value.jsonPrimitive.content })
        val fx = m.obj("effects")
        assertEquals("art/night.webp", fx.text("wallpaperPath"))
        assertEquals("BOKEH", fx.text("shaderPreset"))
        assertEquals("CUSTOM", fx.text("uiFilter"))
        assertEquals("logo-night.svg", m.obj("components").text("logo"))
        assertEquals("audio/night.ogg", m.obj("audio").text("bgmFile"))
        assertEquals(pack.sourceJson.getValue("categoryOverrides"), m.getValue("categoryOverrides"))
        assertEquals(pack.sourceJson.getValue("systemOverrides"), m.getValue("systemOverrides"))
        assertEquals(pack.sourceJson.getValue("rules"), m.getValue("rules"))
        // Only what the night look uses somewhere: not the base's wallpaper, logo and loop.
        val paths = e.paths()
        assertTrue(paths.containsAll(listOf("art/night.webp", "logo-night.svg", "audio/night.ogg", "art/vinyl.png", "art/late.jpg", "shader.frag", "postprocess.agsl", "boot.mp4", "icons/my-music.svg", "sounds/tick.wav")))
        for (p in listOf("art/base.png", "logo.svg", "audio/loop.ogg", "wallpaper.png", "bgm.ogg")) assertFalse(p, p in paths)
    }

    @Test fun theBaseLookOfAPackWithVariantsDropsTheVariantsAndTheirFiles() {
        val pack = stress()
        val e = LookExporter.export(pack, ExportScope.Variant(""), "", date)
        checkShape(e)
        assertEquals("Stress · Base look", e.name)
        assertFalse(e.manifest.containsKey("variants"))
        assertEquals(JsonObject(pack.sourceJson - "imports" - "variants" - identity), JsonObject(e.manifest - identity))
        assertEquals(StressPack.USED - setOf("art/night.webp", "logo-night.svg", "audio/night.ogg"), e.paths())
    }

    @Test fun aVariantPaletteOfAPackWithoutColorsIsThePaletteNotAPatch() {
        File(themes, "plain").mkdirs()
        File(themes, "plain/theme.json").writeText(
            """{"schemaVersion":2,"name":"Plain","palette":{"accent":"#FF0000","circadianCurve":true},
               "variants":[{"id":"blue","name":"Blue","palette":{"canvas":"#000033","accentSecondary":"#nothex"}}]}""",
        )
        val pack = loader.load("plain")
        val e = LookExporter.export(pack, ExportScope.Variant("blue"), "", date)
        // The base's accent never showed (no canvas); the variant's bad colour is left out; the switch stays.
        assertEquals(mapOf("circadianCurve" to "true", "canvas" to "#000033"), e.manifest.obj("palette").mapValues { it.value.jsonPrimitive.content })
    }

    // endregion

    // region Current look

    private val stressPicks = LookPicks(
        variant = "",
        user = UserLook(
            effects = EffectOverrides(focusFrameStyle = "GOLD_BEZEL", shaderPreset = "STARFIELD"),
            surfaceStyle = "FLAT_GLASS",
            layout = LayoutOverrides(preset = "MODERN_WIDESCREEN", uiScale = 1.1f),
            components = ComponentOverrides(batteryMode = "PERCENTAGE"),
            adaptiveGamePalette = false,
        ),
        colorTheme = PACK_THEME,
        wave = WaveValues(speed = 1.4f, lineCount = 8, turbulence = 0.6f, thickness = 2f),
    )

    @Test fun aUserPickIsWrittenIntoTheBaseAndTakenOutOfEveryOverrideAndRule() {
        val pack = stress()
        val e = LookExporter.export(pack, ExportScope.CurrentLook, "", date, picks = stressPicks)
        checkShape(e)
        val m = e.manifest
        assertEquals("Stress (my look)", e.name)
        assertEquals("", m.text("author"))
        assertEquals("1.0.0", m.text("version"))
        val fx = m.obj("effects")
        assertEquals("GOLD_BEZEL", fx.text("focusFrameStyle"))
        assertEquals("STARFIELD", fx.text("shaderPreset"))
        assertEquals("FLAT_GLASS", fx.text("surfaceStyle"))
        assertEquals("false", fx.text("adaptiveGamePalette"))
        // MUSIC keeps its wallpaper and music; GAMES keeps its accent.
        val cats = m.obj("categoryOverrides")
        assertEquals(setOf("wallpaperPath"), cats.obj("MUSIC").obj("effects").keys)
        assertEquals(setOf("palette"), cats.obj("GAMES").keys)
        // Dreamcast's override only set the shader and the surface: dropped, and with it the block.
        assertFalse(m.containsKey("systemOverrides"))
        // The battery rule keeps its accent; the night rule its wallpaper.
        val rules = m.getValue("rules") as JsonArray
        assertEquals(2, rules.size)
        assertEquals(setOf("palette"), rules[0].jsonObject.obj("apply").keys)
        assertEquals(setOf("effects"), rules[1].jsonObject.obj("apply").keys)
        // Layout: the preset instead of the pack's, the scale; the pack's spacing stays.
        val layout = m.obj("layout")
        assertEquals("MODERN_WIDESCREEN", layout.text("preset"))
        assertEquals(1.1f, layout.text("uiScale").toFloat())
        assertEquals(16f, layout.text("itemSpacingDp").toFloat())
        assertFalse(layout.containsKey("crosshairX"))
        // Components resolved; wave values written; the pack's colours kept.
        val comp = m.obj("components")
        assertEquals("PERCENTAGE", comp.text("batteryMode"))
        assertEquals(listOf("LOGO", "WIFI"), (comp.getValue("statusBarLeft") as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("logo.svg", comp.text("logo"))
        assertEquals(mapOf("speed" to "1.4", "lineCount" to "8", "turbulence" to "0.6", "ribbonThickness" to "2.0"), m.obj("wave").mapValues { it.value.jsonPrimitive.content })
        assertEquals("#101018", m.obj("palette").text("canvas"))
        assertFalse(m.containsKey("variants"))
        // No CUSTOM shader is left anywhere: shader.frag isn't copied.
        val paths = e.paths()
        assertFalse("shader.frag" in paths)
        assertTrue(paths.containsAll(listOf("art/base.png", "art/vinyl.png", "art/late.jpg", "postprocess.agsl", "logo.svg")))
        assertFalse("art/night.webp" in paths)
    }

    @Test fun aColorThemeOtherThanThePacksBecomesThePaletteInEveryContext() {
        val pack = stress()
        val crimson = XrossTheme.CRIMSON.palette(date)
        val e = LookExporter.export(pack, ExportScope.CurrentLook, "", date, picks = stressPicks.copy(colorTheme = "CRIMSON"))
        val m = e.manifest
        val pal = m.obj("palette")
        assertEquals(ThemeManifest.colorText(crimson.top), pal.text("canvas"))
        assertEquals(ThemeManifest.colorText(crimson.glow), pal.text("accent"))
        assertEquals(ThemeManifest.colorText(crimson.wave), pal.text("accentSecondary"))
        assertEquals(listOf(ThemeManifest.colorText(crimson.top), ThemeManifest.colorText(crimson.bottom)), (m.obj("wave").getValue("gradient") as JsonArray).map { it.jsonPrimitive.content })
        // GAMES and the battery rule only changed the accent, which the colour theme overrides: both dropped.
        assertEquals(setOf("MUSIC"), m.obj("categoryOverrides").keys)
        assertEquals(1, (m.getValue("rules") as JsonArray).size)
        assertTrue(e.notes.isEmpty())

        val original = LookExporter.export(pack, ExportScope.CurrentLook, "", date, picks = stressPicks.copy(colorTheme = XrossTheme.ORIGINAL.name))
        assertEquals(listOf("Original's November colors"), original.notes)
        assertEquals(ThemeManifest.colorText(XrossTheme.ORIGINAL.palette(date).top), original.manifest.obj("palette").text("canvas"))
    }

    @Test fun aWaveTintOverThePacksColorsKeepsThePacksAccent() {
        val pack = stress()
        val custom = WaveColors(top = "#010203", bottom = "#040506", wave = "#070809")
        val e = LookExporter.export(pack, ExportScope.CurrentLook, "", date, picks = stressPicks.copy(wave = stressPicks.wave.copy(tint = WaveTint.CUSTOM.name, colors = custom)))
        val pal = e.manifest.obj("palette")
        assertEquals("#010203", pal.text("canvas"))
        assertEquals("#070809", pal.text("accentSecondary"))
        assertEquals("#7A5CFF", pal.text("accent"))
        assertEquals(listOf("#010203", "#040506"), (e.manifest.obj("wave").getValue("gradient") as JsonArray).map { it.jsonPrimitive.content })
        // The pack's GAMES accent still shows (the glow follows the pack under a tint).
        assertEquals("#00FF00", e.manifest.obj("categoryOverrides").obj("GAMES").obj("palette").text("accent"))
        assertEquals(listOf("the Custom wave colors"), e.notes)
    }

    @Test fun theCurrentLookOfTheBuiltInPackWithAColorThemeEffectsAndLayout() {
        val pack = default()
        val picks = LookPicks(
            user = UserLook(effects = EffectOverrides(foregroundParticles = "SNOW"), layout = LayoutOverrides(itemSpacingDp = 20f, focusedScale = 1.2f)),
            colorTheme = XrossTheme.LAVENDER.name,
        )
        val e = LookExporter.export(pack, ExportScope.CurrentLook, "Mine", date, picks = picks)
        checkShape(e)
        assertEquals("Mine", e.name)
        assertEquals("SNOW", e.manifest.obj("effects").text("foregroundParticles"))
        assertEquals(20f, e.manifest.obj("layout").text("itemSpacingDp").toFloat())
        assertEquals(1.2f, e.manifest.obj("layout").text("focusedScale").toFloat())
        assertEquals(ThemeManifest.colorText(XrossTheme.LAVENDER.palette().top), e.manifest.obj("palette").text("canvas"))
        // The pack's own files, nothing else.
        assertEquals(pack.files, e.paths())
        assertTrue(e.manifest.obj("surfaces").containsKey("FLAT_GLASS"))
    }

    @Test fun theUsersWallpaperIsCopiedUnderAFreeNameAndReplacesEveryPacksWallpaper() {
        val pack = stress()
        val picks = stressPicks.copy(user = stressPicks.user.copy(customWallpaper = "content://media/external/images/media/7"), wallpaperType = "jpeg")
        val e = LookExporter.export(pack, ExportScope.CurrentLook, "", date, picks = picks)
        assertEquals("wallpaper.jpg", e.manifest.obj("effects").text("wallpaperPath"))
        assertTrue(ExportSourceIsWallpaper(e))
        // No other wallpaper is read in any context.
        assertNull(e.manifest.obj("categoryOverrides")["MUSIC"]?.jsonObject?.get("effects"))
        assertEquals(1, (e.manifest.getValue("rules") as JsonArray).size)
        for (p in listOf("art/base.png", "art/vinyl.png", "art/late.jpg")) assertFalse(p, p in e.paths())

        // Unreadable (no type): left out, the pack's wallpaper stays.
        val unread = LookExporter.export(pack, ExportScope.CurrentLook, "", date, picks = picks.copy(wallpaperType = null))
        assertEquals("art/base.png", unread.manifest.obj("effects").text("wallpaperPath"))
        assertTrue(unread.files.none { it.source == ExportSource.UserWallpaper })
    }

    private fun ExportSourceIsWallpaper(e: LookExport) = e.files.single { it.source == ExportSource.UserWallpaper }.path == "wallpaper.jpg"

    @Test fun nothingSetIsTheFlattenedVariantWithTheWaveValues() {
        val pack = stress()
        val e = LookExporter.export(pack, ExportScope.CurrentLook, "", date, picks = LookPicks(variant = "day"))
        val variant = LookExporter.export(pack, ExportScope.Variant("day"), "", date)
        val drop = identity + setOf("wave", "components")
        assertEquals(JsonObject(variant.manifest - drop), JsonObject(e.manifest - drop))
        assertEquals(JsonPrimitive("STATIC_NEON"), e.manifest.obj("effects")["focusFrameStyle"])
    }

    // endregion
}
