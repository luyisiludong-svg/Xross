package dev.xross.launcher.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import dev.xross.launcher.theme.pack.MenuStyle

/**
 * Where a menu style puts the things the rest of the screen lines up with (Menu styles slice 1).
 * The status bar, the focus signal (sound panning and shaders' `uFocus`) and the game-boot
 * overlay read these instead of the XMB's crosshair, so they follow any style.
 */
@Immutable
data class MenuAnchors(
    /** The focused item's centre, in px. */
    val focus: Offset,
    /** How far down the status bar's left cluster may reach, in px. */
    val statusClearance: Float,
) {
    /** [focus] as fractions of a [width] × [height] screen (0.5 while it has no size). */
    fun focusFraction(width: Float, height: Float): Offset =
        if (width > 0f && height > 0f) Offset(focus.x / width, focus.y / height) else Offset(0.5f, 0.5f)

    companion object {
        /** The anchors of [style] on the screen [m] measures. */
        internal fun of(style: MenuStyle, m: XmbMetrics): MenuAnchors = when (style) {
            MenuStyle.XMB -> xmb(m)
            // Its first row until SlashMenu reports where the focus is.
            MenuStyle.SLASH -> slash(SlashMetrics(m, m.layout.slash, m.layout.slash.focusedContent != dev.xross.launcher.theme.pack.SlashFocusedContent.TITLE), 0, 0, 0)
        }

        /**
         * The XMB: the focused row's icon sits on the crosshair, and the status bar's left cluster
         * stops just above the category bar's focused icon.
         */
        internal fun xmb(m: XmbMetrics): MenuAnchors = MenuAnchors(
            focus = Offset(m.anchorX, m.focusedY),
            statusClearance = (m.barY - m.categoryIcon * m.categoryFocusScale / 2 - m.s * 0.04f).coerceAtLeast(0f),
        )
    }
}
