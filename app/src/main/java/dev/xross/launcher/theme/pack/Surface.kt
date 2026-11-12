package dev.xross.launcher.theme.pack

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A surface as a pack declares it in `surfaces` (usually imported from `surfaces.json`): cards,
 * panels, toasts and pills are drawn from atomic layers (shape, fill, sheen, borders, shadows,
 * glow) instead of a fixed style enum. [resolveSurface] validates it into a [Surface].
 *
 * ```json
 * "glassCard": {
 *   "shape": { "type": "ROUNDED", "radiusDp": 12 },
 *   "fill": { "type": "LINEAR_GRADIENT", "angle": 180, "stops": [["#2A2A40", 0.0, 0.7], ["#0A0A12", 1.0, 0.9]] },
 *   "sheen": { "heightPercent": 0.35, "gradient": [["#FFFFFF", 0.12], ["#FFFFFF", 0.0]] },
 *   "borders": [{ "type": "INNER", "widthDp": 1.0, "color": "#FFFFFF", "opacity": 0.15 }],
 *   "shadows": [{ "offsetDp": [0, 4], "blurDp": 12, "color": "#000000", "opacity": 0.4 }],
 *   "glow": { "color": "#FFB5C5", "radiusDp": 14, "intensity": 0.7 }
 * }
 * ```
 */
@Serializable
data class SurfaceSpec(
    val label: String = "",
    val shape: ShapeSpec? = null,
    val fill: FillSpec? = null,
    /** Read but not drawn yet: backdrop blur comes in 2b.4 (Android 12+, over art only). */
    val backdropBlurDp: Float = 0f,
    val sheen: SheenSpec? = null,
    val borders: List<BorderSpec> = emptyList(),
    val shadows: List<ShadowSpec> = emptyList(),
    val glow: GlowSpec? = null,
) {
    /** `ROUNDED` (with no radius: the component's own corners), `CHAMFERED`, `RECTANGLE`. */
    @Serializable
    data class ShapeSpec(val type: String = "ROUNDED", val radiusDp: Float? = null, val cutDp: List<Float> = emptyList())

    /** `SOLID` (`color`), `LINEAR_GRADIENT` (`angle`, `stops` of `[color, position, opacity?]`), `SWEEP_GRADIENT` (`colors`). */
    @Serializable
    data class FillSpec(
        val type: String = "SOLID",
        val color: String? = null,
        val opacity: Float = 1f,
        val angle: Float = 180f,
        val stops: List<JsonArray> = emptyList(),
        val colors: List<String> = emptyList(),
    )

    /** A highlight over the top [heightPercent]; `gradient` stops are `[color, opacity]`, spread evenly. */
    @Serializable
    data class SheenSpec(val heightPercent: Float = 0.35f, val gradient: List<JsonArray> = emptyList())

    /** `type` `INNER` / `OUTER`, or `side` `TOP` / `LEFT` / `BOTTOM` / `RIGHT`; a `color` or a `brush`. */
    @Serializable
    data class BorderSpec(
        val type: String = "INNER",
        val side: String? = null,
        val widthDp: Float = 1f,
        val color: String? = null,
        val opacity: Float = 1f,
        val brush: FillSpec? = null,
    )

    @Serializable
    data class ShadowSpec(
        val offsetDp: List<Float> = listOf(0f, 0f),
        val blurDp: Float = 8f,
        val color: String = "#000000",
        val opacity: Float = 0.4f,
    )

    @Serializable
    data class GlowSpec(val color: String = "#FFFFFF", val radiusDp: Float = 10f, val intensity: Float = 0.6f)
}

/** A validated surface, ready to draw (`Modifier.xrossSurface`). Colors are ARGB. */
@Immutable
data class Surface(
    val id: String,
    val label: String,
    val shape: SurfaceShape,
    val fill: SurfacePaint?,
    val sheen: Sheen?,
    val borders: List<SurfaceBorder>,
    val shadows: List<SurfaceShadow>,
    val glow: SurfaceShadow?,
    val backdropBlurDp: Float,
) {
    companion object {
        /** Used only if the Default pack's surfaces are missing: a plain dark tint. */
        val FALLBACK = Surface(
            id = "FALLBACK",
            label = "Plain",
            shape = SurfaceShape.Component,
            fill = SurfacePaint.Solid(0xCC101018.toInt()),
            sheen = null,
            borders = emptyList(),
            shadows = emptyList(),
            glow = null,
            backdropBlurDp = 0f,
        )
    }
}

@Immutable
sealed interface SurfaceShape {
    /** The component's own shape (each card keeps its corner radius). */
    data object Component : SurfaceShape
    data object Rectangle : SurfaceShape
    data class Rounded(val radiusDp: Float) : SurfaceShape

    /** Corner cuts in dp: top-left, top-right, bottom-right, bottom-left. */
    data class Chamfered(val cutsDp: List<Float>) : SurfaceShape
}

@Immutable
sealed interface SurfacePaint {
    data class Solid(val argb: Int) : SurfacePaint

    /** CSS-style [angleDeg]: 180 runs top to bottom, 90 left to right. */
    data class Linear(val angleDeg: Float, val stops: List<Pair<Float, Int>>) : SurfacePaint
    data class Sweep(val colors: List<Int>) : SurfacePaint
}

@Immutable
data class Sheen(val heightFraction: Float, val colors: List<Int>)

enum class BorderKind { INNER, OUTER, TOP, LEFT, BOTTOM, RIGHT }

@Immutable
data class SurfaceBorder(val kind: BorderKind, val widthDp: Float, val paint: SurfacePaint)

@Immutable
data class SurfaceShadow(val dxDp: Float, val dyDp: Float, val blurDp: Float, val argb: Int)

/** Caps that keep surfaces cheap on small handhelds (Roadmap §4.3, open surface definitions). */
object SurfaceLimits {
    const val MAX_BORDERS = 3
    const val MAX_SHADOWS = 2
    const val MAX_GLOW_DP = 24f
    const val MAX_BORDER_DP = 8f
    const val MAX_BLUR_DP = 48f
    const val MAX_CUT_DP = 48f
    const val MAX_RADIUS_DP = 64f
    const val MAX_STOPS = 8
}

/** The four built-in styles, defined in the Default pack's `surfaces.json`. */
enum class SurfacePreset(val label: String) {
    FLAT_GLASS("Flat glass"),
    Y2K_GLOSSY_BEZEL("Y2K glossy bezel"),
    OLED_TRUE_BLACK("OLED true black"),
    EINK_HIGH_CONTRAST("E-Ink high contrast"),
}

/**
 * Validates [spec] into a [Surface]. Bad colors and unknown types are reported in [problems] and
 * dropped; numbers are clamped; extra borders and shadows beyond the caps are ignored.
 */
fun resolveSurface(id: String, spec: SurfaceSpec, problems: MutableList<String>): Surface {
    fun bad(what: String) {
        problems += "Surface $id: $what"
    }
    fun color(text: String?, opacity: Float = 1f): Int? {
        val c = text?.let(ThemeManifest::parseColor) ?: return null.also { if (text != null) bad("bad color $text") }
        return withOpacity(c, opacity)
    }

    val shape = spec.shape?.let { s ->
        when (s.type.uppercase()) {
            "ROUNDED" -> s.radiusDp?.let { SurfaceShape.Rounded(it.clamp(0f, SurfaceLimits.MAX_RADIUS_DP)) } ?: SurfaceShape.Component
            "RECTANGLE" -> SurfaceShape.Rectangle
            "CHAMFERED" -> {
                val c = s.cutDp.map { it.clamp(0f, SurfaceLimits.MAX_CUT_DP) }
                SurfaceShape.Chamfered(
                    when (c.size) {
                        0 -> List(4) { 8f }
                        1 -> List(4) { c[0] }
                        2 -> listOf(c[0], c[1], c[0], c[1])
                        else -> (c + List(4) { 0f }).take(4)
                    },
                )
            }
            else -> SurfaceShape.Component.also { bad("unknown shape ${s.type}") }
        }
    } ?: SurfaceShape.Component

    fun paint(f: SurfaceSpec.FillSpec): SurfacePaint? = when (f.type.uppercase()) {
        "SOLID" -> color(f.color, f.opacity)?.let { SurfacePaint.Solid(it) }
        "LINEAR_GRADIENT" -> {
            val stops = f.stops.take(SurfaceLimits.MAX_STOPS).mapIndexedNotNull { i, stop ->
                val c = stop.getOrNull(0)?.jsonPrimitive?.contentOrNull
                val pos = stop.getOrNull(1)?.jsonPrimitive?.floatOrNull ?: (i.toFloat() / (f.stops.size - 1).coerceAtLeast(1))
                val alpha = stop.getOrNull(2)?.jsonPrimitive?.floatOrNull ?: 1f
                color(c, alpha * f.opacity)?.let { pos.clamp(0f, 1f) to it }
            }.sortedBy { it.first }
            if (stops.size >= 2) SurfacePaint.Linear(f.angle, stops) else null.also { bad("a gradient needs two stops") }
        }
        "SWEEP_GRADIENT" -> {
            val colors = f.colors.take(SurfaceLimits.MAX_STOPS).mapNotNull { color(it, f.opacity) }
            if (colors.size >= 2) SurfacePaint.Sweep(colors) else null.also { bad("a sweep needs two colors") }
        }
        else -> null.also { bad("unknown fill ${f.type}") }
    }

    val sheen = spec.sheen?.let { s ->
        val colors = s.gradient.take(SurfaceLimits.MAX_STOPS).mapNotNull { stop ->
            color(stop.getOrNull(0)?.jsonPrimitive?.contentOrNull, stop.getOrNull(1)?.jsonPrimitive?.floatOrNull ?: 1f)
        }
        if (colors.isEmpty()) null else Sheen(s.heightPercent.clamp(0.05f, 1f), if (colors.size == 1) colors + colors else colors)
    }

    if (spec.borders.size > SurfaceLimits.MAX_BORDERS) bad("only ${SurfaceLimits.MAX_BORDERS} borders are drawn")
    val borders = spec.borders.take(SurfaceLimits.MAX_BORDERS).mapNotNull { b ->
        val kind = when (val side = b.side?.uppercase()) {
            null -> when (b.type.uppercase()) {
                "INNER" -> BorderKind.INNER
                "OUTER" -> BorderKind.OUTER
                else -> null.also { bad("unknown border ${b.type}") }
            }
            else -> runCatching { BorderKind.valueOf(side) }.getOrNull()?.takeIf { it.ordinal >= BorderKind.TOP.ordinal }
                ?: null.also { bad("unknown border side ${b.side}") }
        } ?: return@mapNotNull null
        val p = b.brush?.let { paint(it.copy(opacity = it.opacity * b.opacity)) }
            ?: color(b.color ?: "#FFFFFF", b.opacity)?.let { SurfacePaint.Solid(it) }
        p?.let { SurfaceBorder(kind, b.widthDp.clamp(0f, SurfaceLimits.MAX_BORDER_DP), it) }
    }

    if (spec.shadows.size > SurfaceLimits.MAX_SHADOWS) bad("only ${SurfaceLimits.MAX_SHADOWS} shadows are drawn")
    val shadows = spec.shadows.take(SurfaceLimits.MAX_SHADOWS).mapNotNull { s ->
        color(s.color, s.opacity)?.let {
            SurfaceShadow(
                s.offsetDp.getOrElse(0) { 0f }.clamp(-24f, 24f),
                s.offsetDp.getOrElse(1) { 0f }.clamp(-24f, 24f),
                s.blurDp.clamp(0f, SurfaceLimits.MAX_GLOW_DP),
                it,
            )
        }
    }
    val glow = spec.glow?.let { g ->
        color(g.color, g.intensity.clamp(0f, 1f))?.let { SurfaceShadow(0f, 0f, g.radiusDp.clamp(0f, SurfaceLimits.MAX_GLOW_DP), it) }
    }

    return Surface(
        id = id,
        label = spec.label.ifBlank { SurfacePreset.entries.firstOrNull { it.name == id }?.label ?: id },
        shape = shape,
        fill = spec.fill?.let(::paint),
        sheen = sheen,
        borders = borders,
        shadows = shadows,
        glow = glow,
        backdropBlurDp = spec.backdropBlurDp.clamp(0f, SurfaceLimits.MAX_BLUR_DP),
    )
}

private fun Float.clamp(lo: Float, hi: Float): Float = if (isFinite()) coerceIn(lo, hi) else lo

/** [argb] with its alpha multiplied by [opacity] (0–1). */
internal fun withOpacity(argb: Int, opacity: Float): Int {
    val a = Math.round((argb ushr 24) * opacity.clamp(0f, 1f)).coerceIn(0, 255)
    return (a shl 24) or (argb and 0xFFFFFF)
}
