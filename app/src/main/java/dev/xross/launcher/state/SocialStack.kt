package dev.xross.launcher.state

import dev.xross.launcher.input.NavAction
import dev.xross.launcher.profile.FriendsState
import dev.xross.launcher.theme.pack.ResolvedComponents
import dev.xross.launcher.theme.pack.TopLeftClusterMode

/**
 * The social stack's panel while it is open (2b.7c). [index] is the focused friend; [newKeys] are
 * the friends that were new when it opened (opening clears the badge, the panel still marks them).
 */
data class SocialPanelState(
    val index: Int = 0,
    val newKeys: Set<String> = emptySet(),
)

sealed interface SocialPanelEffect {
    data object None : SocialPanelEffect
    data object Close : SocialPanelEffect
    /** Right: over to the avatar's mini-HUD. */
    data object ToAvatar : SocialPanelEffect
    data class MoveCategory(val delta: Int) : SocialPanelEffect
}

data class SocialPanelOutcome(val state: SocialPanelState, val effect: SocialPanelEffect = SocialPanelEffect.None)

/** Pure rules of the social stack: when it shows, how many avatars, and its panel's input. */
object SocialStack {
    /** Overlapping avatars drawn in the stack. */
    const val MAX_AVATARS = 4

    /**
     * The stack draws (and takes room) only with `topLeftCluster: SOCIAL_STACK` and someone to
     * show: it collapses when unlinked, offline without a cache, or following nobody.
     */
    fun shows(components: ResolvedComponents, friends: FriendsState): Boolean =
        components.topLeft == TopLeftClusterMode.SOCIAL_STACK && friends !is FriendsState.Unlinked && friends.friends.isNotEmpty()

    /**
     * Input while the panel is open: Up/Down move through the friends (clamped), Back closes, Right
     * goes to the avatar when the bar has one, L1/R1 move category.
     */
    fun onAction(state: SocialPanelState, action: NavAction, count: Int, avatar: Boolean): SocialPanelOutcome {
        val last = (count - 1).coerceAtLeast(0)
        return when (action) {
            NavAction.UP -> SocialPanelOutcome(state.copy(index = (state.index - 1).coerceAtLeast(0)))
            NavAction.DOWN -> SocialPanelOutcome(state.copy(index = (state.index + 1).coerceAtMost(last)))
            NavAction.BACK -> SocialPanelOutcome(state, SocialPanelEffect.Close)
            NavAction.RIGHT -> SocialPanelOutcome(state, if (avatar) SocialPanelEffect.ToAvatar else SocialPanelEffect.None)
            NavAction.PREV_CATEGORY -> SocialPanelOutcome(state, SocialPanelEffect.MoveCategory(-1))
            NavAction.NEXT_CATEGORY -> SocialPanelOutcome(state, SocialPanelEffect.MoveCategory(+1))
            NavAction.LEFT, NavAction.CONFIRM, NavAction.START, NavAction.OPTIONS, NavAction.MEDIA -> SocialPanelOutcome(state)
        }
    }

    /** "now", "5 min", "3 h", "2 d", "4 wk" since [lastActive] ("yyyy-MM-dd HH:mm:ss" UTC); "" when unknown. */
    fun ago(lastActive: String, nowMs: Long): String {
        val then = parseUtc(lastActive) ?: return ""
        val minutes = ((nowMs - then) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 2 -> "now"
            minutes < 60 -> "$minutes min"
            minutes < 48 * 60 -> "${minutes / 60} h"
            minutes < 14 * 24 * 60 -> "${minutes / (24 * 60)} d"
            minutes < 365L * 24 * 60 -> "${minutes / (7 * 24 * 60)} wk"
            else -> "${minutes / (365L * 24 * 60)} y"
        }
    }

    internal fun parseUtc(text: String): Long? = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC"); isLenient = false }
            .parse(text)?.time
    }.getOrNull()
}
