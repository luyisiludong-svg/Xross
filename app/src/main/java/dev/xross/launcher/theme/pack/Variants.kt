package dev.xross.launcher.theme.pack

import androidx.compose.runtime.Immutable
import dev.xross.launcher.state.Categories
import dev.xross.launcher.storage.PlatformRegistry
import dev.xross.launcher.theme.ThemePalette
import java.util.Locale

/** A `variants` entry checked against its pack: colors that parse, a surface that exists. */
@Immutable
data class ResolvedVariant(
    val id: String,
    val name: String,
    val palette: ThemeManifest.Palette? = null,
    val surfaceId: String? = null,
)

/** A checked `categoryOverrides` / `systemOverrides` entry (its tile aspect lives in [ThemeLayout]). */
@Immutable
data class ThemePatch(val palette: ThemeManifest.Palette? = null, val surfaceId: String? = null)

/**
 * What the active pack looks like in one context, before the user's own settings: its palette
 * (null when the pack brings no colors) and its surface id.
 */
@Immutable
data class ThemeLook(val palette: ThemePalette?, val surfaceId: String, val variantId: String = "")

/**
 * The precedence cascade of Appendix A.4 for palette and effects, lowest to highest:
 * the Default pack (its surface; it has no palette), the pack's base ([roles], [surfaceId]),
 * the selected [variant], the open [category]'s override, then the focused game's [system]
 * override. The user's `settings.json` (theme color, surface style) and, in 2b.3, the game-art
 * palette sit above this; see [LoadedTheme.look].
 *
 * A variant may bring the pack's first palette; category and system overrides only patch a
 * palette that exists (a pack without colors leaves the user's color theme alone).
 */
fun cascade(
    roles: ThemeManifest.Palette?,
    surfaceId: String,
    wave: ThemeManifest.WaveDefaults?,
    variant: ResolvedVariant?,
    category: ThemePatch?,
    system: ThemePatch?,
): ThemeLook {
    var palette = roles
    var surface = surfaceId
    variant?.let { v ->
        v.palette?.let { palette = palette?.over(it) ?: it }
        v.surfaceId?.let { surface = it }
    }
    for (patch in listOfNotNull(category, system)) {
        patch.palette?.let { p -> palette = palette?.over(p) }
        patch.surfaceId?.let { surface = it }
    }
    return ThemeLook(palette?.toPalette(wave), surface, variant?.id.orEmpty())
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
        if (palette != null && !hasPalette && palette.canvas == null) {
            problems += "variants.$id.palette needs a canvas color (the pack has no palette of its own)"
        }
        out += ResolvedVariant(
            id = id,
            name = v.name.trim().ifEmpty { id },
            palette = palette,
            surfaceId = surfaceOf("variants.$id", v.effects, surfaceIds, problems),
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

private fun patchOf(where: String, o: ThemeOverride, surfaceIds: Set<String>, problems: MutableList<String>): ThemePatch? {
    val palette = o.palette?.valid("$where.palette", problems)?.takeUnless { it.empty }
    val surface = surfaceOf(where, o.effects, surfaceIds, problems)
    return if (palette == null && surface == null) null else ThemePatch(palette, surface)
}

private fun surfaceOf(where: String, effects: ThemeManifest.Effects?, surfaceIds: Set<String>, problems: MutableList<String>): String? {
    val wanted = effects?.surfaceStyle?.trim().orEmpty()
    if (wanted.isEmpty()) return null
    if (wanted in surfaceIds) return wanted
    problems += "$where.effects.surfaceStyle: no surface named $wanted"
    return null
}
