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
import dev.xross.launcher.state.GlyphKey
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.ThemeIcon

/** The active theme pack, provided at the root of the UI (see MainActivity). */
val LocalThemePack = staticCompositionLocalOf<LoadedTheme?> { null }

/**
 * The theme pack's typeface for all launcher text. Snapshot state rather than a composition
 * local because [shadowed] builds styles outside composable scope; reads during composition are
 * still tracked, so switching packs redraws text.
 */
object ThemeTypography {
    var family: FontFamily? by mutableStateOf(null)
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
