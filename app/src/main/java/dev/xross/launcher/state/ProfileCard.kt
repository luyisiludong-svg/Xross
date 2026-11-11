package dev.xross.launcher.state

import dev.xross.launcher.input.NavAction

/** Buttons along the bottom of the User Card. */
enum class ProfileCardButton(val label: String) {
    REFRESH("Refresh"),
    LINK("Link account…"),
    UNLINK("Unlink"),
    CLOSE("Close"),
}

/**
 * The console-style User Card opened from the status bar avatar. [index] is the focused button;
 * [confirmUnlink] is set after the first press on Unlink, which then needs a second press.
 */
data class ProfileCardState(val index: Int = 0, val confirmUnlink: Boolean = false) {
    fun buttons(linked: Boolean): List<ProfileCardButton> = ProfileCard.buttons(linked)
    fun focused(linked: Boolean): ProfileCardButton = buttons(linked).let { it[index.coerceIn(0, it.lastIndex)] }
    fun label(button: ProfileCardButton): String =
        if (button == ProfileCardButton.UNLINK && confirmUnlink) "Press again to unlink" else button.label
}

sealed interface ProfileCardEffect {
    data object None : ProfileCardEffect
    data object Close : ProfileCardEffect
    data class Press(val button: ProfileCardButton) : ProfileCardEffect
}

data class ProfileCardOutcome(val state: ProfileCardState, val effect: ProfileCardEffect = ProfileCardEffect.None)

/** Pure input handling for the User Card. */
object ProfileCard {
    fun buttons(linked: Boolean): List<ProfileCardButton> =
        if (linked) {
            listOf(ProfileCardButton.REFRESH, ProfileCardButton.UNLINK, ProfileCardButton.CLOSE)
        } else {
            listOf(ProfileCardButton.LINK, ProfileCardButton.CLOSE)
        }

    fun onAction(state: ProfileCardState, action: NavAction, linked: Boolean): ProfileCardOutcome {
        val buttons = buttons(linked)
        val index = state.index.coerceIn(0, buttons.lastIndex)
        return when (action) {
            NavAction.LEFT, NavAction.UP -> move(state, index - 1, buttons.lastIndex)
            NavAction.RIGHT, NavAction.DOWN -> move(state, index + 1, buttons.lastIndex)
            NavAction.CONFIRM, NavAction.START -> press(state.copy(index = index), buttons[index])
            NavAction.BACK, NavAction.OPTIONS -> ProfileCardOutcome(state, ProfileCardEffect.Close)
            NavAction.PREV_CATEGORY, NavAction.NEXT_CATEGORY, NavAction.MEDIA -> ProfileCardOutcome(state)
        }
    }

    /** Touch: tap a button. */
    fun tap(state: ProfileCardState, index: Int, linked: Boolean): ProfileCardOutcome {
        val buttons = buttons(linked)
        if (index !in buttons.indices) return ProfileCardOutcome(state)
        val moved = if (index == state.index) state else state.copy(index = index, confirmUnlink = false)
        return press(moved, buttons[index])
    }

    private fun move(state: ProfileCardState, to: Int, last: Int): ProfileCardOutcome {
        val next = to.coerceIn(0, last)
        return ProfileCardOutcome(if (next == state.index) state else ProfileCardState(index = next))
    }

    private fun press(state: ProfileCardState, button: ProfileCardButton): ProfileCardOutcome = when {
        button == ProfileCardButton.CLOSE -> ProfileCardOutcome(state, ProfileCardEffect.Close)
        button == ProfileCardButton.UNLINK && !state.confirmUnlink -> ProfileCardOutcome(state.copy(confirmUnlink = true))
        else -> ProfileCardOutcome(state.copy(confirmUnlink = false), ProfileCardEffect.Press(button))
    }
}
