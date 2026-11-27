package dev.xross.launcher.theme.pack

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Locale
import kotlin.math.abs

/** Where a row's text sits inside its box (`slash.textAlign`). */
enum class SlashTextAlign { START, CENTER, END }

/** What marks the focused row when no `focusedSkin` is set (`slash.highlight`). */
enum class SlashHighlight {
    /** An irregular quad, with a smaller offset shard behind it. */
    SHARD,

    /** A skewed rectangle. */
    SLAB,
    NONE,
}

/** What the focused row shows (`slash.focusedContent`). */
enum class SlashFocusedContent {
    TITLE,

    /** The game's logo (`WHEEL` art), else its title. */
    LOGO,

    /** The logo on the panel, the title beside it. */
    LOGO_TITLE,
}

/** `slash.titleCase`. */
enum class SlashTitleCase { AS_IS, UPPER }

/** How a list replaces the one before it (`slash.transition`). */
enum class SlashTransition {
    /** A skewed band of the highlight color sweeps across and reveals the new list. */
    WIPE,

    /** The old list breaks into a few polygons that fly out. */
    SHARDS,
    FADE,
}

/** How categories are drawn (`slash.categories`). */
enum class SlashCategories {
    /** The horizontal icon row, the focused one's name under it. */
    ICONS,

    /** A slanted strip of names. */
    LABELS,

    /** The same strip with the category glyphs. */
    GLYPHS,
}

/** What the empty side of the screen shows for the focused item (`slash.art`). */
enum class SlashArt {
    /** The game's `HERO` art, else its logo. */
    HERO,
    ICON,
    NONE,
}

/**
 * A skin drawn behind the focused category (`slash.categoryDecal`), [size] times the icon. With
 * [columns] × [rows] above one it is a sprite sheet played at [fps], read left to right, top to
 * bottom; it holds its first frame at rest ([SlashDecal.frameAt]).
 */
@Immutable
data class SlashDecal(
    val skin: Skin,
    val size: Float = 1.8f,
    val columns: Int = 1,
    val rows: Int = 1,
    val fps: Float = 0f,
) {
    val frames: Int get() = columns * rows
    val animated: Boolean get() = frames > 1 && fps > 0f

    /** The frame shown [elapsedMs] after the decal arrived; the first while [still] or for a single frame. */
    fun frameAt(elapsedMs: Long, still: Boolean): Int =
        if (!animated || still || elapsedMs <= 0L) 0 else ((elapsedMs * fps / 1000f).toLong() % frames).toInt()

    /** Frame [index]'s source rectangle (x, y, w, h) in an image of [imageW] × [imageH]. */
    fun frameRect(index: Int, imageW: Int, imageH: Int): IntArray {
        val w = imageW / columns.coerceAtLeast(1)
        val h = imageH / rows.coerceAtLeast(1)
        val i = index.coerceIn(0, frames - 1)
        return intArrayOf((i % columns) * w, (i / columns) * h, w, h)
    }

    companion object {
        const val MIN_SIZE = 1f
        const val MAX_SIZE = 3f
        const val MAX_FRAMES = 64
        const val MAX_FPS = 30f
    }
}

/**
 * The `layout.slash` block (Menu styles slices 2–4): how the `SLASH` menu style draws. Every
 * field is optional; [of] reads a pack's block over a base (the Default pack's, else [DEFAULT]),
 * reporting and clamping as `resolveLayout` does. Fractions are of the screen; `uiScale` scales
 * the rows, text and the skins' fixed edges, never these fractions.
 */
@Immutable
data class SlashLayout(
    // Geometry (slice 2).
    val skewDeg: Float = -12f,
    val tiltMin: Float = -3f,
    val tiltMax: Float = 2f,
    val jitter: Float = 0.35f,
    val seed: Int = 7,
    val listX: Float = 0.08f,
    val listY: Float = 0.30f,
    val listWidth: Float = 0.38f,
    val bleed: Boolean = false,
    val textAlign: SlashTextAlign = SlashTextAlign.START,
    // Skins and the focused row (slice 2).
    val rowSkins: List<Skin> = emptyList(),
    val highlight: SlashHighlight = SlashHighlight.SHARD,
    /** Front and back shard: `accent`, a palette role name or a hex color. */
    val highlightColors: List<String> = listOf("accent", "#000000"),
    val focusedSkin: Skin? = null,
    val focusedScale: Float = 1.6f,
    val focusedContent: SlashFocusedContent = SlashFocusedContent.TITLE,
    val focusedHeight: Float = 0.16f,
    val titleCase: SlashTitleCase = SlashTitleCase.UPPER,
    val outline: Float = 2f,
    /** Unfocused rows' text: `accent`, a palette role or a hex color (dark text on light paper skins). */
    val rowTextColor: String = "#FFFFFF",
    /** The focused row's text; null picks black or white against the highlight (white on a focused skin). */
    val focusedTextColor: String? = null,
    // Motion (slice 3).
    val staggerMs: Int = 28,
    val transition: SlashTransition = SlashTransition.WIPE,
    val pop: Float = 0.08f,
    // Categories and art (slice 4).
    val categories: SlashCategories = SlashCategories.ICONS,
    val categoryDecal: SlashDecal? = null,
    val categoryY: Float = 0.22f,
    val art: SlashArt = SlashArt.NONE,
    /** x, y, w, h as fractions of the screen. */
    val artRect: List<Float> = listOf(0.56f, 0.28f, 0.38f, 0.44f),
    val artTilt: Float = -3f,
    val artFrame: Skin? = null,
) {
    /** Every skin this block names, in load order (the budget drops from the end), and whether it's a sprite sheet. */
    val images: List<Pair<String, Boolean>>
        get() = buildList {
            focusedSkin?.let { add(it.path to false) }
            rowSkins.forEach { add(it.path to false) }
            categoryDecal?.let { add(it.skin.path to it.animated) }
            artFrame?.let { add(it.path to false) }
        }.distinctBy { it.first }

    /** The skin of row [index]: one of [rowSkins], spread by [seed] so a list of paper doesn't repeat row after row. */
    fun rowSkin(index: Int): Skin? = if (rowSkins.isEmpty()) null else rowSkins[SlashGeometry.pick(seed, index, rowSkins.size)]

    companion object {
        val DEFAULT = SlashLayout()

        const val MAX_SKEW = 20f
        const val MAX_TILT = 6f
        const val MIN_LIST_WIDTH = 0.15f
        const val MAX_LIST_WIDTH = 0.6f
        const val MIN_FOCUSED_SCALE = 1f
        const val MAX_FOCUSED_SCALE = 2.5f
        const val MIN_FOCUSED_HEIGHT = 0.08f
        const val MAX_FOCUSED_HEIGHT = 0.3f
        const val MAX_OUTLINE = 4f
        const val MAX_STAGGER_MS = 80
        const val MAX_POP = 0.2f
        const val MAX_ART_TILT = 10f

        /** The longest a whole cascade may take, however long the list. */
        const val CASCADE_CAP_MS = 300

        val KEYS = setOf(
            "skewDeg", "rowTilt", "jitter", "seed", "listX", "listY", "listWidth", "bleed", "textAlign",
            "rowSkin", "highlight", "highlightColors", "focusedSkin", "focusedScale", "focusedContent",
            "focusedHeight", "titleCase", "outline", "rowTextColor", "focusedTextColor", "staggerMs", "transition", "pop", "categories",
            "categoryDecal", "categoryY", "art", "artRect", "artTilt", "artFrame",
        )
        private val DECAL_KEYS = setOf("size", "frames", "fps")
        private val HEX = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")

        /** [json] (the `layout.slash` value) over [base]; problems go to [problems] as `layout.slash.<key>: …`. */
        fun of(json: JsonElement?, base: SlashLayout, problems: MutableList<String>): SlashLayout {
            if (json == null || json is JsonNull) return base
            val o = json as? JsonObject ?: return base.also { problems += "layout.slash: give an object" }
            val w = "layout.slash"
            for (k in o.keys - KEYS) problems += "$w.$k: unknown key"

            fun prim(key: String): JsonPrimitive? = when (val v = o[key]) {
                null, is JsonNull -> null
                is JsonPrimitive -> v
                else -> null.also { problems += "$w.$key: give a single value" }
            }
            fun number(key: String, lo: Float, hi: Float, fallback: Float): Float {
                val p = prim(key) ?: return fallback
                val v = p.floatOrNull?.takeIf { it.isFinite() && !p.isString } ?: return fallback.also { problems += "$w.$key: ${p.content} isn't a number" }
                if (v < lo || v > hi) problems += "$w.$key: $v is outside $lo–$hi"
                return v.coerceIn(lo, hi)
            }
            fun <E : Enum<E>> pick(key: String, entries: List<E>, fallback: E): E {
                val t = prim(key)?.contentOrNull ?: return fallback
                val k = t.trim().uppercase(Locale.ROOT).replace(' ', '_').replace('-', '_')
                return entries.firstOrNull { it.name == k } ?: fallback.also { problems += "$w.$key: $t isn't one of ${entries.joinToString { it.name }}" }
            }
            fun color(where: String, t: String?): String? {
                val c = t?.trim()?.takeIf { it.isNotEmpty() } ?: return null.also { problems += "$where: give accent, a palette role or #rrggbb" }
                return if (c.startsWith("#") && !HEX.matches(c)) null.also { problems += "$where: $c isn't a color like #rrggbb" } else c
            }

            val tilt = o["rowTilt"]?.takeUnless { it is JsonNull }?.let { e ->
                val vals = (e as? JsonArray)?.map { (it as? JsonPrimitive)?.floatOrNull }
                when {
                    e is JsonPrimitive && e.floatOrNull != null -> abs(e.floatOrNull!!).let { -it to it }
                    vals == null || vals.size != 2 || vals.any { it == null || !it.isFinite() } ->
                        null.also { problems += "$w.rowTilt: give [min, max] in degrees" }
                    else -> {
                        val (a, b) = vals[0]!! to vals[1]!!
                        if (abs(a) > MAX_TILT || abs(b) > MAX_TILT) problems += "$w.rowTilt: at most ±$MAX_TILT°"
                        minOf(a, b).coerceIn(-MAX_TILT, MAX_TILT) to maxOf(a, b).coerceIn(-MAX_TILT, MAX_TILT)
                    }
                }
            }

            val rowSkins = o["rowSkin"]?.takeUnless { it is JsonNull }?.let { e ->
                if (e is JsonArray) e.mapIndexedNotNull { i, s -> Skin.of("$w.rowSkin[$i]", s, problems) } else listOfNotNull(Skin.of("$w.rowSkin", e, problems))
            }

            val colors = o["highlightColors"]?.takeUnless { it is JsonNull }?.let { e ->
                val list = (e as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull }
                    ?: listOf((e as? JsonPrimitive)?.contentOrNull)
                val checked = list.mapIndexed { i, t -> color("$w.highlightColors[$i]", t) }
                if (checked.size > 2) problems += "$w.highlightColors: give one or two colors (front, back)"
                val front = checked.getOrNull(0) ?: base.highlightColors[0]
                val back = checked.getOrNull(1) ?: base.highlightColors.getOrElse(1) { "#000000" }
                listOf(front, back)
            }

            val decal = o["categoryDecal"]?.takeUnless { it is JsonNull }?.let { e ->
                val obj = e as? JsonObject
                val skin = Skin.of("$w.categoryDecal", obj?.let { JsonObject(it - DECAL_KEYS) } ?: e, problems) ?: return@let null
                if (obj == null) return@let SlashDecal(skin)
                val size = (obj["size"] as? JsonPrimitive)?.floatOrNull?.let { v ->
                    if (v < SlashDecal.MIN_SIZE || v > SlashDecal.MAX_SIZE) problems += "$w.categoryDecal.size: $v is outside ${SlashDecal.MIN_SIZE}–${SlashDecal.MAX_SIZE}"
                    v.coerceIn(SlashDecal.MIN_SIZE, SlashDecal.MAX_SIZE)
                } ?: 1.8f
                val frames = obj["frames"]?.let { f ->
                    val v = (f as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull }
                    if (v == null || v.size != 2 || v.any { it == null || it < 1 }) null.also { problems += "$w.categoryDecal.frames: give [columns, rows], each at least 1" }
                    else if (v[0]!! * v[1]!! > SlashDecal.MAX_FRAMES) null.also { problems += "$w.categoryDecal.frames: at most ${SlashDecal.MAX_FRAMES} frames" }
                    else v[0]!! to v[1]!!
                } ?: (1 to 1)
                val fps = (obj["fps"] as? JsonPrimitive)?.floatOrNull?.let { v ->
                    if (v < 0f || v > SlashDecal.MAX_FPS) problems += "$w.categoryDecal.fps: $v is outside 0–${SlashDecal.MAX_FPS}"
                    v.coerceIn(0f, SlashDecal.MAX_FPS)
                } ?: if (frames.first * frames.second > 1) 6f else 0f
                SlashDecal(skin, size, frames.first, frames.second, fps)
            }

            val artRect = o["artRect"]?.takeUnless { it is JsonNull }?.let { e ->
                val v = (e as? JsonArray)?.map { (it as? JsonPrimitive)?.floatOrNull }
                if (v == null || v.size != 4 || v.any { it == null || !it.isFinite() }) null.also { problems += "$w.artRect: give [x, y, width, height] as fractions of the screen" }
                else {
                    val (x, y) = v[0]!!.coerceIn(0f, 1f) to v[1]!!.coerceIn(0f, 1f)
                    val ww = v[2]!!.coerceIn(0.05f, 1f - x).coerceAtLeast(0.05f)
                    val hh = v[3]!!.coerceIn(0.05f, 1f - y).coerceAtLeast(0.05f)
                    if (ww != v[2] || hh != v[3] || x != v[0] || y != v[1]) problems += "$w.artRect: kept on screen"
                    listOf(x, y, ww, hh)
                }
            }

            val bleed = prim("bleed")?.let { p -> p.booleanOrNull.takeIf { !p.isString } ?: null.also { problems += "$w.bleed: ${p.content} isn't true or false" } }
            val seed = prim("seed")?.let { p -> p.intOrNull ?: p.floatOrNull?.toInt() ?: null.also { problems += "$w.seed: ${p.content} isn't a whole number" } }
            val stagger = prim("staggerMs")?.let { number("staggerMs", 0f, MAX_STAGGER_MS.toFloat(), base.staggerMs.toFloat()).toInt() }

            return SlashLayout(
                skewDeg = number("skewDeg", -MAX_SKEW, MAX_SKEW, base.skewDeg),
                tiltMin = tilt?.first ?: base.tiltMin,
                tiltMax = tilt?.second ?: base.tiltMax,
                jitter = number("jitter", 0f, 1f, base.jitter),
                seed = seed ?: base.seed,
                listX = number("listX", 0f, 0.9f, base.listX),
                listY = number("listY", 0f, 0.9f, base.listY),
                listWidth = number("listWidth", MIN_LIST_WIDTH, MAX_LIST_WIDTH, base.listWidth),
                bleed = bleed ?: base.bleed,
                textAlign = pick("textAlign", SlashTextAlign.entries, base.textAlign),
                rowSkins = rowSkins ?: base.rowSkins,
                highlight = pick("highlight", SlashHighlight.entries, base.highlight),
                highlightColors = colors ?: base.highlightColors,
                focusedSkin = if ("focusedSkin" in o) Skin.of("$w.focusedSkin", o["focusedSkin"], problems) else base.focusedSkin,
                focusedScale = number("focusedScale", MIN_FOCUSED_SCALE, MAX_FOCUSED_SCALE, base.focusedScale),
                focusedContent = pick("focusedContent", SlashFocusedContent.entries, base.focusedContent),
                focusedHeight = number("focusedHeight", MIN_FOCUSED_HEIGHT, MAX_FOCUSED_HEIGHT, base.focusedHeight),
                titleCase = pick("titleCase", SlashTitleCase.entries, base.titleCase),
                outline = number("outline", 0f, MAX_OUTLINE, base.outline),
                rowTextColor = prim("rowTextColor")?.let { color("$w.rowTextColor", it.contentOrNull) } ?: base.rowTextColor,
                focusedTextColor = if ("focusedTextColor" in o) prim("focusedTextColor")?.let { color("$w.focusedTextColor", it.contentOrNull) } else base.focusedTextColor,
                staggerMs = stagger ?: base.staggerMs,
                transition = pick("transition", SlashTransition.entries, base.transition),
                pop = number("pop", 0f, MAX_POP, base.pop),
                categories = pick("categories", SlashCategories.entries, base.categories),
                categoryDecal = if ("categoryDecal" in o) decal else base.categoryDecal,
                categoryY = number("categoryY", 0f, 0.9f, base.categoryY),
                art = pick("art", SlashArt.entries, base.art),
                artRect = artRect ?: base.artRect,
                artTilt = number("artTilt", -MAX_ART_TILT, MAX_ART_TILT, base.artTilt),
                artFrame = if ("artFrame" in o) Skin.of("$w.artFrame", o["artFrame"], problems) else base.artFrame,
            )
        }
    }
}

/**
 * The `SLASH` style's geometry, pure so it is unit tested: per-row variation from a seed, the
 * highlight's corners, the cascade's timing and the shards a list breaks into.
 */
object SlashGeometry {
    /** A stable value in 0..1 for ([seed], [index], [salt]): the same every frame and every visit. */
    fun hash(seed: Int, index: Int, salt: Int = 0): Float {
        var h = seed * 0x27d4eb2d xor (index * 0x165667b1) xor (salt * 0x9e3779b9.toInt())
        h = h xor (h ushr 15)
        h *= 0x2c1b3c6d
        h = h xor (h ushr 12)
        h *= 0x297a2d39
        h = h xor (h ushr 15)
        return (h ushr 8) / 16777216f
    }

    /**
     * A stable pick of one of [n] for row [index]; neighbouring rows always differ when [n] > 1.
     * Each row moves on from the one before by 1..n−1 places, so it walks the list once (rows are
     * picked only as they are composed, a screenful at a time).
     */
    fun pick(seed: Int, index: Int, n: Int): Int {
        if (n <= 1) return 0
        var p = (hash(seed, 0, 1) * n).toInt().coerceIn(0, n - 1)
        for (i in 1..index) p = (p + 1 + (hash(seed, i, 2) * (n - 1)).toInt().coerceIn(0, n - 2)) % n
        return p
    }

    /** Row [index]'s rotation in degrees, within [SlashLayout.tiltMin]..[SlashLayout.tiltMax]. */
    fun rowTilt(l: SlashLayout, index: Int): Float = l.tiltMin + (l.tiltMax - l.tiltMin) * hash(l.seed, index, 3)

    /** Row [index]'s sideways offset, as a fraction of the row height (−0.5..0.5 times jitter). */
    fun rowNudge(l: SlashLayout, index: Int): Float = (hash(l.seed, index, 4) - 0.5f) * l.jitter

    /**
     * The highlight's four corners (x0, y0, … x3, y3, clockwise from top left) inside a [w] × [h]
     * box padded by [pad] on every side: each corner moves inward by up to [jitter] of the padding
     * plus a quarter of the box, so the quad stays inside the padded box and never folds.
     */
    fun highlightCorners(seed: Int, index: Int, w: Float, h: Float, pad: Float, jitter: Float): FloatArray {
        val j = jitter.coerceIn(0f, 1f)
        val rx = pad + w * 0.06f
        val ry = pad + h * 0.12f
        fun d(k: Int, r: Float) = hash(seed, index, 10 + k) * r * j
        return floatArrayOf(
            -pad + d(0, rx), -pad + d(1, ry),
            w + pad - d(2, rx), -pad + d(3, ry),
            w + pad - d(4, rx), h + pad - d(5, ry),
            -pad + d(6, rx), h + pad - d(7, ry),
        )
    }

    /**
     * The first row on screen after the focus moves to [focused] in a list of [count] with
     * [visible] rows of room, from [top] before: the list holds still while the focus stays a row
     * inside either edge, and scrolls just enough when it doesn't (a short list never scrolls).
     */
    fun nextTop(top: Int, focused: Int, count: Int, visible: Int): Int {
        if (count <= visible || visible <= 0) return 0
        val margin = if (visible >= 4) 1 else 0
        var t = top
        if (focused < t + margin) t = focused - margin
        if (focused > t + visible - 1 - margin) t = focused - visible + 1 + margin
        return t.coerceIn(0, count - visible)
    }

    /**
     * A row's box across the screen, in px: [left] and [width] of the art, and how far in from
     * its edges the text starts ([textStart]) and ends ([textEnd]). With [SlashLayout.bleed] the
     * art reaches past the left edge by [bleedPx] and `listX` only places the text.
     */
    data class RowBox(val left: Float, val width: Float, val textStart: Float, val textEnd: Float)

    fun rowBox(l: SlashLayout, screenW: Float, pad: Float, bleedPx: Float): RowBox {
        val x = screenW * l.listX
        val right = screenW * (l.listX + l.listWidth)
        return if (l.bleed) {
            val left = -bleedPx
            RowBox(left, right - left, x - left, pad)
        } else {
            RowBox(x, right - x, pad, pad)
        }
    }

    /**
     * When row [index] starts sliding in, in ms: [staggerMs] apart, squeezed so the last of [count]
     * rows starts by [SlashLayout.CASCADE_CAP_MS] minus one row's own [rowMs]. Zero when [instant].
     */
    fun staggerDelay(index: Int, count: Int, staggerMs: Int, rowMs: Int, instant: Boolean): Int {
        if (instant || staggerMs <= 0 || index <= 0 || count <= 1) return 0
        val room = (SlashLayout.CASCADE_CAP_MS - rowMs).coerceAtLeast(0)
        val step = minOf(staggerMs.toFloat(), room.toFloat() / (count - 1))
        return (step * index.coerceAtMost(count - 1)).toInt()
    }

    /** The cascade's whole length for [count] rows. */
    fun cascadeMs(count: Int, staggerMs: Int, rowMs: Int, instant: Boolean): Int =
        if (instant) 0 else staggerDelay(count - 1, count, staggerMs, rowMs, false) + rowMs

    /**
     * [n] (3–5) polygons that together cover a [w] × [h] box, cut by slanted lines from [seed]:
     * each is four corners (x0, y0, … x3, y3). Their direction to fly out is their centre's side.
     */
    fun shards(seed: Int, w: Float, h: Float, n: Int = 4): List<FloatArray> {
        val k = n.coerceIn(3, 5)
        // Cuts across the box: top and bottom x positions, sorted so neighbours never cross.
        val tops = (1 until k).map { i -> w * (i + (hash(seed, i, 20) - 0.5f) * 0.6f) / k }.sorted()
        val bottoms = (1 until k).map { i -> w * (i + (hash(seed, i, 21) - 0.5f) * 0.6f) / k }.sorted()
        val xt = listOf(0f) + tops + w
        val xb = listOf(0f) + bottoms + w
        return (0 until k).map { i -> floatArrayOf(xt[i], 0f, xt[i + 1], 0f, xb[i + 1], h, xb[i], h) }
    }
}
