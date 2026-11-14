package dev.xross.launcher.state

import dev.xross.launcher.audio.AudioMixer
import dev.xross.launcher.audio.MusicUnderSnaps
import dev.xross.launcher.audio.VolumeChannel
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
import dev.xross.launcher.theme.ProfileField
import dev.xross.launcher.theme.XrossTheme
import java.util.Locale

data class FolderEntry(
    val path: String,
    val autoDetected: Boolean,
    /** False while the folder's volume (SD card, USB drive) is unplugged. */
    val connected: Boolean = true,
    /** The base ROMs folder (Settings ▸ Library ▸ Base ROMs folder). */
    val base: Boolean = false,
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
    /** Imported DATs with their match counts. */
    val dats: List<dev.xross.launcher.data.DatSummary> = emptyList(),
    /** gameId → DAT match, scraped fields and overrides. */
    val metadata: Map<Long, dev.xross.launcher.data.GameMetadataEntity> = emptyMap(),
    /** Folder DATs are imported from (resolved from the setting). */
    val datFolder: String = "",
    /** Which metadata services can run. */
    val scraperSetup: ScraperSetup = ScraperSetup(),
    /** Where the Xross folder is, and the library backup in it. */
    val portable: PortableSetup = PortableSetup(),
    /** Base ROMs folder in use (resolved from the setting). */
    val baseRomsFolder: String = "",
    /** The linked RetroAchievements account, for Settings ▸ Accounts. */
    val accounts: AccountsSetup = AccountsSetup(),
    /** Theme packs found and the active one, for Settings ▸ Theme. */
    val themes: ThemeSetup = ThemeSetup(),
)

/** What Settings ▸ Theme shows about theme packs. */
data class ThemeSetup(
    /** (id, name) of every pack, Default first. */
    val packs: List<Pair<String, String>> = listOf(dev.xross.launcher.theme.pack.ThemePackLoader.DEFAULT_ID to "Default"),
    val activeId: String = dev.xross.launcher.theme.pack.ThemePackLoader.DEFAULT_ID,
    val activeName: String = "Default",
    val activeAuthor: String = "",
    /** The active pack brings its own colors (the "Theme pack" color option). */
    val packPalette: Boolean = false,
    /** Problems loading the active pack, shown under its row. */
    val problems: List<String> = emptyList(),
    /** (id, label) of every surface the active pack offers: the four presets, then its own. */
    val surfaces: List<Pair<String, String>> = emptyList(),
    /** The pack's own `effects.surfaceStyle`. */
    val packSurface: String = "FLAT_GLASS",
    /** Folders in Xross/themes/ skipped as packs, with the reason ("Sakura: no schemaVersion 2"). */
    val rejected: List<String> = emptyList(),
    /** (id, name) of the active pack's variants; empty hides Theme variant. */
    val variants: List<Pair<String, String>> = emptyList(),
    /** The active pack's own circadian, battery-tint and adaptive-palette switches. */
    val packFlags: dev.xross.launcher.theme.pack.ColorFlags = dev.xross.launcher.theme.pack.ColorFlags.NONE,
    /** The active pack brings a wallpaper (`wallpaper.png` or `effects.wallpaperPath`). */
    val packWallpaper: Boolean = false,
    /** The active pack brings ambient music (`bgm.ogg` / `bgm.mp3`); filled in by Phase 2b.5c, which plays it. */
    val packMusic: Boolean = false,
    /** The active pack brings a boot video (`boot.mp4` or `audio.bootVideo`, 2b.5d). */
    val packBootVideo: Boolean = false,
    /**
     * The pack's own look per variant id ("" = its base), no category open, before the user's
     * picks (2b.4f): what the effect rows call "Theme pack (…)".
     */
    val looks: Map<String, dev.xross.launcher.theme.pack.ThemeLook> = emptyMap(),
) {
    /** The look of [variant], else the base look, else a plain default. */
    fun packLook(variant: String): dev.xross.launcher.theme.pack.ThemeLook =
        looks[variant] ?: looks[""] ?: dev.xross.launcher.theme.pack.ThemeLook(palette = null, surfaceId = packSurface)
}

/** What Settings ▸ Accounts shows (public profile data only, never the Web API key). */
data class AccountsSetup(
    val retroAchievementsUser: String = "",
    val retroAchievementsLinked: Boolean = false,
    val retroAchievementsPoints: Long? = null,
    val retroAchievementsRank: Int? = null,
)

/** What Settings ▸ System shows about the Xross folder and the database backup. */
data class PortableSetup(
    /** Path of the Xross folder in use; empty until known. */
    val root: String = "",
    /** False while the folder is in app storage because shared storage can't be written yet. */
    val portable: Boolean = false,
    val backup: dev.xross.launcher.data.BackupStatus = dev.xross.launcher.data.BackupStatus(),
    /** The snapshot currently in `Xross/data/`, if any. */
    val backupOnDisk: dev.xross.launcher.data.BackupInfo? = null,
    /** Set on a clean install when the Xross folder holds a backup with games. */
    val restorable: dev.xross.launcher.data.BackupInfo? = null,
)

/** What the Metadata settings need to know about service accounts (never the secrets). */
data class ScraperSetup(
    /** This build carries ScreenScraper developer credentials. */
    val screenScraperAvailable: Boolean = false,
    val screenScraperUser: String = "",
    val steamGridDbKeySet: Boolean = false,
    /** Last quota ScreenScraper reported ("120 of 20,000 requests today"), if any. */
    val screenScraperQuota: String? = null,
)

/** Pure builder from a [MenuSnapshot] to XMB lists. */
class MenuTree(private val s: MenuSnapshot) {

    private val gamesByPlatform by lazy { s.games.groupBy { it.platformId } }

    /** Game id → platform id, for the theme's system overrides on the focused game. */
    val platformByGame: Map<Long, String> by lazy { s.games.associate { it.id to it.platformId } }
    private val visibleApps by lazy { AppFolders.visible(s.apps, s.settings) }
    private val androidGames by lazy { visibleApps.filter { it.isGame } }

    private val cache = HashMap<String, List<XmbNode>>()

    /** Photo/Music/Video lists and the collections behind the media viewers. */
    val media by lazy { MediaMenus(s.media, s.storageGranted) }

    /** Music root with the live Now Playing row. Not cached. */
    fun musicRoot(now: dev.xross.launcher.media.NowPlaying): List<XmbNode> = media.musicRoot(now)

    /** Lists are memoized: a tree is rebuilt only when the snapshot changes, not per keypress. */
    fun list(key: String): List<XmbNode> = cache.getOrPut(key) { build(key) }

    private fun build(key: String): List<XmbNode> = when {
        key == Categories.rootKey(Categories.GAMES) -> gamesRoot()
        key == Categories.rootKey(Categories.APPS) -> appsRoot()
        key.startsWith(APP_FOLDER_PREFIX) -> appFolder(key.removePrefix(APP_FOLDER_PREFIX))
        key == Categories.rootKey(Categories.SETTINGS) -> settingsRoot()
        key == Categories.rootKey(Categories.PHOTO) -> media.photoRoot()
        key == Categories.rootKey(Categories.VIDEO) -> media.videoRoot()
        key == Categories.rootKey(Categories.MUSIC) -> media.musicRoot(dev.xross.launcher.media.NowPlaying())
        media.handles(key) -> media.list(key)
        key == RECENT -> s.games.filter { it.lastPlayed != null }
            .sortedByDescending { it.lastPlayed }.take(30).map { gameNode(it, showPlatform = true) }
        key == FAVORITES -> s.games.filter { it.favorite }.map { gameNode(it, showPlatform = true) }
        key == ANDROID_GAMES -> androidGames.map { appNode(it, inGames = true) }
        key == ALL_GAMES -> platformFolders()
        key.startsWith(PLATFORM_PREFIX) ->
            gamesByPlatform[key.removePrefix(PLATFORM_PREFIX)].orEmpty().map { gameNode(it, showPlatform = false) }
        key == LIBRARY -> librarySettings()
        key == DATS -> datSettings()
        key == METADATA -> metadataSettings(dev.xross.launcher.scrape.ScrapeProgress())
        key == CONTROLS -> controlSettings()
        key == EMULATORS -> emulatorSettings()
        key == DISPLAY -> displaySettings()
        key == DISPLAY_COLORS -> colorSettings()
        key == DISPLAY_BACKGROUND -> backgroundSettings()
        key == DISPLAY_EFFECTS -> effectSettings()
        key == DISPLAY_INTRO -> introSettings()
        key == DISPLAY_MENU -> menuLookSettings()
        key == SOUND -> soundSettings()
        key == MEDIA_SETTINGS -> mediaSettings()
        key == SYSTEM -> systemSettings()
        key == ACCOUNTS -> accountSettings()
        key == APPS_SETTINGS -> appSettings()
        key == HIDDEN_APPS -> hiddenAppRows()
        key == PHOTO_FOLDERS -> mediaFolderRows(MediaFolderKind.PHOTO)
        key == VIDEO_FOLDERS -> mediaFolderRows(MediaFolderKind.VIDEO)
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
        val platforms = platformFolders()
        when {
            platforms.isEmpty() -> Unit
            s.settings.allGamesFolder -> add(folder(ALL_GAMES, "All Games", GlyphKey.GAMES, allGamesSummary(platforms.size)))
            else -> addAll(platforms)
        }
        if (androidGames.isNotEmpty()) {
            add(folder(ANDROID_GAMES, "Android Games", GlyphKey.HANDHELD, countLabel(androidGames.size)))
        }
        if (s.games.isNotEmpty() || s.accounts.retroAchievementsLinked) {
            add(
                XmbNode(
                    id = RA_HUB,
                    title = "RetroAchievements",
                    subtitle = retroAchievementsSummary(),
                    icon = NodeIcon.Glyph(GlyphKey.TROPHY),
                    action = NodeAction.Run(Command.OpenRaHub),
                ),
            )
        }
        if (s.games.isEmpty()) {
            s.portable.restorable?.let { backup ->
                add(
                    XmbNode(
                        id = "games:restore",
                        title = "Restore library backup",
                        subtitle = "${countOf(backup.games, "game")} found in the Xross folder",
                        icon = NodeIcon.Glyph(GlyphKey.REFRESH),
                        action = NodeAction.Run(Command.ConfirmRestore),
                    ),
                )
            }
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

    private fun retroAchievementsSummary(): String {
        val a = s.accounts
        if (!a.retroAchievementsLinked) return "Link an account to track achievements"
        val number = java.text.NumberFormat.getIntegerInstance()
        return listOfNotNull(
            a.retroAchievementsUser.ifEmpty { null },
            a.retroAchievementsPoints?.let { "${number.format(it)} points" },
            a.retroAchievementsRank?.let { "rank #${number.format(it)}" },
        ).joinToString(" · ").ifEmpty { "Profile, mastered games and recent unlocks" }
    }

    /**
     * One folder per platform that has games, in [PlatformRegistry] order. Shown inside All Games,
     * or at the top level of Games when [XrossSettings.allGamesFolder] is off. The All Games list
     * is built either way, so a navigation stack saved before the setting changed still resolves.
     */
    private fun platformFolders(): List<XmbNode> = PlatformRegistry.all.mapNotNull { platform ->
        val count = gamesByPlatform[platform.id]?.size ?: 0
        if (count == 0) null else folder(PLATFORM_PREFIX + platform.id, platform.name, GlyphKey.PLATFORM, countLabel(count))
    }

    private fun allGamesSummary(platforms: Int): String {
        val systems = if (platforms == 1) "1 system" else "$platforms systems"
        return "$systems · ${countLabel(s.games.size)}"
    }

    private fun gameNode(game: GameEntity, showPlatform: Boolean): XmbNode {
        val platform = PlatformRegistry.byId(game.platformId)
        val parts = buildList {
            if (isOffline(game.path)) add("Not connected")
            if (game.favorite) add("★")
            if (showPlatform && platform != null) add(platform.shortName)
            s.metadata[game.id]?.region?.let { add(it) }
            // Skip the extension when it only repeats the platform ("GBA · GBA").
            val ext = game.fileName.substringAfterLast('.', "").uppercase(Locale.ROOT)
            if (!(showPlatform && ext == platform?.shortName?.uppercase(Locale.ROOT))) add(ext)
            // Tracked playtime where the file size used to be (the size is in Information).
            add(playtimeSubtitle(game.playTimeMs))
        }
        return XmbNode(
            id = "game:${game.id}",
            title = game.title,
            subtitle = parts.filter { it.isNotEmpty() }.joinToString(" · "),
            icon = gameIcon(game, platform),
            action = NodeAction.LaunchGame(game.id),
            menu = gameMenu(game, platform),
        )
    }

    /**
     * The rectangular XMB tile for a game. Only games a service has identified can have art; the
     * others say so, and the tile skips the cache lookup and shows the title on its plate.
     */
    private fun gameIcon(game: GameEntity, platform: Platform?): NodeIcon {
        val glyph = if (platform?.isDiscBased == true || game.isDiscImage()) GlyphKey.GAME_DISC else GlyphKey.GAME_CART
        val meta = s.metadata[game.id]
        val scraped = meta?.scrapeStatus == dev.xross.launcher.scrape.ScrapeStatus.OK.name
        return NodeIcon.Tile(game.uuid, game.title, glyph, if (scraped) meta?.scrapedAt ?: 0L else 0L, scraped)
    }

    /** Options menu for a game: play, favorite, per-game emulator, and its play history. */
    private fun gameMenu(game: GameEntity, platform: Platform?): List<MenuOption> = buildList {
        add(MenuOption("Play", command = Command.LaunchGame(game.id)))
        add(MenuOption("Details", detail = "Box art, description and release info", command = Command.ShowDetails(game.id)))
        if (dev.xross.launcher.profile.RaConsoles.of(game.platformId) != null) {
            add(
                MenuOption(
                    "Achievements",
                    detail = if (s.accounts.retroAchievementsLinked) "Badges, points and progress" else "Link RetroAchievements in Settings ▸ Accounts",
                    command = Command.ShowAchievements(game.id),
                ),
            )
        }
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
        add(MenuOption("Metadata", detail = metadataDetail(game), submenu = metadataMenu(game)))
        add(MenuOption("Information", detail = "File, size, fingerprints and playtime", command = Command.ShowGameInfo(game.id)))
    }

    private fun metadataDetail(game: GameEntity): String {
        val meta = s.metadata[game.id]
        return when {
            meta?.locked == true -> "Set manually"
            meta?.matchKind != null && meta.scrapeStatus == "OK" -> "Verified · scraped"
            meta?.matchKind != null -> "Verified by DAT"
            meta?.scrapeStatus == "OK" -> "Scraped"
            meta?.scrapeStatus == "NOT_FOUND" -> "Not found online"
            else -> "Not scraped yet"
        }
    }

    private fun metadataMenu(game: GameEntity): List<MenuOption> = buildList {
        val meta = s.metadata[game.id]
        add(MenuOption("Edit title…", detail = meta?.titleOverride ?: "Use your own name for this game", command = Command.EditTitle(game.id)))
        add(MenuOption("Find match…", detail = "Search by name when the automatic match is wrong", command = Command.FindMatch(game.id)))
        add(MenuOption("Scrape again", detail = "Refresh text and art for this game", command = Command.Rescrape(game.id)))
        if (meta?.locked == true || meta?.titleOverride != null) {
            add(MenuOption("Use automatic match", detail = "Forget the manual title and match", command = Command.ResetMetadata(game.id)))
        }
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

    private fun appNode(app: AppEntry, inGames: Boolean = false) = XmbNode(
        id = "app:${app.packageName}/${app.activityName}",
        title = app.label,
        subtitle = app.packageName,
        icon = NodeIcon.App(app.packageName),
        action = NodeAction.LaunchApp(app.packageName, app.activityName),
        menu = appMenu(app, inGames),
    )

    // region Apps

    /**
     * Apps: a "Group emulators" suggestion while installed emulators sit loose, then folders
     * (presets first), then the apps that aren't in one.
     */
    private fun appsRoot(): List<XmbNode> = buildList {
        val apps = visibleApps.filterNot { it.isGame }
        val folders = s.settings.appFolders
        val loose = AppFolders.ungroupedEmulators(s.apps, s.settings)
        if (loose.isNotEmpty()) {
            add(
                setting(
                    "apps:group-emulators", "Group emulators",
                    "Move ${countOf(loose.size, "emulator")} into an Emulators folder",
                    GlyphKey.EMULATORS, Command.GroupEmulators,
                ),
            )
        }
        val inFolders = apps.filter { it.packageName in folders }
        for (name in AppFolders.folderNames(inFolders.map { folders.getValue(it.packageName) })) {
            val count = inFolders.count { folders[it.packageName] == name }
            add(folder(APP_FOLDER_PREFIX + name, name, if (name == AppFolders.EMULATORS) GlyphKey.EMULATORS else GlyphKey.FOLDER, countOf(count, "app")))
        }
        addAll(apps.filter { it.packageName !in folders }.map { appNode(it) })
    }

    private fun appFolder(name: String): List<XmbNode> =
        visibleApps.filter { !it.isGame && s.settings.appFolders[it.packageName] == name }.map { appNode(it) }

    /** Options for an app: open, move to a folder (Apps only), hide. */
    private fun appMenu(app: AppEntry, inGames: Boolean): List<MenuOption> = buildList {
        add(MenuOption("Open", command = Command.LaunchApp(app.packageName, app.activityName)))
        if (!inGames) {
            val current = s.settings.appFolders[app.packageName]
            val suggested = AppFolders.suggestedFolder(app.packageName)
            val names = AppFolders.offeredNames(s.settings).let { all -> listOfNotNull(suggested) + all.filter { it != suggested } }
            add(
                MenuOption(
                    "Move to folder",
                    detail = current ?: suggested?.let { "Suggested: $it" } ?: "Not in a folder",
                    submenu = buildList {
                        for (name in names) {
                            add(
                                MenuOption(
                                    name,
                                    detail = if (name == suggested && name != current) "Suggested" else null,
                                    command = Command.MoveAppToFolder(app.packageName, name),
                                    selected = name == current,
                                ),
                            )
                        }
                        add(MenuOption("New folder…", detail = "Name it with the keyboard", command = Command.NewAppFolder(app.packageName)))
                        if (current != null) add(MenuOption("Remove from folder", command = Command.MoveAppToFolder(app.packageName, null)))
                    },
                ),
            )
        }
        add(MenuOption("Hide", detail = "Settings ▸ Apps ▸ Hidden apps shows it again", command = Command.HideApp(app.packageName)))
    }

    /** Settings ▸ Apps. */
    private fun appSettings(): List<XmbNode> = buildList {
        val st = s.settings
        val hidden = hiddenApps()
        add(folder(HIDDEN_APPS, "Hidden apps", GlyphKey.APPS, if (hidden.isEmpty()) "None" else countOf(hidden.size, "app")))
        add(
            setting(
                "apps:system", "Hide system apps",
                if (st.hideSystemApps) "On — preinstalled apps are hidden" else "Off",
                GlyphKey.APPS, Command.ToggleHideSystemApps,
            ),
        )
        val loose = AppFolders.ungroupedEmulators(s.apps, st)
        if (loose.isNotEmpty()) {
            add(setting("apps:group", "Group emulators", "Move ${countOf(loose.size, "emulator")} into Emulators", GlyphKey.EMULATORS, Command.GroupEmulators))
        }
        if (st.appFolders.isNotEmpty()) {
            val folders = AppFolders.folderNames(st.appFolders.values)
            add(setting("apps:clear-folders", "Remove all app folders", folders.joinToString(", "), GlyphKey.REFRESH, Command.ClearAppFolders))
        }
    }

    /** Apps hidden one by one, plus (when on) system apps; packages no longer installed included. */
    private fun hiddenApps(): List<Pair<String, AppEntry?>> {
        val byPackage = s.apps.groupBy { it.packageName }
        val explicit = s.settings.hiddenApps.map { it to byPackage[it]?.firstOrNull() }
        return explicit.sortedBy { (pkg, app) -> (app?.label ?: pkg).lowercase(Locale.ROOT) }
    }

    private fun hiddenAppRows(): List<XmbNode> = buildList {
        val hidden = hiddenApps()
        if (hidden.size > 1) add(setting("hidden:all", "Show all hidden apps", countOf(hidden.size, "app"), GlyphKey.REFRESH, Command.UnhideAllApps))
        for ((pkg, app) in hidden) {
            add(
                XmbNode(
                    id = "hidden:$pkg",
                    title = app?.label ?: pkg,
                    subtitle = if (app == null) "Not installed · press to forget" else "Hidden · press to show again",
                    icon = if (app != null) NodeIcon.App(pkg) else NodeIcon.Glyph(GlyphKey.APPS),
                    action = NodeAction.Run(Command.UnhideApp(pkg)),
                ),
            )
        }
        if (s.settings.hideSystemApps) {
            val system = s.apps.count { it.isSystem && it.packageName !in s.settings.hiddenApps }
            add(XmbNode("hidden:system", "System apps", "${countOf(system, "app")} hidden by Hide system apps", NodeIcon.Glyph(GlyphKey.INFO)))
        }
        if (isEmpty()) add(XmbNode("hidden:none", "No hidden apps", "Options ▸ Hide on any app hides it", NodeIcon.Glyph(GlyphKey.INFO)))
    }

    // endregion

    private fun settingsRoot() = listOf(
        folder(LIBRARY, "Library", GlyphKey.STORAGE, "ROM folders and scanning"),
        folder(CONTROLS, "Controls", GlyphKey.CONTROLS, "Buttons, layout and stick"),
        folder(EMULATORS, "Emulators", GlyphKey.EMULATORS, "${s.installedEmulators.size} detected"),
        folder(DISPLAY, "Theme", GlyphKey.DISPLAY, "${s.themes.activeName} pack · ${themeColorLabel().substringBefore(" ·")} · wave background"),
        folder(SOUND, "Sound", GlyphKey.SOUND, if (s.settings.soundEffects) "Effects on" else "Effects off"),
        folder(MEDIA_SETTINGS, "Media", GlyphKey.MUSIC, "Slideshow, music and video playback"),
        folder(APPS_SETTINGS, "Apps", GlyphKey.APPS, appsSummary()),
        folder(ACCOUNTS, "Accounts", GlyphKey.USERS, accountsSummary()),
        folder(SYSTEM, "System", GlyphKey.SYSTEM, "Version ${s.versionName}"),
    )

    private fun accountsSummary(): String {
        val a = s.accounts
        return if (a.retroAchievementsLinked) "RetroAchievements · ${a.retroAchievementsUser}" else "RetroAchievements profile"
    }

    /** Settings ▸ Accounts. */
    private fun accountSettings(): List<XmbNode> = buildList {
        val a = s.accounts
        val number = java.text.NumberFormat.getIntegerInstance()
        add(
            setting(
                "acct:ra", "RetroAchievements account…",
                if (a.retroAchievementsLinked) {
                    listOfNotNull(
                        "Linked as ${a.retroAchievementsUser}",
                        a.retroAchievementsPoints?.let { "${number.format(it)} points" },
                        a.retroAchievementsRank?.let { "rank #${number.format(it)}" },
                    ).joinToString(" · ")
                } else {
                    "Link with your user name and Web API key (retroachievements.org ▸ Settings ▸ Keys)"
                },
                GlyphKey.TROPHY, Command.LinkRetroAchievements,
            ),
        )
        if (a.retroAchievementsLinked) {
            add(setting("acct:card", "User Card", "Profile picture, rank, points and recent unlocks", GlyphKey.USERS, Command.OpenProfileCard))
            add(setting("acct:refresh", "Refresh profile", "Fetch points, rank and avatar again", GlyphKey.REFRESH, Command.RefreshRetroAchievements))
        }
    }

    private fun appsSummary(): String {
        val hidden = s.settings.hiddenApps.size
        val folders = s.settings.appFolders.values.toSet().size
        return buildList {
            add(if (hidden == 0) "No hidden apps" else "${countOf(hidden, "app")} hidden")
            if (folders > 0) add(countOf(folders, "folder"))
            if (s.settings.hideSystemApps) add("system apps hidden")
        }.joinToString(" · ")
    }

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
        add(
            XmbNode(
                id = "lib:base",
                title = "Base ROMs folder…",
                subtitle = s.baseRomsFolder.ifEmpty { "Xross/roms" } + if (s.settings.baseRomsFolder.isEmpty()) " (default)" else "",
                icon = NodeIcon.Glyph(GlyphKey.FOLDER),
                action = NodeAction.Run(Command.PickBaseRomsFolder),
                menu = buildList {
                    add(MenuOption("Choose another folder…", command = Command.PickBaseRomsFolder))
                    if (s.settings.baseRomsFolder.isNotEmpty()) {
                        add(MenuOption("Use the default", detail = "roms/ in the Xross folder", command = Command.ResetBaseRomsFolder))
                    }
                    add(MenuOption("Create system folders", detail = "One folder per system", command = Command.CreateSystemFolders))
                },
            ),
        )
        add(
            setting(
                "lib:sysfolders", "Create system folders",
                "Makes ${PlatformRegistry.builtIn.size} folders (snes/, psx/, …) in the base ROMs folder",
                GlyphKey.ADD, Command.CreateSystemFolders,
            ),
        )
        add(setting("lib:rescan", "Rescan library", s.scanStatus ?: "${s.games.size} games", GlyphKey.REFRESH, Command.Rescan))
        add(setting("lib:fingerprints", "Game fingerprints", fingerprintSummary(status), GlyphKey.STORAGE, Command.RecheckFingerprints))
        add(folder(DATS, "DAT files", GlyphKey.INFO, datSummary()))
        add(folder(METADATA, "Metadata & artwork", GlyphKey.DOWNLOAD, scrapeSummary()))
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
                    subtitle = (if (folder.base) "Base ROMs folder" else if (folder.autoDetected) "Auto-detected" else "Added by you") +
                        if (folder.connected) "" else " · not connected",
                    icon = NodeIcon.Glyph(GlyphKey.FOLDER),
                    menu = if (folder.autoDetected || folder.base) {
                        emptyList()
                    } else {
                        listOf(MenuOption("Remove from library", command = Command.RemoveFolder(folder.path)))
                    },
                ),
            )
        }
    }

    private fun verifiedCount() = s.metadata.values.count { it.matchKind != null }

    private fun datSummary(): String = when {
        s.dats.isEmpty() -> "Verify titles offline with No-Intro and Redump DATs"
        else -> "${countOf(s.dats.size, "DAT")} · ${verifiedCount()} of ${s.games.size} games verified"
    }

    /** Settings ▸ Library ▸ DAT files: import, folder, re-check, and one row per imported DAT. */
    private fun datSettings(): List<XmbNode> = buildList {
        add(
            setting(
                "dat:import",
                "Import DATs",
                "From ${s.datFolder} — .dat, .xml or .zip",
                GlyphKey.REFRESH,
                Command.ImportDats,
            ),
        )
        add(setting("dat:folder", "DAT folder…", s.datFolder, GlyphKey.FOLDER, Command.PickDatFolder))
        if (s.dats.isNotEmpty()) {
            add(
                setting(
                    "dat:match",
                    "Verify library",
                    "${verifiedCount()} of ${s.games.size} games match a DAT entry",
                    GlyphKey.STORAGE,
                    Command.MatchDats,
                ),
            )
        }
        for (dat in s.dats) {
            val parts = buildList {
                dat.source?.let { add(it) }
                dat.version?.let { add(it) }
                add(countOf(dat.gameCount, "game"))
                add("${dat.matched} in your library")
            }
            add(
                XmbNode(
                    id = "dat:file:${dat.id}",
                    title = dat.name,
                    subtitle = parts.joinToString(" · "),
                    icon = NodeIcon.Glyph(GlyphKey.INFO),
                    menu = listOf(
                        MenuOption("File", detail = dat.path),
                        MenuOption("Remove DAT", detail = "Forget its entries; the file stays", command = Command.RemoveDat(dat.id)),
                    ),
                ),
            )
        }
    }

    private fun scrapeSummary(): String {
        val scraped = s.metadata.values.count { it.scrapeStatus == "OK" }
        return if (scraped == 0) "Descriptions, box art and videos" else "$scraped of ${s.games.size} games scraped"
    }

    /** Settings ▸ Library ▸ Metadata & artwork, with the live scrape progress. Not cached. */
    fun metadataSettings(progress: dev.xross.launcher.scrape.ScrapeProgress): List<XmbNode> = buildList {
        val st = s.settings
        val setup = s.scraperSetup
        val now = when {
            progress.running && progress.current != null -> "Scraping ${progress.done + 1} of ${progress.total} · ${progress.current}"
            progress.running -> "Starting…"
            progress.message != null -> progress.message
            else -> scrapeSummary()
        }
        add(setting("meta:now", "Scrape library now", now, GlyphKey.DOWNLOAD, Command.ScrapeNow))
        add(
            setting(
                "meta:auto", "Scrape automatically",
                if (st.scrapeAutomatically) "On — new games are scraped while Xross is open" else "Off — only when you ask",
                GlyphKey.REFRESH, Command.ToggleScrapeAutomatically,
            ),
        )
        add(setting("meta:wifi", "Wi-Fi only", onOff(st.scrapeWifiOnly), GlyphKey.NETWORK, Command.ToggleScrapeWifiOnly))
        add(setting("meta:videos", "Video snaps", if (st.scrapeVideos) "On — up to 40 MB per game" else "Off", GlyphKey.VIDEO, Command.ToggleScrapeVideos))
        add(setting("meta:region", "Region", REGION_LABELS[st.scrapeRegion] ?: st.scrapeRegion, GlyphKey.NETWORK, Command.CycleScrapeRegion))
        add(setting("meta:language", "Language", LANGUAGE_LABELS[st.scrapeLanguage] ?: st.scrapeLanguage, GlyphKey.NETWORK, Command.CycleScrapeLanguage))
        add(
            setting(
                "meta:hasheous", "Hasheous",
                if (st.hasheousEnabled) "On — exact identification by fingerprint, no account" else "Off",
                GlyphKey.INFO, Command.ToggleHasheous,
            ),
        )
        add(
            setting(
                "meta:ss", "ScreenScraper account…",
                when {
                    !setup.screenScraperAvailable -> "Unavailable in this build (no developer credentials)"
                    setup.screenScraperUser.isNotBlank() -> "Signed in as ${setup.screenScraperUser}"
                    else -> "Optional — your own account raises the daily quota"
                },
                GlyphKey.INFO, Command.EditScreenScraperAccount,
            ),
        )
        if (setup.screenScraperAvailable) {
            add(
                setting(
                    "meta:sscheck", "Check ScreenScraper account",
                    setup.screenScraperQuota ?: "Sign in and show today's quota",
                    GlyphKey.NETWORK, Command.CheckScreenScraperAccount,
                ),
            )
        }
        add(
            setting(
                "meta:sgdb", "SteamGridDB API key…",
                if (setup.steamGridDbKeySet) "Set — hero banners, capsules and logos" else "Needed for hero banners — free key at steamgriddb.com",
                GlyphKey.INFO, Command.EditSteamGridDbKey,
            ),
        )
        add(setting("meta:clear", "Clear downloaded artwork", "Empties Xross/media (covers, heroes, wheels, snaps…); art downloads again on the next scrape", GlyphKey.STORAGE, Command.ClearArtwork))
    }

    private fun countOf(n: Int, noun: String) = if (n == 1) "1 $noun" else "${java.text.NumberFormat.getIntegerInstance().format(n)} ${noun}s"

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
            deadzoneLabel(s.settings.deadzone),
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

    private fun avatarSummary(): String {
        val a = s.settings.avatarStyle
        val color = dev.xross.launcher.theme.AvatarColorPreset.of(a.borderColorHex)?.label ?: a.borderColorHex
        return "$color ring · ${formatDecimal(a.borderWidthDp)} dp · glow " + if (a.glowEnabled) "on" else "off"
    }

    private fun displaySettings() = listOf(
        XmbNode(
            id = "disp:pack",
            title = "Theme pack",
            subtitle = themePackSummary(),
            icon = NodeIcon.Glyph(GlyphKey.DISPLAY),
            action = NodeAction.Run(Command.CycleThemePack),
            menu = buildList {
                for ((id, name) in s.themes.packs) {
                    add(MenuOption(name, command = Command.SetThemePack(id), selected = id == s.themes.activeId))
                }
                add(MenuOption("Look for new packs", detail = "Rescan Xross/themes", command = Command.ReloadThemePacks))
            },
        ),
    ) + themeVariantRow() + listOf(
        quickProfileRow(),
        folder(DISPLAY_COLORS, "Colors", GlyphKey.DISPLAY, profiled(ProfileField.THEME_COLOR, themeColorLabel())),
        folder(DISPLAY_BACKGROUND, "Background", GlyphKey.DISPLAY, backgroundSummary()),
        folder(DISPLAY_EFFECTS, "Effects", GlyphKey.DISPLAY, profiled(ProfileField.SURFACE, surfaceSummary())),
        folder(DISPLAY_INTRO, "Intro and transitions", GlyphKey.DISPLAY, introSummary()),
        folder(DISPLAY_MENU, "Menu and profile", GlyphKey.USERS, "Profile customization, button hints, platform folders"),
        resetThemeRow(),
        setting(
            "disp:import", "Import theme (.xtheme)…",
            "Pick a theme file; it is checked, then unpacked into Xross/themes",
            GlyphKey.DOWNLOAD, Command.ImportThemeArchive,
        ),
    )

    /** Settings ▸ Theme ▸ Colors: the base color and the optional color layers over it. */
    private fun colorSettings() = listOf(
        themeColorRow(),
        colorSwitchRow("disp:adaptive", "Adaptive colors", ColorSwitch.ADAPTIVE, "Colors follow the focused game's art"),
        colorSwitchRow("disp:circadian", "Circadian curve", ColorSwitch.CIRCADIAN, "Colors shift with the time of day"),
        colorSwitchRow("disp:battery", "Battery alert tint", ColorSwitch.BATTERY, "Amber to red below 20%, green while charging"),
    )

    /** Settings ▸ Theme ▸ Background: what is drawn behind the menu. */
    private fun backgroundSettings() = listOf(
        setting("disp:wave", "Wave background", onOff(s.settings.waveEnabled), GlyphKey.DISPLAY, Command.ToggleWave),
        setting(
            "disp:wavesettings", "Wave settings",
            if (activeProfile.stillMotion) "Still in ${activeProfile.label} · ${waveSummary()}" else waveSummary(),
            GlyphKey.DISPLAY, Command.OpenWaveSettings,
        ),
        wallpaperRow(),
        effectRow(dev.xross.launcher.theme.pack.EffectSlot.SHADER),
        effectRow(dev.xross.launcher.theme.pack.EffectSlot.BACKGROUND_FILTER),
    )

    /** Settings ▸ Theme ▸ Effects: surfaces and what is drawn over the menu. */
    private fun effectSettings() = listOf(
        setting("disp:surface", "Surface style", profiled(ProfileField.SURFACE, surfaceSummary()), GlyphKey.DISPLAY, Command.CycleSurfaceStyle),
    ) + dev.xross.launcher.theme.pack.EffectSlot.entries
        .filter { it !in BACKGROUND_SLOTS && it !in INTRO_SLOTS }
        .map(::effectRow)

    /** Settings ▸ Theme ▸ Intro and transitions (2b.5d): the cold-start intro and the launch curtain. */
    private fun introSettings() = INTRO_SLOTS.map(::effectRow)

    /** "Theme pack (Video) · Bloom". */
    private fun introSummary(): String =
        INTRO_SLOTS.joinToString(" · ") { slot ->
            val own = s.settings.effectOverrides[slot]
            if (own.isEmpty()) slot.packLabel(s.themes.packLook(s.settings.themeVariant), s.themes.packBootVideo) else slot.label(own)
        }

    /** Settings ▸ Theme ▸ Menu and profile: how the menu is laid out, not how it is colored. */
    private fun menuLookSettings() = listOf(
        setting("disp:avatar", "Profile customization", avatarSummary(), GlyphKey.USERS, Command.OpenAvatarStyle),
        setting(
            "disp:hints",
            "Button hints",
            if (s.settings.showButtonHints) "Shown in the corner of the home menu" else "Hidden",
            GlyphKey.DISPLAY,
            Command.ToggleButtonHints,
        ),
        setting(
            "disp:allgames",
            "Platform folders",
            if (s.settings.allGamesFolder) "Inside All Games" else "At the top of Games (classic)",
            GlyphKey.GAMES,
            Command.ToggleAllGamesFolder,
        ),
    )

    private val activeProfile by lazy { dev.xross.launcher.theme.QuickProfile.byName(s.settings.quickProfile) }

    /**
     * [own] (the user's value), or, when the quick profile takes [field] over, that it does and
     * what the user's value is underneath ([forced]: the value the profile gives, when it has one).
     */
    private fun profiled(field: ProfileField, own: String, forced: String? = null): String {
        if (!activeProfile.sets(field)) return own
        val by = if (forced != null) "$forced (set by ${activeProfile.label})" else "Set by ${activeProfile.label}"
        return "$by · yours: $own"
    }

    /** "Wave on · Theme pack (Wave)", "Wave off · a picture you chose". */
    private fun backgroundSummary(): String = buildString {
        append(if (s.settings.waveEnabled) "Wave on · " + effectShown(dev.xross.launcher.theme.pack.EffectSlot.SHADER) else "Wave off")
        when {
            s.settings.customWallpaper.isNotBlank() -> append(" · a picture you chose")
            s.themes.packWallpaper -> append(" · theme wallpaper")
        }
    }

    /** Theme color: Confirm cycles; Options list every color (and the pack's own, when it has one). */
    private fun themeColorRow(): XmbNode {
        val current = s.settings.theme
        val options = buildList {
            if (s.themes.packPalette) {
                add(MenuOption("Theme pack", detail = s.themes.activeName, command = Command.SetTheme(dev.xross.launcher.theme.PACK_THEME), selected = current == dev.xross.launcher.theme.PACK_THEME))
            }
            for (t in XrossTheme.entries) {
                add(MenuOption(t.label, detail = if (t.dynamic) "Changes every month" else null, command = Command.SetTheme(t.name), selected = current == t.name))
            }
        }
        return XmbNode(
            id = "disp:theme",
            title = "Theme color",
            subtitle = profiled(ProfileField.THEME_COLOR, themeColorLabel()),
            icon = NodeIcon.Glyph(GlyphKey.DISPLAY),
            action = NodeAction.Run(Command.CycleTheme),
            menu = options,
        )
    }

    /**
     * One of the effect rows (2b.4f): Background shader, Background filter, UI filter, Focus frame
     * style, Ambient particles, App icon mask. Confirm cycles "Theme pack (…)" then each choice;
     * Options lists them all. Saved as `display.effects` in settings.json.
     */
    private fun effectRow(slot: dev.xross.launcher.theme.pack.EffectSlot): XmbNode {
        val own = s.settings.effectOverrides[slot]
        val look = s.themes.packLook(s.settings.themeVariant)
        val shown = effectShown(slot)
        val note = when (slot) {
            dev.xross.launcher.theme.pack.EffectSlot.SHADER -> if (s.settings.waveEnabled) "Behind the menu" else "Wave background is off"
            dev.xross.launcher.theme.pack.EffectSlot.BACKGROUND_FILTER -> "Over the background only"
            dev.xross.launcher.theme.pack.EffectSlot.UI_FILTER ->
                if (dev.xross.launcher.ui.UiFilterSupport.supported()) "Over the whole menu" else "Needs Android 13"
            dev.xross.launcher.theme.pack.EffectSlot.FOCUS_FRAME -> "Around the focused item"
            dev.xross.launcher.theme.pack.EffectSlot.PARTICLES -> "Over the art, resting when idle"
            dev.xross.launcher.theme.pack.EffectSlot.ICON_MASK -> "Installed apps' icons"
            dev.xross.launcher.theme.pack.EffectSlot.BOOT_INTRO -> when {
                activeProfile.stillMotion -> "Off in ${activeProfile.label}"
                !s.settings.bootSound || s.settings.cueVolume <= 0f -> "Off while the startup sound is off"
                own == dev.xross.launcher.theme.pack.BootIntro.VIDEO.name && !s.themes.packBootVideo -> "This pack has no boot video"
                else -> "On a cold start; any button skips it"
            }
            dev.xross.launcher.theme.pack.EffectSlot.LAUNCH_CURTAIN ->
                if (activeProfile.stillMotion) "Plain black in ${activeProfile.label}" else "Before a game starts"
        }
        val id = "disp:fx:" + slot.name.lowercase(Locale.ROOT)
        return XmbNode(
            id = id,
            title = slot.title,
            subtitle = "$shown · $note",
            icon = NodeIcon.Glyph(GlyphKey.DISPLAY),
            action = NodeAction.Run(Command.CycleEffect(slot)),
            menu = listOf(
                MenuOption(slot.packLabel(look, s.themes.packBootVideo), command = Command.SetEffect(slot, ""), selected = own.isEmpty()),
            ) + slot.choices.map { (name, label) ->
                MenuOption(label, command = Command.SetEffect(slot, name), selected = own == name)
            },
        )
    }

    /** "Theme pack (Wave)" when [slot] follows the pack, else the user's choice. */
    private fun effectShown(slot: dev.xross.launcher.theme.pack.EffectSlot): String {
        val own = s.settings.effectOverrides[slot]
        return if (own.isEmpty()) slot.packLabel(s.themes.packLook(s.settings.themeVariant), s.themes.packBootVideo) else slot.label(own)
    }

    /** Reset to theme defaults: what it would clear, or that nothing is overridden. */
    private fun resetThemeRow(): XmbNode {
        val st = s.settings
        val changed = buildList {
            val fx = dev.xross.launcher.theme.pack.EffectSlot.entries.count { st.effectOverrides[it].isNotEmpty() }
            if (fx > 0) add(countOf(fx, "effect"))
            if (st.surfaceStyle.isNotEmpty()) add("surface")
            val layers = listOf(st.adaptiveGamePalette, st.circadianCurve, st.batteryTint).count { it != null }
            if (layers > 0) add(countOf(layers, "color layer"))
            if (st.customWallpaper.isNotBlank()) add("wallpaper")
        }
        val summary = if (changed.isEmpty()) "Nothing overrides ${s.themes.activeName}" else "Clears " + changed.joinToString(", ")
        return setting("disp:reset", "Reset to theme defaults", summary, GlyphKey.DISPLAY, Command.ResetThemeDefaults)
    }

    /**
     * Xross wallpaper: the photo set from Photo ▸ Options, the theme's, or none. Confirm on a
     * custom one goes back to the theme's; otherwise it says where to set one.
     */
    private fun wallpaperRow(): XmbNode {
        val custom = s.settings.customWallpaper.isNotBlank()
        val summary = when {
            custom -> "A picture you chose · Confirm to use the theme's"
            s.themes.packWallpaper -> "From ${s.themes.activeName} · Confirm to pick your own"
            else -> "None (the background shader shows) · Confirm to pick one"
        }
        val action = NodeAction.Run(if (custom) Command.ResetXrossWallpaper else Command.PickXrossWallpaper)
        val theirs = if (s.themes.packWallpaper) "Reset to theme" else "Reset to theme (none)"
        return XmbNode(
            "disp:wallpaper", "Xross wallpaper", summary, NodeIcon.Glyph(GlyphKey.PHOTO), action,
            menu = listOf(
                MenuOption("Pick custom…", detail = "Any picture, through the file picker", command = Command.PickXrossWallpaper),
                MenuOption(theirs, command = Command.ResetXrossWallpaper, selected = !custom),
            ),
        )
    }

    /** Theme variant, only while the active pack has variants; its Options list them all. */
    private fun themeVariantRow(): List<XmbNode> {
        val t = s.themes
        if (t.variants.isEmpty()) return emptyList()
        val choices = listOf("" to "Standard") + t.variants
        // A saved id the pack doesn't have (renamed, or another pack's) means its base look.
        val active = s.settings.themeVariant.takeIf { v -> t.variants.any { it.first == v } }.orEmpty()
        val current = choices.first { it.first == active }.second
        return listOf(
            XmbNode(
                id = "disp:variant",
                title = "Theme variant",
                subtitle = "$current · ${countOf(choices.size, "look")} in ${t.activeName}",
                icon = NodeIcon.Glyph(GlyphKey.DISPLAY),
                action = NodeAction.Run(Command.CycleThemeVariant),
                menu = choices.map { (id, name) ->
                    MenuOption(name, command = Command.SetThemeVariant(id), selected = id == active)
                },
            ),
        )
    }

    /** Quick profile: a built-in look over the theme; its Options list every profile. */
    private fun quickProfileRow(): XmbNode {
        val active = dev.xross.launcher.theme.QuickProfile.byName(s.settings.quickProfile)
        val note = when (active) {
            dev.xross.launcher.theme.QuickProfile.NONE -> "The theme's own look"
            dev.xross.launcher.theme.QuickProfile.OLED_TRUE_BLACK -> "Pure black, glowing outlines"
            dev.xross.launcher.theme.QuickProfile.Y2K_CYBER_PASTEL -> "Lavender, lilac and strawberry, glossy bezels"
            dev.xross.launcher.theme.QuickProfile.MONOCHROME_EINK -> "Black and white, no motion"
        }
        // Say what the profile takes over, so the rows it overrides aren't a surprise.
        val takes = active.fields.joinToString(", ") { it.label }
        return XmbNode(
            id = "disp:profile",
            title = "Quick profile",
            subtitle = if (active == dev.xross.launcher.theme.QuickProfile.NONE) "None · $note" else "${active.label} · $note · sets $takes",
            icon = NodeIcon.Glyph(GlyphKey.DISPLAY),
            action = NodeAction.Run(Command.CycleQuickProfile),
            menu = dev.xross.launcher.theme.QuickProfile.entries.map { p ->
                MenuOption(p.label, command = Command.SetQuickProfile(p.name), selected = p == active)
            },
        )
    }

    /**
     * One of the optional color layers: on or off, and whether that is the user's choice or the
     * theme pack's. Its Options set it either way or hand it back to the pack.
     */
    private fun colorSwitchRow(id: String, title: String, which: ColorSwitch, note: String): XmbNode {
        val (own, pack) = colorSwitch(which)
        val on = own ?: pack
        val field = when (which) {
            ColorSwitch.ADAPTIVE -> ProfileField.ADAPTIVE_COLORS
            ColorSwitch.CIRCADIAN -> ProfileField.CIRCADIAN
            ColorSwitch.BATTERY -> ProfileField.BATTERY_TINT
        }
        val ownText = if (own == null) "${onOff(on)} (theme pack) · $note" else "${onOff(on)} · $note"
        // Every profile that takes a color layer over turns it off.
        val subtitle = profiled(field, ownText, forced = "Off")
        return XmbNode(
            id = id,
            title = title,
            subtitle = subtitle,
            icon = NodeIcon.Glyph(GlyphKey.DISPLAY),
            action = NodeAction.Run(Command.ToggleColorSwitch(which)),
            menu = listOf(
                MenuOption("On", command = Command.SetColorSwitch(which, true), selected = own == true),
                MenuOption("Off", command = Command.SetColorSwitch(which, false), selected = own == false),
                MenuOption("Theme pack default", detail = onOff(pack), command = Command.SetColorSwitch(which, null), selected = own == null),
            ),
        )
    }

    /** (the user's choice, the pack's value or the default) for [which]. */
    private fun colorSwitch(which: ColorSwitch): Pair<Boolean?, Boolean> {
        val f = s.themes.packFlags
        return when (which) {
            ColorSwitch.ADAPTIVE -> s.settings.adaptiveGamePalette to (f.adaptiveGamePalette ?: true)
            ColorSwitch.CIRCADIAN -> s.settings.circadianCurve to (f.circadianCurve ?: false)
            ColorSwitch.BATTERY -> s.settings.batteryTint to (f.batteryTint ?: false)
        }
    }

    /** "Theme pack (Flat glass)", or the chosen surface's label. */
    private fun surfaceSummary(): String {
        val t = s.themes
        fun label(id: String) = t.surfaces.firstOrNull { it.first == id }?.second ?: id
        val own = s.settings.surfaceStyle
        return if (own.isEmpty() || t.surfaces.none { it.first == own }) "Theme pack (${label(t.packSurface)})" else label(own)
    }

    private fun soundSettings() = listOf(
        setting("snd:fx", "Sound effects", onOff(s.settings.soundEffects), GlyphKey.SOUND, Command.ToggleSoundEffects),
        volumeRow("snd:vol", VolumeChannel.EFFECTS),
        volumeRow("snd:cues", VolumeChannel.CUES),
        volumeRow("snd:bgm", VolumeChannel.BGM, note = if (s.themes.packMusic) null else "No theme music in this pack"),
        volumeRow("snd:music", VolumeChannel.MUSIC, glyph = GlyphKey.MUSIC),
        volumeRow("snd:snaps", VolumeChannel.SNAPS, glyph = GlyphKey.VIDEO),
        musicUnderSnapsRow(),
        setting("snd:boot", "Startup sound", onOff(s.settings.bootSound), GlyphKey.SOUND, Command.ToggleBootSound),
        setting("snd:gameboot", "Game start sound", onOff(s.settings.gameBootSound), GlyphKey.SOUND, Command.ToggleGameBootSound),
    )

    /** A volume (2b.5b): Confirm steps it 10% (wrapping to 0); Options offer Mute, 50% and 100%. */
    private fun volumeRow(id: String, channel: VolumeChannel, note: String? = null, glyph: GlyphKey = GlyphKey.SOUND): XmbNode {
        val v = channel.of(s.settings)
        val value = if (v <= 0f) "Muted" else VolumeChannel.percent(v)
        return XmbNode(
            id = id,
            title = channel.title,
            subtitle = if (note == null) value else "$value · $note",
            icon = NodeIcon.Glyph(glyph),
            action = NodeAction.Run(Command.CycleVolume(channel)),
            menu = listOf("Mute" to 0f, "50%" to 0.5f, "100%" to 1f).map { (label, level) ->
                MenuOption(label, command = Command.SetVolume(channel, level), selected = kotlin.math.abs(v - level) < 0.001f)
            },
        )
    }

    private fun musicUnderSnapsRow(): XmbNode {
        val mode = s.settings.musicUnderSnaps
        val note = when (mode) {
            MusicUnderSnaps.PAUSE -> "Pause, then resume when the snap ends"
            MusicUnderSnaps.DUCK -> "Lower to ${(AudioMixer.SNAP_DUCK * 100).toInt()}% while the snap plays"
        }
        return XmbNode(
            id = "snd:undersnaps",
            title = "Music during video snaps",
            subtitle = "${mode.label} · $note",
            icon = NodeIcon.Glyph(GlyphKey.MUSIC),
            action = NodeAction.Run(Command.CycleMusicUnderSnaps),
            menu = MusicUnderSnaps.entries.map { m -> MenuOption(m.label, command = Command.SetMusicUnderSnaps(m), selected = m == mode) },
        )
    }

    private fun systemSettings() = buildList {
        add(XmbNode("sys:version", "Xross", "Version ${s.versionName}", NodeIcon.Glyph(GlyphKey.INFO)))
        add(setting("sys:home", "Set as home app", "Choose Xross as the default launcher", GlyphKey.HOME, Command.OpenHomeSettings))
        val p = s.portable
        add(
            setting(
                "sys:folder", "Xross folder",
                when {
                    p.root.isEmpty() -> "Checking…"
                    p.portable -> "${p.root} · press to use another drive"
                    else -> "In app storage until All files access is granted — press to grant"
                },
                GlyphKey.STORAGE, Command.CycleXrossVolume,
            ),
        )
        add(setting("sys:backup", "Back up library now", backupSummary(p), GlyphKey.DOWNLOAD, Command.BackupNow))
        p.backupOnDisk?.let { backup ->
            add(
                setting(
                    "sys:restore", "Restore library backup…",
                    "${countOf(backup.games, "game")} · saved ${formatTime(backup.modifiedAt)}",
                    GlyphKey.REFRESH, Command.ConfirmRestore,
                ),
            )
        }
        add(setting("sys:android", "Android settings", null, GlyphKey.SETTINGS, Command.OpenAndroidSettings))
    }

    private fun backupSummary(p: PortableSetup): String = when {
        p.backup.running -> "Backing up…"
        !p.portable -> "Needs All files access (copies the library to Xross/data/)"
        p.backup.message != null -> p.backup.message
        p.backup.lastBackupAt != null -> "Last backup ${formatTime(p.backup.lastBackupAt)} · also on leaving Xross"
        else -> "Copies the library to Xross/data/xross.db; also on leaving Xross"
    }

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
        folder(PHOTO_FOLDERS, "Photo folders", GlyphKey.PHOTO, mediaFoldersSummary(s.settings.photoFolders, "photos")),
        folder(VIDEO_FOLDERS, "Video folders", GlyphKey.VIDEO, mediaFoldersSummary(s.settings.videoFolders, "videos")),
    )

    private fun mediaFoldersSummary(folders: List<String>, noun: String) =
        if (folders.isEmpty()) "All $noun on this device" else "Only " + countOf(folders.size, "folder")

    /** Settings ▸ Media ▸ Photo / Video folders. */
    private fun mediaFolderRows(kind: MediaFolderKind): List<XmbNode> = buildList {
        val folders = if (kind == MediaFolderKind.PHOTO) s.settings.photoFolders else s.settings.videoFolders
        val glyph = if (kind == MediaFolderKind.PHOTO) GlyphKey.PHOTO else GlyphKey.VIDEO
        val noun = if (kind == MediaFolderKind.PHOTO) "photos" else "videos"
        add(
            setting(
                "medfolders:${kind.name}:add", "Add folder…",
                "${kind.label} then shows only the folders listed here",
                GlyphKey.ADD, Command.AddMediaFolder(kind),
            ),
        )
        if (folders.isEmpty()) {
            add(XmbNode("medfolders:${kind.name}:all", "All $noun", "Everything Android has indexed (MediaStore)", NodeIcon.Glyph(glyph)))
        } else {
            add(setting("medfolders:${kind.name}:clear", "Show all $noun again", "Forget the folders below", GlyphKey.REFRESH, Command.ClearMediaFolders(kind)))
            for (path in folders) {
                add(
                    XmbNode(
                        id = "medfolders:${kind.name}:$path",
                        title = path,
                        subtitle = (if (isOffline(path)) "Not connected · " else "") + "Indexed and unindexed files, including .nomedia folders",
                        icon = NodeIcon.Glyph(GlyphKey.FOLDER),
                        menu = listOf(MenuOption("Remove folder", command = Command.RemoveMediaFolder(kind, path))),
                    ),
                )
            }
        }
    }

    private val mountedRoots by lazy { s.mountedRoots.mapTo(HashSet()) { it.trimEnd('/') } }

    /** True when the file's volume (SD card, USB drive) is currently unplugged. */
    fun isOffline(path: String): Boolean =
        mountedRoots.isNotEmpty() && StorageVolumes.volumeRootOf(path)?.let { it !in mountedRoots } ?: false

    private fun onOff(on: Boolean) = if (on) "On" else "Off"

    private fun themeLabel(theme: XrossTheme) =
        if (theme.dynamic) "${theme.label} · changes every month" else theme.label

    private fun themeColorLabel(): String =
        if (s.settings.theme == dev.xross.launcher.theme.PACK_THEME && s.themes.packPalette) {
            "Theme pack · ${s.themes.activeName}"
        } else {
            themeLabel(XrossTheme.byName(s.settings.theme))
        }

    private fun themePackSummary(): String = buildString {
        append(s.themes.activeName)
        if (s.themes.activeAuthor.isNotBlank()) append(" by ${s.themes.activeAuthor}")
        val others = s.themes.packs.size - 1
        append(if (others == 0) " · add packs to Xross/themes" else " · ${s.themes.packs.size} packs")
        s.themes.problems.firstOrNull()?.let { append(" · $it") }
        if (s.themes.problems.isEmpty()) s.themes.rejected.firstOrNull()?.let { append(" · skipped $it") }
    }

    private fun waveSummary(): String {
        val st = s.settings
        val tint = dev.xross.launcher.theme.WaveTint.byName(st.waveTint).label
        return "${formatDecimal(st.waveSpeed)}× · ${st.waveLineCount} lines · $tint"
    }

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
        const val ALL_GAMES = "collection:all-games"
        /** Id of the Games ▸ RetroAchievements row. */
        const val RA_HUB = "games:retroachievements"
        const val PLATFORM_PREFIX = "platform:"
        const val LIBRARY = "settings:library"
        const val DATS = "settings:dats"
        const val METADATA = "settings:metadata"

        /** Row icons prefer the box, then a capsule. */
        val ICON_ART = listOf(dev.xross.launcher.scrape.ArtKind.BOX_3D, dev.xross.launcher.scrape.ArtKind.BOX_2D, dev.xross.launcher.scrape.ArtKind.GRID)

        val REGIONS = listOf("us", "eu", "jp", "wor")
        val REGION_LABELS = mapOf("us" to "USA", "eu" to "Europe", "jp" to "Japan", "wor" to "World")
        val LANGUAGES = listOf("en", "fr", "de", "es", "it", "pt", "ja")
        val LANGUAGE_LABELS = mapOf(
            "en" to "English", "fr" to "Français", "de" to "Deutsch", "es" to "Español",
            "it" to "Italiano", "pt" to "Português", "ja" to "日本語",
        )
        const val CONTROLS = "settings:controls"
        const val EMULATORS = "settings:emulators"
        const val DISPLAY = "settings:display"
        const val DISPLAY_COLORS = "settings:display:colors"
        const val DISPLAY_BACKGROUND = "settings:display:background"
        const val DISPLAY_EFFECTS = "settings:display:effects"
        const val DISPLAY_MENU = "settings:display:menu"
        const val DISPLAY_INTRO = "settings:display:intro"

        /** The effect rows in Settings ▸ Theme ▸ Background; the rest of the 2b.4f rows are in Effects. */
        private val BACKGROUND_SLOTS = setOf(dev.xross.launcher.theme.pack.EffectSlot.SHADER, dev.xross.launcher.theme.pack.EffectSlot.BACKGROUND_FILTER)

        /** The effect rows in Settings ▸ Theme ▸ Intro and transitions (2b.5d). */
        private val INTRO_SLOTS = listOf(dev.xross.launcher.theme.pack.EffectSlot.BOOT_INTRO, dev.xross.launcher.theme.pack.EffectSlot.LAUNCH_CURTAIN)

        /** Settings ▸ Theme and its folders, top level first. */
        val THEME_LISTS = listOf(DISPLAY, DISPLAY_COLORS, DISPLAY_BACKGROUND, DISPLAY_EFFECTS, DISPLAY_INTRO, DISPLAY_MENU)
        const val SOUND = "settings:sound"
        const val MEDIA_SETTINGS = "settings:media"
        const val APPS_SETTINGS = "settings:apps"
        const val HIDDEN_APPS = "settings:apps:hidden"
        const val PHOTO_FOLDERS = "settings:media:photo-folders"
        const val VIDEO_FOLDERS = "settings:media:video-folders"
        const val APP_FOLDER_PREFIX = "apps:folder:"
        private val DISC_EXTENSIONS = setOf("iso", "cue", "chd", "cso", "rvz", "gcz", "wbfs", "cdi", "gdi", "pbp", "m3u")
        const val SYSTEM = "settings:system"
        const val ACCOUNTS = "settings:accounts"

        /** "Off — hardware only" for 0 (the driver's own centre zone is all that applies), "35%" otherwise. */
        fun deadzoneLabel(deadzone: Float): String =
            if (deadzone <= 0f) "Off — hardware only" else "${Math.round(deadzone * 100)}%"

        private fun countLabel(n: Int) = if (n == 1) "1 title" else "$n titles"

        /** "45 min", "2 h 15 min", "120 h"; null under a minute (nothing worth showing). */
        /** "1.5", "2", "0.8": one decimal, none when whole. */
        fun formatDecimal(v: Float): String {
            val r = kotlin.math.round(v * 10f) / 10f
            return if (r % 1f == 0f) r.toInt().toString() else String.format(Locale.ROOT, "%.1f", r)
        }

        /**
         * The line under a game's title: "Never played", "Playtime: 12 mins" under an hour, then
         * "Playtime: 14h 25m". A session shorter than a minute counts as one minute.
         */
        fun playtimeSubtitle(ms: Long): String {
            if (ms <= 0) return "Never played"
            val minutes = (ms / 60_000).coerceAtLeast(1)
            return when {
                minutes < 60 -> "Playtime: $minutes " + if (minutes == 1L) "min" else "mins"
                else -> "Playtime: ${minutes / 60}h ${minutes % 60}m"
            }
        }

        fun formatPlaytime(ms: Long): String? {
            val minutes = ms / 60_000
            return when {
                minutes < 1 -> null
                minutes < 60 -> "$minutes min"
                minutes >= 100 * 60 || minutes % 60 == 0L -> "${minutes / 60} h"
                else -> "${minutes / 60} h ${minutes % 60} min"
            }
        }

        /** "12 Nov 2026, 14:05" in the device's locale. */
        fun formatTime(epochMs: Long): String =
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(epochMs))

        fun formatSize(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
            else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
        }
    }
}
