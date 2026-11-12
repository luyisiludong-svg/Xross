package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MediaMenus
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.ThemeSetup
import dev.xross.launcher.theme.WallpaperSource
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.theme.pack.WallpaperRef
import dev.xross.launcher.ui.PreBlur
import dev.xross.launcher.ui.PreBlurCache
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
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
import kotlin.math.abs

/**
 * Theme 2.0 2b.4c: wallpapers (`wallpaper.png`, `effects.wallpaperPath` through the cascade, Set
 * as Xross wallpaper in `display.customWallpaper`) and the pre-blurred backdrop copies frosted
 * surfaces draw their slice of (the blur, its sizes and radius, and the copy cache).
 */
@RunWith(RobolectricTestRunner::class)
class BackdropTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    private fun pack(id: String, body: String, vararg files: String): File {
        val dir = File(themes, id).apply { mkdirs() }
        File(dir, "theme.json").writeText("""{"schemaVersion": 2, "name": "$id"${if (body.isBlank()) "" else ", $body"}}""")
        for (f in files) File(dir, f).apply { parentFile?.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
        return dir
    }

    // region Wallpaper references

    @Test fun wallpaperPathParsesNoneAndRejectsEscapes() {
        val problems = mutableListOf<String>()
        assertNull(WallpaperRef.of("theme", null, problems))
        assertNull(WallpaperRef.of("theme", "  ", problems))
        assertEquals(WallpaperRef.NONE, WallpaperRef.of("theme", "none", problems))
        assertEquals(WallpaperRef.NONE, WallpaperRef.of("theme", "OFF", problems))
        assertEquals(WallpaperRef("art/night.webp"), WallpaperRef.of("theme", "\\art\\night.webp", problems))
        assertTrue(problems.isEmpty())
        assertNull(WallpaperRef.of("theme", "../other/wallpaper.png", problems))
        assertNull(WallpaperRef.of("theme", "art/night.gif", problems))
        assertEquals(2, problems.size)
        assertTrue(problems[0], problems[0].contains("inside the pack"))
    }

    @Test fun aPacksWallpaperPngIsItsDefault() {
        pack("night", "", "Wallpaper.PNG")
        val t = loader.load("night")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        // Found whatever its case (the name is the disk's, or the canonical one on a case-blind disk).
        assertEquals("wallpaper.png", t.wallpaper.path?.lowercase())
        assertTrue(t.hasWallpaper)
        assertEquals("wallpaper.png", t.wallpaperFile(t.look().wallpaper)?.name?.lowercase())
    }

    @Test fun packsWithoutAWallpaperShowNone() {
        pack("plain", "")
        val t = loader.load("plain")
        assertEquals(WallpaperRef.NONE, t.wallpaper)
        assertFalse(t.hasWallpaper)
        assertNull(t.wallpaperFile(t.look().wallpaper))
        assertNull(WallpaperSource.resolve("", t, t.look()))
        // The built-in pack has none either.
        val d = loader.load(ThemePackLoader.DEFAULT_ID)
        assertEquals(WallpaperRef.NONE, d.look().wallpaper)
    }

    @Test fun variantsOverridesAndRulesPickTheirOwnWallpaper() {
        pack(
            "cascade",
            """
            "effects": {"wallpaper": "art/day.jpg"},
            "variants": [{"id": "dark", "effects": {"wallpaperPath": "art/night.webp"}}, {"id": "bare", "effects": {"wallpaperPath": "none"}}],
            "categoryOverrides": {"MUSIC": {"effects": {"wallpaperPath": "art/vinyl.png"}}},
            "rules": [{"match": {"category": "PHOTO"}, "apply": {"effects": {"wallpaperPath": "none"}}}]
            """,
            "art/day.jpg", "art/night.webp", "art/vinyl.png", "wallpaper.png",
        )
        val t = loader.load("cascade")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        fun file(variant: String = "", category: String? = null) = t.wallpaperFile(t.look(variant, category)?.wallpaper ?: WallpaperRef.NONE)?.name
        // effects.wallpaperPath beats the pack's wallpaper.png.
        assertEquals("day.jpg", file())
        assertEquals("night.webp", file("dark"))
        assertNull(file("bare"))
        assertEquals("vinyl.png", file("bare", "music"))
        assertNull(file(category = "photo"))
        assertEquals(setOf("art/day.jpg", "art/night.webp", "art/vinyl.png"), t.wallpapers.keys)
    }

    @Test fun aMissingWallpaperIsReportedAndShowsNone() {
        pack("broken", """"effects": {"wallpaperPath": "art/missing.png"}""")
        val t = loader.load("broken")
        assertTrue(t.problems.toString(), t.problems.any { it.contains("art/missing.png not found") })
        assertNull(t.wallpaperFile(t.look().wallpaper))
        assertFalse(t.hasWallpaper)
    }

    @Test fun xrossWallpaperBeatsThePacks() {
        val dir = pack("night", "", "wallpaper.jpg")
        val t = loader.load("night")
        val packWallpaper = WallpaperSource.resolve("", t, t.look())
        assertEquals(WallpaperSource.PackFile(File(dir, "wallpaper.jpg")), packWallpaper)
        val custom = WallpaperSource.resolve(" content://media/external/images/media/7 ", t, t.look())
        assertEquals(WallpaperSource.Custom("content://media/external/images/media/7"), custom)
        assertTrue((custom as WallpaperSource.Custom).isUri)
        // The pack's stays known, for when the user's photo can't be read any more.
        assertEquals(packWallpaper, WallpaperSource.packWallpaper(t, t.look()))
        assertFalse(WallpaperSource.Custom("/sdcard/Pictures/a.png").isUri)
        // The key changes with the file, so decoded and blurred copies are made again.
        val edited = WallpaperSource.PackFile(File(dir, "wallpaper.jpg"), modified = 1, bytes = 3)
        assertFalse(edited.key == packWallpaper!!.key)
    }

    // endregion

    // region Settings

    @Test fun customWallpaperIsSavedUnderDisplay() {
        val s = XrossSettings(customWallpaper = "content://media/external/images/media/42")
        val json = kotlinx.serialization.json.Json.parseToJsonElement(ConfigCodec.encodeSettings(s)).jsonObject
        assertEquals("content://media/external/images/media/42", json["display"]!!.jsonObject["customWallpaper"]!!.jsonPrimitive.content)
        assertEquals(s.customWallpaper, ConfigCodec.decodeSettings(XrossSettings(), ConfigCodec.encodeSettings(s)).customWallpaper)
        // Missing: the theme's wallpaper. Hand-edited: trimmed, and capped in length.
        assertEquals("", ConfigCodec.decodeSettings(XrossSettings(), """{"display": {}}""").customWallpaper)
        assertEquals("/sdcard/a.png", ConfigCodec.decodeSettings(XrossSettings(), """{"display": {"customWallpaper": "  /sdcard/a.png "}}""").customWallpaper)
        val long = "x".repeat(5000)
        assertEquals(ConfigCodec.MAX_WALLPAPER_CHARS, ConfigCodec.decodeSettings(XrossSettings(), """{"display": {"customWallpaper": "$long"}}""").customWallpaper.length)
    }

    @Test fun photoOptionsOfferBothWallpapersAndTheWayBack() {
        assertEquals(
            listOf(Command.SetXrossWallpaper(5), Command.SetWallpaper(5)),
            MediaMenus.photoOptions(5, customWallpaper = false).map { it.command },
        )
        assertEquals(Command.ResetXrossWallpaper, MediaMenus.photoOptions(5, customWallpaper = true).last().command)
    }

    @Test fun theWallpaperRowSaysWhereTheWallpaperComesFrom() {
        fun row(custom: String, packWallpaper: Boolean) = MenuTree(
            MenuSnapshot(
                settings = XrossSettings(customWallpaper = custom),
                themes = ThemeSetup(activeId = "night", activeName = "Night", packWallpaper = packWallpaper),
            ),
        ).list(MenuTree.DISPLAY).single { it.id == "disp:wallpaper" }

        assertEquals(NodeAction.Run(Command.ResetXrossWallpaper), row("content://x", true).action)
        assertTrue(row("", true).subtitle!!.startsWith("From Night"))
        assertEquals(NodeAction.None, row("", true).action)
        assertTrue(row("", false).subtitle!!.startsWith("None"))
    }

    // endregion

    // region Pre-blur

    @Test fun theSmallCopyIs192WideAndNeverUpscaled() {
        assertEquals(192 to 108, PreBlur.smallSize(1920, 1080))
        assertEquals(192 to 341, PreBlur.smallSize(1080, 1920))
        assertEquals(100 to 50, PreBlur.smallSize(100, 50))
        assertEquals(192 to 1, PreBlur.smallSize(4000, 2))
        assertEquals(0 to 0, PreBlur.smallSize(0, 10))
    }

    @Test fun theRadiusScalesFromScreenToSmallPixels() {
        // 14 dp at 3x = 42 px on a 1920 px wide drawing of a 192 px copy: about 4 small px.
        assertEquals(4, PreBlur.radiusFor(42f, 1920f, 192))
        assertEquals(1, PreBlur.radiusFor(1f, 1920f, 192))
        assertEquals(PreBlur.MAX_RADIUS, PreBlur.radiusFor(10_000f, 1920f, 192))
        assertEquals(0, PreBlur.radiusFor(0f, 1920f, 192))
        assertEquals(0, PreBlur.radiusFor(42f, 0f, 192))
    }

    @Test fun cropRectCentersAndCovers() {
        // A 16:9 image on a 4:3 box: scaled to the height, cut at the sides.
        val r = PreBlur.cropRect(1600f, 900f, 1200f, 900f)
        assertArrayEquals(floatArrayOf(-200f, 0f, 1600f, 900f), r, 0.01f)
        // Portrait art on a landscape screen: scaled to the width, cut at top and bottom.
        val p = PreBlur.cropRect(100f, 200f, 400f, 300f)
        assertArrayEquals(floatArrayOf(0f, -250f, 400f, 800f), p, 0.01f)
    }

    @Test fun opacityIsCheckedPerPixel() {
        assertTrue(PreBlur.isOpaque(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())))
        assertFalse(PreBlur.isOpaque(intArrayOf(0xFF000000.toInt(), 0xFEFFFFFF.toInt())))
    }

    @Test fun aUniformImageStaysUniform() {
        val c = 0xFF336699.toInt()
        val out = PreBlur.blur(IntArray(30 * 20) { c }, 30, 20, 4)
        assertTrue(out.all { it == c })
    }

    @Test fun blurSpreadsAPointAndKeepsItsEnergy() {
        val w = 41
        val h = 41
        val px = IntArray(w * h) { 0xFF000000.toInt() }
        px[20 * w + 20] = 0xFFFFFFFF.toInt()
        val out = PreBlur.blur(px, w, h, 3)
        fun red(i: Int) = (out[i] shr 16) and 0xFF
        val total = (0 until w * h).sumOf { red(it) }
        // Rounding each pixel loses a little; the sum stays near 255.
        assertTrue("total $total", abs(total - 255) < 60)
        assertTrue(red(20 * w + 20) in 1..254)
        assertTrue(red(20 * w + 20) > red(20 * w + 25))
        assertTrue(red(20 * w + 23) > 0)
        // Symmetric.
        assertEquals(red(20 * w + 17), red(20 * w + 23))
        assertEquals(red(17 * w + 20), red(23 * w + 20))
    }

    @Test fun transparentNeighborsDoNotDarkenTheEdge() {
        // Left half opaque red, right half fully transparent black: premultiplied blur keeps red.
        val w = 20
        val px = IntArray(w * 4) { i -> if (i % w < 10) 0xFFFF0000.toInt() else 0x00000000 }
        val out = PreBlur.blur(px, w, 4, 3)
        val edge = out[1 * w + 10]
        assertTrue("alpha ${edge ushr 24}", (edge ushr 24) in 1..254)
        assertEquals(0xFF, (edge shr 16) and 0xFF)
        assertEquals(0, (edge shr 8) and 0xFF)
    }

    @Test fun radiusZeroCopies() {
        val px = intArrayOf(1, 2, 3, 4)
        val out = PreBlur.blur(px, 2, 2, 0)
        assertArrayEquals(px, out)
        assertFalse(px === out)
    }

    // endregion

    // region Pre-blurred copy cache

    @Test fun theCacheEvictsTheLeastRecentlyUsedCopy() {
        val cache = PreBlurCache<String>(maxEntries = 3)
        cache.put("a", 4, "a4")
        cache.put("b", 4, "b4")
        cache.put("c", 4, "c4")
        assertEquals("a4", cache["a", 4]) // a is now the most recent
        cache.put("d", 4, "d4")
        assertEquals(3, cache.size)
        assertFalse(cache.contains("b", 4))
        assertTrue(cache.contains("a", 4) && cache.contains("c", 4) && cache.contains("d", 4))
    }

    @Test fun eachRadiusIsItsOwnCopyAndEvictDropsThemAll() {
        val cache = PreBlurCache<String>()
        cache.put("wall", 4, "w4")
        cache.put("wall", 8, "w8")
        cache.put("hero", 4, "h4")
        assertEquals("w8", cache["wall", 8])
        cache.evict("wall")
        assertNull(cache["wall", 4])
        assertNull(cache["wall", 8])
        assertEquals("h4", cache["hero", 4])
    }

    @Test fun getOrPutBlursOnceAndDoesNotCacheFailures() = runBlocking {
        val cache = PreBlurCache<String>()
        var made = 0
        assertEquals("x", cache.getOrPut("img", 2) { made++; "x" })
        assertEquals("x", cache.getOrPut("img", 2) { made++; "y" })
        assertEquals(1, made)
        assertNull(cache.getOrPut("bad", 2) { null })
        assertFalse(cache.contains("bad", 2))
        assertNotNull(cache["img", 2])
    }

    // endregion
}
