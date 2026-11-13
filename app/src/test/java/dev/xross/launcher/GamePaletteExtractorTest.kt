package dev.xross.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import dev.xross.launcher.scrape.ArtKind
import dev.xross.launcher.state.GameDetailsState
import dev.xross.launcher.state.GlyphKey
import dev.xross.launcher.state.NodeIcon
import dev.xross.launcher.state.XmbNode
import dev.xross.launcher.state.XmbUiState
import dev.xross.launcher.theme.ArtSwatches
import dev.xross.launcher.theme.ColorMath
import dev.xross.launcher.theme.GameColors
import dev.xross.launcher.theme.GamePaletteExtractor
import dev.xross.launcher.theme.SwatchSet
import dev.xross.launcher.ui.adaptiveArtKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executors
import kotlin.coroutines.ContinuationInterceptor

/**
 * Theme 2.0, 2b.3 contextual game-art palette (Roadmap Appendix A.13): extraction runs off the
 * caller's thread, focus changes are debounced 250 ms, and results are cached per game (LRU).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GamePaletteExtractorTest {

    /** Each "image" is just the key; its swatches are a distinct vivid color per key. */
    private fun swatchesFor(key: String): SwatchSet {
        val hue = (key.hashCode() and 0x7FFFFFFF) % 360
        return SwatchSet(
            dominant = ColorMath.fromHsv(hue.toFloat(), 0.8f, 0.8f),
            vibrant = ColorMath.fromHsv(hue.toFloat(), 0.9f, 1f),
            darkMuted = ColorMath.fromHsv(hue.toFloat(), 0.5f, 0.2f),
        )
    }

    /** Past the debounce. (advanceUntilIdle leaves backgroundScope work alone, so time is moved by hand.) */
    private fun kotlinx.coroutines.test.TestScope.settle() {
        advanceTimeBy(GamePaletteExtractor.DEBOUNCE_MS + 50)
        runCurrent()
    }

    @Test fun focusIsDebouncedSoScrollingExtractsOnlyWhereTheCursorRests() = runTest {
        val work = StandardTestDispatcher(testScheduler, name = "palette-work")
        val loaded = ArrayList<String>()
        val extractor = GamePaletteExtractor(
            scope = backgroundScope,
            load = { key -> loaded += key; key },
            analyze = ::swatchesFor,
            work = work,
        )
        // Scrolling: a new game every 100 ms, faster than the debounce.
        for (key in listOf("a/1", "b/1", "c/1", "d/1")) {
            extractor.focus(key)
            advanceTimeBy(100)
            runCurrent()
        }
        assertTrue(loaded.toString(), loaded.isEmpty())
        assertNull(extractor.colors.value)
        // Resting on d: one extraction, after 250 ms in all.
        advanceTimeBy(GamePaletteExtractor.DEBOUNCE_MS - 100 + 1)
        runCurrent()
        assertEquals(listOf("d/1"), loaded)
        assertEquals(GameColors.from(swatchesFor("d/1")), extractor.colors.value)
        assertEquals(1, extractor.extractions)

        // Leaving the games: back to the theme's colors, after the same delay.
        extractor.focus(null)
        advanceTimeBy(GamePaletteExtractor.DEBOUNCE_MS - 10)
        runCurrent()
        assertNotNull(extractor.colors.value)
        settle()
        assertNull(extractor.colors.value)
    }

    @Test fun revisitingAGameIsACacheHit() = runTest {
        val loaded = ArrayList<String>()
        val extractor = GamePaletteExtractor(
            scope = backgroundScope,
            load = { key -> loaded += key; key },
            analyze = ::swatchesFor,
            work = StandardTestDispatcher(testScheduler),
        )
        for (key in listOf("a/1", "b/1", "a/1", "b/1", "a/1")) {
            extractor.focus(key)
            settle()
        }
        assertEquals(listOf("a/1", "b/1"), loaded)
        assertEquals(2, extractor.extractions)
        assertEquals(GameColors.from(swatchesFor("a/1")), extractor.colors.value)
        assertTrue(extractor.cached("a/1"))
        // New art (a new version) is read again.
        extractor.focus("a/2")
        settle()
        assertEquals(listOf("a/1", "b/1", "a/2"), loaded)
        // Art without usable colors is remembered too, so it isn't decoded on every visit.
        val grey = GamePaletteExtractor(
            scope = backgroundScope,
            load = { key -> loaded += key; key },
            analyze = { SwatchSet(dominant = 0xFF808080.toInt()) },
            work = StandardTestDispatcher(testScheduler),
        )
        loaded.clear()
        repeat(3) {
            assertNull(grey.colorsOf("logo/1"))
        }
        assertEquals(listOf("logo/1"), loaded)
    }

    @Test fun theCacheKeepsTheLastHundredGames() = runTest {
        val extractor = GamePaletteExtractor(
            scope = backgroundScope,
            load = { key -> key },
            analyze = ::swatchesFor,
            work = StandardTestDispatcher(testScheduler),
            cacheSize = GamePaletteExtractor.CACHE_SIZE,
        )
        for (i in 0..GamePaletteExtractor.CACHE_SIZE) extractor.colorsOf("g$i/1") // 101 games
        assertFalse(extractor.cached("g0/1")) // the oldest fell out
        assertTrue(extractor.cached("g1/1"))
        // Touching an entry makes it the newest: it survives the next eviction.
        extractor.colorsOf("g1/1")
        extractor.colorsOf("new/1")
        assertTrue(extractor.cached("g1/1"))
        assertFalse(extractor.cached("g2/1"))
        assertEquals(GamePaletteExtractor.CACHE_SIZE + 2, extractor.extractions)
    }

    @Test fun extractionRunsOnTheWorkerNotTheCallersThread() = runTest {
        val work = StandardTestDispatcher(testScheduler, name = "palette-work")
        var loadedOn: ContinuationInterceptor? = null
        val extractor = GamePaletteExtractor(
            scope = backgroundScope,
            load = { key -> loadedOn = currentCoroutineContext()[ContinuationInterceptor]; key },
            analyze = ::swatchesFor,
            work = work,
        )
        extractor.focus("a/1")
        settle()
        assertSame(work, loadedOn)
    }

    @Test fun extractionLeavesTheMainThreadForARealWorker() {
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "palette-worker") }
        val scope = CoroutineScope(SupervisorJob() + pool.asCoroutineDispatcher())
        try {
            val caller = Thread.currentThread()
            var analyzedOn: Thread? = null
            val extractor = GamePaletteExtractor(
                scope = scope,
                load = { key -> key },
                analyze = { key -> analyzedOn = Thread.currentThread(); swatchesFor(key) },
                work = pool.asCoroutineDispatcher(),
                debounceMs = 0,
            )
            extractor.focus("a/1")
            val colors = runBlocking { withTimeout(5_000) { extractor.colors.first { it != null } } }
            assertEquals(GameColors.from(swatchesFor("a/1")), colors)
            assertEquals("palette-worker", analyzedOn?.name)
            assertNotEquals(caller, analyzedOn)
        } finally {
            scope.cancel()
            pool.shutdownNow()
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun androidxPaletteReadsRealArt() {
        // A hero banner that is mostly deep blue with a bright orange logo.
        val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(0xFF0B1E4A.toInt())
            drawRect(50f, 25f, 110f, 65f, Paint().apply { color = 0xFFFF7A10.toInt() })
        }
        val swatches = ArtSwatches.analyze(bitmap)
        assertNotNull(swatches)
        val colors = GameColors.from(swatches!!)
        assertNotNull(colors)
        val accentHue = ColorMath.hsv(colors!!.accent)[0]
        assertTrue("accent hue $accentHue", accentHue in 15f..40f) // the orange logo
        val canvas = ColorMath.hsv(colors.canvas)
        assertTrue("canvas $canvas", canvas[0] in 205f..240f && canvas[2] <= GameColors.MAX_CANVAS_VALUE + 0.01f) // the blue, dark
        assertEquals("u-1/3", ArtSwatches.key("u-1", 3))
        assertEquals("u-1", ArtSwatches.uuidOf("u-1/3"))
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun aWheelLogosTransparentPixelsAreIgnored() {
        // A wheel logo: mostly fully transparent, an orange word mark with anti-aliased edges and a
        // thin magenta underline. Transparent pixels read back as 0x00000000 (black, no alpha); if
        // Palette counted them, black would be the dominant swatch and the canvas would be grey.
        val bitmap = Bitmap.createBitmap(240, 90, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            val orange = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF7A10.toInt() }
            drawRoundRect(60f, 25f, 180f, 60f, 12f, 12f, orange)
            drawCircle(40f, 42f, 14f, orange)
            drawRect(60f, 66f, 180f, 70f, Paint().apply { color = 0xFFE028C8.toInt() })
        }
        assertEquals(0, bitmap.getPixel(2, 2)) // the background really is transparent
        val swatches = ArtSwatches.analyze(bitmap)
        assertNotNull(swatches)
        val dominant = ColorMath.hsv(swatches!!.dominant!!)
        assertTrue("dominant ${dominant.toList()} is the logo, not the transparent black", dominant[0] in 15f..40f && dominant[2] > 0.5f)
        val colors = GameColors.from(swatches)
        assertNotNull("a logo on transparency still recolors the theme", colors)
        val accentHue = ColorMath.hsv(colors!!.accent)[0]
        assertTrue("accent hue $accentHue", accentHue in 15f..40f || accentHue in 290f..320f)
        // The canvas comes from the logo's hues too, darkened, never the transparent black.
        val canvas = ColorMath.hsv(colors.canvas)
        assertTrue("canvas ${canvas.toList()}", canvas[1] >= GameColors.MIN_SATURATION && canvas[2] > 0.05f)

        // A logo that is all transparent says nothing: no swatches, the theme's colors stay.
        assertNull(ArtSwatches.analyze(Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)))
    }

    // region Which art is read

    private fun row(icon: NodeIcon) = XmbNode("game:1", "G", icon = icon)
    private fun details(uuid: String) = GameDetailsState(1, uuid, "G", null, null, emptyList(), null, "", artVersion = 99)

    @Test fun theFocusedGameOrTheOpenDetailsScreenNamesTheArt() {
        val art = NodeIcon.Art("u-1", listOf(ArtKind.HERO), GlyphKey.GAME_CART, version = 7)
        val scraped = NodeIcon.Tile("u-2", "T", GlyphKey.GAME_CART, version = 5, scraped = true)
        val unscraped = NodeIcon.Tile("u-3", "T", GlyphKey.GAME_CART, version = 0, scraped = false)

        assertEquals("u-1/7", adaptiveArtKey(XmbUiState(items = listOf(row(art)))))
        assertEquals("u-2/5", adaptiveArtKey(XmbUiState(items = listOf(row(scraped)))))
        // An unscraped tile has no art to read; neither has a plain row or an empty list.
        assertNull(adaptiveArtKey(XmbUiState(items = listOf(row(unscraped)))))
        assertNull(adaptiveArtKey(XmbUiState(items = listOf(row(NodeIcon.Glyph(GlyphKey.SETTINGS))))))
        assertNull(adaptiveArtKey(XmbUiState()))

        // Details of the focused game: the same key as its row, so the cached colors are reused.
        assertEquals("u-1/7", adaptiveArtKey(XmbUiState(items = listOf(row(art)), details = details("u-1"))))
        // Details paged to another game: that game's art, whatever row is focused below it.
        assertEquals("u-9/0", adaptiveArtKey(XmbUiState(items = listOf(row(art)), details = details("u-9"))))
        // Details of an unscraped game still names its art (it may have a screenshot).
        assertEquals("u-3/0", adaptiveArtKey(XmbUiState(items = listOf(row(unscraped)), details = details("u-3"))))
    }

    // endregion
}
