package dev.xross.launcher.theme.pack

import androidx.compose.runtime.Immutable
import dev.xross.launcher.theme.shader.ImageSize
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import java.util.Locale

/**
 * A stretchable image a pack names for the menu (Menu styles slice 1): row and panel skins, the
 * category decal, the art frame, the clock and hint plates.
 *
 * ```json
 * "menu/row.webp"
 * { "path": "menu/row.webp", "slice": [48, 14, 48, 14], "tint": "accent", "outset": [0, 4, 0, 4] }
 * ```
 *
 * - [slice]: left, top, right, bottom in image pixels. The corners keep their size, the edges
 *   stretch one way and the middle both ways ([NineSlice]). Null stretches the whole image.
 * - [tint]: `accent`, a palette role name or a `#rrggbb` / `#aarrggbb` color, multiplied in; null
 *   draws the image as it is. Resolved against the palette when drawn.
 * - [outset]: how far the image reaches past the box it skins, in dp (left, top, right, bottom),
 *   so torn edges or stars can spill over.
 */
@Immutable
data class Skin(
    val path: String,
    val slice: Edges? = null,
    val tint: String? = null,
    val outset: Edges = Edges.ZERO,
) {
    /** Four edge values: left, top, right, bottom. */
    @Immutable
    data class Edges(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        companion object {
            val ZERO = Edges(0f, 0f, 0f, 0f)
        }
    }

    companion object {
        /** Image types a skin may be. */
        val TYPES = WallpaperRef.TYPES

        /** The largest outset, in dp. */
        const val MAX_OUTSET = 64f

        /**
         * A skin as written at [where]: a path string or an object. Problems go to [problems];
         * a skin whose path is unusable is null (the style draws its flat fallback), and a bad
         * `slice`, `tint` or `outset` is dropped on its own.
         */
        fun of(where: String, json: JsonElement?, problems: MutableList<String>): Skin? {
            if (json == null || json is JsonNull) return null
            if (json is JsonPrimitive) return pathOf(where, json.contentOrNull, problems)?.let(::Skin)
            if (json !is JsonObject) {
                problems += "$where: give an image path or { \"path\": … }"
                return null
            }
            val path = pathOf("$where.path", (json["path"] as? JsonPrimitive)?.contentOrNull, problems) ?: return null
            for (key in json.keys - KEYS) problems += "$where.$key: unknown key"
            val slice = json["slice"]?.let { edges("$where.slice", it, problems, max = Float.MAX_VALUE, whole = true) }
            val outset = json["outset"]?.let { edges("$where.outset", it, problems, max = MAX_OUTSET, whole = false) } ?: Edges.ZERO
            val tint = (json["tint"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { t ->
                if (t.startsWith("#") && !HEX.matches(t)) null.also { problems += "$where.tint: $t isn't a color like #rrggbb" } else t
            }
            return Skin(path, slice, tint, outset)
        }

        private val KEYS = setOf("path", "slice", "tint", "outset")
        private val HEX = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")

        private fun pathOf(where: String, text: String?, problems: MutableList<String>): String? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return null.also { problems += "$where: an image path is missing" }
            val path = t.replace('\\', '/').trimStart('/')
            return when {
                path.split('/').any { it == ".." } -> null.also { problems += "$where: $t must stay inside the pack" }
                path.substringAfterLast('.', "").lowercase(Locale.ROOT) !in TYPES -> null.also { problems += "$where: $t isn't a PNG, JPEG or WebP image" }
                else -> path
            }
        }

        private fun edges(where: String, json: JsonElement, problems: MutableList<String>, max: Float, whole: Boolean): Edges? {
            val values = runCatching { json.jsonArray.map { (it as JsonPrimitive).let { p -> if (whole) p.intOrNull?.toFloat() else p.floatOrNull } } }.getOrNull()
            if (values == null || values.size != 4 || values.any { it == null || !it.isFinite() || it < 0f }) {
                problems += "$where: give four ${if (whole) "whole " else ""}numbers, left, top, right, bottom, none negative"
                return null
            }
            val v = values.map { it!! }
            if (v.any { it > max }) problems += "$where: at most $max"
            return Edges(v[0].coerceAtMost(max), v[1].coerceAtMost(max), v[2].coerceAtMost(max), v[3].coerceAtMost(max))
        }
    }
}

/**
 * Nine-slice geometry (pure, so it is unit tested). An image of [imageW] × [imageH] with [slice]
 * insets drawn into a [dstW] × [dstH] box: the corners keep their size times [scale] (the
 * density, times `uiScale`), the edges stretch along their side and the middle both ways. A box
 * smaller than its corners scales them down evenly so they still meet.
 */
object NineSlice {
    /** One piece: a source rectangle in image pixels and where it goes in the box. */
    @Immutable
    data class Patch(
        val srcX: Int, val srcY: Int, val srcW: Int, val srcH: Int,
        val dstX: Float, val dstY: Float, val dstW: Float, val dstH: Float,
    )

    fun patches(imageW: Int, imageH: Int, slice: Skin.Edges?, dstW: Float, dstH: Float, scale: Float = 1f): List<Patch> {
        if (imageW <= 0 || imageH <= 0 || dstW <= 0f || dstH <= 0f) return emptyList()
        if (slice == null) return listOf(Patch(0, 0, imageW, imageH, 0f, 0f, dstW, dstH))
        // Insets that don't fit the image are shared out so they leave at least one pixel between.
        val (sl, sr) = split(slice.left, slice.right, imageW)
        val (st, sb) = split(slice.top, slice.bottom, imageH)
        // Corner sizes on screen, scaled down together where the box is too small for them.
        val kx = minOf(1f, dstW / ((sl + sr) * scale).coerceAtLeast(1e-6f))
        val ky = minOf(1f, dstH / ((st + sb) * scale).coerceAtLeast(1e-6f))
        val k = minOf(kx, ky)
        val dl = sl * scale * k
        val dr = sr * scale * k
        val dt = st * scale * k
        val db = sb * scale * k
        val srcXs = intArrayOf(0, sl, imageW - sr, imageW)
        val srcYs = intArrayOf(0, st, imageH - sb, imageH)
        val dstXs = floatArrayOf(0f, dl, dstW - dr, dstW)
        val dstYs = floatArrayOf(0f, dt, dstH - db, dstH)
        val out = ArrayList<Patch>(9)
        for (row in 0 until 3) for (col in 0 until 3) {
            val sw = srcXs[col + 1] - srcXs[col]
            val sh = srcYs[row + 1] - srcYs[row]
            val dw = dstXs[col + 1] - dstXs[col]
            val dh = dstYs[row + 1] - dstYs[row]
            if (sw <= 0 || sh <= 0 || dw <= 0f || dh <= 0f) continue
            out += Patch(srcXs[col], srcYs[row], sw, sh, dstXs[col], dstYs[row], dw, dh)
        }
        return out
    }

    /** Insets [a] and [b] on a side of [length] pixels, shrunk together to leave one pixel between. */
    private fun split(a: Float, b: Float, length: Int): Pair<Int, Int> {
        val room = (length - 1).coerceAtLeast(0).toFloat()
        val k = if (a + b > room && a + b > 0f) room / (a + b) else 1f
        return (a * k).toInt() to (b * k).toInt()
    }
}

/**
 * The menu images' budget (pure): each one's long side is capped ([MAX_SIDE], [MAX_SHEET_SIDE] for
 * a sprite sheet), then they are taken in order while they fit [BUDGET_PIXELS] together. One that
 * doesn't fit is dropped (its style draws the flat fallback), and a smaller one after it may still fit.
 */
object MenuImagePlan {
    const val MAX_SIDE = 1024
    const val MAX_SHEET_SIDE = 2048
    const val BUDGET_PIXELS = 6L * 1024 * 1024

    /** Upload sizes for [sizes] (null: missing or unreadable), null where an image is dropped; [sheets] are sprite-sheet indices. */
    fun fit(sizes: List<ImageSize?>, sheets: Set<Int> = emptySet(), budget: Long = BUDGET_PIXELS): List<ImageSize?> {
        var used = 0L
        return sizes.mapIndexed { i, s ->
            val target = s?.let { dev.xross.launcher.theme.shader.ShaderImagePlan.scaled(it, if (i in sheets) MAX_SHEET_SIDE else MAX_SIDE) } ?: return@mapIndexed null
            if (used + target.pixels > budget) null else target.also { used += it.pixels }
        }
    }
}
