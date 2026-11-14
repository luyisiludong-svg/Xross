package dev.xross.launcher.input

import dev.xross.launcher.model.GlyphSet

/**
 * Controller family, detected from the USB/Bluetooth vendor ID. Drives the AUTO confirm-button
 * layout and, unless a theme picks another set, the on-screen glyphs ([glyphs]).
 */
enum class ControllerFamily {
    PLAYSTATION,
    XBOX,
    NINTENDO,
    GENERIC,
    KEYBOARD;

    /** Confirm side used when the user's setting is AUTO. */
    val defaultConfirm: ConfirmLayout
        get() = if (this == NINTENDO) ConfirmLayout.EAST else ConfirmLayout.SOUTH

    /** The face-button labels of this family's own controllers. */
    val glyphs: GlyphSet
        get() = when (this) {
            PLAYSTATION -> GlyphSet.PLAYSTATION
            XBOX -> GlyphSet.XBOX
            NINTENDO -> GlyphSet.NINTENDO
            GENERIC -> GlyphSet.GENERIC
            KEYBOARD -> GlyphSet.KEYBOARD
        }

    companion object {
        private const val VENDOR_SONY = 0x054C
        private const val VENDOR_MICROSOFT = 0x045E
        private const val VENDOR_NINTENDO = 0x057E

        fun fromVendor(vendorId: Int, isGamepad: Boolean): ControllerFamily = when {
            !isGamepad -> KEYBOARD
            vendorId == VENDOR_SONY -> PLAYSTATION
            vendorId == VENDOR_MICROSOFT -> XBOX
            vendorId == VENDOR_NINTENDO -> NINTENDO
            else -> GENERIC
        }
    }
}

fun ConfirmLayout.resolve(family: ControllerFamily): ConfirmLayout =
    if (this == ConfirmLayout.AUTO) family.defaultConfirm else this
