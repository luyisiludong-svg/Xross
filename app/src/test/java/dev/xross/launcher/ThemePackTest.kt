package dev.xross.launcher

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.audio.UiCue
import dev.xross.launcher.audio.UiSound
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.model.GlyphKey
import dev.xross.launcher.storage.XrossEnvironment
import dev.xross.launcher.storage.XrossLayout
import dev.xross.launcher.theme.pack.BorderKind
import dev.xross.launcher.theme.pack.PackSource
import dev.xross.launcher.theme.pack.SurfaceLimits
import dev.xross.launcher.theme.pack.SurfacePaint
import dev.xross.launcher.theme.pack.SurfacePreset
import dev.xross.launcher.theme.pack.SurfaceShape
import dev.xross.launcher.theme.pack.SurfaceSpec
import dev.xross.launcher.theme.pack.resolveSurface
import kotlinx.serialization.json.jsonObject
import dev.xross.launcher.theme.pack.SoundRole
import dev.xross.launcher.theme.pack.SoundSource
import dev.xross.launcher.theme.pack.SvgIcon
import dev.xross.launcher.theme.pack.ThemeIcon
import dev.xross.launcher.theme.pack.ThemeManifest
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.ThemeRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Theme packs: the built-in Default pack (the bundled icons and sounds), discovery of packs in
 * `Xross/themes/`, partial packs inheriting from Default, case-insensitive and contained file
 * lookups, the SVG icon reader, and the fixed UI sound mapping (cursor.wav for the D-pad,
 * `01. opening.wav` at startup, system_ng for errors).
 */
@RunWith(RobolectricTestRunner::class)
class ThemePackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    // region Default pack and audio mapping

    @Test fun defaultPackShipsEveryIconAndSound() {
        val theme = loader.load(ThemePackLoader.DEFAULT_ID)
        assertEquals("Default", theme.info.name)
        assertTrue(theme.info.builtIn)
        for (key in GlyphKey.entries) assertTrue("icon for $key", theme.icons[key] is ThemeIcon.Vector)
        for (role in SoundRole.entries) assertTrue("sound for $role", theme.sounds[role] is SoundSource.Asset)
        assertNull("no palette of its own", theme.palette)
        assertEquals(5, theme.wave?.lineCount)
        assertTrue(theme.problems.isEmpty())
    }

    @Test fun uiSoundsMapToTheirFixedRoles() {
        assertEquals(SoundRole.CURSOR, UiSound.MOVE.role) // every D-pad step
        assertEquals(SoundRole.SYSTEM_NG, UiSound.ERROR.role) // errors and refused actions
        assertEquals(SoundRole.OPENING, UiCue.OPENING.role) // startup
        assertEquals("cursor.wav", SoundRole.CURSOR.file)
        assertEquals("01. opening.wav", SoundRole.OPENING.file)
        assertEquals("system_ng.wav", SoundRole.SYSTEM_NG.file)

        val theme = loader.load(ThemePackLoader.DEFAULT_ID)
        fun path(role: SoundRole) = (theme.sounds[role] as SoundSource.Asset).path
        assertEquals("themes/default/sounds/cursor.wav", path(SoundRole.CURSOR))
        assertEquals("themes/default/sounds/01. opening.wav", path(SoundRole.OPENING))
        assertEquals("themes/default/sounds/system_ng.wav", path(SoundRole.SYSTEM_NG))
        assertEquals(0.5f, theme.gains[SoundRole.CURSOR]!!, 1e-6f)

        val sounds = UiSounds(context)
        sounds.useTheme(theme)
        assertEquals(SoundSource.Asset("themes/default/sounds/cursor.wav"), sounds.sourceOf(UiSound.MOVE))
        assertEquals(SoundSource.Asset("themes/default/sounds/system_ng.wav"), sounds.sourceOf(UiSound.ERROR))
        // The old silent placeholder and the old boot cue are gone from the APK.
        val shipped = context.assets.list("themes/default/sounds")!!.toList()
        assertFalse(shipped.any { it.contains("snd_error") || it.contains("coldboot") })
        assertTrue("01. opening.wav" in shipped)
    }

    // endregion

    // region Folder packs

    /** A folder pack; [manifest] is the body of a v2 `theme.json` without the braces' version line. */
    private fun pack(id: String, manifest: String, files: Map<String, ByteArray> = emptyMap()): File =
        rawPack(id, manifest.replaceFirst("{", """{"schemaVersion": 2, """), files)

    private fun rawPack(id: String, manifest: String, files: Map<String, ByteArray> = emptyMap()): File {
        val dir = File(themes, id).apply { mkdirs() }
        File(dir, "theme.json").writeText(manifest)
        for ((path, bytes) in files) File(dir, path).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        return dir
    }

    private val wav = "RIFF0000WAVEfmt ".toByteArray()

    @Test fun discoversFolderPacksAfterDefaultAndSkipsBrokenOnes() {
        pack("sakura", """{"name": "Sakura", "author": "me", "palette": {"canvas": "#2E0A22", "accentSecondary": "#FFC6EA"}}""")
        rawPack("broken", """{"schemaVersion": 2, "name": "Broken",""")
        pack("nameless", """{"author": "x"}""")
        rawPack("old", """{"name": "Old v1", "palette": {"top": "#000000", "bottom": "#111111", "wave": "#FFFFFF"}}""")
        File(themes, "no-manifest").mkdirs()
        val scan = loader.scan()
        assertEquals(listOf("default", "sakura"), scan.packs.map { it.id })
        assertTrue(scan.packs[1].source is PackSource.Folder)
        // Skipped folders are reported, with the reason: v1 packs aren't read.
        val reasons = scan.rejected.associate { it.id to it.reason }
        assertEquals(setOf("broken", "nameless", "old"), reasons.keys)
        assertTrue(reasons["old"]!!.contains("schemaVersion"))
        assertTrue(reasons["broken"]!!.startsWith("theme.json"))
    }

    @Test fun aPartialPackInheritsEverythingElseFromDefault() {
        pack(
            "tiny",
            """{"name": "Tiny", "audio": {"sounds": {"cursor": "Tick.WAV"}, "soundGains": {"cursor": 0.4}}, "wave": {"lineCount": 9, "turbulence": 1.5}}""",
            mapOf("SOUNDS/tick.wav" to wav, "icons/Settings.SVG" to SVG.toByteArray()),
        )
        val theme = loader.load("tiny")
        assertEquals("tiny", theme.id)
        // Case-insensitive lookups (FAT cards, hand-made packs).
        assertEquals(SoundSource.Disk::class, theme.sounds[SoundRole.CURSOR]!!::class)
        assertTrue((theme.sounds[SoundRole.CURSOR] as SoundSource.Disk).file.name.equals("tick.wav", ignoreCase = true))
        assertEquals(0.4f, theme.gains[SoundRole.CURSOR]!!, 1e-6f)
        // What it lacks comes from Default, gains included.
        assertEquals(SoundSource.Asset("themes/default/sounds/system_ng.wav"), theme.sounds[SoundRole.SYSTEM_NG])
        assertEquals(0.6f, theme.gains[SoundRole.SYSTEM_NG]!!, 1e-6f)
        assertTrue(theme.icons[GlyphKey.SETTINGS] is ThemeIcon.Vector)
        assertEquals(GlyphKey.entries.size, theme.icons.size)
        assertEquals(9, theme.wave?.lineCount)
        assertEquals(1.5f, theme.wave?.turbulence)
    }

    @Test fun pngIconsAndPalettesLoad() {
        val png = java.io.ByteArrayOutputStream().also {
            Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        pack(
            "pastel",
            """{"name": "Pastel", "palette": {"canvas": "#3B3560", "accent": "#FDE2FF", "accentSecondary": "#FFF5D6"}, "wave": {"gradient": ["#3B3560", "#A7C7E7"]}, "icons": {"games": "pad.png"}}""",
            mapOf("icons/pad.png" to png),
        )
        val theme = loader.load("pastel")
        val icon = theme.icons[GlyphKey.GAMES] as ThemeIcon.Bitmap
        assertTrue("downsampled", icon.image.width <= ThemePackLoader.ICON_PX)
        // canvas → top, the wave gradient's last stop → bottom, accentSecondary → wave, accent → glow.
        assertEquals(0xFF3B3560.toInt(), theme.palette!!.top)
        assertEquals(0xFFA7C7E7.toInt(), theme.palette!!.bottom)
        assertEquals(0xFFFFF5D6.toInt(), theme.palette!!.wave)
        assertEquals(0xFFFDE2FF.toInt(), theme.palette!!.glow)
    }

    @Test fun filesOutsideThePackAreNeverRead() {
        File(themes, "secret.wav").writeBytes(wav)
        pack("sneaky", """{"name": "Sneaky", "audio": {"sounds": {"cursor": "../../secret.wav"}}, "typography": {"body": "/etc/fonts.xml"}, "icons": {"games": "../../../x.svg"}}""")
        val theme = loader.load("sneaky")
        assertEquals(SoundSource.Asset("themes/default/sounds/cursor.wav"), theme.sounds[SoundRole.CURSOR])
        assertNull(theme.fonts.body)
        assertTrue(theme.problems.isNotEmpty())
        assertNull(ThemePackLoader.inside(File(themes, "sneaky"), "../secret.wav"))
        assertNull(ThemePackLoader.inside(File(themes, "sneaky"), ""))
    }

    @Test fun badPalettesAndUnknownIdsFallBack() {
        pack("bad", """{"name": "Bad", "palette": {"canvas": "red", "accent": "#fff"}}""")
        val bad = loader.load("bad")
        assertNull(bad.palette)
        assertTrue(bad.problems.any { it.contains("Palette") })
        assertEquals("default", loader.load("does-not-exist").id)
        assertEquals(0xFFAABBCC.toInt(), ThemeManifest.parseColor("#abc".replace("abc", "AABBCC")))
        assertEquals(0xFFFFFFFF.toInt(), ThemeManifest.parseColor("#fff"))
        assertEquals(0x80112233.toInt(), ThemeManifest.parseColor("80112233"))
        assertNull(ThemeManifest.parseColor("#12"))
    }

    @Test fun runtimeSwitchesPacksAndFallsBackToDefault() = runBlocking {
        pack("sakura", """{"name": "Sakura"}""")
        rawPack("old", """{"name": "Old"}""")
        val runtime = ThemeRuntime(loader)
        assertEquals("default", runtime.active.value.id)
        assertEquals(listOf("default", "sakura"), runtime.rediscover().map { it.id })
        assertEquals("sakura", runtime.select("sakura").id)
        assertEquals("default", runtime.select("gone").id)
        assertEquals(listOf("old"), runtime.rejected.value.map { it.id })
        assertEquals("default", runtime.select("old").id) // a v1 pack can't be activated
    }

    // endregion

    // region Theme 2.0 (schema v2): imports, surfaces, aliases, hot reload

    @Test fun importsMergeUnderThemeJson() {
        pack(
            "split",
            """{"name": "Split", "imports": ["surfaces.json", "extra.json"], "palette": {"canvas": "#101010"}, "effects": {"surfaceStyle": "card"}}""",
            mapOf(
                "surfaces.json" to """{"surfaces": {"card": {"shape": {"type": "CHAMFERED", "cutDp": [12, 0]}}}, "palette": {"canvas": "#FFFFFF", "accent": "#FF0000"}}""".toByteArray(),
                "extra.json" to """{"wave": {"lineCount": 3}, "imports": ["nested.json"]}""".toByteArray(),
            ),
        )
        val theme = loader.load("split")
        assertEquals("split", theme.id)
        assertEquals(0xFF101010.toInt(), theme.palette!!.top) // theme.json wins over the import
        assertEquals(0xFFFF0000.toInt(), theme.palette!!.glow) // keys only the import has are kept
        assertEquals(3, theme.wave?.lineCount)
        assertEquals("card", theme.surfaceId)
        assertEquals(SurfaceShape.Chamfered(listOf(12f, 0f, 12f, 0f)), theme.surface().shape)
    }

    @Test fun importsMustBeJsonInsideThePack() {
        File(themes, "outside.json").writeText("""{"name": "x"}""")
        pack("escape", """{"name": "Escape", "imports": ["../outside.json"]}""")
        pack("missing", """{"name": "Missing", "imports": ["nope.json"]}""")
        pack("notjson", """{"name": "NotJson", "imports": ["sounds/cursor.wav"]}""", mapOf("sounds/cursor.wav" to wav))
        val rejected = loader.scan().rejected.associate { it.id to it.reason }
        assertEquals(setOf("escape", "missing", "notjson"), rejected.keys)
        assertTrue(rejected["missing"]!!.contains("nope.json"))
    }

    @Test fun defaultPackShipsTheFourSurfacePresets() {
        val theme = loader.load(ThemePackLoader.DEFAULT_ID)
        assertEquals(SurfacePreset.entries.map { it.name }, theme.surfaces.keys.toList())
        assertEquals(SurfacePreset.FLAT_GLASS.name, theme.surfaceId)
        val glass = theme.surface()
        assertEquals("Flat glass", glass.label)
        assertEquals(SurfaceShape.Component, glass.shape) // each card keeps its corners
        assertTrue(glass.fill is SurfacePaint.Linear)
        assertEquals(BorderKind.INNER, glass.borders.single().kind)
        assertTrue(glass.borders.single().paint is SurfacePaint.Sweep)
        assertEquals(SurfaceShape.Rectangle, theme.surface("EINK_HIGH_CONTRAST").shape)
        assertEquals(BorderKind.OUTER, theme.surface("Y2K_GLOSSY_BEZEL").borders.first().kind)
        assertNotNull(theme.surface("OLED_TRUE_BLACK").glow)
        assertEquals(glass, theme.surface("not-a-surface")) // unknown ids fall back to the pack's
    }

    @Test fun aPackAddsSurfacesAndInheritsThePresets() {
        pack(
            "neon",
            """{"name": "Neon", "effects": {"surfaceStyle": "cyberpunkChamfer"}, "surfaces": {"cyberpunkChamfer": {
                "shape": {"type": "CHAMFERED", "cutDp": [12, 0, 12, 0]},
                "fill": {"type": "SOLID", "color": "#0D0B18", "opacity": 0.85},
                "borders": [{"type": "OUTER", "widthDp": 1.5, "brush": {"type": "SWEEP_GRADIENT", "colors": ["#00F0FF", "#FF003C", "#00F0FF"]}}],
                "glow": {"color": "#00F0FF", "radiusDp": 10, "intensity": 0.75}}}}""",
        )
        val theme = loader.load("neon")
        assertEquals(SurfacePreset.entries.size + 1, theme.surfaces.size)
        val s = theme.surface()
        assertEquals("cyberpunkChamfer", s.id)
        assertEquals(0xD90D0B18.toInt(), (s.fill as SurfacePaint.Solid).argb) // 0.85 opacity
        assertEquals(10f, s.glow!!.blurDp)
        assertTrue(theme.problems.isEmpty())
    }

    @Test fun surfacesAreClampedAndBadPartsReported() {
        val problems = ArrayList<String>()
        val spec = ThemeManifest.JSON.decodeFromString(
            SurfaceSpec.serializer(),
            """{"shape": {"type": "BLOB"}, "fill": {"type": "SOLID", "color": "pink"},
                "borders": [{"widthDp": 99}, {"side": "TOP"}, {"type": "OUTER"}, {"type": "INNER"}],
                "shadows": [{}, {}, {}], "glow": {"radiusDp": 500}, "backdropBlurDp": -3}""",
        )
        val s = resolveSurface("x", spec, problems)
        assertEquals(SurfaceShape.Component, s.shape)
        assertNull(s.fill)
        assertEquals(SurfaceLimits.MAX_BORDERS, s.borders.size)
        assertEquals(SurfaceLimits.MAX_BORDER_DP, s.borders[0].widthDp)
        assertEquals(BorderKind.TOP, s.borders[1].kind)
        assertEquals(SurfaceLimits.MAX_SHADOWS, s.shadows.size)
        assertEquals(SurfaceLimits.MAX_GLOW_DP, s.glow!!.blurDp)
        assertEquals(0f, s.backdropBlurDp)
        assertTrue(problems.any { it.contains("BLOB") })
        assertTrue(problems.any { it.contains("pink") })
        assertTrue(problems.any { it.contains("borders") })
    }

    @Test fun specSoundNamesAreAliases() {
        assertEquals(SoundRole.BACK, SoundRole.byKey("cancel"))
        assertEquals(SoundRole.OPTION, SoundRole.byKey("optionOpen"))
        assertEquals(SoundRole.GAMEBOOT, SoundRole.byKey("gameLaunch"))
        assertEquals(SoundRole.TROPHY, SoundRole.byKey("achievementUnlock"))
        assertEquals(SoundRole.SYSTEM_NG, SoundRole.byKey("SYSTEM_NG"))
        pack(
            "aliased",
            """{"name": "Aliased", "audio": {"sounds": {"cancel": "audio/no.wav", "whoosh": "x.wav"}}}""",
            mapOf("audio/no.wav" to wav),
        )
        val theme = loader.load("aliased")
        assertEquals("no.wav", (theme.sounds[SoundRole.BACK] as SoundSource.Disk).file.name) // a path from the pack root
        assertTrue(theme.problems.any { it.contains("whoosh") })
    }

    @Test fun hotReloadAppliesEditsAndKeepsTheLastGoodTheme() = runBlocking {
        val dir = pack("live", """{"name": "Live", "wave": {"lineCount": 4}}""")
        val runtime = ThemeRuntime(loader)
        runtime.rediscover()
        runtime.select("live")
        val first = runtime.active.value
        assertNull(runtime.reloadIfChanged()) // nothing changed

        File(dir, "theme.json").writeText("""{"schemaVersion": 2, "name": "Live", "wave": {"lineCount": 8}}""")
        File(dir, "theme.json").setLastModified(System.currentTimeMillis() + 5_000)
        assertNull(runtime.hotReload())
        assertEquals(8, runtime.active.value.wave?.lineCount)
        assertTrue(runtime.active.value.revision > first.revision)

        // A broken save keeps the last good theme and says where the error is.
        File(dir, "theme.json").writeText(listOf("{", "  \"schemaVersion\": 2,", "  \"name\": \"Live\",,", "}").joinToString("\n"))
        File(dir, "theme.json").setLastModified(System.currentTimeMillis() + 10_000)
        val error = runtime.reloadIfChanged()
        assertNotNull(error)
        assertTrue(error, error!!.contains("line 3"))
        assertEquals(8, runtime.active.value.wave?.lineCount)
        assertNull("the same error isn't repeated on every resume", runtime.reloadIfChanged())
    }

    @Test fun mergeIsDeepForObjectsOnly() {
        val base = ThemeManifest.JSON.parseToJsonElement("""{"a": {"x": 1, "y": 2}, "b": [1, 2], "c": 1}""").jsonObject
        val over = ThemeManifest.JSON.parseToJsonElement("""{"a": {"y": 3}, "b": [9]}""").jsonObject
        assertEquals(
            ThemeManifest.JSON.parseToJsonElement("""{"a": {"x": 1, "y": 3}, "b": [9], "c": 1}"""),
            ThemeManifest.merge(base, over),
        )
    }

    @Test fun themesFolderIsBootstrappedWithNomedia() {
        val env = XrossEnvironment(context, writeAccess = { true }, primaryRoot = { File(tmp.root, "emulated/0/Xross") })
        val layout = env.current
        assertTrue(File(layout.themes, XrossLayout.NOMEDIA).isFile)
        assertTrue(File(layout.media, XrossLayout.NOMEDIA).isFile)
    }

    // endregion

    // region SVG icons

    @Test fun svgReaderHandlesTheShippedIconShape() {
        val icon = SvgIcon.parse(SVG)!!
        assertEquals(24f, icon.viewportWidth)
        val group = icon.root.single() as VectorGroup
        assertEquals(-2f, group.translationX)
        assertEquals(-3f, group.translationY)
        // The black backdrop rect is dropped; the white path stays.
        assertEquals(1, group.count { it is VectorPath })
    }

    @Test fun svgReaderUnderstandsBasicShapesAndRejectsJunk() {
        val shapes = SvgIcon.parse(
            """<svg viewBox="10 10 20 20"><circle cx="20" cy="20" r="5"/><rect x="11" y="11" width="2" height="2" fill="#fff"/>
               <polygon points="12,12 14,12 13,14"/><g transform="rotate(45)"><path d="M0 0L1 1"/></g></svg>""",
        )!!
        val root = shapes.root.single() as VectorGroup // the viewBox offset
        assertEquals(-10f, root.translationX)
        assertEquals(3, root.count { it is VectorPath }) // rotated group skipped
        assertNull(SvgIcon.parse("not xml"))
        assertNull(SvgIcon.parse("<svg viewBox=\"0 0 0 0\"><path d=\"M0 0\"/></svg>"))
        assertNull(SvgIcon.parse("<svg viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\" fill=\"#000\"/></svg>")) // nothing drawn
        assertNull(SvgIcon.parse("<html/>"))
        assertEquals(SvgIcon.Transform(5f, 6f, 2f, 2f), SvgIcon.transform("translate(5 6) scale(2)"))
        assertNull(SvgIcon.transform("skewX(10)"))
    }

    // endregion

    private companion object {
        const val SVG = """<!-- test -->
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
  <rect width="24" height="24" fill="#000000"/>
  <g transform="translate(-2 -3)">
    <path fill="#FFFFFF" d="M4 4H20V20H4Z"/>
    <rect width="2" height="2" fill="black"/>
  </g>
</svg>"""
    }
}
