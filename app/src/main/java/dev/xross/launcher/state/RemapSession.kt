package dev.xross.launcher.state

import dev.xross.launcher.input.NavAction

/** Step-by-step "press the button for X" capture used by the button remapping wizard. */
data class RemapSession(
    val step: Int = 0,
    val captured: Map<Int, NavAction> = emptyMap(),
) {
    val target: NavAction? get() = STEPS.getOrNull(step)
    val isDone: Boolean get() = step >= STEPS.size
    val progress: String get() = "${(step + 1).coerceAtMost(STEPS.size)}/${STEPS.size}"

    /** Ignores a key already bound in this session so a held/double press can't eat two steps. */
    fun capture(keyCode: Int): RemapSession {
        val target = target ?: return this
        if (keyCode in captured) return this
        return RemapSession(step + 1, captured + (keyCode to target))
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
