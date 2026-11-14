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
 * Only games RetroAchievements recognizes and has a set for get one. The compact pill (2b.7d
 * `COMPACT_PILL`) draws the counts; the rich card (`RICH_CARD`) also the platform, the newest
 * unlocks ([slots]) and who of the followed users played it last (by [raGameId]).
 */
@Immutable
data class GameTelemetry(
    val gameId: Long,
    val unlocked: Int,
    val total: Int,
    val earnedPoints: Int,
    val totalPoints: Int,
    /** The library game's art key, for the rich card's icon; "" when unknown. */
    val uuid: String = "",
    /** RetroAchievements' id of the game; 0 when unknown. Friends' latest games match by it. */
    val raGameId: Int = 0,
    /** The library's short tag of the system ("SNES"), else RetroAchievements' console name. */
    val platform: String = "",
    /** The rich card's badge row: the newest unlocks first, then locked ones; [TelemetryCard.SLOTS] at most. */
    val slots: List<TelemetrySlot> = emptyList(),
    /**
     * The progress is the saved copy because RetroAchievements didn't answer (a failed fetch, not
     * the instant copy read before the fetch): the rich card leaves out its friend strip.
     */
    val offline: Boolean = false,
) {
    /** Every achievement unlocked. */
    val mastered: Boolean get() = total > 0 && unlocked >= total

    companion object {
        /**
         * Null for anything but a recognized game with achievements (unlinked, unsupported,
         * unhashed, failed). [platform]: the library's tag for the game's system. [peeked]: the
         * instant copy read before the fetch, never [offline] (the fetch hasn't failed yet).
         */
        fun of(
            gameId: Long,
            state: AchievementsState?,
            platform: String? = null,
            peeked: Boolean = false,
            uuid: String = "",
        ): GameTelemetry? {
            val ready = state as? AchievementsState.Ready ?: return null
            val progress = ready.progress
            if (progress.total == 0) return null
            return GameTelemetry(
                gameId = gameId,
                unlocked = progress.unlocked,
                total = progress.total,
                earnedPoints = progress.earnedPoints,
                totalPoints = progress.totalPoints,
                uuid = uuid,
                raGameId = progress.gameId,
                platform = platform?.takeIf { it.isNotBlank() } ?: progress.consoleName,
                slots = TelemetryCard.slots(progress),
                offline = ready.offline && !peeked,
            )
        }
    }
}
