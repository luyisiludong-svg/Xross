package dev.xross.launcher.state

import dev.xross.launcher.data.AppEntry
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.input.ControllerKey
import dev.xross.launcher.launch.EmulatorRegistry
import dev.xross.launcher.launch.GameLauncher
import dev.xross.launcher.storage.EmulatorOption
import dev.xross.launcher.storage.Platform
import dev.xross.launcher.storage.PlatformRegistry
import dev.xross.launcher.storage.StorageVolumes
import dev.xross.launcher.theme.XrossTheme
import java.util.Locale

data class FolderEntry(
    val path: String,
    val autoDetected: Boolean,
    /** False while the folder's volume (SD card, USB drive) is unplugged. */
    val connected: Boolean = true,
)

/** Live fingerprinting status; kept out of [MenuSnapshot] so progress never rebuilds game lists. */
data class FingerprintStatus(
    val stats: dev.xross.launcher.data.FingerprintStats? = null,
    /** Title being fingerprinted right now, or null when idle. */
    val current: String? = null,
)

/** Everything the menu is derived from. Immutable; rebuilt whenever any input changes. */
data class MenuSnapshot(
    val games: List<GameEntity> = emptyList(),
    val apps: List<AppEntry> = emptyList(),
    val settings: XrossSettings = XrossSettings(),
    val installedEmulators: Set<String> = emptySet(),
    val storageGranted: Boolean = false,
    val folders: List<FolderEntry> = emptyList(),
    val scanStatus: String? = null,
    val versionName: String = "",
    val media: dev.xross.launcher.media.MediaLibrary = dev.xross.launcher.media.MediaLibrary(),
    /** Roots of mounted volumes; empty means "unknown", in which case nothing is marked offline. */
    val mountedRoots: List<String> = emptyList(),
)

/** Pure builder from a [MenuSnapshot] to XMB lists. */
class MenuTree(private val s: MenuSnapshot) {

    private val gamesByPlatform by lazy { s.games.groupBy { it.platformId } }
    private val androidGames by lazy { s.apps.filter { it.isGame } }

    private val cache = HashMap<String, List<XmbNode>>()

    /** Photo/Music/Video lists and the collections behind the media viewers. */
    val media by lazy { MediaMenus(s.media, s.storageGranted) }

    /** Music root with the live Now Playing row. Not cached. */
    fun musicRoot(now: dev.xross.launcher.media.NowPlaying): List<XmbNode> = media.musicRoot(now)

    /** Lists are memoized: a tree is rebuilt only when the snapshot changes, not per keypress. */
    fun list(key: String): List<XmbNode> = cache.getOrPut(key) { build(key) }

    private fun build(key: String): List<XmbNode> = when {
        key == Categories.rootKey(Categories.GAMES) -> gamesRoot()
        key == Categories.rootKey(Categories.APPS) -> s.apps.filterNot { it.isGame }.map(::appNode)
        key == Categories.rootKey(Categories.SETTINGS) -> settingsRoot()
        key == Categories.rootKey(Categories.PHOTO) -> media.photoRoot()
        key == Categories.rootKey(Categories.VIDEO) -> media.videoRoot()
        key == Categories.rootKey(Categories.MUSIC) -> media.musicRoot(dev.xross.launcher.media.NowPlaying())
        media.handles(key) -> media.list(key)
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
        key == SOUND -> soundSettings()
        key == MEDIA_SETTINGS -> mediaSettings()
        key == SYSTEM -> systemSettings()
        else -> emptyList()
    }

    /** Rows for search results: the same game rows (action, Options menu) as the library lists. */
    fun gameNodes(games: List<GameEntity>): List<XmbNode> = games.map { gameNode(it, showPlatform = true) }

    private fun gamesRoot(): List<XmbNode> = buildList {
        if (s.games.isNotEmpty()) {
            add(XmbNode("games:search", "Search", "Find a game by name", NodeIcon.Glyph(GlyphKey.SEARCH), NodeAction.Run(Command.OpenSearch)))
        }
        if (s.games.any { it.lastPlayed != null }) {
            add(folder(RECENT, "Recently Played", GlyphKey.RECENT, null))
        }
        val favorites = s.games.count { it.favorite }
        if (favorites > 0) add(folder(FAVORITES, "Favorites", GlyphKey.FAVORITES, countLabel(favorites)))
        for (platform in PlatformRegistry.all) {
            val count = gamesByPlatform[platform.id]?.size ?: 0
            if (count > 0) {
                add(folder(PLATFORM_PREFIX + platform.id, platform.name, GlyphKey.PLATFORM, countLabel(count)))
            }
        }
        if (androidGames.isNotEmpty()) {
            add(folder(ANDROID_GAMES, "Android Games", GlyphKey.HANDHELD, countLabel(androidGames.size)))
        }
        if (s.games.isEmpty()) {
            add(
                XmbNode(
                    id = "games:empty",
                    title = if (s.storageGranted) "No games found" else "Storage access needed",
                    subtitle = "Open Settings ▸ Library to add ROM folders",
                    icon = NodeIcon.Glyph(GlyphKey.INFO),
                    action = NodeAction.Run(Command.OpenLibrarySettings),
                ),
            )
        }
    }

    private fun gameNode(game: GameEntity, showPlatform: Boolean): XmbNode {
        val platform = PlatformRegistry.byId(game.platformId)
        val parts = buildList {
            if (isOffline(game.path)) add("Not connected")
            if (game.favorite) add("★")
            if (showPlatform && platform != null) add(platform.shortName)
            // Skip the extension when it only repeats the platform ("GBA · GBA").
            val ext = game.fileName.substringAfterLast('.', "").uppercase(Locale.ROOT)
            if (!(showPlatform && ext == platform?.shortName?.uppercase(Locale.ROOT))) add(ext)
            add(formatSize(game.sizeBytes))
            formatPlaytime(game.playTimeMs)?.let { add("Played $it") }
        }
        return XmbNode(
            id = "game:${game.id}",
            title = game.title,
            subtitle = parts.filter { it.isNotEmpty() }.joinToString(" · "),
            icon = NodeIcon.Glyph(if (platform?.isDiscBased == true || game.isDiscImage()) GlyphKey.GAME_DISC else GlyphKey.GAME_CART),
            action = NodeAction.LaunchGame(game.id),
            menu = gameMenu(game, platform),
        )
    }

    /** Options menu for a game: play, favorite, per-game emulator, and its play history. */
    private fun gameMenu(game: GameEntity, platform: Platform?): List<MenuOption> = buildList {
        add(MenuOption("Play", command = Command.LaunchGame(game.id)))
        add(
            MenuOption(
                if (game.favorite) "Remove from favorites" else "Add to favorites",
                command = Command.ToggleFavorite(game.id),
            ),
        )
        if (platform != null && platform.emulators.isNotEmpty()) {
            val choice = s.settings.emulatorChoice[platform.id]
            val platformDefault = GameLauncher.resolveOption(platform, choice, s.installedEmulators)
                ?: platform.emulators.firstOrNull { it.key == choice }
                ?: platform.emulators.first()
            val override = platform.emulators.firstOrNull { it.key == game.emulatorOverride }
            add(
                MenuOption(
                    "Emulator",
                    detail = if (override != null) "${emulatorLabel(override)} · this game only" else emulatorLabel(platformDefault),
                    submenu = buildList {
                        add(
                            MenuOption(
                                "Platform default",
                                detail = emulatorLabel(platformDefault),
                                command = Command.SetGameEmulator(game.id, null),
                                selected = override == null,
                            ),
                        )
                        for (option in platform.emulators) {
                            add(
                                MenuOption(
                                    emulatorLabel(option),
                                    detail = if (option.emulatorId in s.installedEmulators) null else "Not installed",
                                    command = Command.SetGameEmulator(game.id, option.key),
                                    selected = override == option,
                                ),
                            )
                        }
                    },
                ),
            )
        }
        formatPlaytime(game.playTimeMs)?.let { played ->
            val launches = if (game.playCount == 1) "1 time" else "${game.playCount} times"
            add(MenuOption("Played", detail = "$played · $launches"))
        }
        add(MenuOption("Information", detail = "File, size, fingerprints and playtime", command = Command.ShowGameInfo(game.id)))
    }

    /**
     * The emulator [game] would launch with, as shown on the Information screen:
     * "DuckStation · this game only" for an override, the platform choice otherwise.
     */
    fun emulatorSummary(game: GameEntity): String? {
        val platform = PlatformRegistry.byId(game.platformId) ?: return null
        if (platform.emulators.isEmpty()) return null
        val override = platform.emulators.firstOrNull { it.key == game.emulatorOverride }
        if (override != null) return "${emulatorLabel(override)} · this game only"
        val choice = s.settings.emulatorChoice[platform.id]
        val resolved = GameLauncher.resolveOption(platform, choice, s.installedEmulators)
        val shown = resolved ?: platform.emulators.firstOrNull { it.key == choice } ?: platform.emulators.first()
        return emulatorLabel(shown) + if (resolved == null) " (not installed)" else ""
    }

    private fun emulatorLabel(option: EmulatorOption): String =
        (EmulatorRegistry.byId(option.emulatorId)?.name ?: option.emulatorId) + (option.core?.let { " · $it" } ?: "")

    private fun appNode(app: AppEntry) = XmbNode(
        id = "app:${app.packageName}/${app.activityName}",
        title = app.label,
        subtitle = app.packageName,
        icon = NodeIcon.App(app.packageName),
        action = NodeAction.LaunchApp(app.packageName, app.activityName),
    )

    private fun settingsRoot() = listOf(
        folder(LIBRARY, "Library", GlyphKey.STORAGE, "ROM folders and scanning"),
        folder(CONTROLS, "Controls", GlyphKey.CONTROLS, "Buttons, layout and stick"),
        folder(EMULATORS, "Emulators", GlyphKey.EMULATORS, "${s.installedEmulators.size} detected"),
        folder(DISPLAY, "Theme", GlyphKey.DISPLAY, "${XrossTheme.byName(s.settings.theme).label} · wave background"),
        folder(SOUND, "Sound", GlyphKey.SOUND, if (s.settings.soundEffects) "Effects on" else "Effects off"),
        folder(MEDIA_SETTINGS, "Media", GlyphKey.MUSIC, "Slideshow, music and video playback"),
        folder(SYSTEM, "System", GlyphKey.SYSTEM, "Version ${s.versionName}"),
    )

    /** Library settings with live fingerprint rows. Not cached: it's small and changes while hashing. */
    fun library(status: FingerprintStatus): List<XmbNode> = librarySettings(status)

    private fun librarySettings(status: FingerprintStatus = FingerprintStatus()): List<XmbNode> = buildList {
        add(
            setting(
                "lib:access",
                "All files access",
                if (s.storageGranted) "Granted" else "Required to scan ROM folders — press to grant",
                if (s.storageGranted) GlyphKey.STORAGE else GlyphKey.INFO,
                Command.RequestStorageAccess,
            ),
        )
        add(setting("lib:add", "Add ROM folder…", "Pick a folder with system subfolders (snes/, psx/…)", GlyphKey.ADD, Command.AddFolder))
        add(setting("lib:rescan", "Rescan library", s.scanStatus ?: "${s.games.size} games", GlyphKey.REFRESH, Command.Rescan))
        add(setting("lib:fingerprints", "Game fingerprints", fingerprintSummary(status), GlyphKey.STORAGE, Command.RecheckFingerprints))
        add(
            setting(
                "lib:hashlarge",
                "Fingerprint large disc images",
                if (s.settings.hashLargeImages) {
                    "On — slow on SD cards; runs only while Xross is open"
                } else {
                    "Off — ISO/BIN files over 256 MB are skipped"
                },
                GlyphKey.STORAGE,
                Command.ToggleLargeImageHashing,
            ),
        )
        add(
            setting(
                "lib:auto",
                "Auto-detect ROM folders",
                if (s.settings.autoDetectFolders) "On — finds ROMs/, Games/, Emulation/ on every drive" else "Off",
                GlyphKey.FOLDER,
                Command.ToggleAutoDetect,
            ),
        )
        for (folder in s.folders) {
            add(
                XmbNode(
                    id = "lib:folder:${folder.path}",
                    title = folder.path,
                    subtitle = (if (folder.autoDetected) "Auto-detected" else "Added by you") +
                        if (folder.connected) "" else " · not connected",
                    icon = NodeIcon.Glyph(GlyphKey.FOLDER),
                    menu = if (folder.autoDetected) {
                        emptyList()
                    } else {
                        listOf(MenuOption("Remove from library", command = Command.RemoveFolder(folder.path)))
                    },
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
            GlyphKey.CONTROLS,
            Command.CycleConfirmLayout,
        ),
        setting(
            "ctl:deadzone",
            "Analog stick deadzone",
            "${(s.settings.deadzone * 100).toInt()}%",
            GlyphKey.CONTROLS,
            Command.CycleDeadzone,
        ),
        setting(
            "ctl:axial",
            "Axial deadzone",
            if (s.settings.axialDeadzone <= 0f) "Off — radial only" else "${(s.settings.axialDeadzone * 100).toInt()}% per axis",
            GlyphKey.CONTROLS,
            Command.CycleAxialDeadzone,
        ),
        setting(
            "ctl:remap",
            "Remap buttons…",
            "Saved for the controller you press first; other pads keep theirs",
            GlyphKey.CONTROLS,
            Command.StartRemap,
        ),
        setting(
            "ctl:reset",
            "Reset all button mappings",
            mappingSummary(),
            GlyphKey.REFRESH,
            Command.ResetRemap,
        ),
    ) + controllerProfileRows()

    private fun mappingSummary(): String {
        val profiles = s.settings.controllerProfiles.size
        val global = s.settings.keyRemap.isNotEmpty()
        if (profiles == 0 && !global) return "Using defaults"
        return buildList {
            if (profiles > 0) add(if (profiles == 1) "1 controller" else "$profiles controllers")
            if (global) add("default mapping changed")
        }.joinToString(" · ")
    }

    /** One row per controller model with its own mapping; Options ▸ Reset removes just that one. */
    private fun controllerProfileRows(): List<XmbNode> = buildList {
        if (s.settings.keyRemap.isNotEmpty()) {
            add(
                XmbNode(
                    id = "ctl:profile:global",
                    title = "Default mapping",
                    subtitle = "${bindingCount(s.settings.keyRemap.size)} · keyboards and other controllers",
                    icon = NodeIcon.Glyph(GlyphKey.CONTROLS),
                    menu = listOf(MenuOption("Reset to defaults", command = Command.ResetGlobalRemap)),
                ),
            )
        }
        for ((id, profile) in s.settings.controllerProfiles.entries.sortedBy { it.value.name.lowercase(Locale.ROOT) }) {
            val key = ControllerKey.parse(id)
            add(
                XmbNode(
                    id = "ctl:profile:$id",
                    title = profile.name.ifBlank { "Controller ${key?.display ?: id}" },
                    subtitle = bindingCount(profile.remap.size) + (key?.let { " · ${it.display}" } ?: ""),
                    icon = NodeIcon.Glyph(GlyphKey.CONTROLS),
                    menu = listOf(MenuOption("Reset this controller", command = Command.ResetControllerProfile(id))),
                ),
            )
        }
    }

    private fun bindingCount(n: Int) = if (n == 1) "1 custom binding" else "$n custom bindings"

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
            GlyphKey.EMULATORS,
            Command.CycleEmulator(platform.id),
        )
    }

    private fun displaySettings() = listOf(
        setting("disp:theme", "Theme color", themeLabel(XrossTheme.byName(s.settings.theme)), GlyphKey.DISPLAY, Command.CycleTheme),
        setting("disp:wave", "Wave background", onOff(s.settings.waveEnabled), GlyphKey.DISPLAY, Command.ToggleWave),
        setting("disp:speed", "Wave speed", "${s.settings.waveSpeed}x", GlyphKey.DISPLAY, Command.CycleWaveSpeed),
        setting(
            "disp:hints",
            "Button hints",
            if (s.settings.showButtonHints) "Shown in the corner of the home menu" else "Hidden",
            GlyphKey.DISPLAY,
            Command.ToggleButtonHints,
        ),
    )

    private fun soundSettings() = listOf(
        setting("snd:fx", "Sound effects", onOff(s.settings.soundEffects), GlyphKey.SOUND, Command.ToggleSoundEffects),
        setting("snd:vol", "Effects volume", "${(s.settings.soundVolume * 100).toInt()}%", GlyphKey.SOUND, Command.CycleSoundVolume),
        setting("snd:boot", "Startup sound", onOff(s.settings.bootSound), GlyphKey.SOUND, Command.ToggleBootSound),
        setting("snd:gameboot", "Game start sound", onOff(s.settings.gameBootSound), GlyphKey.SOUND, Command.ToggleGameBootSound),
    )

    private fun systemSettings() = listOf(
        XmbNode("sys:version", "Xross", "Version ${s.versionName}", NodeIcon.Glyph(GlyphKey.INFO)),
        setting("sys:home", "Set as home app", "Choose Xross as the default launcher", GlyphKey.HOME, Command.OpenHomeSettings),
        setting("sys:android", "Android settings", null, GlyphKey.SETTINGS, Command.OpenAndroidSettings),
    )

    private fun folder(key: String, title: String, glyph: GlyphKey, subtitle: String?) =
        XmbNode(key, title, subtitle, NodeIcon.Glyph(glyph), NodeAction.OpenList(key))

    private fun setting(id: String, title: String, subtitle: String?, glyph: GlyphKey, command: Command) =
        XmbNode(id, title, subtitle, NodeIcon.Glyph(glyph), NodeAction.Run(command))

    private fun mediaSettings() = listOf(
        setting("med:slideshow", "Slideshow interval", "${s.settings.slideshowSeconds} seconds", GlyphKey.PHOTO, Command.CycleSlideshowInterval),
        setting(
            "med:music",
            "Music while playing games",
            if (s.settings.resumeMusicAfterGames) "Pause, then resume when you return" else "Stop when a game starts",
            GlyphKey.MUSIC,
            Command.ToggleResumeMusicAfterGames,
        ),
        setting("med:loop", "Loop videos", onOff(s.settings.videoLoop), GlyphKey.VIDEO, Command.ToggleVideoLoop),
    )

    private val mountedRoots by lazy { s.mountedRoots.mapTo(HashSet()) { it.trimEnd('/') } }

    /** True when the file's volume (SD card, USB drive) is currently unplugged. */
    fun isOffline(path: String): Boolean =
        mountedRoots.isNotEmpty() && StorageVolumes.volumeRootOf(path)?.let { it !in mountedRoots } ?: false

    private fun onOff(on: Boolean) = if (on) "On" else "Off"

    private fun themeLabel(theme: XrossTheme) =
        if (theme.dynamic) "${theme.label} · changes every month" else theme.label

    private fun fingerprintSummary(status: FingerprintStatus): String {
        val st = status.stats ?: return "Counting…"
        if (status.current != null) {
            return "Fingerprinting ${st.total - st.pending} of ${st.total} · ${status.current}"
        }
        val parts = buildList {
            add("${st.hashed} identified by content")
            if (st.byName > 0) add(if (st.byName == 1) "1 arcade set by name" else "${st.byName} arcade sets by name")
            if (st.skipped > 0) add("${st.skipped} skipped")
            if (st.pending > 0) add("${st.pending} waiting")
        }
        return parts.joinToString(" · ")
    }

    private fun GameEntity.isDiscImage(): Boolean =
        fileName.substringAfterLast('.').lowercase() in DISC_EXTENSIONS

    companion object {
        const val RECENT = "collection:recent"
        const val FAVORITES = "collection:favorites"
        const val ANDROID_GAMES = "collection:android-games"
        const val PLATFORM_PREFIX = "platform:"
        const val LIBRARY = "settings:library"
        const val CONTROLS = "settings:controls"
        const val EMULATORS = "settings:emulators"
        const val DISPLAY = "settings:display"
        const val SOUND = "settings:sound"
        const val MEDIA_SETTINGS = "settings:media"
        private val DISC_EXTENSIONS = setOf("iso", "cue", "chd", "cso", "rvz", "gcz", "wbfs", "cdi", "gdi", "pbp", "m3u")
        const val SYSTEM = "settings:system"

        private fun countLabel(n: Int) = if (n == 1) "1 title" else "$n titles"

        /** "45 min", "2 h 15 min", "120 h"; null under a minute (nothing worth showing). */
        fun formatPlaytime(ms: Long): String? {
            val minutes = ms / 60_000
            return when {
                minutes < 1 -> null
                minutes < 60 -> "$minutes min"
                minutes >= 100 * 60 || minutes % 60 == 0L -> "${minutes / 60} h"
                else -> "${minutes / 60} h ${minutes % 60} min"
            }
        }

        fun formatSize(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
            else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
        }
    }
}
