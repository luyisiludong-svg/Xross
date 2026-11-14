package dev.xross.launcher.state

import dev.xross.launcher.input.ControllerFamily
import dev.xross.launcher.model.GlyphSet
import dev.xross.launcher.theme.pack.GlyphOverrideMode

/**
 * The labels drawn for [family] under this `glyphOverride` (2b.7b): [GlyphOverrideMode.AUTO] the
 * detected controller's own, any other the chosen set whatever is plugged in. Labels only: the
 * confirm side still follows [family] and the user's Confirm setting.
 */
fun GlyphOverrideMode.glyphsFor(family: ControllerFamily): GlyphSet = when (this) {
    GlyphOverrideMode.AUTO -> family.glyphs
    GlyphOverrideMode.PLAYSTATION -> GlyphSet.PLAYSTATION
    GlyphOverrideMode.XBOX -> GlyphSet.XBOX_COLORED
    GlyphOverrideMode.NINTENDO -> GlyphSet.NINTENDO
    GlyphOverrideMode.MONOCHROME_MINIMAL -> GlyphSet.MINIMAL
}
