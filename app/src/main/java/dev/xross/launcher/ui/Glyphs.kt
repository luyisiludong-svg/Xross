package dev.xross.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontFamily
import dev.xross.launcher.model.GlyphKey
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.BitmapIcon
import dev.xross.launcher.theme.pack.VectorIcon
import dev.xross.launcher.model.GlyphSet
import dev.xross.launcher.theme.pack.ResolvedComponents
import dev.xross.launcher.theme.pack.Surface
import dev.xross.launcher.theme.pack.ThemeLayout
import dev.xross.launcher.theme.pack.ThemeLook
import dev.xross.launcher.theme.pack.ThemeFont
import dev.xross.launcher.theme.pack.ThemeFonts
import dev.xross.launcher.theme.pack.typeface

/** The active theme pack, provided at the root of the UI (see MainActivity). */
val LocalThemePack = staticCompositionLocalOf<LoadedTheme?> { null }

/**
 * The active pack's look in the current context (variant, category and system overrides). Dynamic,
 * unlike [LocalThemePack], so a category or system switch recomposes only what reads it.
 */
val LocalThemeLook = androidx.compose.runtime.compositionLocalOf<ThemeLook?> { null }

/**
 * The widgets drawn (2b.7a, [dev.xross.launcher.theme.pack.ResolvedComponents]): status-bar slots,
 * battery and clock modes. Changes only with the pack, its variant or Settings ▸ Theme ▸ Components.
 */
val LocalComponents = androidx.compose.runtime.compositionLocalOf { ResolvedComponents.DEFAULT }

/**
 * The face-button labels on screen (2b.7b): the detected controller's or the set `glyphOverride`
 * picks. [Hint] reads it to draw a label in its set's style (Xbox colors, outlined).
 */
val LocalGlyphs = androidx.compose.runtime.compositionLocalOf { GlyphSet.KEYBOARD }

/**
 * The layout drawn now: the pack's, with the tile shapes and `layout` patches of the context rules
 * that hold, and the quick profile's motion (E-Ink moves instantly). Null: the pack's own.
 */
val LocalThemeLayout = androidx.compose.runtime.compositionLocalOf<ThemeLayout?> { null }

/**
 * The theme pack's typefaces for all launcher text, by role. Changes only with the pack, so it is a
 * plain value: [shadowed] and every style read it from [LocalThemeTypography] while composing, and
 * a pack switch recomposes the text.
 */
@Immutable
data class ThemeTypography(
    /** `body`: titles, descriptions, menus, dialogs (every style that doesn't pick a role). */
    val family: FontFamily? = null,
    /** `display`: the clock and category names; the body font when the pack has none. */
    val display: FontFamily? = null,
    /** `mono`: figures that should line up (battery, points, playtime); the body font otherwise. */
    val mono: FontFamily? = null,
) {
    /** [display], or the body font. */
    val displayOrBody: FontFamily? get() = display ?: family

    /** [mono], or the body font. */
    val monoOrBody: FontFamily? get() = mono ?: family

    companion object {
        /** The system font in every role (no pack loaded, or a composition without a provider). */
        val SYSTEM = ThemeTypography()

        /** The roles of [fonts] (the active pack's). */
        fun of(fonts: ThemeFonts): ThemeTypography {
            fun family(f: ThemeFont?) = f?.typeface?.let { FontFamily(it) }
            return ThemeTypography(family(fonts.body), family(fonts.display), family(fonts.mono))
        }
    }
}

/** The active pack's [ThemeTypography]; MainActivity provides it. */
val LocalThemeTypography = staticCompositionLocalOf { ThemeTypography.SYSTEM }

/**
 * The surface every card, panel and toast is drawn with (`Modifier.xrossSurface`). Snapshot state,
 * read only while drawing, so switching surfaces redraws without recomposing anything. One per
 * composition root, provided through [LocalThemeSurfaces].
 */
@Stable
class ThemeSurfaces(initial: Surface = Surface.FALLBACK) {
    var active: Surface by mutableStateOf(initial)
}

/** The root's [ThemeSurfaces]. Not provided is an error, so two compositions never share one. */
val LocalThemeSurfaces = staticCompositionLocalOf<ThemeSurfaces> { error("No ThemeSurfaces provided (MainActivity provides one)") }

private val Blank = ColorPainter(Color.Transparent)

/**
 * The icon for [key] from the active theme pack: an SVG drawn as a vector, or a PNG/WebP bitmap.
 * Packs that lack an icon inherit the Default pack's (XMB-style glyphs in
 * assets/themes/default/icons), so every key resolves while a pack is active.
 */
@Composable
fun glyphPainter(key: GlyphKey): Painter {
    return when (val icon = LocalThemePack.current?.icons?.get(key)) {
        is VectorIcon -> rememberVectorPainter(icon.image)
        is BitmapIcon -> remember(icon) { BitmapPainter(icon.image) }
        else -> Blank
    }
}
