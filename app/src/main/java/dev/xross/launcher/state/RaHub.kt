package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.profile.AchievementsState
import dev.xross.launcher.profile.RaLibrarySummary

/**
 * Games ▸ RetroAchievements: the profile dashboard (identity, completion across the library,
 * mastered games, recent unlocks). [summary] is null while the library's cached progress is read.
 */
@Immutable
data class RaHubState(
    /** Scroll step of the right-hand column, moved by up/down. */
    val scroll: Int = 0,
    val summary: RaLibrarySummary? = null,
    /** Changes when new art is stored, so the mastery showcase reloads its box art. */
    val artVersion: Long = 0,
) {
    companion object {
        const val MAX_SCROLL = 30
    }
}

sealed interface RaHubEffect {
    data object None : RaHubEffect
    data object Close : RaHubEffect
    /** Fetch the profile again, or start linking when no account is linked. */
    data object Primary : RaHubEffect
}

data class RaHubOutcome(val state: RaHubState, val effect: RaHubEffect = RaHubEffect.None)

/** Pure input handling for the RetroAchievements dashboard. */
object RaHub {
    fun onAction(state: RaHubState, action: NavAction): RaHubOutcome = when (action) {
        NavAction.UP -> RaHubOutcome(state.copy(scroll = (state.scroll - 1).coerceAtLeast(0)))
        NavAction.DOWN -> RaHubOutcome(state.copy(scroll = (state.scroll + 1).coerceAtMost(RaHubState.MAX_SCROLL)))
        NavAction.PREV_CATEGORY -> RaHubOutcome(state.copy(scroll = (state.scroll - 4).coerceAtLeast(0)))
        NavAction.NEXT_CATEGORY -> RaHubOutcome(state.copy(scroll = (state.scroll + 4).coerceAtMost(RaHubState.MAX_SCROLL)))
        NavAction.BACK, NavAction.LEFT -> RaHubOutcome(state, RaHubEffect.Close)
        NavAction.CONFIRM, NavAction.START, NavAction.OPTIONS -> RaHubOutcome(state, RaHubEffect.Primary)
        NavAction.RIGHT, NavAction.MEDIA -> RaHubOutcome(state)
    }
}

/**
 * The focused game's RetroAchievements numbers for the badge in the bottom corner of the menu.
 * Only games RetroAchievements recognizes and has a set for get one.
 */
@Immutable
data class GameTelemetry(
    val gameId: Long,
    val unlocked: Int,
    val total: Int,
    val earnedPoints: Int,
    val totalPoints: Int,
) {
    /** Every achievement unlocked. */
    val mastered: Boolean get() = total > 0 && unlocked >= total

    companion object {
        /** Null for anything but a recognized game with achievements (unlinked, unsupported, unhashed, failed). */
        fun of(gameId: Long, state: AchievementsState?): GameTelemetry? {
            val progress = (state as? AchievementsState.Ready)?.progress ?: return null
            if (progress.total == 0) return null
            return GameTelemetry(gameId, progress.unlocked, progress.total, progress.earnedPoints, progress.totalPoints)
        }
    }
}
