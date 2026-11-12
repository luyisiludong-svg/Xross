package dev.xross.launcher.theme.pack

import androidx.compose.runtime.Immutable
import dev.xross.launcher.state.Categories
import dev.xross.launcher.storage.PlatformRegistry
import dev.xross.launcher.theme.ThemePalette
import dev.xross.launcher.theme.shader.ShaderRef
import java.util.Locale

/** A `variants` entry checked against its pack: colors that parse, a surface that exists. */
@Immutable
data class ResolvedVariant(
    val id: String,
    val name: String,
    val palette: ThemeManifest.Palette? = null,
    val surfaceId: String? = null,
    val flags: ColorFlags? = null,
    /** `effects.shaderPreset` / `effects.shaderPath`, checked. */
    val shader: ShaderRef? = null,
    /** `effects.wallpaperPath`, checked ([WallpaperRef.NONE] turns the pack's off). */
    val wallpaper: WallpaperRef? = null,
)

/** A checked `categoryOverrides` / `systemOverrides` entry (its tile aspect lives in [ThemeLayout]). */
@Immutable
data class ThemePatch(
    val palette: ThemeManifest.Palette? = null,
    val surfaceId: String? = null,
    /** Circadian curve, battery tint and adaptive palette switches. */
    val flags: ColorFlags? = null,
    /** The background program while this patch holds. */
    val shader: ShaderRef? = null,
    /** The wallpaper while this patch holds ([WallpaperRef.NONE]: none). */
    val wallpaper: WallpaperRef? = null,
)

/**
 * What the active pack looks like in one context, before the user's own settings: its palette
 * (null when the pack brings no colors) and its surface id.
 */
@Immutable
data class ThemeLook(
    val palette: ThemePalette?,
    val surfaceId: String,
    val variantId: String = "",
    /** The pack's circadian, battery-tint and adaptive-palette switches here (null: not set). */
    val flags: ColorFlags = ColorFlags.NONE,
    /** The pack's layout with the tile shapes and `layout` patches of the rules that hold; null: unchanged. */
    val layout: ThemeLayout? = null,
    /** Indices of the context rules that hold here. */
    val rules: List<Int> = emptyList(),
    /** The background program here (2b.4a): the last layer that names one wins. */
    val shader: ShaderRef = ShaderRef.WAVE,
    /** The pack's wallpaper here (2b.4c), resolved through [LoadedTheme.wallpaperFile]. */
    val wallpaper: WallpaperRef = WallpaperRef.NONE,
)

/**
 * The precedence cascade of Appendix A.4 for palette and effects, lowest to highest:
 * the Default pack (its surface; it has no palette), the pack's base ([roles], [surfaceId],
 * [flags]), the selected [variant], the open [category]'s override, the focused game's [system]
 * override, then the context [rules] that hold, in file order (the last one wins). The user's
 * `settings.json` (theme color, quick profile, surface style) and the game-art palette sit above
 * this; see [LoadedTheme.look] and [dev.xross.launcher.theme.ColorPipeline].
 *
 * A variant may bring the pack's first palette; overrides and rules only patch a palette that
 * exists (a pack without colors leaves the user's color theme alone).
 */
fun cascade(
    roles: ThemeManifest.Palette?,
    surfaceId: String,
    wave: ThemeManifest.WaveDefaults?,
    variant: ResolvedVariant?,
    category: ThemePatch?,
    system: ThemePatch?,
    rules: List<ResolvedRule> = emptyList(),
    flags: ColorFlags = ColorFlags.NONE,
    shader: ShaderRef = ShaderRef.WAVE,
    wallpaper: WallpaperRef = WallpaperRef.NONE,
): ThemeLook {
    var palette = roles
    var surface = surfaceId
    var f = flags
    var program = shader
    var backdrop = wallpaper
    variant?.let { v ->
        v.palette?.let { palette = palette?.over(it) ?: it }
        v.surfaceId?.let { surface = it }
        f = f.over(v.flags)
        v.shader?.let { program = it }
        v.wallpaper?.let { backdrop = it }
    }
    for (patch in listOfNotNull(category, system) + rules.mapNotNull { it.patch }) {
        patch.palette?.let { p -> palette = palette?.over(p) }
        patch.surfaceId?.let { surface = it }
        f = f.over(patch.flags)
        patch.shader?.let { program = it }
        patch.wallpaper?.let { backdrop = it }
    }
    return ThemeLook(
        palette?.toPalette(wave), surface, variant?.id.orEmpty(), f,
        rules = rules.map { it.index }, shader = program, wallpaper = backdrop,
    )
}

/** Variant ids: what `settings.json` stores. */
private val VARIANT_ID = Regex("[A-Za-z0-9_.-]{1,48}")

/** Category ids an override may name (`GAMES` or `games`). */
private val CATEGORY_IDS: Set<String> by lazy { Categories.all.mapTo(HashSet()) { it.id } }

/** [ThemeManifest.variants] checked: unique valid ids, parseable colors, known surfaces. */
internal fun resolveVariants(
    variants: List<ThemeVariant>,
    hasPalette: Boolean,
    surfaceIds: Set<String>,
    problems: MutableList<String>,
): List<ResolvedVariant> {
    val out = ArrayList<ResolvedVariant>()
    for (v in variants) {
        val id = v.id.trim()
        if (!VARIANT_ID.matches(id)) {
            problems += "Variant id \"${v.id}\": use letters, digits, ., - and _"
            continue
        }
        if (out.any { it.id.equals(id, ignoreCase = true) }) {
            problems += "Variant $id is listed twice"
            continue
        }
        val palette = v.palette?.valid("variants.$id.palette", problems)?.takeUnless { it.empty }
        if (palette != null && palette.hasColors && !hasPalette && palette.canvas == null) {
            problems += "variants.$id.palette needs a canvas color (the pack has no palette of its own)"
        }
        out += ResolvedVariant(
            id = id,
            name = v.name.trim().ifEmpty { id },
            palette = palette,
            surfaceId = surfaceOf("variants.$id", v.effects, surfaceIds, problems),
            flags = ColorFlags.of(v.palette, v.effects).takeUnless { it.empty },
            shader = ShaderRef.of("variants.$id", v.effects?.shaderPreset, v.effects?.shaderPath, problems),
            wallpaper = WallpaperRef.of("variants.$id", v.effects?.wallpaperPath, problems),
        )
    }
    return out
}

/** `categoryOverrides` checked, keyed by category id in lower case ("games"). */
internal fun resolveCategoryPatches(
    overrides: Map<String, ThemeOverride>,
    surfaceIds: Set<String>,
    problems: MutableList<String>,
): Map<String, ThemePatch> {
    val out = LinkedHashMap<String, ThemePatch>()
    for ((key, o) in overrides) {
        val id = key.trim().lowercase(Locale.ROOT)
        if (id !in CATEGORY_IDS) {
            problems += "categoryOverrides.$key: no such category (use ${CATEGORY_IDS.sorted().joinToString { it.uppercase(Locale.ROOT) }})"
            continue
        }
        patchOf("categoryOverrides.$key", o, surfaceIds, problems)?.let { out[id] = it }
    }
    return out
}

/**
 * `systemOverrides` with each key resolved to a Xross platform id ("dc" → "dreamcast"), through
 * [platformId] (the platform registry by default). Unknown systems are reported and dropped.
 */
internal fun resolveSystemKeys(
    overrides: Map<String, ThemeOverride>,
    problems: MutableList<String>,
    platformId: (String) -> String? = ::platformIdOf,
): Map<String, ThemeOverride> {
    val out = LinkedHashMap<String, ThemeOverride>()
    for ((key, o) in overrides) {
        val id = platformId(key.trim())
        if (id == null) {
            problems += "systemOverrides.$key: no such system"
            continue
        }
        // An exact platform id beats an alias for the same system.
        if (id in out && !key.trim().equals(id, ignoreCase = true)) continue
        out[id] = o
    }
    return out
}

/** [resolveSystemKeys]' output checked into patches. */
internal fun resolveSystemPatches(
    overrides: Map<String, ThemeOverride>,
    surfaceIds: Set<String>,
    problems: MutableList<String>,
): Map<String, ThemePatch> {
    val out = LinkedHashMap<String, ThemePatch>()
    for ((id, o) in overrides) patchOf("systemOverrides.$id", o, surfaceIds, problems)?.let { out[id] = it }
    return out
}

/** A platform id, or the platform a folder alias names ("dc", "Sega Dreamcast"); null if unknown. */
fun platformIdOf(key: String): String? {
    val k = key.trim()
    if (k.isEmpty()) return null
    return (PlatformRegistry.byId(k.lowercase(Locale.ROOT)) ?: PlatformRegistry.forFolder(k))?.id
}

internal fun patchOf(where: String, o: ThemeOverride, surfaceIds: Set<String>, problems: MutableList<String>): ThemePatch? {
    val palette = o.palette?.valid("$where.palette", problems)?.takeIf { it.hasColors }
    val surface = surfaceOf(where, o.effects, surfaceIds, problems)
    val flags = ColorFlags.of(o.palette, o.effects).takeUnless { it.empty }
    val shader = ShaderRef.of(where, o.effects?.shaderPreset, o.effects?.shaderPath, problems)
    val wallpaper = WallpaperRef.of(where, o.effects?.wallpaperPath, problems)
    return if (palette == null && surface == null && flags == null && shader == null && wallpaper == null) {
        null
    } else {
        ThemePatch(palette, surface, flags, shader, wallpaper)
    }
}

private fun surfaceOf(where: String, effects: ThemeManifest.Effects?, surfaceIds: Set<String>, problems: MutableList<String>): String? {
    val wanted = effects?.surfaceStyle?.trim().orEmpty()
    if (wanted.isEmpty()) return null
    if (wanted in surfaceIds) return wanted
    problems += "$where.effects.surfaceStyle: no surface named $wanted"
    return null
}
