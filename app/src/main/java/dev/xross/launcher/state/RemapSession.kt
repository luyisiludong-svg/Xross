package dev.xross.launcher.state

import dev.xross.launcher.input.ConnectedController
import dev.xross.launcher.input.NavAction

/**
 * Step-by-step "press the button for X" capture used by the button remapping wizard.
 *
 * The first press decides which controller is being remapped ([controller]); presses from any
 * other device are ignored for the rest of the session, so a second pad or a keyboard can't
 * mix its keys into the mapping. A session started from a device without IDs ([controller] null)
 * produces the global mapping.
 */
data class RemapSession(
    val step: Int = 0,
    val captured: Map<Int, NavAction> = emptyMap(),
    /** Locked on the first press; null until then, or for devices without IDs. */
    val controller: ConnectedController? = null,
    /** True once the first press has chosen the device (even if it had no IDs). */
    val deviceChosen: Boolean = false,
) {
    val target: NavAction? get() = STEPS.getOrNull(step)
    val isDone: Boolean get() = step >= STEPS.size
    val progress: String get() = "${(step + 1).coerceAtMost(STEPS.size)}/${STEPS.size}"

    /**
     * Records [keyCode] for the current step. Ignores a key already bound in this session so a
     * held/double press can't eat two steps, and ignores presses from a different device.
     */
    fun capture(keyCode: Int, from: ConnectedController? = null): RemapSession {
        val target = target ?: return this
        if (deviceChosen && from?.key != controller?.key) return this
        if (keyCode in captured) return this
        return copy(
            step = step + 1,
            captured = captured + (keyCode to target),
            controller = if (deviceChosen) controller else from,
            deviceChosen = true,
        )
    }

    companion object {
        val STEPS = listOf(
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT,
            NavAction.CONFIRM, NavAction.BACK, NavAction.OPTIONS,
            NavAction.PREV_CATEGORY, NavAction.NEXT_CATEGORY,
        )

        fun label(action: NavAction) = when (action) {
            NavAction.UP -> "Up"
            NavAction.DOWN -> "Down"
            NavAction.LEFT -> "Left"
            NavAction.RIGHT -> "Right"
            NavAction.CONFIRM -> "Confirm"
            NavAction.BACK -> "Back"
            NavAction.OPTIONS -> "Options"
            NavAction.PREV_CATEGORY -> "Previous category (L1)"
            NavAction.NEXT_CATEGORY -> "Next category (R1)"
            NavAction.START -> "Start"
        }
    }
}
