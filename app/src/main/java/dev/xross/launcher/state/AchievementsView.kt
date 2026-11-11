package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.profile.AchievementsState
import dev.xross.launcher.profile.RaAchievement
import dev.xross.launcher.profile.RaBadge

/**
 * The Achievements screen for one game: a grid of badges (unlocked first, newest first; then
 * locked in the game's order) with the focused badge's details below. [result] is null while
 * loading.
 */
@Immutable
data class AchievementsViewState(
    val gameId: Long,
    val uuid: String,
    val title: String,
    val platform: String?,
    val result: AchievementsState? = null,
    val index: Int = 0,
    /** Changes when new art is stored, so the header reloads the box art. */
    val artVersion: Long = 0,
) {
    val achievements: List<RaAchievement>
        get() = (result as? AchievementsState.Ready)?.progress?.ordered().orEmpty()

    val focused: RaAchievement? get() = achievements.getOrNull(index)

    fun withResult(next: AchievementsState): AchievementsViewState {
        val size = (next as? AchievementsState.Ready)?.progress?.achievements?.size ?: 0
        return copy(result = next, index = index.coerceIn(0, (size - 1).coerceAtLeast(0)))
    }

    companion object {
        /** Badges per grid row; the screen sizes cells so about two and a half rows show. */
        const val COLUMNS = 10
    }
}

sealed interface AchievementsEffect {
    data object None : AchievementsEffect
    data object Close : AchievementsEffect
    data object Refresh : AchievementsEffect
}

data class AchievementsOutcome(val state: AchievementsViewState, val effect: AchievementsEffect = AchievementsEffect.None)

/** Pure input handling for the badge grid. */
object AchievementsGrid {
    fun onAction(state: AchievementsViewState, action: NavAction, columns: Int = AchievementsViewState.COLUMNS): AchievementsOutcome {
        val size = state.achievements.size
        fun to(i: Int) = AchievementsOutcome(if (size == 0) state else state.copy(index = i.coerceIn(0, size - 1)))
        return when (action) {
            NavAction.LEFT -> to(state.index - 1)
            NavAction.RIGHT -> to(state.index + 1)
            NavAction.UP -> if (state.index < columns) AchievementsOutcome(state) else to(state.index - columns)
            NavAction.DOWN -> if (state.index + columns > size - 1 && state.index / columns == (size - 1) / columns) {
                AchievementsOutcome(state)
            } else {
                to(state.index + columns)
            }
            NavAction.PREV_CATEGORY -> to(state.index - columns * 3)
            NavAction.NEXT_CATEGORY -> to(state.index + columns * 3)
            NavAction.BACK -> AchievementsOutcome(state, AchievementsEffect.Close)
            NavAction.OPTIONS -> AchievementsOutcome(state, AchievementsEffect.Refresh)
            NavAction.CONFIRM, NavAction.START, NavAction.MEDIA -> AchievementsOutcome(state)
        }
    }

    /** Touch: tap a badge. */
    fun tap(state: AchievementsViewState, index: Int): AchievementsViewState =
        if (index in state.achievements.indices) state.copy(index = index) else state
}

/** One unlock toast on screen; [more] counts the unlocks still queued behind it. */
@Immutable
data class UnlockToastState(val badge: RaBadge, val serial: Long, val more: Int = 0)

/**
 * Queue behind the unlock toasts: unlocks arrive in batches (one refresh can find several) and are
 * shown one at a time, oldest first. Duplicates (same title, game and date) are dropped so a
 * repeated refresh never announces an unlock twice.
 */
class UnlockQueue(private val limit: Int = 12) {
    private val pending = ArrayDeque<RaBadge>()
    private val seen = LinkedHashSet<String>()
    private var serial = 0L

    val size: Int get() = pending.size

    fun offer(badges: List<RaBadge>) {
        for (b in badges) {
            val key = "${b.gameTitle}\u0000${b.title}\u0000${b.date}"
            if (!seen.add(key)) continue
            if (seen.size > 256) seen.remove(seen.first())
            if (pending.size < limit) pending.addLast(b)
        }
    }

    /** The next toast to show, or null when the queue is empty. */
    fun next(): UnlockToastState? {
        val badge = pending.removeFirstOrNull() ?: return null
        return UnlockToastState(badge, ++serial, more = pending.size)
    }
}
