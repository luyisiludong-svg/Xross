package dev.wave.launcher.state

import androidx.compose.runtime.Immutable

/** One row of an item's Options (△ / Y) menu. */
@Immutable
data class MenuOption(
    val label: String,
    /** Current value or extra information, shown beside the label. */
    val detail: String? = null,
    /** Runs when selected; null for submenu parents and information rows. */
    val command: Command? = null,
    val submenu: List<MenuOption> = emptyList(),
    /** Marks the active choice in a submenu. */
    val selected: Boolean = false,
)

/**
 * Pure state of the open Options menu: a stack of levels (the menu and any opened submenus),
 * each with its own cursor.
 */
@Immutable
data class OptionsMenuState(val title: String, val levels: List<Level>) {
    @Immutable
    data class Level(val title: String, val options: List<MenuOption>, val index: Int = 0)

    sealed interface Selection {
        data class Run(val command: Command) : Selection
        data class Open(val menu: OptionsMenuState) : Selection
        data object None : Selection
    }

    val current: Level get() = levels.last()
    val focused: MenuOption? get() = current.options.getOrNull(current.index)
    val depth: Int get() = levels.size

    fun move(delta: Int): OptionsMenuState {
        val level = current
        if (level.options.isEmpty()) return this
        val index = (level.index + delta).coerceIn(0, level.options.lastIndex)
        return copy(levels = levels.dropLast(1) + level.copy(index = index))
    }

    fun moveTo(index: Int): OptionsMenuState = move(index - current.index)

    fun select(): Selection {
        val option = focused ?: return Selection.None
        return when {
            option.submenu.isNotEmpty() -> {
                // Open on the active choice so "which one is set?" is answered at a glance.
                val start = option.submenu.indexOfFirst { it.selected }.coerceAtLeast(0)
                Selection.Open(copy(levels = levels + Level(option.label, option.submenu, start)))
            }
            option.command != null -> Selection.Run(option.command)
            else -> Selection.None
        }
    }

    /** Leaves the current submenu; null when the menu itself should close. */
    fun back(): OptionsMenuState? = if (levels.size <= 1) null else copy(levels = levels.dropLast(1))

    companion object {
        fun open(title: String, options: List<MenuOption>) = OptionsMenuState(title, listOf(Level(title, options)))
    }
}
