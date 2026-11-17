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

    @Test fun theBundledXmbPackIsListedAfterDefaultAndAFolderCantTakeItsId() {
        File(themes, "xmb").apply { mkdirs() }.resolve("theme.json").writeText("""{"schemaVersion": 2, "name": "Impostor"}""")
        File(themes, "sakura").apply { mkdirs() }.resolve("theme.json").writeText("""{"schemaVersion": 2, "name": "Sakura"}""")
        val packs = loader.discover()
        assertEquals(listOf("default", "xmb", "sakura"), packs.map { it.id })
        val xmb = packs[1]
        assertEquals("XMB", xmb.name)
        assertEquals(PackSource.Builtin("themes/xmb"), xmb.source)
        assertTrue(xmb.builtIn)
        // Loading by id reads the APK's copy, not the folder of the same name.
        assertEquals("XMB", loader.load("xmb").info.name)
        assertEquals(0L, loader.fingerprint("xmb"))
    }

    @Test fun theBundledXmbPackLoadsEveryIconAndSoundFromItsOwnAssets() {
        val t = loader.load(ThemePackLoader.XMB_ID)
        assertEquals("xmb", t.id)
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        for (key in GlyphKey.entries) {
            val file = t.info.manifest.icons[key.iconId] ?: "${key.iconId}.svg"
            assertTrue("$key from icons/$file", "icons/$file" in t.files)
        }
        for (role in SoundRole.entries) {
            val src = t.sounds[role]
            assertTrue("$role: $src", src is SoundSource.Asset && src.path.startsWith("themes/xmb/sounds/"))
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
