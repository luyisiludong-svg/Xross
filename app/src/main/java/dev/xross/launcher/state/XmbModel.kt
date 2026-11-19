package dev.xross.launcher.state

import dev.xross.launcher.model.GlyphKey
import androidx.compose.runtime.Immutable
import dev.xross.launcher.media.ThumbKind
import dev.xross.launcher.model.CategoryIds
import dev.xross.launcher.audio.VolumeChannel
import dev.xross.launcher.data.MusicUnderSnaps
import dev.xross.launcher.model.ArtKind
import dev.xross.launcher.scrape.ProviderId
import dev.xross.launcher.theme.pack.BadgePill
import dev.xross.launcher.theme.pack.ComponentRow
import dev.xross.launcher.theme.pack.EffectSlot
import dev.xross.launcher.theme.pack.ExportScope
import dev.xross.launcher.theme.pack.StatusSide
import dev.xross.launcher.theme.pack.StatusSlot

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
        val kinds: List<ArtKind>,
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

/** The optional color layers of Settings ▸ Theme (Theme 2.0, 2b.3). */
enum class ColorSwitch { ADAPTIVE, CIRCADIAN, BATTERY }

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
    /** Settings ▸ Theme ▸ Layout (2b.8a): the crosshair, UI scale, spacing and focus panel. */
    data object OpenLayoutSettings : Command
    /** Settings ▸ Theme ▸ Profile customization: the avatar's ring and glow. */
    data object OpenAvatarStyle : Command
    /** Games ▸ RetroAchievements: the profile dashboard. */
    data object OpenRaHub : Command
    data object CycleTheme : Command
    /** Settings ▸ Theme ▸ Colors ▸ Theme color ▸ Options: an [dev.xross.launcher.theme.XrossTheme] name or PACK. */
    data class SetTheme(val name: String) : Command
    data object CycleThemePack : Command
    data class SetThemePack(val id: String) : Command
    data object ReloadThemePacks : Command
    /** Settings ▸ Theme ▸ Surface style: the pack's own, then each surface it offers. */
    data object CycleSurfaceStyle : Command
    /** Next variant of the active pack ("" = its base look first). */
    data object CycleThemeVariant : Command
    data class SetThemeVariant(val id: String) : Command
    /** Pick a `.xtheme` archive through the system file picker. */
    data object ImportThemeArchive : Command
    /** Settings ▸ Theme ▸ Quick profile: None → OLED True Black → Y2K Cyber-Pastel → Monochrome E-Ink. */
    data object CycleQuickProfile : Command
    data class SetQuickProfile(val name: String) : Command
    /** Flips one of the optional color layers (the user's choice wins over the pack's). */
    data class ToggleColorSwitch(val which: ColorSwitch) : Command
    /** Sets one of them; null hands it back to the theme pack. */
    data class SetColorSwitch(val which: ColorSwitch, val on: Boolean?) : Command
    /** Install the staged `.xtheme` over the installed pack of the same id. */
    data object ReplaceImportedTheme : Command
    /** Install the staged `.xtheme` next to the installed pack, under a new id. */
    data object KeepBothImportedThemes : Command
    data object ToggleSoundEffects : Command
    /** Settings ▸ Sound: the next 10% step of [channel] (wrapping to 0). */
    data class CycleVolume(val channel: VolumeChannel) : Command
    /** Settings ▸ Sound ▸ a volume ▸ Options: Mute, 50% or 100%. */
    data class SetVolume(val channel: VolumeChannel, val value: Float) : Command
    /** Settings ▸ Sound ▸ Music during video snaps: Pause ↔ Lower. */
    data object CycleMusicUnderSnaps : Command
    data class SetMusicUnderSnaps(val mode: MusicUnderSnaps) : Command
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
    /** [key] is an [dev.xross.launcher.model.EmulatorOption.key]; null returns to the platform default. */
    data class SetGameEmulator(val gameId: Long, val key: String?) : Command
    data object OpenNowPlaying : Command
    data object ShuffleAllSongs : Command
    /** Type a name, then create that playlist with [paths] in it (none: an empty playlist). */
    data class NewPlaylist(val paths: List<String> = emptyList()) : Command
    /** Appends the songs at [paths] to playlist [name] (those already in it are skipped). */
    data class AddToPlaylist(val name: String, val paths: List<String>) : Command
    data class RemoveFromPlaylist(val name: String, val path: String) : Command
    /** Moves the song at [path] one place up ([delta] -1) or down (+1) in playlist [name]. */
    data class MoveInPlaylist(val name: String, val path: String, val delta: Int) : Command
    data class DeletePlaylist(val name: String) : Command
    /** Plays playlist [name] from the start, or shuffled. */
    data class PlayPlaylist(val name: String, val shuffle: Boolean) : Command
    data class SetSongSort(val sort: dev.xross.launcher.data.SongSort) : Command
    data class SetAlbumSort(val sort: dev.xross.launcher.data.AlbumSort) : Command
    /** Sets the photo as the Android system wallpaper (through the system's cropper). */
    data class SetWallpaper(val photoId: Long) : Command
    /** Draws the photo behind Xross's menu instead of the theme's wallpaper (`display.customWallpaper`). */
    data class SetXrossWallpaper(val photoId: Long) : Command
    /** How the Xross wallpaper is placed (Fill, Fit, Stretch, Center). */
    data class SetWallpaperFit(val fit: dev.xross.launcher.data.WallpaperFit) : Command
    /** Back to the theme pack's wallpaper (or none). */
    data object ResetXrossWallpaper : Command
    /** Settings ▸ Theme ▸ Xross wallpaper ▸ Pick a picture: any image, through the system picker. */
    data object PickXrossWallpaper : Command
    /** Settings ▸ Theme (2b.4f): the next choice of [slot] over the pack (the pack's own first). */
    data class CycleEffect(val slot: EffectSlot) : Command
    /** Sets [slot] to [value] (an enum name; empty hands it back to the theme pack). */
    data class SetEffect(val slot: EffectSlot, val value: String) : Command
    /**
     * Settings ▸ Theme ▸ Reset to theme defaults: drops every pick that overrides the pack's look
     * (effects, surface style, color layers, the Xross wallpaper), keeping the pack, variant,
     * theme color, quick profile and wave settings.
     */
    data object ResetThemeDefaults : Command
    /** Settings ▸ Theme ▸ Export theme… (2b.8b): the scopes the active pack can be exported in. */
    data object ExportTheme : Command
    /** The Theme pack row's Options ▸ Export a pack…: the scopes pack [id] can be exported in. */
    data class ExportThemePack(val id: String) : Command
    /** Export pack [packId] in [scope] (to an `.xtheme` file when [share], 2b.8c): the name entry opens next. */
    data class ChooseExportScope(val packId: String, val scope: ExportScope, val share: Boolean = false) : Command
    /** The confirm panel's Export: writes the pending export into Xross/themes. */
    data object ConfirmThemeExport : Command
    /** After an export: switch to the new pack, so the screen doesn't change. */
    data object SwitchToExportedTheme : Command
    /** The Theme pack row's Options ▸ Share as .xtheme… (2b.8c): the scopes pack [id] can be shared in. */
    data class ShareThemePack(val id: String) : Command
    /** After an export (2b.8c): zip the folder just written into an `.xtheme` file the user picks. */
    data object ShareExportedTheme : Command
    /** Settings ▸ Theme ▸ Components (2b.7a): sets status bar [side]'s slots; null hands it back to the pack. */
    data class SetStatusSlots(val side: StatusSide, val slots: List<StatusSlot>?) : Command
    /** One move, removal or addition of [slot] in status bar [side]; the slot list reopens on it. */
    data class EditStatusSlot(val side: StatusSide, val slot: StatusSlot, val edit: SlotEdit) : Command
    /** The next choice of a Components [row] over the pack (the pack's own first). */
    data class CycleComponent(val row: ComponentRow) : Command
    /** Sets [row] to [value] (null hands it back to the theme pack). */
    data class SetComponent(val row: ComponentRow, val value: String?) : Command
    /** Settings ▸ Theme ▸ Components ▸ Title badges (2b.7b): sets the badges in order; null hands them back to the pack. */
    data class SetBadgePills(val pills: List<BadgePill>?) : Command
    /** Turns one title badge on (at the end) or off; the list reopens on it. */
    data class ToggleBadgePill(val pill: BadgePill) : Command
    /** Opens the focused row's Options list (rows whose choices live there). */
    data object ShowOptions : Command
    data object CycleSlideshowInterval : Command
    data object ToggleResumeMusicAfterGames : Command
    data object ToggleVideoLoop : Command
    data object ToggleAllGamesFolder : Command
    data object ToggleFileFormat : Command
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
    data class ChooseMatch(val gameId: Long, val provider: ProviderId, val id: String) : Command
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
    /** A game's `badgePills` (2b.7b), drawn beside its title while it is focused; empty for anything else. */
    val pills: List<TitlePill> = emptyList(),
)

@Immutable
data class XmbCategory(
    val id: String,
    val title: String,
    val icon: GlyphKey,
)

object Categories {
    const val SETTINGS = CategoryIds.SETTINGS
    const val PHOTO = CategoryIds.PHOTO
    const val MUSIC = CategoryIds.MUSIC
    const val VIDEO = CategoryIds.VIDEO
    const val GAMES = CategoryIds.GAMES
    const val APPS = CategoryIds.APPS

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
