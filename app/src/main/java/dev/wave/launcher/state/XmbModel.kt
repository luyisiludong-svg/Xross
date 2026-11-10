package dev.wave.launcher.state

import androidx.compose.runtime.Immutable
import dev.wave.launcher.media.ThumbKind

/** Icon identifiers; the UI layer maps these to vectors or loads app icons. */
sealed interface NodeIcon {
    data class Glyph(val key: GlyphKey) : NodeIcon
    data class App(val packageName: String) : NodeIcon
    /** Photo/video thumbnail or embedded album art, with a glyph to show until it loads. */
    data class Thumb(val kind: ThumbKind, val id: Long, val fallback: GlyphKey) : NodeIcon
}

enum class MediaKind { PHOTO, SONG, VIDEO }

/** Icon roles; each maps to an `xmb_*` vector drawable generated from assets/icons. */
enum class GlyphKey {
    SETTINGS, GAMES, APPS, PHOTO, MUSIC, VIDEO, ALBUM, ARTIST, SHUFFLE, SEARCH,
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
    data object ToggleLargeImageHashing : Command
    data object RecheckFingerprints : Command
    data object OpenHomeSettings : Command
    data object OpenAndroidSettings : Command
    data object OpenLibrarySettings : Command
    data class ToggleFavorite(val gameId: Long) : Command
    data class LaunchGame(val gameId: Long) : Command
    data object OpenSearch : Command
    /** [key] is an [dev.wave.launcher.storage.EmulatorOption.key]; null returns to the platform default. */
    data class SetGameEmulator(val gameId: Long, val key: String?) : Command
    data object OpenNowPlaying : Command
    data object ShuffleAllSongs : Command
    data class SetWallpaper(val photoId: Long) : Command
    data object CycleSlideshowInterval : Command
    data object ToggleResumeMusicAfterGames : Command
    data object ToggleVideoLoop : Command
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
