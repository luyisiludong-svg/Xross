package dev.xross.launcher.model

import androidx.compose.runtime.Immutable

/**
 * The four face-button labels the hints, Search, Achievements and the avatar HUD draw. Display
 * only: which side confirms follows the detected controller and the user's Confirm setting, never
 * the labels, so a theme (2b.7b `glyphOverride`) can pick a set without changing the controls.
 */
@Immutable
data class GlyphSet(
    val south: String,
    val east: String,
    val north: String,
    val west: String,
) {
    companion object {
        val PLAYSTATION = GlyphSet(south = "✕", east = "○", north = "△", west = "□")
        val XBOX = GlyphSet(south = "A", east = "B", north = "Y", west = "X")
        /** Nintendo positions: A is on the east, so south reads B. */
        val NINTENDO = GlyphSet(south = "B", east = "A", north = "X", west = "Y")
        val GENERIC = GlyphSet(south = "A", east = "B", north = "Y", west = "X")
        val KEYBOARD = GlyphSet(south = "Enter", east = "Esc", north = "Tab", west = "Space")
    }
}
