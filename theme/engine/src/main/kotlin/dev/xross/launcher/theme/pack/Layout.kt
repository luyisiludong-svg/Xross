package dev.xross.launcher.theme.pack

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * The `layout` block of `theme.json` (Roadmap Appendix A.5). Every field is optional; what a pack
 * leaves out comes from the Default pack. [resolveLayout] validates it into a [ThemeLayout].
 *
 * ```json
 * "layout": {
 *   "preset": "CLASSIC_PS3",
 *   "crosshairX": 0.30, "crosshairY": 0.35,
 *   "cardAspectRatio": "16:9",
 *   "tile": { "music": "1:1", "photo": "3:4" },
 *   "itemSpacingDp": 12, "focusedScale": 1.18, "unfocusedAlpha": 0.45, "uiScale": 1.0,
 *   "focusDivider": "FULL_BLEED_LINE", "drillDownStyle": "CHEVRON_COLLAPSE",
 *   "textOverflow": "MARQUEE", "motionProfile": "CRISP",
 *   "spring": { "stiffness": 600, "dampingRatio": 0.9 },
 *   "menuStyle": "SLASH", "slash": { "skewDeg": -12, "rowSkin": "menu/row.webp" }
 * }
 * ```
 */
@Serializable
data class LayoutSpec(
    /** A [CrosshairPreset] name; [crosshairX] / [crosshairY] win over it. */
    val preset: String? = null,
    /** The active column's centre, as a fraction of the width. */
    val crosshairX: Float? = null,
    /** The category bar's centre, as a fraction of the height. */
    val crosshairY: Float? = null,
    /** Tile aspect for every category ("16:9", "2:1", "1:1", "3:4", "2:3", "4:1", or "1.5"). */
    val cardAspectRatio: String? = null,
    /** Category id ("games", "music", "photo", "video", "apps", "settings") → tile aspect. */
    val tile: Map<String, String> = emptyMap(),
    /** Gap between list rows: 6 tight, 12 standard (the default), 20 spacious. */
    val itemSpacingDp: Float? = null,
    val focusedScale: Float? = null,
    val unfocusedAlpha: Float? = null,
    val uiScale: Float? = null,
    val focusDivider: String? = null,
    val drillDownStyle: String? = null,
    val textOverflow: String? = null,
    val motionProfile: String? = null,
    /** Short name for [motionProfile]. */
    val motion: String? = null,
    /** Optional spring parameters over the motion profile's. */
    val spring: SpringSpecJson? = null,
    /**
     * A [MenuStyle] name (Menu styles slice 1). Only the base `layout` and a variant's set it: a
     * rule that does is reported and ignored, since switching the whole menu while navigating
     * would be jarring.
     */
    val menuStyle: String? = null,
    /**
     * The `SLASH` style's block ([SlashLayout], Menu styles slices 2–4), kept as JSON so skins can
     * be a path or an object and one bad key never costs the manifest. Base `layout` only.
     */
    val slash: kotlinx.serialization.json.JsonElement? = null,
) {
    @Serializable
    data class SpringSpecJson(val stiffness: Float? = null, val dampingRatio: Float? = null)
}

/** The pre-variants name of a `categoryOverrides` entry. */
typealias CategoryOverride = ThemeOverride

/** Crosshair anchors (Appendix A.5): where the category bar and the active column cross. */
enum class CrosshairPreset(val x: Float, val y: Float, val label: String) {
    CLASSIC_PS3(0.30f, 0.35f, "Classic PS3"),
    PSP_CENTER(0.50f, 0.50f, "PSP centre"),
    /** The bar near the top, leaving ~80% of the screen to the list and the media stage. */
    MODERN_WIDESCREEN(0.18f, 0.20f, "Modern widescreen"),
    ;

    companion object {
        fun byName(name: String?): CrosshairPreset? = name?.let { n ->
            val k = n.trim().uppercase(Locale.ROOT).replace(' ', '_').replace('-', '_')
            entries.firstOrNull { it.name == k } ?: if (k == "PSP_CENTRE") PSP_CENTER else null
        }
    }
}

/** A hairline between the focused row's title and its subtitle. */
enum class FocusDividerStyle {
    NONE,

    /** From the focused tile's right edge to the screen's right edge. */
    FULL_BLEED_LINE,

    /** Under the title only, fading out to the right: the built-in look. */
    TITLE_UNDERLINE,
}

/** How the open list shows where it is in the tree. */
enum class DrillDownStyle {
    /** The opened folder's icon parks beside the list, no text path (PS3 / PSP). */
    XMB_FOLDER_COLUMN,

    /** The category bar collapses and a ◀ sits beside the focused item. */
    CHEVRON_COLLAPSE,

    /** A text path beside the open category: the built-in look. */
    BREADCRUMB,
}

/**
 * How the menu is drawn (Menu styles). Every style uses the same navigation state and input;
 * the XMB-only `layout` fields (crosshair, tiles, divider, drill-down style) are ignored by the
 * others, without a problem, so one pack can offer several styles as variants.
 */
enum class MenuStyle(val label: String) {
    /** The cross media bar: the built-in look. */
    XMB("XMB"),

    /** A bold, slanted list of art-skinned rows ([SlashLayout]). */
    SLASH("Slash"),
    ;

    companion object {
        fun byName(name: String?): MenuStyle? = name?.let { n ->
            val k = n.trim().uppercase(Locale.ROOT).replace(' ', '_').replace('-', '_')
            entries.firstOrNull { it.name == k }
        }
    }
}

/** Titles too long for their row. */
enum class TextOverflowMode { MARQUEE, EDGE_FADE, ELLIPSIS }

/**
 * Navigation springs (Appendix A.5). [CRISP] is the built-in feel; [INSTANT] has no animation at
 * all (E-Ink). The spec's long names are aliases.
 */
enum class MotionProfile(val stiffness: Float, val dampingRatio: Float) {
    /** Critically damped, ~200 ms settle. */
    CRISP(520f, 1f),

    /** Stiffer, settles faster. */
    SNAPPY(800f, 1f),

    /** Softer, with a slight overshoot. */
    BOUNCY(450f, 0.75f),

    /** No animation. */
    INSTANT(Float.POSITIVE_INFINITY, 1f),
    ;

    companion object {
        private val ALIASES = mapOf(
            "PS3_CRISP" to CRISP,
            "PSP_SNAPPY" to SNAPPY,
            "Y2K_BOUNCY" to BOUNCY,
            "EINK_INSTANT" to INSTANT,
            "E_INK_INSTANT" to INSTANT,
        )

        fun byName(name: String?): MotionProfile? = name?.let { n ->
            val k = n.trim().uppercase(Locale.ROOT).replace(' ', '_').replace('-', '_')
            entries.firstOrNull { it.name == k } ?: ALIASES[k]
        }
    }
}

/**
 * The motion the UI animates with: a [MotionProfile] and, optionally, a pack's own spring
 * [stiffness] and [dampingRatio] over the profile's. Numbers only; `:app` maps them to Compose
 * animation specs (`MotionSpecs.kt`: `nav()`, `tween()`).
 */
@Immutable
data class Motion(
    val profile: MotionProfile = MotionProfile.CRISP,
    val stiffness: Float = profile.stiffness,
    val dampingRatio: Float = profile.dampingRatio,
) {
    val instant: Boolean get() = profile == MotionProfile.INSTANT

    companion object {
        val DEFAULT = Motion()
    }
}

/**
 * A resolved layout: every value is set and clamped. Crosshair fractions are what the pack asked
 * for; the screen clamps them per display ([Crosshair.clamp]).
 */
@Immutable
data class ThemeLayout(
    val crosshairX: Float = 0.30f,
    val crosshairY: Float = 0.38f,
    /** Tile aspect (width / height) for every category, or null for each tile's own shape. */
    val cardAspect: Float? = null,
    /** Per category id, over [cardAspect]. */
    val categoryAspects: Map<String, Float> = emptyMap(),
    /** Per Xross platform id (`systemOverrides.<id>.cardAspectRatio`), over the category's, for that system's game tiles. */
    val systemAspects: Map<String, Float> = emptyMap(),
    val itemSpacingDp: Float = 12f,
    val focusedScale: Float = 1.32f,
    /** Opacity of rows without the focus; null keeps the built-in mix (full icons, 72% titles). */
    val unfocusedAlpha: Float? = null,
    val uiScale: Float = 1f,
    val focusDivider: FocusDividerStyle = FocusDividerStyle.TITLE_UNDERLINE,
    val drillDown: DrillDownStyle = DrillDownStyle.BREADCRUMB,
    val textOverflow: TextOverflowMode = TextOverflowMode.ELLIPSIS,
    val motion: Motion = Motion.DEFAULT,
    val menuStyle: MenuStyle = MenuStyle.XMB,
    /** How the `SLASH` style draws; read only under [MenuStyle.SLASH]. */
    val slash: SlashLayout = SlashLayout.DEFAULT,
) {
    /** The tile aspect in [categoryId], or null for the tile's own shape. */
    fun aspectFor(categoryId: String): Float? = categoryAspects[categoryId] ?: cardAspect

    /** The tile aspect of a game of [systemId] in [categoryId]: the system's, else the category's. */
    fun aspectFor(categoryId: String, systemId: String?): Float? = systemId?.let(systemAspects::get) ?: aspectFor(categoryId)

    companion object {
        val DEFAULT = ThemeLayout()
    }
}

object LayoutLimits {
    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 1.5f
    const val MIN_FOCUS_SCALE = 1f
    const val MAX_FOCUS_SCALE = 1.35f
    const val MIN_UNFOCUSED_ALPHA = 0.1f
    const val MAX_UNFOCUSED_ALPHA = 0.8f
    const val MAX_SPACING_DP = 32f

    /** Narrowest and widest tiles: a 1:3 strip up to the 4:1 text pill. */
    const val MIN_ASPECT = 1f / 3f
    const val MAX_ASPECT = 4f
    const val MIN_STIFFNESS = 50f
    const val MAX_STIFFNESS = 5000f
    const val MIN_DAMPING = 0.3f
    const val MAX_DAMPING = 2f
}

/** "16:9", "4:1", "1.5" → width / height; null for anything else. */
fun parseAspect(text: String?): Float? {
    val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = t.split(':', '/', 'x', 'X')
    val value = when (parts.size) {
        1 -> parts[0].trim().toFloatOrNull()
        2 -> {
            val w = parts[0].trim().toFloatOrNull()
            val h = parts[1].trim().toFloatOrNull()
            if (w == null || h == null || h <= 0f) null else w / h
        }
        else -> null
    }
    return value?.takeIf { it.isFinite() && it > 0f }
}

/**
 * Validates [spec] over [base] (the Default pack's layout, or [ThemeLayout.DEFAULT]). Unknown names
 * are reported in [problems] and keep [base]'s value; numbers are clamped to [LayoutLimits].
 * [overrides] are the pack's `categoryOverrides`; their `cardAspectRatio` wins over `layout.tile`.
 * [systems] are its `systemOverrides` keyed by platform id ([resolveSystemKeys]).
 */
fun resolveLayout(
    spec: LayoutSpec?,
    overrides: Map<String, ThemeOverride>,
    base: ThemeLayout,
    problems: MutableList<String>,
    systems: Map<String, ThemeOverride> = emptyMap(),
): ThemeLayout {
    if (spec == null && (overrides.values + systems.values).all { it.cardAspectRatio == null }) return base
    val s = spec ?: LayoutSpec()

    fun <E> named(field: String, value: String?, parse: (String) -> E?, fallback: E): E {
        if (value == null) return fallback
        return parse(value) ?: fallback.also { problems += "layout.$field: unknown value $value" }
    }
    fun <E : Enum<E>> enumOf(field: String, value: String?, entries: List<E>, fallback: E): E = named(field, value, { v ->
        val k = v.trim().uppercase(Locale.ROOT).replace(' ', '_').replace('-', '_')
        entries.firstOrNull { it.name == k }
    }, fallback)
    fun number(field: String, value: Float?, lo: Float, hi: Float, fallback: Float): Float {
        if (value == null) return fallback
        if (!value.isFinite()) return fallback.also { problems += "layout.$field: not a number" }
        if (value < lo || value > hi) problems += "layout.$field: $value is outside $lo–$hi"
        return value.coerceIn(lo, hi)
    }
    fun aspect(field: String, value: String?): Float? {
        if (value == null) return null
        val a = parseAspect(value) ?: return null.also { problems += "$field: $value isn't an aspect ratio like 16:9" }
        return a.coerceIn(LayoutLimits.MIN_ASPECT, LayoutLimits.MAX_ASPECT)
    }

    val preset = named("preset", s.preset, CrosshairPreset::byName, null)
    val aspects = LinkedHashMap(base.categoryAspects)
    for ((cat, value) in s.tile) aspect("layout.tile.$cat", value)?.let { aspects[cat.trim().lowercase(Locale.ROOT)] = it }
    for ((cat, o) in overrides) aspect("categoryOverrides.$cat.cardAspectRatio", o.cardAspectRatio)?.let { aspects[cat.trim().lowercase(Locale.ROOT)] = it }
    val systemAspects = LinkedHashMap(base.systemAspects)
    for ((id, o) in systems) aspect("systemOverrides.$id.cardAspectRatio", o.cardAspectRatio)?.let { systemAspects[id] = it }

    val profile = named("motionProfile", s.motionProfile ?: s.motion, MotionProfile::byName, base.motion.profile)
    val profileChanged = profile != base.motion.profile
    val motion = Motion(
        profile = profile,
        stiffness = number(
            "spring.stiffness", s.spring?.stiffness, LayoutLimits.MIN_STIFFNESS, LayoutLimits.MAX_STIFFNESS,
            if (profileChanged) profile.stiffness else base.motion.stiffness,
        ).let { if (profile == MotionProfile.INSTANT) profile.stiffness else it },
        dampingRatio = number(
            "spring.dampingRatio", s.spring?.dampingRatio, LayoutLimits.MIN_DAMPING, LayoutLimits.MAX_DAMPING,
            if (profileChanged) profile.dampingRatio else base.motion.dampingRatio,
        ),
    )

    return ThemeLayout(
        crosshairX = number("crosshairX", s.crosshairX, 0f, 1f, preset?.x ?: base.crosshairX),
        crosshairY = number("crosshairY", s.crosshairY, 0f, 1f, preset?.y ?: base.crosshairY),
        cardAspect = aspect("layout.cardAspectRatio", s.cardAspectRatio) ?: base.cardAspect,
        categoryAspects = aspects,
        systemAspects = systemAspects,
        itemSpacingDp = number("itemSpacingDp", s.itemSpacingDp, 0f, LayoutLimits.MAX_SPACING_DP, base.itemSpacingDp),
        focusedScale = number("focusedScale", s.focusedScale, LayoutLimits.MIN_FOCUS_SCALE, LayoutLimits.MAX_FOCUS_SCALE, base.focusedScale),
        unfocusedAlpha = s.unfocusedAlpha?.let {
            number("unfocusedAlpha", it, LayoutLimits.MIN_UNFOCUSED_ALPHA, LayoutLimits.MAX_UNFOCUSED_ALPHA, base.unfocusedAlpha ?: 1f)
        } ?: base.unfocusedAlpha,
        uiScale = number("uiScale", s.uiScale, LayoutLimits.MIN_SCALE, LayoutLimits.MAX_SCALE, base.uiScale),
        focusDivider = enumOf("focusDivider", s.focusDivider, FocusDividerStyle.entries, base.focusDivider),
        drillDown = enumOf("drillDownStyle", s.drillDownStyle, DrillDownStyle.entries, base.drillDown),
        textOverflow = enumOf("textOverflow", s.textOverflow, TextOverflowMode.entries, base.textOverflow),
        motion = motion,
        menuStyle = named("menuStyle", s.menuStyle, MenuStyle::byName, base.menuStyle),
        slash = SlashLayout.of(s.slash, base.slash, problems),
    )
}

/**
 * Where the category bar and the active column cross on a [width] × [height] screen, from the
 * layout's fractions, clamped so the screen always shows one whole neighbouring category on each
 * side of the column and the focused row (with one row below it) under the bar.
 *
 * [s] is the usable short side ([dev.xross.launcher.ui.XmbMetrics.s]); [categorySpacing] and
 * [categoryIcon] are the bar's cell width and icon size in pixels.
 */
object Crosshair {
    /** Room a neighbouring category needs beside the column: a whole cell plus its icon's half. */
    fun neighbourRoom(categorySpacing: Float, categoryIcon: Float): Float = categorySpacing + categoryIcon * 0.8f

    fun clampX(fraction: Float, width: Float, categorySpacing: Float, categoryIcon: Float): Float {
        val room = neighbourRoom(categorySpacing, categoryIcon)
        // Past the middle the list's text has no room; the right neighbour needs its cell too.
        val hi = minOf(width - room, width * 0.5f)
        val x = width * fraction
        return if (room >= hi) room else x.coerceIn(room, hi)
    }

    /**
     * [s]-relative bounds: items above the bar stay on screen, and so do the focused row and the
     * row below it, which need [listBelow] pixels under the bar.
     */
    fun clampY(fraction: Float, height: Float, s: Float, listBelow: Float): Float {
        // On tall (portrait) screens the bar follows the short side, so it doesn't drift away from the list.
        val y = minOf(height * fraction, s * fraction * 1.2f)
        val lo = s * 0.20f
        val hi = height - listBelow
        return if (lo >= hi) lo else y.coerceIn(lo, hi)
    }
}

/**
 * The user's own layout values over the theme pack (Phase 2b.8a, Settings ▸ Theme ▸ Layout),
 * saved as `display.layout` in `settings.json`. Every field is null to follow the pack (its base,
 * variant, category and system overrides and `rules.json` layout patches); a value set here wins
 * in every context, like `display.effects`. The quick profile still applies after it, and only
 * to motion.
 *
 * ```json
 * "layout": { "preset": "PSP_CENTER", "uiScale": 1.1, "itemSpacingDp": 6, "focusedScale": null }
 * ```
 * Decoded leniently ([LenientLayoutOverrides]): a value of the wrong type drops on its own, an
 * unknown preset follows the pack and numbers clamp to [LayoutLimits] ([normalized]).
 */
@Immutable
@Serializable
data class LayoutOverrides(
    /** A [CrosshairPreset] name; it replaces the pack's `crosshairX` / `crosshairY`. */
    val preset: String? = null,
    val uiScale: Float? = null,
    val itemSpacingDp: Float? = null,
    val focusedScale: Float? = null,
) {
    val crosshair: CrosshairPreset? get() = CrosshairPreset.byName(preset)

    /** True when nothing is overridden: the pack's layout as it is. */
    val empty: Boolean get() = this == NONE

    /** How many values the user set (for Reset to theme defaults). */
    val count: Int get() = listOf(preset, uiScale, itemSpacingDp, focusedScale).count { it != null }

    /** The preset's canonical name (or null when unknown), numbers finite and clamped to [LayoutLimits]. */
    fun normalized(): LayoutOverrides = LayoutOverrides(
        preset = crosshair?.name,
        uiScale = uiScale.clampOrNull(LayoutLimits.MIN_SCALE, LayoutLimits.MAX_SCALE),
        itemSpacingDp = itemSpacingDp.clampOrNull(0f, LayoutLimits.MAX_SPACING_DP),
        focusedScale = focusedScale.clampOrNull(LayoutLimits.MIN_FOCUS_SCALE, LayoutLimits.MAX_FOCUS_SCALE),
    )

    /**
     * [base] (the pack's layout in this context, its rules already applied) with these values on
     * top. The crosshair preset replaces both fractions; the screen still clamps them per display.
     */
    fun applyTo(base: ThemeLayout): ThemeLayout {
        if (empty) return base
        val n = normalized()
        val p = n.crosshair
        val out = base.copy(
            crosshairX = p?.x ?: base.crosshairX,
            crosshairY = p?.y ?: base.crosshairY,
            uiScale = n.uiScale ?: base.uiScale,
            itemSpacingDp = n.itemSpacingDp ?: base.itemSpacingDp,
            focusedScale = n.focusedScale ?: base.focusedScale,
        )
        return if (out == base) base else out
    }

    companion object {
        val NONE = LayoutOverrides()

        private fun Float?.clampOrNull(lo: Float, hi: Float): Float? = this?.takeIf { it.isFinite() }?.coerceIn(lo, hi)
    }
}

/**
 * `display.layout` as written by hand: keys whose value isn't of the right type (a word for a
 * number, an object for the preset) are dropped one by one before decoding, so one typo never
 * costs the rest of `settings.json`. Numbers may be quoted.
 */
object LenientLayoutOverrides :
    kotlinx.serialization.json.JsonTransformingSerializer<LayoutOverrides>(LayoutOverrides.serializer()) {
    private val NUMBERS = setOf("uiScale", "itemSpacingDp", "focusedScale")

    override fun transformDeserialize(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return kotlinx.serialization.json.JsonObject(emptyMap())
        val kept = buildMap {
            for ((key, value) in obj) {
                val p = value as? kotlinx.serialization.json.JsonPrimitive ?: continue
                if (p is kotlinx.serialization.json.JsonNull) continue
                when (key) {
                    "preset" -> if (p.isString) put(key, p)
                    in NUMBERS -> p.content.trim().toFloatOrNull()?.takeIf { it.isFinite() }
                        ?.let { put(key, kotlinx.serialization.json.JsonPrimitive(it)) }
                }
            }
        }
        return kotlinx.serialization.json.JsonObject(kept)
    }
}
