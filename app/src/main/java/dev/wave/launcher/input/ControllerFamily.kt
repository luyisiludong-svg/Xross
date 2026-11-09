package dev.wave.launcher.input

/**
 * Controller family, detected from the USB/Bluetooth vendor ID. Drives on-screen glyphs and
 * the AUTO confirm-button layout.
 */
enum class ControllerFamily(
    val south: String,
    val east: String,
    val north: String,
    val west: String,
) {
    PLAYSTATION(south = "✕", east = "○", north = "△", west = "□"),
    XBOX(south = "A", east = "B", north = "Y", west = "X"),
    NINTENDO(south = "B", east = "A", north = "X", west = "Y"),
    GENERIC(south = "A", east = "B", north = "Y", west = "X"),
    KEYBOARD(south = "Enter", east = "Esc", north = "Tab", west = "Space");

    /** Confirm side used when the user's setting is AUTO. */
    val defaultConfirm: ConfirmLayout
        get() = if (this == NINTENDO) ConfirmLayout.EAST else ConfirmLayout.SOUTH

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
