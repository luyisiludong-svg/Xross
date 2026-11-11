package dev.xross.launcher.input

import android.view.KeyEvent

/**
 * Pure key-code → [NavAction] mapping. User remaps win over defaults.
 *
 * Android reports face buttons by position on standard-mapped pads (BUTTON_A = south,
 * BUTTON_B = east), so the confirm/cancel swap is a positional swap.
 */
object KeyMapper {

    fun map(
        keyCode: Int,
        confirm: ConfirmLayout,
        remap: Map<Int, NavAction> = emptyMap(),
    ): NavAction? {
        remap[keyCode]?.let { return it }
        val eastConfirms = confirm == ConfirmLayout.EAST
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> NavAction.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> NavAction.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> NavAction.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> NavAction.RIGHT

            KeyEvent.KEYCODE_BUTTON_A -> if (eastConfirms) NavAction.BACK else NavAction.CONFIRM
            KeyEvent.KEYCODE_BUTTON_B -> if (eastConfirms) NavAction.CONFIRM else NavAction.BACK
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_TAB -> NavAction.OPTIONS

            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_PAGE_UP -> NavAction.PREV_CATEGORY
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_PAGE_DOWN -> NavAction.NEXT_CATEGORY
            KeyEvent.KEYCODE_BUTTON_START -> NavAction.START
            KeyEvent.KEYCODE_BUTTON_SELECT -> NavAction.MEDIA

            // Keyboards, TV remotes, and handheld system keys.
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE -> NavAction.CONFIRM
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_DEL -> NavAction.BACK
            else -> null
        }
    }
}
