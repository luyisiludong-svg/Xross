package dev.xross.launcher.data

/**
 * Which console logos system folders show. [PACK] is the active theme pack's own `icons/systems/`
 * files, with [WHITE] for the systems it has none of; every other style is one of the bundled sets
 * in `assets/system_logos/<folder>/<platform id>.webp` (Dan Patrick's platform logos, see CREDITS.md).
 */
enum class SystemLogoStyle(
    val label: String,
    val detail: String?,
    /** The bundled set's folder under `assets/system_logos/`, or null for [PACK]. */
    val folder: String?,
    /** Drawn in their own colors; false gets the XMB's white gloss like any other icon. */
    val keepsColors: Boolean,
) {
    PACK("Theme pack", "The pack's own logos, else White", null, false),
    WHITE("White", "Plain white, like the other icons", "white", false),
    COLOR("Color", "Official colors", "color", true),
    MONO("Black & white", null, "mono", true),
    OUTLINED("Color, outlined", "A white outline keeps dark logos visible", "outline", true),
    DARK_COLOR("Color for light backgrounds", null, "dark_color", true),
    DARK_MONO("Black & white for light backgrounds", null, "dark_mono", true),
    BLACK("Black", "For light backgrounds", "black", true),
    ;

    companion object {
        /** [name] as saved in settings, else [PACK]. */
        fun of(name: String?): SystemLogoStyle = entries.firstOrNull { it.name == name } ?: PACK

        /** The bundled set that stands in for [PACK] where the pack has no logo. */
        val PACK_FALLBACK = WHITE
    }
}
