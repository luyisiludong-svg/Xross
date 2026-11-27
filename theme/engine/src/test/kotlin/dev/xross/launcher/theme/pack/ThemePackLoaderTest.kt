package dev.xross.launcher.theme.pack

import dev.xross.launcher.theme.pack.PackSource
import dev.xross.launcher.theme.pack.SoundRole
import dev.xross.launcher.theme.pack.SoundSource
import dev.xross.launcher.theme.pack.ThemeManifest
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.iconId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import dev.xross.launcher.model.GlyphKey
import dev.xross.launcher.theme.shader.ShaderPreset

/**
 * 2b.8-prep: a loaded pack keeps the merged manifest JSON it was decoded from
 * ([dev.xross.launcher.theme.pack.LoadedTheme.sourceJson]) and the pack files it read
 * ([dev.xross.launcher.theme.pack.LoadedTheme.files]), for the 2b.8b export.
 */
class ThemePackLoaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { TestAssets.loader { themes } }

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun sourceJsonIsTheImportsMergedUnderTheManifestWithRulesAddedUp() {
        StressPack.write(themes)
        val t = loader.load(StressPack.ID)
        assertEquals(StressPack.ID, t.id)
        assertTrue(t.problems.toString(), t.problems.isEmpty())

        val root = parse(StressPack.THEME_JSON)
        val surfaces = parse(StressPack.SURFACES_JSON)
        val rules = parse(StressPack.RULES_JSON)
        // The merge readManifest documents: imports first (rules aside), the manifest on top, rules concatenated.
        var expected = ThemeManifest.merge(JsonObject(emptyMap()), surfaces)
        expected = ThemeManifest.merge(expected, JsonObject(rules - "rules"))
        expected = ThemeManifest.merge(expected, JsonObject(root - "rules"))
        expected = JsonObject(expected + ("rules" to JsonArray(rules.getValue("rules") as JsonArray)))
        assertEquals(expected, t.sourceJson)
        assertEquals("stress_glass", t.sourceJson.getValue("surfaces").jsonObject.keys.single())
        assertEquals(2, (t.sourceJson.getValue("rules") as JsonArray).size)
        // The discovered pack carries the same JSON as the loaded one.
        assertEquals(t.sourceJson, loader.discover().single { it.id == StressPack.ID }.sourceJson)
        // And it decodes back to the manifest the pack was loaded from.
        assertEquals(t.info.manifest.copy(schemaProblems = emptyList()), ThemeManifest.JSON.decodeFromJsonElement(ThemeManifest.serializer(), t.sourceJson))
    }

    @Test fun theBuiltInPacksSourceJsonHasItsSurfacesImport() {
        val t = loader.load(ThemePackLoader.DEFAULT_ID)
        val surfaces = parse(TestAssets.platform.openAsset("${ThemePackLoader.DEFAULT_DIR}/surfaces.json").bufferedReader().use { it.readText() })
        val manifest = parse(TestAssets.platform.openAsset("${ThemePackLoader.DEFAULT_DIR}/theme.json").bufferedReader().use { it.readText() })
        assertEquals(ThemeManifest.merge(ThemeManifest.merge(JsonObject(emptyMap()), surfaces), JsonObject(manifest - "rules")), t.sourceJson)
    }

    @Test fun filesAreExactlyWhatTheStressPackReads() {
        val dir = StressPack.write(themes)
        val t = loader.load(StressPack.ID)
        assertEquals(PackSource.Folder(dir), t.info.source)
        assertEquals(StressPack.USED, t.files)
        // Nothing unreferenced, nothing outside the pack, every one a file in it.
        assertTrue(t.files.none { it in StressPack.UNUSED })
        for (path in t.files) {
            assertTrue(path, File(dir, path).isFile)
            assertTrue(path, !path.startsWith("/") && ".." !in path.split('/'))
        }
    }

    @Test fun theBuiltInPacksFilesAreItsAssetPaths() {
        val t = loader.load(ThemePackLoader.DEFAULT_ID)
        val dir = ThemePackLoader.DEFAULT_DIR
        val shipped = TestAssets.platform.listAssets("$dir/icons").orEmpty().map { "icons/$it" }
        // An icon is read when a glyph has its name (icons/power.svg ships but no glyph draws it).
        val icons = GlyphKey.entries.map { "icons/${it.iconId}.svg" }.filter { it in shipped }
        assertTrue("power.svg isn't a glyph", "icons/power.svg" in shipped && "icons/power.svg" !in icons)
        // Every sound in the folder is a role's.
        val sounds = TestAssets.platform.listAssets("$dir/sounds").orEmpty().map { "sounds/$it" }
        assertEquals((icons + sounds).toSet(), t.files)
        for (path in t.files) TestAssets.platform.openAsset("$dir/$path").close()
    }

    @Test fun theBundledXrossPackIsListedAfterDefaultAndAFolderCantTakeItsId() {
        File(themes, "xross").apply { mkdirs() }.resolve("theme.json").writeText("""{"schemaVersion": 2, "name": "Impostor"}""")
        File(themes, "sakura").apply { mkdirs() }.resolve("theme.json").writeText("""{"schemaVersion": 2, "name": "Sakura"}""")
        val packs = loader.discover()
        assertEquals(listOf("default", "xross", "blackhole", "memento_mori", "unit_terminal", "breakcore_err", "sakura"), packs.map { it.id })
        assertEquals("XMB", packs[0].name)
        val xross = packs[1]
        assertEquals("Xross", xross.name)
        assertEquals(PackSource.Builtin("themes/xross"), xross.source)
        assertTrue(xross.builtIn)
        // Loading by id reads the APK's copy, not the folder of the same name.
        assertEquals("Xross", loader.load("xross").info.name)
        assertEquals(0L, loader.fingerprint("xross"))
    }

    @Test fun theFormerXmbIdLoadsTheDefaultPackAndStaysReserved() {
        File(themes, "xmb").apply { mkdirs() }.resolve("theme.json").writeText("""{"schemaVersion": 2, "name": "Impostor"}""")
        assertEquals(listOf("default", "xross", "blackhole", "memento_mori", "unit_terminal", "breakcore_err"), loader.discover().map { it.id })
        val t = loader.load("xmb")
        assertEquals(ThemePackLoader.DEFAULT_ID, t.id)
        assertEquals("XMB", t.info.name)
        assertTrue(ThemePackLoader.isBuiltIn("XMB"))
    }

    @Test fun theBundledGargantuaPackLoadsItsShaderAndFontsFromTheApk() {
        // A folder of the same id is ignored: the APK's copy is the pack.
        File(themes, "Blackhole").apply { mkdirs() }.resolve("theme.json").writeText("""{"schemaVersion": 2, "name": "Impostor"}""")
        val t = loader.load("Blackhole")
        assertEquals(ThemePackLoader.BLACKHOLE_ID, t.id)
        assertEquals("Gargantua", t.info.name)
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        assertEquals(ShaderPreset.CUSTOM, t.shader.preset)
        assertTrue(t.customShaders.values.single().contains("Gargantua"))
        assertTrue("black_hole.frag" in t.files)
        assertTrue(t.fonts.display != null && t.fonts.body != null && t.fonts.mono != null)
    }

    @Test fun theBundledShowcasePacksLoadFromTheApkWithNoProblems() {
        val names = mapOf(
            ThemePackLoader.MEMENTO_MORI_ID to "MEMENTO//MORI",
            ThemePackLoader.UNIT_TERMINAL_ID to "UNIT//TERMINAL",
            ThemePackLoader.BREAKCORE_ERR_ID to "BREAKCORE.ERR",
        )
        for ((id, name) in names) {
            val t = loader.load(id)
            assertEquals(id, t.id)
            assertTrue(t.info.builtIn)
            assertEquals(name, t.info.name)
            assertTrue("$id: ${t.problems}", t.problems.isEmpty())
            assertEquals("$id draws its own shader", ShaderPreset.CUSTOM, t.shader.preset)
            assertTrue("$id has its surfaces", t.surfaces.keys.any { it !in SurfacePreset.entries.map { p -> p.name } })
        }
        // A bundled pack's wallpaper is drawn from a file the platform makes of its asset.
        val memento = loader.load(ThemePackLoader.MEMENTO_MORI_ID)
        assertTrue(memento.hasWallpaper)
        assertTrue("wallpapers/specimen.png" in memento.files)
    }

    @Test fun theBundledXrossPackLoadsEveryIconAndSoundFromItsOwnAssets() {
        val t = loader.load(ThemePackLoader.XROSS_ID)
        assertEquals("xross", t.id)
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        for (key in GlyphKey.entries) {
            val file = t.info.manifest.icons[key.iconId] ?: "${key.iconId}.svg"
            assertTrue("$key from icons/$file", "icons/$file" in t.files)
        }
        for (role in SoundRole.entries) {
            val src = t.sounds[role]
            assertTrue("$role: $src", src is SoundSource.Asset && src.path.startsWith("themes/xross/sounds/"))
        }
    }

    @Test fun aFolderPackDoesNotListWhatTheDefaultPackLendsIt() {
        val dir = File(themes, "tiny").apply { mkdirs() }
        File(dir, "theme.json").writeText("""{"schemaVersion": 2, "name": "Tiny"}""")
        File(dir, "sounds").mkdirs()
        File(dir, "sounds/ok.wav").writeBytes(ByteArray(32))
        val t = loader.load("tiny")
        assertEquals("tiny", t.id)
        assertEquals(setOf("sounds/ok.wav"), t.files)
        assertTrue("Default's icons still draw", t.icons.isNotEmpty())
    }

    @Test fun aMissingFileIsNotListed() {
        val dir = File(themes, "gaps").apply { mkdirs() }
        File(dir, "theme.json").writeText(
            """{"schemaVersion": 2, "name": "Gaps", "effects": {"wallpaperPath": "art/missing.png"}, "audio": {"bgmFile": "missing.ogg"}, "components": {"logo": "nope.svg"}}""",
        )
        val t = loader.load("gaps")
        assertEquals(emptySet<String>(), t.files)
    }
}
