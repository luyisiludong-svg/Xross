package dev.xross.launcher

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.audio.UiCue
import dev.xross.launcher.audio.UiSound
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.state.GlyphKey
import dev.xross.launcher.storage.XrossEnvironment
import dev.xross.launcher.storage.XrossLayout
import dev.xross.launcher.theme.pack.PackSource
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
        assertEquals(0.55f, theme.gains[SoundRole.CURSOR]!!, 1e-6f)

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

    private fun pack(id: String, manifest: String, files: Map<String, ByteArray> = emptyMap()): File {
        val dir = File(themes, id).apply { mkdirs() }
        File(dir, "theme.json").writeText(manifest)
        for ((path, bytes) in files) File(dir, path).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        return dir
    }

    private val wav = "RIFF0000WAVEfmt ".toByteArray()

    @Test fun discoversFolderPacksAfterDefaultAndSkipsBrokenOnes() {
        pack("sakura", """{"name": "Sakura", "author": "me", "palette": {"top": "#2E0A22", "bottom": "#B04C8E", "wave": "#FFC6EA"}}""")
        pack("broken", """{"name": "Broken",""")
        pack("nameless", """{"author": "x"}""")
        File(themes, "no-manifest").mkdirs()
        val found = loader.discover()
        assertEquals(listOf("default", "sakura"), found.map { it.id })
        assertTrue(found[1].source is PackSource.Folder)
    }

    @Test fun aPartialPackInheritsEverythingElseFromDefault() {
        pack(
            "tiny",
            """{"name": "Tiny", "sounds": {"cursor": "Tick.WAV"}, "soundGains": {"cursor": 0.4}, "wave": {"lineCount": 9, "turbulence": 1.5}}""",
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
        assertEquals(0.8f, theme.gains[SoundRole.SYSTEM_NG]!!, 1e-6f)
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
            """{"name": "Pastel", "palette": {"top": "#3B3560", "bottom": "#A7C7E7", "wave": "#FFF5D6", "glow": "#FDE2FF"}, "icons": {"games": "pad.png"}}""",
            mapOf("icons/pad.png" to png),
        )
        val theme = loader.load("pastel")
        val icon = theme.icons[GlyphKey.GAMES] as ThemeIcon.Bitmap
        assertTrue("downsampled", icon.image.width <= ThemePackLoader.ICON_PX)
        assertEquals(0xFF3B3560.toInt(), theme.palette!!.top)
        assertEquals(0xFFFDE2FF.toInt(), theme.palette!!.glow)
    }

    @Test fun filesOutsideThePackAreNeverRead() {
        File(themes, "secret.wav").writeBytes(wav)
        pack("sneaky", """{"name": "Sneaky", "sounds": {"cursor": "../../secret.wav"}, "font": "/etc/fonts.xml", "icons": {"games": "../../../x.svg"}}""")
        val theme = loader.load("sneaky")
        assertEquals(SoundSource.Asset("themes/default/sounds/cursor.wav"), theme.sounds[SoundRole.CURSOR])
        assertNull(theme.font)
        assertTrue(theme.problems.isNotEmpty())
        assertNull(ThemePackLoader.inside(File(themes, "sneaky"), "../secret.wav"))
        assertNull(ThemePackLoader.inside(File(themes, "sneaky"), ""))
    }

    @Test fun badPalettesAndUnknownIdsFallBack() {
        pack("bad", """{"name": "Bad", "palette": {"top": "red", "bottom": "#000", "wave": "#fff"}}""")
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
        val runtime = ThemeRuntime(loader)
        assertEquals("default", runtime.active.value.id)
        assertEquals(listOf("default", "sakura"), runtime.rediscover().map { it.id })
        assertEquals("sakura", runtime.select("sakura").id)
        assertEquals("default", runtime.select("gone").id)
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
