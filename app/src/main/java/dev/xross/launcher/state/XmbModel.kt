package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.media.ThumbKind

/** Icon identifiers; the UI layer maps these to vectors or loads app icons. */
sealed interface NodeIcon {
    data class Glyph(val key: GlyphKey) : NodeIcon
    data class App(val packageName: String) : NodeIcon
    /** Photo/video thumbnail or embedded album art, with a glyph to show until it loads. */
    data class Thumb(val kind: ThumbKind, val id: Long, val fallback: GlyphKey) : NodeIcon

    /**
     * Scraped game art from the asset cache (first kind present wins), crossfading in over
     * [fallback]. [version] changes when new art is stored, so the row reloads it.
     */
    data class Art(
        val uuid: String,
        val kinds: List<dev.xross.launcher.scrape.ArtKind>,
        val fallback: GlyphKey,
        val version: Long,
    ) : NodeIcon

    /**
     * A game's rectangular XMB tile: its clear logo, else wide art, else [label] on a frosted
     * plate with [fallback] behind it. [scraped] is false for games no service has identified
     * (nothing to load). [version] changes when new art is stored, so the tile reloads it.
     */
    data class Tile(
        val uuid: String,
        val label: String,
        val fallback: GlyphKey,
        val version: Long,
        val scraped: Boolean,
    ) : NodeIcon
}

enum class MediaKind { PHOTO, SONG, VIDEO }

/** Icon roles; each maps to an `xmb_*` vector drawable generated from assets/icons. */
enum class GlyphKey {
    SETTINGS, GAMES, APPS, PHOTO, MUSIC, VIDEO, ALBUM, ARTIST, SHUFFLE, SEARCH,
    PLATFORM, GAME_CART, GAME_DISC, FAVORITES, RECENT, HANDHELD, FOLDER, STORAGE,
    CONTROLS, EMULATORS, DISPLAY, SOUND, SYSTEM,
    ADD, REFRESH, HOME, INFO, DOWNLOAD, NETWORK, USERS, TROPHY,
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
    data object OpenWaveSettings : Command
    /** Settings ▸ Theme ▸ Profile customization: the avatar's ring and glow. */
    data object OpenAvatarStyle : Command
    /** Games ▸ RetroAchievements: the profile dashboard. */
    data object OpenRaHub : Command
    data object CycleTheme : Command
    data object CycleThemePack : Command
    data class SetThemePack(val id: String) : Command
    data object ReloadThemePacks : Command
    /** Settings ▸ Theme ▸ Surface style: the pack's own, then each surface it offers. */
    data object CycleSurfaceStyle : Command
    data object ToggleSoundEffects : Command
    data object CycleSoundVolume : Command
    data object ToggleBootSound : Command
    data object ToggleGameBootSound : Command
    data object ToggleLargeImageHashing : Command
    data object RecheckFingerprints : Command
    data object OpenHomeSettings : Command
    data object OpenAndroidSettings : Command
    data object OpenLibrarySettings : Command
    data class ToggleFavorite(val gameId: Long) : Command
    data class LaunchGame(val gameId: Long) : Command
    data object OpenSearch : Command
    /** [key] is an [dev.xross.launcher.storage.EmulatorOption.key]; null returns to the platform default. */
    data class SetGameEmulator(val gameId: Long, val key: String?) : Command
    data object OpenNowPlaying : Command
    data object ShuffleAllSongs : Command
    data class SetWallpaper(val photoId: Long) : Command
    data object CycleSlideshowInterval : Command
    data object ToggleResumeMusicAfterGames : Command
    data object ToggleVideoLoop : Command
    data object ToggleAllGamesFolder : Command
    /** Moves the Xross folder to the next mounted volume (primary storage, SD card, …). */
    data object CycleXrossVolume : Command
    data object BackupNow : Command
    /** Asks before replacing the library with the backup in the Xross folder. */
    data object ConfirmRestore : Command
    data object RestoreBackup : Command

    // Apps: hiding and folders
    data class LaunchApp(val packageName: String, val activityName: String) : Command
    data class HideApp(val packageName: String) : Command
    data class UnhideApp(val packageName: String) : Command
    data object UnhideAllApps : Command
    data object ToggleHideSystemApps : Command
    /** [folder] null takes the app out of its folder. */
    data class MoveAppToFolder(val packageName: String, val folder: String?) : Command
    /** Type a new folder name on the gamepad keyboard, then move the app into it. */
    data class NewAppFolder(val packageName: String) : Command
    /** Moves every installed emulator that isn't in a folder into Emulators. */
    data object GroupEmulators : Command
    data object ClearAppFolders : Command

    // Library: base ROMs folder
    data object PickBaseRomsFolder : Command
    data object ResetBaseRomsFolder : Command
    data object CreateSystemFolders : Command

    // Media: custom Photo / Video folders
    data class AddMediaFolder(val kind: MediaFolderKind) : Command
    data class RemoveMediaFolder(val kind: MediaFolderKind, val path: String) : Command
    data class ClearMediaFolders(val kind: MediaFolderKind) : Command
    data class ShowGameInfo(val gameId: Long) : Command
    data object ToggleButtonHints : Command
    data object CycleAxialDeadzone : Command
    /** Removes one controller model's saved mapping ([dev.xross.launcher.input.ControllerKey.id]). */
    data class ResetControllerProfile(val id: String) : Command
    data object ResetGlobalRemap : Command
    data object ImportDats : Command
    data object PickDatFolder : Command
    data object MatchDats : Command
    data class RemoveDat(val id: Long) : Command
    // Scraping
    data object ScrapeNow : Command
    data object ToggleScrapeAutomatically : Command
    data object ToggleScrapeWifiOnly : Command
    data object ToggleScrapeVideos : Command
    data object CycleScrapeRegion : Command
    data object CycleScrapeLanguage : Command
    data object ToggleHasheous : Command
    data object EditScreenScraperAccount : Command
    data object CheckScreenScraperAccount : Command
    data object EditSteamGridDbKey : Command
    data object ClearArtwork : Command
    // Accounts: RetroAchievements
    data object LinkRetroAchievements : Command
    data object RefreshRetroAchievements : Command
    data object OpenProfileCard : Command
    // Per game
    data class ShowDetails(val gameId: Long) : Command
    /** The game's RetroAchievements list (badges, points, progress). */
    data class ShowAchievements(val gameId: Long) : Command
    data class EditTitle(val gameId: Long) : Command
    data class FindMatch(val gameId: Long) : Command
    data class ChooseMatch(val gameId: Long, val provider: dev.xross.launcher.scrape.ProviderId, val id: String) : Command
    data class Rescrape(val gameId: Long) : Command
    data class ResetMetadata(val gameId: Long) : Command
}

sealed interface NodeAction {
    data class OpenList(val key: String) : NodeAction
    data class LaunchGame(val gameId: Long) : NodeAction
    data class LaunchApp(val packageName: String, val activityName: String) : NodeAction
    data class Run(val command: Command) : NodeAction
    /** Open item [index] of the photo/song/video collection behind list [collection]. */
    data class OpenMedia(val kind: MediaKind, val collection: String, val index: Int) : NodeAction
    data object None : NodeAction
}

/** Immutable so Compose can skip unchanged rows when the cursor moves. */
@Immutable
data class XmbNode(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: NodeIcon = NodeIcon.Glyph(GlyphKey.INFO),
    val action: NodeAction = NodeAction.None,
    /** Options (△ / Y) menu for this item; empty when it has none. */
    val menu: List<MenuOption> = emptyList(),
)

@Immutable
data class XmbCategory(
    val id: String,
    val title: String,
    val icon: GlyphKey,
)

object Categories {
    const val SETTINGS = "settings"
    const val PHOTO = "photo"
    const val MUSIC = "music"
    const val VIDEO = "video"
    const val GAMES = "games"
    const val APPS = "apps"

    val all = listOf(
        XmbCategory(SETTINGS, "Settings", GlyphKey.SETTINGS),
        XmbCategory(PHOTO, "Photo", GlyphKey.PHOTO),
        XmbCategory(MUSIC, "Music", GlyphKey.MUSIC),
        XmbCategory(VIDEO, "Video", GlyphKey.VIDEO),
        XmbCategory(GAMES, "Games", GlyphKey.GAMES),
        XmbCategory(APPS, "Apps", GlyphKey.APPS),
    )

    fun rootKey(categoryId: String) = "root:$categoryId"
}

/** Which media tab a custom folder list belongs to. */
enum class MediaFolderKind(val label: String) { PHOTO("Photo"), VIDEO("Video") }
