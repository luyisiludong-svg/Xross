package dev.xross.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import dev.xross.launcher.theme.ArtSwatches
import dev.xross.launcher.theme.ColorMath
import dev.xross.launcher.theme.GameColors
import dev.xross.launcher.theme.GamePaletteExtractor
import dev.xross.launcher.theme.SwatchSet
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
}
