package dev.wave.launcher.state

/** Icon identifiers; the UI layer maps these to vectors or loads app icons. */
sealed interface NodeIcon {
    data class Glyph(val key: GlyphKey) : NodeIcon
    data class App(val packageName: String) : NodeIcon
}

/** Icon roles; each maps to an `xmb_*` vector drawable generated from assets/icons. */
enum class GlyphKey {
    SETTINGS, GAMES, APPS,
    PLATFORM, GAME_CART, GAME_DISC, FAVORITES, RECENT, HANDHELD, FOLDER, STORAGE,
    CONTROLS, EMULATORS, DISPLAY, SOUND, SYSTEM,
    ADD, REFRESH, HOME, INFO,
}

sealed interface Command {
    data object RequestStorageAccess : Command
    data object AddFolder : Command
    data class RemoveFolder(val path: String) : Command
    data object Rescan : Command
    data object ToggleAutoDetect : Command
    data object CycleConfirmLayout : Command
    data object CycleDeadzone : Command
    data object StartRemap : Command
    data object ResetRemap : Command
    data class CycleEmulator(val platformId: String) : Command
    data object ToggleWave : Command
    data object CycleWaveSpeed : Command
    data object CycleTheme : Command
    data object ToggleSoundEffects : Command
    data object CycleSoundVolume : Command
    data object ToggleBootSound : Command
    data object ToggleGameBootSound : Command
    data object OpenHomeSettings : Command
    data object OpenAndroidSettings : Command
    data object OpenLibrarySettings : Command
    data class ToggleFavorite(val gameId: Long) : Command
}

sealed interface NodeAction {
    data class OpenList(val key: String) : NodeAction
    data class LaunchGame(val gameId: Long) : NodeAction
    data class LaunchApp(val packageName: String, val activityName: String) : NodeAction
    data class Run(val command: Command) : NodeAction
    data object None : NodeAction
}

data class XmbNode(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: NodeIcon = NodeIcon.Glyph(GlyphKey.INFO),
    val action: NodeAction = NodeAction.None,
    /** Secondary action bound to the Options button (△ / Y). */
    val options: Command? = null,
    val optionsLabel: String? = null,
)

data class XmbCategory(
    val id: String,
    val title: String,
    val icon: GlyphKey,
)

object Categories {
    const val SETTINGS = "settings"
    const val GAMES = "games"
    const val APPS = "apps"

    val all = listOf(
        XmbCategory(SETTINGS, "Settings", GlyphKey.SETTINGS),
        XmbCategory(GAMES, "Games", GlyphKey.GAMES),
        XmbCategory(APPS, "Apps", GlyphKey.APPS),
    )

    fun rootKey(categoryId: String) = "root:$categoryId"
}
