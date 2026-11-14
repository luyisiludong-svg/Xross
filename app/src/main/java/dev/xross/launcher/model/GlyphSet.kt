package dev.xross.launcher.model

import androidx.compose.runtime.Immutable

/** A face button by its position on the pad (the confirm side follows the controller, not the label). */
enum class Face { SOUTH, EAST, NORTH, WEST }

/** How the hints draw a set's labels (2b.7b `glyphOverride`). */
enum class GlyphStyle {
    /** White labels on the hint's pill (every set before 2b.7b). */
    PLAIN,

    /** Each letter in its Xbox color: A green, B red, X blue, Y yellow. */
    XBOX_COLORS,

    /** Outlined pills around the labels, no fill. */
    OUTLINE,
}

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
    val style: GlyphStyle = GlyphStyle.PLAIN,
) {
    /** The label of [face]. */
    operator fun get(face: Face): String = when (face) {
        Face.SOUTH -> south
        Face.EAST -> east
        Face.NORTH -> north
        Face.WEST -> west
    }

    /** Which face [glyph] labels in this set; null for any other text ("L1", "Hold"). */
    fun faceOf(glyph: String): Face? = Face.entries.firstOrNull { this[it] == glyph }

    companion object {
        val PLAYSTATION = GlyphSet(south = "✕", east = "○", north = "△", west = "□")
        val XBOX = GlyphSet(south = "A", east = "B", north = "Y", west = "X")
        /** Nintendo positions: A is on the east, so south reads B. */
        val NINTENDO = GlyphSet(south = "B", east = "A", north = "X", west = "Y")
        val GENERIC = GlyphSet(south = "A", east = "B", north = "Y", west = "X")
        val KEYBOARD = GlyphSet(south = "Enter", east = "Esc", north = "Tab", west = "Space")

        /** [XBOX] with each letter in its color (`glyphOverride: XBOX`). */
        val XBOX_COLORED = XBOX.copy(style = GlyphStyle.XBOX_COLORS)

        /**
         * `glyphOverride: MONOCHROME_MINIMAL`: an outlined triangle pointing at the button's place
         * on the pad, no letters. Plain text, so a glyph quoted in a sentence reads the same.
         */
        val MINIMAL = GlyphSet(south = "▽", east = "▷", north = "△", west = "◁", style = GlyphStyle.OUTLINE)
    }
}
