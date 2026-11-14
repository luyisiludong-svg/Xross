package dev.xross.launcher.ui

import androidx.compose.runtime.Composable
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
import dev.xross.launcher.theme.pack.ThemeIcon

/** The active theme pack, provided at the root of the UI (see MainActivity). */
val LocalThemePack = staticCompositionLocalOf<LoadedTheme?> { null }

/**
 * The active pack's look in the current context (variant, category and system overrides). Dynamic,
 * unlike [LocalThemePack], so a category or system switch recomposes only what reads it.
 */
val LocalThemeLook = androidx.compose.runtime.compositionLocalOf<dev.xross.launcher.theme.pack.ThemeLook?> { null }

/**
 * The layout drawn now: the pack's, with the tile shapes and `layout` patches of the context rules
 * that hold, and the quick profile's motion (E-Ink moves instantly). Null: the pack's own.
 */
val LocalThemeLayout = androidx.compose.runtime.compositionLocalOf<dev.xross.launcher.theme.pack.ThemeLayout?> { null }

/**
 * The theme pack's typeface for all launcher text. Snapshot state rather than a composition
 * local because [shadowed] builds styles outside composable scope; reads during composition are
 * still tracked, so switching packs redraws text.
 */
object ThemeTypography {
    /** `body`: titles, descriptions, menus, dialogs (every style that doesn't pick a role). */
    var family: FontFamily? by mutableStateOf(null)

    /** `display`: the clock and category names; the body font when the pack has none. */
    var display: FontFamily? by mutableStateOf(null)

    /** `mono`: figures that should line up (battery, points, playtime); the body font otherwise. */
    var mono: FontFamily? by mutableStateOf(null)
}

/**
 * The surface every card, panel and toast is drawn with (`Modifier.xrossSurface`). Snapshot state,
 * read only while drawing, so switching surfaces redraws without recomposing anything.
 */
object ThemeSurfaces {
    var active: dev.xross.launcher.theme.pack.Surface by mutableStateOf(dev.xross.launcher.theme.pack.Surface.FALLBACK)
}

private val Blank = ColorPainter(Color.Transparent)

/**
 * The icon for [key] from the active theme pack: an SVG drawn as a vector, or a PNG/WebP bitmap.
 * Packs that lack an icon inherit the Default pack's (generated from assets/icons by
 * tools/icons/svg_icons.py), so every key resolves while a pack is active.
 */
@Composable
fun glyphPainter(key: GlyphKey): Painter {
    return when (val icon = LocalThemePack.current?.icons?.get(key)) {
        is ThemeIcon.Vector -> rememberVectorPainter(icon.image)
        is ThemeIcon.Bitmap -> remember(icon) { BitmapPainter(icon.image) }
        null -> Blank
    }
}
