package dev.wave.launcher.state

import dev.wave.launcher.data.AppEntry
import dev.wave.launcher.data.GameEntity
import dev.wave.launcher.data.WaveSettings
import dev.wave.launcher.input.ConfirmLayout
import dev.wave.launcher.launch.EmulatorRegistry
import dev.wave.launcher.launch.GameLauncher
import dev.wave.launcher.storage.PlatformRegistry
import java.util.Locale

data class FolderEntry(val path: String, val autoDetected: Boolean)

/** Everything the menu is derived from. Immutable; rebuilt whenever any input changes. */
data class MenuSnapshot(
    val games: List<GameEntity> = emptyList(),
    val apps: List<AppEntry> = emptyList(),
    val settings: WaveSettings = WaveSettings(),
    val installedEmulators: Set<String> = emptySet(),
    val storageGranted: Boolean = false,
    val folders: List<FolderEntry> = emptyList(),
    val scanStatus: String? = null,
    val versionName: String = "",
)

/** Pure builder from a [MenuSnapshot] to XMB lists. */
class MenuTree(private val s: MenuSnapshot) {

    private val gamesByPlatform by lazy { s.games.groupBy { it.platformId } }
    private val androidGames by lazy { s.apps.filter { it.isGame } }

    private val cache = HashMap<String, List<XmbNode>>()

    /** Lists are memoized: a tree is rebuilt only when the snapshot changes, not per keypress. */
    fun list(key: String): List<XmbNode> = cache.getOrPut(key) { build(key) }

    private fun build(key: String): List<XmbNode> = when {
        key == Categories.rootKey(Categories.GAMES) -> gamesRoot()
        key == Categories.rootKey(Categories.APPS) -> s.apps.filterNot { it.isGame }.map(::appNode)
        key == Categories.rootKey(Categories.SETTINGS) -> settingsRoot()
        key == RECENT -> s.games.filter { it.lastPlayed != null }
            .sortedByDescending { it.lastPlayed }.take(30).map { gameNode(it, showPlatform = true) }
        key == FAVORITES -> s.games.filter { it.favorite }.map { gameNode(it, showPlatform = true) }
        key == ANDROID_GAMES -> androidGames.map(::appNode)
        key.startsWith(PLATFORM_PREFIX) ->
            gamesByPlatform[key.removePrefix(PLATFORM_PREFIX)].orEmpty().map { gameNode(it, showPlatform = false) }
        key == LIBRARY -> librarySettings()
        key == CONTROLS -> controlSettings()
        key == EMULATORS -> emulatorSettings()
        key == DISPLAY -> displaySettings()
        key == SYSTEM -> systemSettings()
        else -> emptyList()
    }

    private fun gamesRoot(): List<XmbNode> = buildList {
        if (s.games.any { it.lastPlayed != null }) {
            add(folder(RECENT, "Recently Played", GlyphKey.HISTORY, null))
        }
        val favorites = s.games.count { it.favorite }
        if (favorites > 0) add(folder(FAVORITES, "Favorites", GlyphKey.STAR, countLabel(favorites)))
        for (platform in PlatformRegistry.all) {
            val count = gamesByPlatform[platform.id]?.size ?: 0
            if (count > 0) {
                add(folder(PLATFORM_PREFIX + platform.id, platform.name, GlyphKey.PLATFORM, countLabel(count)))
            }
        }
        if (androidGames.isNotEmpty()) {
            add(folder(ANDROID_GAMES, "Android Games", GlyphKey.ANDROID, countLabel(androidGames.size)))
        }
        if (s.games.isEmpty()) {
            add(
                XmbNode(
                    id = "games:empty",
                    title = if (s.storageGranted) "No games found" else "Storage access needed",
                    subtitle = "Open Settings ▸ Library to add ROM folders",
                    icon = NodeIcon.Glyph(GlyphKey.WARNING),
                    action = NodeAction.Run(Command.OpenLibrarySettings),
                ),
            )
        }
    }

    private fun gameNode(game: GameEntity, showPlatform: Boolean): XmbNode {
        val platform = PlatformRegistry.byId(game.platformId)
        val parts = buildList {
            if (game.favorite) add("★")
            if (showPlatform && platform != null) add(platform.shortName)
            add(game.fileName.substringAfterLast('.', "").uppercase(Locale.ROOT))
            add(formatSize(game.sizeBytes))
        }
        return XmbNode(
            id = "game:${game.id}",
            title = game.title,
            subtitle = parts.filter { it.isNotEmpty() }.joinToString(" · "),
            icon = NodeIcon.Glyph(GlyphKey.GAME),
            action = NodeAction.LaunchGame(game.id),
            options = Command.ToggleFavorite(game.id),
            optionsLabel = if (game.favorite) "Unfavorite" else "Favorite",
        )
    }

    private fun appNode(app: AppEntry) = XmbNode(
        id = "app:${app.packageName}/${app.activityName}",
        title = app.label,
        subtitle = app.packageName,
        icon = NodeIcon.App(app.packageName),
        action = NodeAction.LaunchApp(app.packageName, app.activityName),
    )

    private fun settingsRoot() = listOf(
        folder(LIBRARY, "Library", GlyphKey.STORAGE, "ROM folders and scanning"),
        folder(CONTROLS, "Controls", GlyphKey.CONTROLLER, "Buttons, layout and stick"),
        folder(EMULATORS, "Emulators", GlyphKey.EMULATOR, "${s.installedEmulators.size} detected"),
        folder(DISPLAY, "Display", GlyphKey.DISPLAY, "Wave background"),
        folder(SYSTEM, "System", GlyphKey.INFO, "Version ${s.versionName}"),
    )

    private fun librarySettings(): List<XmbNode> = buildList {
        add(
            setting(
                "lib:access",
                "All files access",
                if (s.storageGranted) "Granted" else "Required to scan ROM folders — press to grant",
                if (s.storageGranted) GlyphKey.TOGGLE_ON else GlyphKey.WARNING,
                Command.RequestStorageAccess,
            ),
        )
        add(setting("lib:add", "Add ROM folder…", "Pick a folder with system subfolders (snes/, psx/…)", GlyphKey.ADD, Command.AddFolder))
        add(setting("lib:rescan", "Rescan library", s.scanStatus ?: "${s.games.size} games", GlyphKey.REFRESH, Command.Rescan))
        add(
            setting(
                "lib:auto",
                "Auto-detect ROM folders",
                if (s.settings.autoDetectFolders) "On — finds ROMs/, Games/, Emulation/ on every drive" else "Off",
                toggleGlyph(s.settings.autoDetectFolders),
                Command.ToggleAutoDetect,
            ),
        )
        for (folder in s.folders) {
            add(
                XmbNode(
                    id = "lib:folder:${folder.path}",
                    title = folder.path,
                    subtitle = if (folder.autoDetected) "Auto-detected" else "Added by you",
                    icon = NodeIcon.Glyph(GlyphKey.FOLDER),
                    options = if (folder.autoDetected) null else Command.RemoveFolder(folder.path),
                    optionsLabel = if (folder.autoDetected) null else "Remove",
                ),
            )
        }
    }

    private fun controlSettings() = listOf(
        setting(
            "ctl:confirm",
            "Confirm button",
            when (s.settings.confirmLayout) {
                ConfirmLayout.AUTO -> "Auto — East on Nintendo pads, South on others"
                ConfirmLayout.SOUTH -> "South (✕ / A on Xbox)"
                ConfirmLayout.EAST -> "East (○ / A on Nintendo)"
            },
            GlyphKey.CONTROLLER,
            Command.CycleConfirmLayout,
        ),
        setting(
            "ctl:deadzone",
            "Analog stick deadzone",
            "${(s.settings.deadzone * 100).toInt()}%",
            GlyphKey.CONTROLLER,
            Command.CycleDeadzone,
        ),
        setting("ctl:remap", "Remap buttons…", "Press each button when asked", GlyphKey.CONTROLLER, Command.StartRemap),
        setting(
            "ctl:reset",
            "Reset button mapping",
            if (s.settings.keyRemap.isEmpty()) "Using defaults" else "${s.settings.keyRemap.size} custom bindings",
            GlyphKey.REFRESH,
            Command.ResetRemap,
        ),
    )

    private fun emulatorSettings() = PlatformRegistry.all.map { platform ->
        val option = GameLauncher.resolveOption(platform, s.settings.emulatorChoice[platform.id], s.installedEmulators)
        val explicit = platform.emulators.firstOrNull { it.key == s.settings.emulatorChoice[platform.id] }
        val shown = option ?: explicit ?: platform.emulators.first()
        val name = EmulatorRegistry.byId(shown.emulatorId)?.name ?: shown.emulatorId
        val label = name + (shown.core?.let { " · $it" } ?: "")
        val status = if (option == null) " (not installed)" else ""
        setting(
            "emu:${platform.id}",
            platform.name,
            label + status,
            GlyphKey.EMULATOR,
            Command.CycleEmulator(platform.id),
        )
    }

    private fun displaySettings() = listOf(
        setting("disp:wave", "Wave background", if (s.settings.waveEnabled) "On" else "Off", toggleGlyph(s.settings.waveEnabled), Command.ToggleWave),
        setting("disp:speed", "Wave speed", "${s.settings.waveSpeed}x", GlyphKey.WAVE, Command.CycleWaveSpeed),
    )

    private fun systemSettings() = listOf(
        XmbNode("sys:version", "Wave", "Version ${s.versionName}", NodeIcon.Glyph(GlyphKey.INFO)),
        setting("sys:home", "Set as home app", "Choose Wave as the default launcher", GlyphKey.HOME, Command.OpenHomeSettings),
        setting("sys:android", "Android settings", null, GlyphKey.SETTINGS, Command.OpenAndroidSettings),
    )

    private fun folder(key: String, title: String, glyph: GlyphKey, subtitle: String?) =
        XmbNode(key, title, subtitle, NodeIcon.Glyph(glyph), NodeAction.OpenList(key))

    private fun setting(id: String, title: String, subtitle: String?, glyph: GlyphKey, command: Command) =
        XmbNode(id, title, subtitle, NodeIcon.Glyph(glyph), NodeAction.Run(command))

    private fun toggleGlyph(on: Boolean) = if (on) GlyphKey.TOGGLE_ON else GlyphKey.TOGGLE_OFF

    companion object {
        const val RECENT = "collection:recent"
        const val FAVORITES = "collection:favorites"
        const val ANDROID_GAMES = "collection:android-games"
        const val PLATFORM_PREFIX = "platform:"
        const val LIBRARY = "settings:library"
        const val CONTROLS = "settings:controls"
        const val EMULATORS = "settings:emulators"
        const val DISPLAY = "settings:display"
        const val SYSTEM = "settings:system"

        private fun countLabel(n: Int) = if (n == 1) "1 title" else "$n titles"

        fun formatSize(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
            else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
        }
    }
}
