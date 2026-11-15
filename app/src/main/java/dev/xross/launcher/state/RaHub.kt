package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.profile.AchievementsState
import dev.xross.launcher.profile.RaBadge
import dev.xross.launcher.profile.RaHubSummary
import dev.xross.launcher.profile.RaLibrarySummary

/** Where the focus is in Games ▸ RetroAchievements (2b.7e). */
enum class HubFocus { DASHBOARD, SIDEBAR }

/**
 * Games ▸ RetroAchievements: the profile dashboard (identity, completion across the library,
 * mastered games, recent unlocks) and the `FOLLOWING` sidebar. [summary] is null while the
 * library's cached progress is read.
 */
@Immutable
data class RaHubState(
    /** Scroll step of the right-hand column, moved by up/down. */
    val scroll: Int = 0,
    val summary: RaHubSummary? = null,
    /** Changes when new art is stored, so the mastery showcase reloads its box art. */
    val artVersion: Long = 0,
    /** The platform filter pill: 0 is All, then [RaHubSummary.systems] in order (L1/R1). */
    val filter: Int = 0,
    val focus: HubFocus = HubFocus.DASHBOARD,
    /** The focused row of the sidebar ([HubSidebar.rows]). */
    val row: Int = 0,
) {
    /** Filter pills: "All", then each system with tracked games. */
    val pills: List<String> get() = listOf("All") + summary?.systems.orEmpty().map { it.tag }

    /** What the gauge and the showcase count under the current filter. */
    val shown: RaLibrarySummary? get() = summary?.summaryFor(filter)

    /** Recent unlocks under the current filter: every one for All, else those of the system's sets. */
    fun activity(badges: List<RaBadge>): List<RaBadge> {
        val system = summary?.systems?.getOrNull(filter - 1) ?: return badges
        return badges.filter { it.gameTitle.lowercase() in system.titles }
    }

    companion object {
        const val MAX_SCROLL = 30
    }
}

sealed interface RaHubEffect {
    data object None : RaHubEffect
    data object Close : RaHubEffect
    /** Fetch the profile again, or start linking when no account is linked. */
    data object Primary : RaHubEffect
    /** Confirm on sidebar row [row]: open that friend's latest game if the library has it. */
    data class OpenFriend(val row: Int) : RaHubEffect
}

data class RaHubOutcome(val state: RaHubState, val effect: RaHubEffect = RaHubEffect.None)

/** Pure input handling for the RetroAchievements dashboard. */
object RaHub {
    /**
     * [rows]: the sidebar's rows, 0 when it is collapsed. On the dashboard Up/Down scroll, L1/R1
     * move the platform filter (clamped at All and the last system, back to the top), Right goes
     * to the sidebar, Left and Back close. In the sidebar Up/Down move (clamped), Left goes back,
     * Confirm opens the friend's latest game, L1/R1 still filter.
     */
    fun onAction(state: RaHubState, action: NavAction, rows: Int = 0): RaHubOutcome {
        // A sidebar that collapsed under the focus hands it back to the dashboard.
        val s = if (state.focus == HubFocus.SIDEBAR && rows == 0) state.copy(focus = HubFocus.DASHBOARD) else state
        val lastPill = s.pills.size - 1
        fun filter(delta: Int): RaHubOutcome {
            val next = (s.filter + delta).coerceIn(0, lastPill)
            return RaHubOutcome(if (next == s.filter) s else s.copy(filter = next, scroll = 0))
        }
        when (action) {
            NavAction.PREV_CATEGORY -> return filter(-1)
            NavAction.NEXT_CATEGORY -> return filter(+1)
            NavAction.BACK -> return RaHubOutcome(s, RaHubEffect.Close)
            NavAction.OPTIONS -> return RaHubOutcome(s, RaHubEffect.Primary)
            NavAction.MEDIA -> return RaHubOutcome(s)
            else -> Unit
        }
        return if (s.focus == HubFocus.SIDEBAR) {
            val last = rows - 1
            when (action) {
                NavAction.UP -> RaHubOutcome(s.copy(row = (s.row - 1).coerceIn(0, last)))
                NavAction.DOWN -> RaHubOutcome(s.copy(row = (s.row + 1).coerceIn(0, last)))
                NavAction.LEFT -> RaHubOutcome(s.copy(focus = HubFocus.DASHBOARD))
                NavAction.CONFIRM, NavAction.START -> RaHubOutcome(s, RaHubEffect.OpenFriend(s.row.coerceIn(0, last)))
                else -> RaHubOutcome(s)
            }
        } else {
            when (action) {
                NavAction.UP -> RaHubOutcome(s.copy(scroll = (s.scroll - 1).coerceAtLeast(0)))
                NavAction.DOWN -> RaHubOutcome(s.copy(scroll = (s.scroll + 1).coerceAtMost(RaHubState.MAX_SCROLL)))
                NavAction.RIGHT -> RaHubOutcome(if (rows > 0) s.copy(focus = HubFocus.SIDEBAR, row = s.row.coerceIn(0, rows - 1)) else s)
                NavAction.LEFT -> RaHubOutcome(s, RaHubEffect.Close)
                NavAction.CONFIRM, NavAction.START -> RaHubOutcome(s, RaHubEffect.Primary)
                else -> RaHubOutcome(s)
            }
        }
    }

    /** The summary was read again: keep the filter on the same system if it is still there. */
    fun withSummary(state: RaHubState, summary: RaHubSummary): RaHubState {
        val system = state.summary?.systems?.getOrNull(state.filter - 1)?.platformId
        val filter = system?.let { id -> summary.systems.indexOfFirst { it.platformId == id }.takeIf { it >= 0 }?.plus(1) } ?: 0
        return state.copy(summary = summary, filter = filter, scroll = if (filter == state.filter) state.scroll else 0)
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
