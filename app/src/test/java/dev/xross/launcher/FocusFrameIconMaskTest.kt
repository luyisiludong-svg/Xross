package dev.xross.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.data.AppRepository
import dev.xross.launcher.data.IconStyle
import dev.xross.launcher.data.IconStyler
import dev.xross.launcher.theme.ThemePalette
import dev.xross.launcher.theme.pack.Cosmetics
import dev.xross.launcher.theme.pack.FocusFrameStyle
import dev.xross.launcher.theme.pack.IconFilter
import dev.xross.launcher.theme.pack.IconMask
import dev.xross.launcher.theme.pack.ThemeManifest
import dev.xross.launcher.theme.pack.ThemePackLoader
import dev.xross.launcher.ui.FocusFrame
import dev.xross.launcher.ui.Gold
import dev.xross.launcher.ui.LocalFocusFrame
import dev.xross.launcher.ui.drawPlateFrame
import dev.xross.launcher.ui.focusFrameOf
import dev.xross.launcher.ui.rememberFocusPulse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Theme 2.0, 2b.4d: `effects.focusFrameStyle`, `iconMask` and `iconFilter` through the cascade;
 * the focus pulse holding still under every frame but the breathing halo; app icons cut to their
 * mask (adaptive layers, padded legacy icons, the chrome bezel), filtered by a palette color
 * matrix, and cached by package, style and size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusFrameIconMaskTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    @get:Rule val compose = createComposeRule()

    private val themes get() = File(tmp.root, "Xross/themes").apply { mkdirs() }
    private val loader by lazy { ThemePackLoader(context.assets) { themes } }

    private fun pack(id: String, body: String) {
        val dir = File(themes, id).apply { mkdirs() }
        File(dir, "theme.json").writeText("""{"schemaVersion": 2, "name": "$id", $body}""")
    }

    // region Manifest and cascade

    @Test fun effectsNamesAreReadIgnoringCaseAndDashes() {
        val problems = mutableListOf<String>()
        val c = Cosmetics.of(
            "theme",
            ThemeManifest.Effects(focusFrameStyle = "gold-bezel", iconMask = "squircle", iconFilter = "Duotone"),
            problems,
        )
        assertEquals(Cosmetics(FocusFrameStyle.GOLD_BEZEL, IconMask.SQUIRCLE, IconFilter.DUOTONE), c)
        assertTrue(problems.isEmpty())
        assertNull(Cosmetics.of("theme", ThemeManifest.Effects(surfaceStyle = "FLAT_GLASS"), problems))
    }

    @Test fun unknownNamesAreReportedAndLeftUnset() {
        val problems = mutableListOf<String>()
        val c = Cosmetics.of("variants.x", ThemeManifest.Effects(focusFrameStyle = "SPARKLES", iconMask = "CIRCLE"), problems)
        assertEquals(Cosmetics(iconMask = IconMask.CIRCLE), c)
        assertEquals(1, problems.size)
        assertTrue(problems[0], problems[0].startsWith("variants.x.effects.focusFrameStyle: SPARKLES"))
    }

    @Test fun defaultsAreTheBreathingHaloAndPlainIcons() {
        val look = loader.load("default").look()
        assertEquals(FocusFrameStyle.BREATHING_HALO, look.cosmetics.frame)
        assertEquals(IconMask.NONE, look.cosmetics.mask)
        assertEquals(IconFilter.NONE, look.cosmetics.filter)
        assertNull(look.focusGold)
    }

    @Test fun eachLayerOverridesOnlyWhatItNames() {
        pack(
            "neo",
            """
            "palette": {"canvas": "#101018", "accent": "#00E5FF", "focusGold": "#E0B040"},
            "effects": {"focusFrameStyle": "STATIC_NEON", "iconMask": "CIRCLE"},
            "variants": [{"id": "chrome", "effects": {"iconMask": "Y2K_CHROME_FRAME", "iconFilter": "MONOCHROME"}}],
            "categoryOverrides": {"GAMES": {"effects": {"focusFrame": "GOLD_BEZEL"}}},
            "rules": [{"match": {"category": "APPS"}, "apply": {"effects": {"iconFilter": "DUOTONE"}, "palette": {"focusGold": "#FFCC00"}}}]
            """,
        )
        val t = loader.load("neo")
        assertTrue(t.problems.toString(), t.problems.isEmpty())

        val base = t.look()
        assertEquals(Cosmetics(FocusFrameStyle.STATIC_NEON, IconMask.CIRCLE, null), base.cosmetics)
        assertEquals(0xFFE0B040.toInt(), base.focusGold)

        val variant = t.look("chrome")
        assertEquals(Cosmetics(FocusFrameStyle.STATIC_NEON, IconMask.Y2K_CHROME_FRAME, IconFilter.MONOCHROME), variant.cosmetics)

        val games = t.look("chrome", category = "games")
        assertEquals(FocusFrameStyle.GOLD_BEZEL, games.cosmetics.frame)
        assertEquals(IconMask.Y2K_CHROME_FRAME, games.cosmetics.mask)

        // The rule wins over the variant's filter, and patches the gold.
        val apps = t.look("chrome", category = "apps")
        assertEquals(IconFilter.DUOTONE, apps.cosmetics.filter)
        assertEquals(FocusFrameStyle.STATIC_NEON, apps.cosmetics.frame)
        assertEquals(0xFFFFCC00.toInt(), apps.focusGold)
    }

    @Test fun anOverrideWithOnlyCosmeticsIsKept() {
        pack("solo", """"categoryOverrides": {"APPS": {"effects": {"iconMask": "SQUIRCLE"}}}""")
        val t = loader.load("solo")
        assertTrue(t.problems.toString(), t.problems.isEmpty())
        assertEquals(IconMask.SQUIRCLE, t.look(category = "apps").cosmetics.mask)
        assertEquals(IconMask.NONE, t.look(category = "games").cosmetics.mask)
    }

    // endregion

    // region Focus frames

    @Test fun onlyTheBreathingHaloBreathes() {
        assertEquals(listOf(FocusFrameStyle.BREATHING_HALO), FocusFrameStyle.entries.filter { it.breathes })
    }

    @Test fun neonUsesTheAccentOrTheWaveColorAndGoldFallsBack() {
        val white = ThemePalette(0xFF101018.toInt(), 0xFF202030.toInt(), 0xFF9CFFD2.toInt())
        val f = focusFrameOf(FocusFrameStyle.STATIC_NEON, white, null)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF9CFFD2), f.neon)
        assertEquals(Gold, f.gold)

        val cyan = white.copy(glow = 0xFF00E5FF.toInt())
        val g = focusFrameOf(FocusFrameStyle.GOLD_BEZEL, cyan, 0xFFE0B040.toInt())
        assertEquals(androidx.compose.ui.graphics.Color(0xFF00E5FF), g.neon)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFE0B040), g.gold)
    }

    /** The shared pulse under [style], sampled every 250 ms for [samples] steps of a running, non-idle screen. */
    private fun pulseUnder(style: FocusFrameStyle, samples: Int = 16): Pair<androidx.compose.runtime.State<Float>, List<Float>> {
        var pulse: androidx.compose.runtime.State<Float>? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalFocusFrame provides FocusFrame(style)) {
                pulse = rememberFocusPulse(running = true)
                val frame = LocalFocusFrame.current
                val p = pulse!!
                Box(Modifier.size(64.dp).drawBehind { drawPlateFrame(frame, p.value, 6.dp.toPx()) })
            }
        }
        val values = List(samples) {
            compose.mainClock.advanceTimeBy(250)
            pulse!!.value
        }
        return pulse!! to values
    }

    @Test fun theBreathingHaloBreathes() {
        val (_, values) = pulseUnder(FocusFrameStyle.BREATHING_HALO)
        assertTrue(values.toString(), values.distinct().size > 8)
        assertTrue(values.toString(), values.any { it > 0.9f } && values.any { it < 0.5f })
    }

    /**
     * A steady frame's pulse never moves, and isn't snapshot state at all: a draw that reads it
     * can't be invalidated by it, so a resting focused tile draws no frames.
     */
    private fun assertHoldsStill(style: FocusFrameStyle) {
        val (state, values) = pulseUnder(style)
        assertEquals(style.name, List(values.size) { FocusFrame.STEADY }, values)
        assertTrue(style.name, state !is androidx.compose.runtime.snapshots.StateObject)
    }

    @Test fun staticNeonHoldsStill() = assertHoldsStill(FocusFrameStyle.STATIC_NEON)

    @Test fun hardShadowHoldsStill() = assertHoldsStill(FocusFrameStyle.HARD_SHADOW)

    @Test fun goldBezelHoldsStill() = assertHoldsStill(FocusFrameStyle.GOLD_BEZEL)

    // endregion

    // region Icon filters

    @Test fun duotoneMapsBlackToTheCanvasAndWhiteToTheAccent() {
        val canvas = 0xFF102030.toInt()
        val accent = 0xFFFFC6EA.toInt()
        val m = IconStyler.filterMatrix(IconFilter.DUOTONE, canvas, accent)!!
        assertEquals(canvas, IconStyler.applyMatrix(m, Color.BLACK))
        assertEquals(accent, IconStyler.applyMatrix(m, Color.WHITE))
        // Alpha passes through.
        assertEquals(0x80, IconStyler.applyMatrix(m, 0x80FFFFFF.toInt()) ushr 24)
        // Equal-luma colors land on the same tone.
        val mid = IconStyler.applyMatrix(m, Color.rgb(128, 128, 128))
        assertTrue(Color.red(mid) in Color.red(canvas)..Color.red(accent))
    }

    @Test fun monochromeRunsFromBlackToTheAccent() {
        val m = IconStyler.filterMatrix(IconFilter.MONOCHROME, 0xFF123456.toInt(), Color.WHITE)!!
        assertEquals(Color.BLACK, IconStyler.applyMatrix(m, Color.BLACK))
        val red = IconStyler.applyMatrix(m, Color.RED)
        // Rec. 709 luma of pure red, as grey.
        assertEquals(54f, Color.red(red).toFloat(), 1f)
        assertEquals(Color.red(red), Color.green(red))
        assertEquals(Color.red(red), Color.blue(red))
        assertNull(IconStyler.filterMatrix(IconFilter.NONE, 0, 0))
    }

    @Test fun aStyleWithoutFilterIgnoresThePalette() {
        assertEquals(
            IconStyle.of(IconMask.CIRCLE, IconFilter.NONE, canvas = 0xFF000000.toInt(), accent = 0xFFFF0000.toInt()),
            IconStyle.of(IconMask.CIRCLE, IconFilter.NONE, canvas = 0xFF00FF00.toInt(), accent = 0xFF0000FF.toInt()),
        )
        // Monochrome only depends on the accent.
        assertEquals(
            IconStyle.of(IconMask.NONE, IconFilter.MONOCHROME, canvas = 1, accent = Color.WHITE),
            IconStyle.of(IconMask.NONE, IconFilter.MONOCHROME, canvas = 2, accent = Color.WHITE),
        )
    }

    @Test fun theRenderedIconIsFiltered() {
        val style = IconStyle.of(IconMask.NONE, IconFilter.DUOTONE, canvas = 0xFF000040.toInt(), accent = 0xFFFFFF00.toInt())
        val out = IconStyler.render(ColorDrawable(Color.WHITE), style, 32)
        assertClose(0xFFFFFF00.toInt(), out.getPixel(16, 16))
        val dark = IconStyler.render(ColorDrawable(Color.BLACK), style, 32)
        assertClose(0xFF000040.toInt(), dark.getPixel(16, 16))
    }

    // endregion

    // region Masks

    private val n = 96

    private fun alphaAt(b: Bitmap, x: Int, y: Int) = Color.alpha(b.getPixel(x, y))

    @Test fun noMaskKeepsTheWholeSquare() {
        val out = IconStyler.render(ColorDrawable(Color.RED), IconStyle.PLAIN, n)
        assertEquals(255, alphaAt(out, 0, 0))
        assertEquals(255, alphaAt(out, n - 1, n - 1))
    }

    @Test fun theCircleAndTheSquircleCutTheCornersDifferently() {
        val circle = IconStyler.render(ColorDrawable(Color.RED), IconStyle(IconMask.CIRCLE), n)
        val squircle = IconStyler.render(ColorDrawable(Color.RED), IconStyle(IconMask.SQUIRCLE), n)
        for (b in listOf(circle, squircle)) {
            assertEquals(0, alphaAt(b, 0, 0))
            assertEquals(0, alphaAt(b, n - 1, n - 1))
            assertEquals(255, alphaAt(b, n / 2, n / 2))
            // Edge midpoints are inside both.
            assertEquals(255, alphaAt(b, n / 2, 2))
            assertEquals(255, alphaAt(b, 2, n / 2))
        }
        // (10, 10): outside the circle, inside the squircle's fuller corner.
        assertTrue(IconStyler.contains(IconMask.SQUIRCLE, -0.78, -0.78))
        assertTrue(!IconStyler.contains(IconMask.CIRCLE, -0.78, -0.78))
        assertEquals(0, alphaAt(circle, 10, 10))
        assertEquals(255, alphaAt(squircle, 10, 10))
    }

    @Test fun theMaskMatchesItsShapeEverywhere() {
        // Away from the anti-aliased rim, a pixel is opaque exactly when its center is inside.
        for (mask in listOf(IconMask.CIRCLE, IconMask.SQUIRCLE)) {
            val b = IconStyler.render(ColorDrawable(Color.RED), IconStyle(mask), n)
            val r = n / 2.0
            for (y in 0 until n step 3) for (x in 0 until n step 3) {
                val nx = (x + 0.5 - r) / r
                val ny = (y + 0.5 - r) / r
                val a = alphaAt(b, x, y)
                val inset = IconStyler.contains(mask, nx * 1.04, ny * 1.04)
                val outset = IconStyler.contains(mask, nx * 0.96, ny * 0.96)
                when {
                    inset -> assertEquals("$mask ($x,$y)", 255, a)
                    !outset -> assertEquals("$mask ($x,$y)", 0, a)
                }
            }
        }
    }

    /** A layer that remembers where it was drawn and paints its bounds in [color]. */
    private class Layer(private val color: Int) : Drawable() {
        val drawnAt = ArrayList<Rect>()
        override fun draw(canvas: Canvas) {
            drawnAt += Rect(bounds)
            canvas.drawRect(bounds, Paint().apply { this.color = this@Layer.color })
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    @Test fun adaptiveLayersBleedAQuarterPastTheMask() {
        val back = Layer(Color.BLUE)
        val fore = Layer(Color.RED)
        val icon = AdaptiveIconDrawable(back, fore)
        IconStyler.render(icon, IconStyle(IconMask.SQUIRCLE), n)
        val q = n / 4
        assertEquals(listOf(Rect(-q, -q, n + q, n + q)), back.drawnAt)
        assertEquals(listOf(Rect(-q, -q, n + q, n + q)), fore.drawnAt)
    }

    /** A legacy icon: a red disc with transparent corners. */
    private class Disc : Drawable() {
        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawOval(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED })
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    @Test fun legacyIconsArePaddedOntoAMatchingPlate() {
        val out = IconStyler.render(Disc(), IconStyle(IconMask.SQUIRCLE), n)
        // Above the shrunken disc, inside the squircle: the plate, a pale red.
        val plate = out.getPixel(n / 2, 6)
        assertEquals(255, Color.alpha(plate))
        assertEquals(255f, Color.red(plate).toFloat(), 2f)
        assertTrue(Integer.toHexString(plate), Color.green(plate) in 190..240 && Color.green(plate) == Color.blue(plate))
        // The disc itself, smaller, in the middle.
        assertClose(Color.RED, out.getPixel(n / 2, n / 2))
        // Opaque legacy squares are cut whole, not padded.
        val square = IconStyler.render(ColorDrawable(Color.GREEN), IconStyle(IconMask.SQUIRCLE), n)
        assertClose(Color.GREEN, square.getPixel(n / 2, 6))
    }

    @Test fun theChromeFrameIsAnOpaqueMetalRing() {
        val out = IconStyler.render(ColorDrawable(Color.RED), IconStyle(IconMask.Y2K_CHROME_FRAME), n)
        val bezel = (n * IconStyler.CHROME_WIDTH).toInt()
        // In the bezel on each edge: opaque and grey (not the icon's red).
        for ((x, y) in listOf(n / 2 to bezel / 2, bezel / 2 to n / 2, n / 2 to n - 1 - bezel / 2)) {
            val p = out.getPixel(x, y)
            assertEquals("($x,$y)", 255, Color.alpha(p))
            assertTrue("($x,$y) ${Integer.toHexString(p)}", abs(Color.red(p) - Color.green(p)) < 40)
        }
        // The plate inside is the icon, the corners still clear.
        assertEquals(255, Color.red(out.getPixel(n / 2, n * 3 / 4)))
        assertEquals(0, alphaAt(out, 0, 0))
    }

    @Test fun drawablesKeepTheirBounds() {
        val d = ColorDrawable(Color.RED).apply { setBounds(1, 2, 3, 4) }
        IconStyler.render(d, IconStyle(IconMask.CIRCLE, IconFilter.MONOCHROME, Color.BLACK, Color.WHITE), 40)
        assertEquals(Rect(1, 2, 3, 4), d.bounds)
    }

    // endregion

    // region Cache

    private fun repo(loads: AtomicInteger, missing: Set<String> = emptySet()) = AppRepository(context) { pkg ->
        loads.incrementAndGet()
        if (pkg in missing) throw android.content.pm.PackageManager.NameNotFoundException(pkg) else ColorDrawable(Color.RED)
    }

    @Test fun iconsAreCachedByPackageStyleAndSize() = runBlocking {
        val loads = AtomicInteger()
        val apps = repo(loads)
        val circle = IconStyle(IconMask.CIRCLE)
        val first = apps.icon("a.b", 96, circle)
        assertNotNull(first)
        assertSame(first, apps.icon("a.b", 96, circle))
        assertEquals(1, loads.get())
        assertEquals(1, apps.rendered)

        // Each part of the key is its own bitmap.
        val squircle = apps.icon("a.b", 96, IconStyle(IconMask.SQUIRCLE))
        val bigger = apps.icon("a.b", 144, circle)
        val other = apps.icon("c.d", 96, circle)
        val tinted = apps.icon("a.b", 96, IconStyle.of(IconMask.CIRCLE, IconFilter.DUOTONE, Color.BLACK, Color.WHITE))
        assertEquals(5, apps.rendered)
        assertEquals(144, bigger!!.width)
        assertNotEquals(first, squircle)
        assertNotEquals(first, other)
        assertNotEquals(first, tinted)

        // All still cached: going back costs nothing.
        assertSame(first, apps.icon("a.b", 96, circle))
        assertSame(squircle, apps.icon("a.b", 96, IconStyle(IconMask.SQUIRCLE)))
        assertEquals(5, apps.rendered)
        assertEquals(5, loads.get())
    }

    @Test fun theMaskIsInTheCachedBitmap() = runBlocking {
        val apps = repo(AtomicInteger())
        val plain = apps.icon("a.b", n, IconStyle.PLAIN)!!.asAndroidBitmap()
        val circle = apps.icon("a.b", n, IconStyle(IconMask.CIRCLE))!!.asAndroidBitmap()
        assertEquals(255, alphaAt(plain, 0, 0))
        assertEquals(0, alphaAt(circle, 0, 0))
        assertEquals(255, alphaAt(circle, n / 2, n / 2))
    }

    @Test fun missingAppsAreNotCached() = runBlocking {
        val loads = AtomicInteger()
        val apps = repo(loads, missing = setOf("gone"))
        assertNull(apps.icon("gone", 96, IconStyle(IconMask.CIRCLE)))
        assertNull(apps.icon("gone", 96, IconStyle(IconMask.CIRCLE)))
        assertEquals(2, loads.get())
        assertEquals(0, apps.rendered)
    }

    // endregion

    private fun assertClose(expected: Int, actual: Int, tolerance: Int = 3) {
        val ok = listOf(24, 16, 8, 0).all { s -> abs((expected shr s and 0xFF) - (actual shr s and 0xFF)) <= tolerance }
        assertTrue("expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}", ok)
    }
}
