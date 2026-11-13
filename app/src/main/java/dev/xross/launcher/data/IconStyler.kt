package dev.xross.launcher.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import dev.xross.launcher.theme.pack.IconFilter
import dev.xross.launcher.theme.pack.IconMask
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * How installed apps' icons are drawn (2b.4d): the theme's [mask] and [filter], and the two
 * palette colors the filter maps to ([dark] = `palette.canvas`, [light] = `palette.accent`).
 * Build it with [of], which zeroes the colors when no filter uses them, so a palette change
 * doesn't throw away icons it wouldn't change.
 */
@Immutable
data class IconStyle(
    val mask: IconMask = IconMask.NONE,
    val filter: IconFilter = IconFilter.NONE,
    val dark: Int = 0,
    val light: Int = 0,
) {
    /** True when icons are drawn exactly as the app ships them. */
    val plain: Boolean get() = mask == IconMask.NONE && filter == IconFilter.NONE

    companion object {
        val PLAIN = IconStyle()

        fun of(mask: IconMask, filter: IconFilter, canvas: Int, accent: Int): IconStyle = when (filter) {
            IconFilter.NONE -> IconStyle(mask, filter)
            // Monochrome runs from black, so only the accent matters.
            IconFilter.MONOCHROME -> IconStyle(mask, filter, dark = Color.BLACK, light = accent or OPAQUE)
            IconFilter.DUOTONE -> IconStyle(mask, filter, dark = canvas or OPAQUE, light = accent or OPAQUE)
        }

        private const val OPAQUE = 0xFF000000.toInt()
    }
}

/**
 * Cuts an app's icon to an [IconMask] and runs it through an [IconFilter], into a bitmap drawn
 * as is (no per-frame work). Adaptive icons (API 26+) are rebuilt from their layers so the mask
 * cuts the full-bleed artwork; legacy icons are cut whole when they are opaque squares, else
 * shrunk onto a plate tinted from their own colors.
 */
object IconStyler {
    /** Width of [IconMask.Y2K_CHROME_FRAME]'s bezel, as a share of the icon's size. */
    const val CHROME_WIDTH = 0.085f

    /** Superellipse exponent of [IconMask.SQUIRCLE] (2 is a circle, ∞ a square). */
    const val SQUIRCLE_EXPONENT = 4.0

    /** A legacy icon with transparent corners is drawn at this share of the plate (≈ inside a circle). */
    const val LEGACY_SCALE = 0.70f

    /**
     * Adaptive layers are 108 dp with the visible 72 dp in the middle: each layer is drawn 1.5×
     * the mask, a quarter of the mask's size past each edge.
     */
    const val ADAPTIVE_BLEED = 0.25f

    /**
     * The 4×5 [ColorMatrix] of [filter] mapping brightness (Rec. 709 luma) onto the ramp from
     * [dark] to [light]: luma 0 → [dark], luma 1 → [light], alpha kept. Null for [IconFilter.NONE].
     */
    fun filterMatrix(filter: IconFilter, dark: Int, light: Int): FloatArray? {
        if (filter == IconFilter.NONE) return null
        val lo = if (filter == IconFilter.MONOCHROME) Color.BLACK else dark
        val out = FloatArray(20)
        for ((row, shift) in listOf(16, 8, 0).withIndex()) {
            val d = (lo shr shift and 0xFF).toFloat()
            val l = (light shr shift and 0xFF).toFloat()
            val k = (l - d) / 255f
            out[row * 5 + 0] = LUMA_R * k
            out[row * 5 + 1] = LUMA_G * k
            out[row * 5 + 2] = LUMA_B * k
            out[row * 5 + 4] = d
        }
        out[18] = 1f
        return out
    }

    /** [matrix] applied to one ARGB color, as the GPU / Skia would (clamped, rounded). */
    fun applyMatrix(matrix: FloatArray, argb: Int): Int {
        val r = (argb shr 16 and 0xFF).toFloat()
        val g = (argb shr 8 and 0xFF).toFloat()
        val b = (argb and 0xFF).toFloat()
        val a = (argb ushr 24).toFloat()
        fun ch(row: Int): Int {
            val m = row * 5
            val v = matrix[m] * r + matrix[m + 1] * g + matrix[m + 2] * b + matrix[m + 3] * a + matrix[m + 4]
            return v.roundToInt().coerceIn(0, 255)
        }
        return (ch(3) shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
    }

    /** The outline of [mask] in the square at ([left], [top]) of side [size]; null for [IconMask.NONE]. */
    fun maskPath(mask: IconMask, left: Float, top: Float, size: Float): Path? = when (mask) {
        IconMask.NONE -> null
        IconMask.CIRCLE -> Path().apply { addOval(RectF(left, top, left + size, top + size), Path.Direction.CW) }
        IconMask.SQUIRCLE, IconMask.Y2K_CHROME_FRAME -> squircle(left, top, size)
    }

    /** A superellipse |x|ⁿ + |y|ⁿ = 1 filling the square, as a closed polygon of [points] points. */
    fun squircle(left: Float, top: Float, size: Float, exponent: Double = SQUIRCLE_EXPONENT, points: Int = 128): Path {
        val r = size / 2f
        val cx = left + r
        val cy = top + r
        val e = 2.0 / exponent
        val path = Path()
        for (i in 0 until points) {
            val t = 2.0 * Math.PI * i / points
            val c = cos(t)
            val s = sin(t)
            val x = cx + r * (sign(c) * abs(c).pow(e)).toFloat()
            val y = cy + r * (sign(s) * abs(s).pow(e)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    /** True when (x, y), relative to the square's center and in units of its half side, is inside [mask]. */
    fun contains(mask: IconMask, x: Double, y: Double): Boolean = when (mask) {
        IconMask.NONE -> abs(x) <= 1 && abs(y) <= 1
        IconMask.CIRCLE -> x * x + y * y <= 1
        IconMask.SQUIRCLE, IconMask.Y2K_CHROME_FRAME -> abs(x).pow(SQUIRCLE_EXPONENT) + abs(y).pow(SQUIRCLE_EXPONENT) <= 1
    }

    /** [icon] drawn [sizePx] square in [style]. Runs on any thread; touches only [icon]'s bounds. */
    fun render(icon: Drawable, style: IconStyle, sizePx: Int): Bitmap {
        val n = sizePx.coerceAtLeast(1)
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        filterMatrix(style.filter, style.dark, style.light)?.let { paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(it)) }

        if (style.mask == IconMask.NONE) {
            // The icon as shipped (an adaptive one with the system's own mask).
            val plain = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            drawAt(icon, Canvas(plain), 0, 0, n, n)
            canvas.drawBitmap(plain, 0f, 0f, paint)
            plain.recycle()
            return out
        }

        val bezel = if (style.mask == IconMask.Y2K_CHROME_FRAME) (n * CHROME_WIDTH).roundToInt().coerceAtLeast(1) else 0
        val inner = (n - 2 * bezel).coerceAtLeast(1)
        val content = Bitmap.createBitmap(inner, inner, Bitmap.Config.ARGB_8888)
        drawContent(icon, Canvas(content), inner)

        // Anti-aliased cut: fill the mask, then keep the (filtered) content only where it is.
        val shape = maskPath(style.mask, bezel.toFloat(), bezel.toFloat(), inner.toFloat())!!
        canvas.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(content, bezel.toFloat(), bezel.toFloat(), paint)
        content.recycle()

        if (style.mask == IconMask.Y2K_CHROME_FRAME) drawChrome(canvas, n.toFloat(), bezel.toFloat(), shape)
        return out
    }

    /** The full-bleed artwork for a mask of side [n]: adaptive layers, or a legacy icon. */
    private fun drawContent(icon: Drawable, canvas: Canvas, n: Int) {
        if (icon is AdaptiveIconDrawable) {
            val bleed = (n * ADAPTIVE_BLEED).roundToInt()
            icon.background?.let { drawAt(it, canvas, -bleed, -bleed, n + bleed, n + bleed) }
            icon.foreground?.let { drawAt(it, canvas, -bleed, -bleed, n + bleed, n + bleed) }
            return
        }
        val raw = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        drawAt(icon, Canvas(raw), 0, 0, n, n)
        if (fullBleed(raw)) {
            canvas.drawBitmap(raw, 0f, 0f, null)
        } else {
            canvas.drawColor(plateColor(raw))
            val pad = n * (1f - LEGACY_SCALE) / 2f
            canvas.drawBitmap(raw, null, RectF(pad, pad, n - pad, n - pad), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        raw.recycle()
    }

    /** Draws [d] into the rectangle, then restores its bounds (drawables may be shared). */
    private fun drawAt(d: Drawable, canvas: Canvas, l: Int, t: Int, r: Int, b: Int) {
        val old = d.copyBounds()
        d.setBounds(l, t, r, b)
        d.draw(canvas)
        d.bounds = old
    }

    /** True when a legacy icon fills its square (opaque corners): it is cut whole, not padded. */
    internal fun fullBleed(icon: Bitmap): Boolean {
        val w = icon.width
        val h = icon.height
        val i = (minOf(w, h) * 0.06f).roundToInt().coerceAtLeast(0)
        val probes = listOf(i to i, w - 1 - i to i, i to h - 1 - i, w - 1 - i to h - 1 - i)
        return probes.all { (x, y) -> Color.alpha(icon.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))) >= 0xF0 }
    }

    /**
     * The plate behind a padded legacy icon: the average of its opaque pixels, lifted most of the
     * way to white, so the plate matches the icon without competing with it. White if it has none.
     */
    internal fun plateColor(icon: Bitmap): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        val step = (minOf(icon.width, icon.height) / 24).coerceAtLeast(1)
        var y = 0
        while (y < icon.height) {
            var x = 0
            while (x < icon.width) {
                val p = icon.getPixel(x, y)
                if (Color.alpha(p) >= 0x80) {
                    r += Color.red(p); g += Color.green(p); b += Color.blue(p); count++
                }
                x += step
            }
            y += step
        }
        if (count == 0) return Color.WHITE
        fun lift(sum: Long) = ((sum / count) + (255 - sum / count) * PLATE_LIFT).roundToInt().coerceIn(0, 255)
        return Color.rgb(lift(r), lift(g), lift(b))
    }

    /** The Y2K bezel: brushed-chrome ring, a dark rim, and a gloss over the top of the plate. */
    private fun drawChrome(canvas: Canvas, n: Float, bezel: Float, plate: Path) {
        val outer = squircle(0f, 0f, n)
        val ring = Path().apply { op(outer, plate, Path.Op.DIFFERENCE) }
        val metal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, n, n, CHROME_COLORS, CHROME_STOPS, Shader.TileMode.CLAMP)
        }
        canvas.drawPath(ring, metal)
        val line = (n / 96f).coerceAtLeast(1f)
        canvas.drawPath(outer, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = line; color = 0x99202430.toInt()
        })
        canvas.drawPath(plate, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = line; color = 0x66000000
        })
        // Gloss: a soft white lens over the top half of the plate.
        val inner = n - 2 * bezel
        val lens = Path().apply {
            addOval(RectF(bezel - inner * 0.25f, bezel - inner * 0.6f, bezel + inner * 1.25f, bezel + inner * 0.48f), Path.Direction.CW)
            op(plate, Path.Op.INTERSECT)
        }
        canvas.drawPath(lens, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, bezel, 0f, bezel + inner * 0.48f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
    }

    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f
    private const val PLATE_LIFT = 0.82f

    private val CHROME_COLORS = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFB9BFCA.toInt(), 0xFF5A606B.toInt(), 0xFFE8ECF2.toInt(), 0xFF8B929D.toInt(), 0xFFF5F7FA.toInt(),
    )
    private val CHROME_STOPS = floatArrayOf(0f, 0.22f, 0.45f, 0.62f, 0.82f, 1f)
}
