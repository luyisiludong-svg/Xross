package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.profile.AchievementsState
import dev.xross.launcher.profile.RaAchievement
import dev.xross.launcher.profile.RaBadge

/**
 * The Achievements screen for one game: a grid of badges (unlocked first, newest first; then
 * locked in the game's order) with the focused badge's details below. [result] is null while
 * loading. The grid shows exactly [ROWS] rows of [columns] badges (the screen picks [columns] from
 * its width, [AchievementsLayout]); [top] is the first row shown, moved a row at a time.
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
    val columns: Int = COLUMNS,
    val top: Int = 0,
) {
    val achievements: List<RaAchievement>
        get() = (result as? AchievementsState.Ready)?.progress?.ordered().orEmpty()

    val focused: RaAchievement? get() = achievements.getOrNull(index)

    /** Rows of badges in all. */
    val rowCount: Int get() = (achievements.size + columns - 1) / columns

    fun withResult(next: AchievementsState): AchievementsViewState {
        val size = (next as? AchievementsState.Ready)?.progress?.achievements?.size ?: 0
        return copy(result = next, index = index.coerceIn(0, (size - 1).coerceAtLeast(0))).settled()
    }

    /** The screen measured a new width: keep the focused badge, and its row in view. */
    fun withColumns(n: Int): AchievementsViewState = if (n == columns || n < 1) this else copy(columns = n).settled()

    /** [top] moved the least that keeps the focused row among the [ROWS] shown. */
    fun settled(): AchievementsViewState {
        val row = index / columns
        val lastTop = (rowCount - ROWS).coerceAtLeast(0)
        val next = when {
            row < top -> row
            row > top + ROWS - 1 -> row - (ROWS - 1)
            else -> top
        }.coerceIn(0, lastTop)
        return if (next == top) this else copy(top = next)
    }

    companion object {
        /** Badges per row until the screen has measured its width. */
        const val COLUMNS = 10

        /** Rows of badges shown at once, on every display. */
        const val ROWS = 3
    }
}

/**
 * How the badge grid fills its area (2b.7e): cells as tall as fit exactly [AchievementsViewState.ROWS]
 * rows, as many columns of them as the width takes ([MIN_COLUMNS]–[MAX_COLUMNS]); on a narrow
 * display the cells shrink to fit [MIN_COLUMNS] across and the three rows take less height.
 */
object AchievementsLayout {
    const val MIN_COLUMNS = 4
    const val MAX_COLUMNS = 16

    /** Columns and the cell size for a grid area of [width] × [height] with [gap] between cells (any one unit). */
    fun fit(width: Float, height: Float, gap: Float): Pair<Int, Float> {
        val rows = AchievementsViewState.ROWS
        val byHeight = ((height - gap * (rows - 1)) / rows).coerceAtLeast(1f)
        val columns = ((width + gap) / (byHeight + gap)).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        val byWidth = (width - gap * (columns - 1)) / columns
        return columns to minOf(byHeight, byWidth).coerceAtLeast(1f)
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
    fun onAction(state: AchievementsViewState, action: NavAction): AchievementsOutcome {
        val columns = state.columns
        val size = state.achievements.size
        fun to(i: Int) = AchievementsOutcome(if (size == 0) state else state.copy(index = i.coerceIn(0, size - 1)).settled())
        return when (action) {
            NavAction.LEFT -> to(state.index - 1)
            NavAction.RIGHT -> to(state.index + 1)
            NavAction.UP -> if (state.index < columns) AchievementsOutcome(state) else to(state.index - columns)
            NavAction.DOWN -> if (state.index + columns > size - 1 && state.index / columns == (size - 1) / columns) {
                AchievementsOutcome(state)
            } else {
                to(state.index + columns)
            }
            NavAction.PREV_CATEGORY -> to(state.index - columns * AchievementsViewState.ROWS)
            NavAction.NEXT_CATEGORY -> to(state.index + columns * AchievementsViewState.ROWS)
            NavAction.BACK -> AchievementsOutcome(state, AchievementsEffect.Close)
            NavAction.OPTIONS -> AchievementsOutcome(state, AchievementsEffect.Refresh)
            NavAction.CONFIRM, NavAction.START, NavAction.MEDIA -> AchievementsOutcome(state)
        }
    }

    /** Touch: tap a badge. */
    fun tap(state: AchievementsViewState, index: Int): AchievementsViewState =
        if (index in state.achievements.indices) state.copy(index = index).settled() else state

    /** The banner's status line: "HARDCORE" / "SOFTCORE" for an unlock (its date beside it), else "Locked". */
    fun status(a: RaAchievement): String = when {
        a.hardcore -> "HARDCORE"
        a.unlocked -> "SOFTCORE"
        else -> "Locked"
    }
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
