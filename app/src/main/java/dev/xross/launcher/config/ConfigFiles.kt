package dev.xross.launcher.config

import android.view.KeyEvent
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.input.ControllerKey
import dev.xross.launcher.input.ControllerProfile
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.model.Platform
import dev.xross.launcher.model.PlatformRegistry
import dev.xross.launcher.model.SystemOverride
import dev.xross.launcher.theme.AvatarStyleConfig
import dev.xross.launcher.theme.AvatarStyleLimits
import dev.xross.launcher.theme.WaveLimits
import dev.xross.launcher.theme.WaveTint
import dev.xross.launcher.theme.pack.ThemeManifest
import dev.xross.launcher.theme.pack.ThemePackLoader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import dev.xross.launcher.data.MusicUnderSnaps
import dev.xross.launcher.model.FolderNames
import dev.xross.launcher.theme.QuickProfile
import dev.xross.launcher.theme.pack.ComponentOverrides
import dev.xross.launcher.theme.pack.EffectOverrides
import dev.xross.launcher.theme.pack.LayoutOverrides
import dev.xross.launcher.theme.pack.LenientLayoutOverrides

/** Defaults come from [XrossSettings] so they are defined in one place. */
private val D = XrossSettings()

// region settings.json

/**
 * `Xross/config/settings.json`: UI and behaviour preferences, grouped the way Settings is.
 * Hand-editable; unknown keys are ignored and missing ones take their defaults. Never holds
 * account names, passwords or API keys (those stay in app-private storage, see CredentialStore).
 */
@Serializable
data class SettingsFile(
    val version: Int = ConfigCodec.VERSION,
    val library: Library = Library(),
    val controls: Controls = Controls(),
    val display: Display = Display(),
    val sound: Sound = Sound(),
    val media: Media = Media(),
    val metadata: Metadata = Metadata(),
    val apps: Apps = Apps(),
    val photo: MediaFolders = MediaFolders(),
    val video: MediaFolders = MediaFolders(),
    val music: MediaFolders = MediaFolders(),
    val profile: Profile = Profile(),
    val performance: Performance = Performance(),
) {
    /**
     * Settings ▸ System ▸ Performance. `standbyMotion`: AFTER_3S, AFTER_30S or NEVER.
     * `fullFrameRate`: the background at the screen's full refresh rate instead of ~60 fps.
     * `slowShaders`: RETIRE or WARN.
     */
    @Serializable
    data class Performance(
        val standbyMotion: String = D.standbyMotion.name,
        val fullFrameRate: Boolean = D.fullFrameRate,
        val slowShaders: String = D.slowShaders.name,
    )

    /** The profile avatar: ring color (`#RRGGBB`), ring width 1–6 dp, glow on/off, speed 0.5–2.0, intensity 0.1–1.0. */
    @Serializable
    data class Profile(val avatarStyle: AvatarStyleConfig = D.avatarStyle)

    @Serializable
    data class Library(
        /** Home of the library; empty = `roms/` in the Xross folder. Scanned like an added folder. */
        val baseRomsFolder: String = D.baseRomsFolder,
        val romFolders: List<String> = D.romFolders,
        val autoDetectFolders: Boolean = D.autoDetectFolders,
        val knownAutoFolders: List<String> = D.knownAutoFolders,
        val hashLargeImages: Boolean = D.hashLargeImages,
        val datFolder: String = D.datFolder,
    )

    @Serializable
    data class Controls(
        /** AUTO, SOUTH or EAST. */
        val confirmButton: String = D.confirmLayout.name,
        /** 0 = off (hardware flat zone only) … 1 = full travel. */
        val stickDeadzone: Float = D.deadzone,
        val axialDeadzone: Float = D.axialDeadzone,
    )

    @Serializable
    data class Display(
        /** Color theme name, or "PACK" for the theme pack's own palette. */
        val theme: String = D.theme,
        /** Folder name in Xross/themes/, or "default". */
        val themePack: String = D.themePack,
        /** Surface id (FLAT_GLASS, Y2K_GLOSSY_BEZEL, OLED_TRUE_BLACK, EINK_HIGH_CONTRAST or the pack's); empty = the pack's. */
        val surfaceStyle: String = D.surfaceStyle,
        /** Variant id of the theme pack ("cyber-lilac"); empty = the pack's base look. */
        val themeVariant: String = D.themeVariant,
        /** Xross wallpaper: a photo's content:// URI or a file path; empty = the theme pack's wallpaper. */
        val customWallpaper: String = D.customWallpaper,
        /** FILL, FIT, STRETCH or CENTER: how [customWallpaper] is placed on the screen. */
        val wallpaperFit: String = D.wallpaperFit.name,
        /**
         * Overrides of the pack's effects (Settings ▸ Theme): `shaderPreset`, `backgroundFilter`,
         * `uiFilter`, `focusFrameStyle`, `foregroundParticles`, `iconMask`; empty = the pack's.
         */
        val effects: EffectOverrides = D.effectOverrides,
        /**
         * Overrides of the pack's `components` (Settings ▸ Theme ▸ Components, 2b.7a):
         * `statusBarLeft`, `statusBarRight`, `batteryMode`, `clockFormat`, …; null = the pack's.
         */
        val components: ComponentOverrides = D.componentOverrides,
        /**
         * Overrides of the pack's layout (Settings ▸ Theme ▸ Layout, 2b.8a): `preset` (a crosshair
         * preset), `uiScale` 0.5–1.5, `itemSpacingDp` 0–32, `focusedScale` 1.00–1.35; null = the pack's.
         */
        @Serializable(with = LenientLayoutOverrides::class)
        val layout: LayoutOverrides = D.layoutOverrides,
        /** NONE, OLED_TRUE_BLACK, Y2K_CYBER_PASTEL or MONOCHROME_EINK. */
        val quickProfile: String = D.quickProfile,
        /** Colors from the focused game's art; null = the theme pack's choice (on by default). */
        val adaptiveGamePalette: Boolean? = D.adaptiveGamePalette,
        /** Time-of-day color curve; null = the theme pack's choice (off by default). */
        val circadianCurve: Boolean? = D.circadianCurve,
        /** Low-battery and charging tint; null = the theme pack's choice (off by default). */
        val batteryTint: Boolean? = D.batteryTint,
        val wave: Boolean = D.waveEnabled,
        /** Wave shader uniforms: speed 0.5–2.0, lines 0–12, turbulence 0–2, thickness 0.5–3. */
        val waveSpeed: Float = D.waveSpeed,
        val waveLines: Int = D.waveLineCount,
        val waveTurbulence: Float = D.waveTurbulence,
        val waveThickness: Float = D.waveThickness,
        /** THEME, AURORA, SUNSET, OCEAN, SAKURA, PASTEL, MONO or CUSTOM (uses [waveColors]). */
        val waveTint: String = D.waveTint,
        val waveColors: WaveColors = WaveColors(),
        val buttonHints: Boolean = D.showButtonHints,
        val allGamesFolder: Boolean = D.allGamesFolder,
        /** The file type ("ISO", "CHD") in game rows; the Information screen shows it either way. */
        val showFileFormat: Boolean = D.showFileFormat,
        /** The theme pack's console logo on system folders (`icons/systems/`), else the platform glyph. */
        val showSystemLogos: Boolean = D.showSystemLogos,
        /** PACK, WHITE, COLOR, MONO, OUTLINED, DARK_COLOR, DARK_MONO or BLACK. */
        val systemLogoStyle: String = D.systemLogoStyle,
        /** Wallpaper rotation (0.2.2.0 § 5b): a folder of pictures and how often they change. */
        val wallpaperRotation: WallpaperRotation = WallpaperRotation(D.wallpaperFolder, D.wallpaperInterval, D.wallpaperAlbum),
        /** Theme music (0.2.2.0 § 5): an audio file, a folder, or `none`; empty = the theme pack's. */
        val bgm: String = D.themeMusic,
        /** SHUFFLE or IN_ORDER, for a folder of tracks. */
        val bgmOrder: String = D.themeMusicOrder,
    )

    /** `interval`: OFF, MIN_1, MIN_5, MIN_15, MIN_60 or FRONT. `album`: a Photo album's name; wins over `folder`. */
    @Serializable
    data class WallpaperRotation(val folder: String = "", val interval: String = "OFF", val album: String = "")

    /** `#RRGGBB` gradient for the CUSTOM wave tint. */
    @Serializable
    data class WaveColors(
        val top: String = D.waveColors.top,
        val bottom: String = D.waveColors.bottom,
        val wave: String = D.waveColors.wave,
    )

    /**
     * Volumes are 0–1. A file from before 2b.5b has none of the four newer ones (null here): cues
     * and snaps then take [effectsVolume], which drove them until then, music 1 and theme music
     * 0.6, so nobody's mix changes on upgrade.
     */
    @Serializable
    data class Sound(
        val effects: Boolean = D.soundEffects,
        val effectsVolume: Float = D.soundVolume,
        val startupSound: Boolean = D.bootSound,
        val gameStartSound: Boolean = D.gameBootSound,
        /** The `opening` and `gameboot` cues. */
        val cueVolume: Float? = null,
        /** The theme pack's ambient music. */
        val bgmVolume: Float? = null,
        /** Xross's music player, on top of the system media volume. */
        val musicVolume: Float? = null,
        /** Video snaps. */
        val snapVolume: Float? = null,
        /** PAUSE (default) or DUCK (`LOWER` also reads as DUCK). */
        val musicUnderSnaps: String? = null,
    )

    @Serializable
    data class Media(
        val slideshowSeconds: Int = D.slideshowSeconds,
        val resumeMusicAfterGames: Boolean = D.resumeMusicAfterGames,
        val loopVideos: Boolean = D.videoLoop,
        /** TITLE, TITLE_DESC, ARTIST, ALBUM or RECENT. */
        val songSort: String = D.songSort.name,
        /** TITLE, TITLE_DESC, ARTIST or RECENT. */
        val albumSort: String = D.albumSort.name,
    )

    @Serializable
    data class Apps(
        /** Package names hidden from Apps and Games ▸ Android Games. */
        val hidden: List<String> = D.hiddenApps.toList(),
        val hideSystemApps: Boolean = D.hideSystemApps,
        /** Package name → folder name in Apps. */
        val folders: Map<String, String> = D.appFolders,
        /** Video, music and photo apps go to the Apps folder of those categories. */
        val autoSort: Boolean = D.autoSortApps,
        /** Package name → APPS, VIDEO, MUSIC or PHOTO: where the user moved it. */
        val homes: Map<String, String> = emptyMap(),
    )

    /** Empty [customFolders]: every photo, video or song MediaStore knows. Otherwise only these folders. */
    @Serializable
    data class MediaFolders(val customFolders: List<String> = emptyList())

    @Serializable
    data class Metadata(
        val scrapeAutomatically: Boolean = D.scrapeAutomatically,
        val wifiOnly: Boolean = D.scrapeWifiOnly,
        val videos: Boolean = D.scrapeVideos,
        val region: String = D.scrapeRegion,
        val language: String = D.scrapeLanguage,
        val hasheous: Boolean = D.hasheousEnabled,
    )
}

// endregion

// region controllers.json

/**
 * `Xross/config/controllers.json`: button mappings. [default] applies to keyboards, devices
 * without IDs and controllers without their own entry; [controllers] is keyed by USB/Bluetooth
 * `vendor:product` ("054c:0ce6"). Keys are Android key names ("KEYCODE_BUTTON_A") or numbers,
 * values are Xross actions (CONFIRM, BACK, OPTIONS, MEDIA, …).
 */
@Serializable
data class ControllersFile(
    val version: Int = ConfigCodec.VERSION,
    val default: Mapping = Mapping(),
    val controllers: Map<String, Controller> = emptyMap(),
) {
    @Serializable
    data class Mapping(val buttons: Map<String, String> = emptyMap())

    @Serializable
    data class Controller(val name: String = "", val buttons: Map<String, String> = emptyMap())
}

// endregion

// region systems.json

/**
 * `Xross/config/systems.json`: one entry per system, keyed by its ES-DE style id ("snes").
 *  - [System.emulator]: the launch recipe to use ("retroarch:snes9x", "duckstation"); null picks
 *    the first installed one in [BuiltIn.emulators] order.
 *  - [System.extraFolders] / [System.extraExtensions]: added to the built-in folder names and
 *    file types.
 *  - [System.builtIn]: what Xross ships, for reference. Rewritten on every save; edits to it are
 *    ignored.
 */
@Serializable
data class SystemsFile(
    val version: Int = ConfigCodec.VERSION,
    val systems: Map<String, System> = emptyMap(),
) {
    /**
     * A built-in system uses [emulator], [extraFolders] and [extraExtensions]. An id Xross doesn't
     * ship is a **custom system** (0.2.2.0 § 1): it needs [name] and [extensions]; [folders]
     * defaults to the id; [emulators] are launch recipe keys (built-in like "retroarch:mgba", or
     * an `emulators.json` id), first installed wins. [source] is "device" for one made in Settings.
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Serializable
    data class System(
        val emulator: String? = null,
        val extraFolders: List<String> = emptyList(),
        val extraExtensions: List<String> = emptyList(),
        @SerialName("builtIn") val builtIn: BuiltIn? = null,
        // Written only when set, so a built-in system's entry stays as short as before.
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val name: String? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val shortName: String? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val folders: List<String>? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val extensions: List<String>? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val emulators: List<String>? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val logo: String? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val source: String? = null,
    )

    @Serializable
    data class BuiltIn(
        val name: String,
        val shortName: String,
        val folders: List<String>,
        val extensions: List<String>,
        val emulators: List<String>,
    )
}

// endregion

// region emulators.json

/**
 * `Xross/config/emulators.json` (0.2.2.0 § 1): launch recipes for emulators Xross doesn't ship,
 * keyed by an id of the user's choosing. [CustomEmulator.command] is ES-DE's syntax
 * ([dev.xross.launcher.launch.EsdeCommand]). Comments (`//`, `/* */`) are allowed; Xross only
 * rewrites the entries it made itself (`"source": "device"`) and leaves the rest of the file as
 * the user wrote it.
 */
@Serializable
data class EmulatorsFile(
    val version: Int = ConfigCodec.VERSION,
    val emulators: Map<String, CustomEmulator> = emptyMap(),
)

@Serializable
data class CustomEmulator(
    val name: String = "",
    /** Package ids in preference order; the first installed one with [activity] runs. */
    val packages: List<String> = emptyList(),
    /** Fully qualified, or starting with "." for one inside the package. */
    val activity: String = "",
    /** System ids this emulator runs (built-in or custom). */
    val systems: List<String> = emptyList(),
    val command: String = "",
    /** "device" when made in Settings ▸ Emulators ▸ Add emulator; null for hand-written. */
    val source: String? = null,
) {
    val fromDevice: Boolean get() = source == ConfigCodec.SOURCE_DEVICE
}

/** A custom system from `systems.json`, as Settings keeps it. */
data class CustomSystem(
    val name: String,
    val shortName: String,
    val folders: List<String>,
    val extensions: List<String>,
    val emulators: List<String>,
    /** An image file shown as the system's console logo; empty for none. */
    val logo: String = "",
    val source: String? = null,
) {
    val fromDevice: Boolean get() = source == ConfigCodec.SOURCE_DEVICE
}

// endregion

/** Converts between [XrossSettings] and the config files. */
object ConfigCodec {
    const val VERSION = 1

    /** `"source"` of an entry made on the device; Xross edits and deletes only those. */
    const val SOURCE_DEVICE = "device"

    /** A hand-edited `display.customWallpaper` longer than this is cut (URIs and paths are far shorter). */
    const val MAX_WALLPAPER_CHARS = 1024

    val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        coerceInputValues = true
        isLenient = true
        allowComments = true
        allowTrailingComma = true
    }

    // region settings.json

    fun settingsFile(s: XrossSettings) = SettingsFile(
        library = SettingsFile.Library(s.baseRomsFolder, s.romFolders, s.autoDetectFolders, s.knownAutoFolders, s.hashLargeImages, s.datFolder),
        controls = SettingsFile.Controls(s.confirmLayout.name, s.deadzone, s.axialDeadzone),
        display = SettingsFile.Display(
            theme = s.theme,
            themePack = s.themePack,
            surfaceStyle = s.surfaceStyle,
            themeVariant = s.themeVariant,
            customWallpaper = s.customWallpaper,
            wallpaperFit = s.wallpaperFit.name,
            effects = s.effectOverrides,
            components = s.componentOverrides,
            layout = s.layoutOverrides,
            quickProfile = s.quickProfile,
            adaptiveGamePalette = s.adaptiveGamePalette,
            circadianCurve = s.circadianCurve,
            batteryTint = s.batteryTint,
            wave = s.waveEnabled,
            waveSpeed = s.waveSpeed,
            waveLines = s.waveLineCount,
            waveTurbulence = s.waveTurbulence,
            waveThickness = s.waveThickness,
            waveTint = s.waveTint,
            waveColors = SettingsFile.WaveColors(s.waveColors.top, s.waveColors.bottom, s.waveColors.wave),
            buttonHints = s.showButtonHints,
            allGamesFolder = s.allGamesFolder,
            showFileFormat = s.showFileFormat,
            showSystemLogos = s.showSystemLogos,
            systemLogoStyle = s.systemLogoStyle,
            wallpaperRotation = SettingsFile.WallpaperRotation(s.wallpaperFolder, s.wallpaperInterval, s.wallpaperAlbum),
            bgm = s.themeMusic,
            bgmOrder = s.themeMusicOrder,
        ),
        sound = SettingsFile.Sound(
            effects = s.soundEffects,
            effectsVolume = s.soundVolume,
            startupSound = s.bootSound,
            gameStartSound = s.gameBootSound,
            cueVolume = s.cueVolume,
            bgmVolume = s.bgmVolume,
            musicVolume = s.musicVolume,
            snapVolume = s.snapVolume,
            musicUnderSnaps = s.musicUnderSnaps.name,
        ),
        media = SettingsFile.Media(s.slideshowSeconds, s.resumeMusicAfterGames, s.videoLoop, s.songSort.name, s.albumSort.name),
        metadata = SettingsFile.Metadata(
            s.scrapeAutomatically, s.scrapeWifiOnly, s.scrapeVideos, s.scrapeRegion, s.scrapeLanguage, s.hasheousEnabled,
        ),
        apps = SettingsFile.Apps(
            s.hiddenApps.sorted(), s.hideSystemApps, s.appFolders.toSortedMap(),
            autoSort = s.autoSortApps,
            homes = s.appHomes.mapValues { it.value.name }.toSortedMap(),
        ),
        photo = SettingsFile.MediaFolders(s.photoFolders),
        video = SettingsFile.MediaFolders(s.videoFolders),
        music = SettingsFile.MediaFolders(s.musicFolders),
        profile = SettingsFile.Profile(s.avatarStyle),
        performance = SettingsFile.Performance(s.standbyMotion.name, s.fullFrameRate, s.slowShaders.name),
    )

    /** Applies settings.json onto [base], keeping [base]'s mappings and systems. Out-of-range numbers are clamped. */
    fun applySettings(base: XrossSettings, f: SettingsFile): XrossSettings = base.copy(
        baseRomsFolder = f.library.baseRomsFolder.trim(),
        romFolders = f.library.romFolders.filter { it.isNotBlank() }.distinct(),
        autoDetectFolders = f.library.autoDetectFolders,
        knownAutoFolders = f.library.knownAutoFolders.filter { it.isNotBlank() }.distinct(),
        hashLargeImages = f.library.hashLargeImages,
        datFolder = f.library.datFolder,
        confirmLayout = ConfirmLayout.entries.firstOrNull { it.name.equals(f.controls.confirmButton, ignoreCase = true) }
            ?: D.confirmLayout,
        deadzone = f.controls.stickDeadzone.finiteOr(D.deadzone).coerceIn(0f, 0.95f),
        axialDeadzone = f.controls.axialDeadzone.finiteOr(D.axialDeadzone).coerceIn(0f, 0.95f),
        theme = f.display.theme.ifBlank { D.theme },
        // A former id of the Default pack ("xmb") is saved back as "default".
        themePack = f.display.themePack.trim().let { if (ThemePackLoader.isDefault(it)) ThemePackLoader.DEFAULT_ID else it },
        surfaceStyle = f.display.surfaceStyle.trim().take(48),
        themeVariant = f.display.themeVariant.trim().take(48),
        customWallpaper = f.display.customWallpaper.trim().take(MAX_WALLPAPER_CHARS),
        wallpaperFit = dev.xross.launcher.data.WallpaperFit.parse(f.display.wallpaperFit),
        effectOverrides = f.display.effects.normalized(),
        componentOverrides = f.display.components.normalized(),
        layoutOverrides = f.display.layout.normalized(),
        quickProfile = QuickProfile.normalize(f.display.quickProfile),
        adaptiveGamePalette = f.display.adaptiveGamePalette,
        circadianCurve = f.display.circadianCurve,
        batteryTint = f.display.batteryTint,
        waveEnabled = f.display.wave,
        waveSpeed = WaveLimits.clampSpeed(f.display.waveSpeed),
        waveLineCount = WaveLimits.clampLines(f.display.waveLines),
        waveTurbulence = WaveLimits.clampTurbulence(f.display.waveTurbulence),
        waveThickness = WaveLimits.clampThickness(f.display.waveThickness),
        waveTint = WaveTint.byName(f.display.waveTint).name,
        waveColors = f.display.waveColors.let { c ->
            // Each color falls back to the default on its own, so one typo doesn't reset the rest.
            fun pick(text: String, fallback: String) = if (ThemeManifest.parseColor(text) != null) text.trim() else fallback
            dev.xross.launcher.theme.WaveColors(
                top = pick(c.top, D.waveColors.top),
                bottom = pick(c.bottom, D.waveColors.bottom),
                wave = pick(c.wave, D.waveColors.wave),
            )
        },
        showButtonHints = f.display.buttonHints,
        allGamesFolder = f.display.allGamesFolder,
        showFileFormat = f.display.showFileFormat,
        showSystemLogos = f.display.showSystemLogos,
        systemLogoStyle = dev.xross.launcher.data.SystemLogoStyle.of(f.display.systemLogoStyle).name,
        themeMusic = f.display.bgm.trim().take(MAX_WALLPAPER_CHARS),
        wallpaperFolder = f.display.wallpaperRotation.folder.trim().trimEnd('/').take(MAX_WALLPAPER_CHARS),
        wallpaperAlbum = f.display.wallpaperRotation.album.trim().take(256),
        wallpaperInterval = f.display.wallpaperRotation.interval.trim().uppercase().let { i ->
            if (i in setOf("OFF", "MIN_1", "MIN_5", "MIN_15", "MIN_60", "FRONT")) i else "OFF"
        },
        themeMusicOrder = dev.xross.launcher.data.ThemeMusic.normalizeOrder(f.display.bgmOrder),
        soundEffects = f.sound.effects,
        soundVolume = f.sound.effectsVolume.finiteOr(D.soundVolume).coerceIn(0f, 1f),
        cueVolume = f.sound.cueVolume.volumeOr(f.sound.effectsVolume.finiteOr(D.soundVolume)),
        bgmVolume = f.sound.bgmVolume.volumeOr(D.bgmVolume),
        musicVolume = f.sound.musicVolume.volumeOr(D.musicVolume),
        snapVolume = f.sound.snapVolume.volumeOr(f.sound.effectsVolume.finiteOr(D.soundVolume)),
        musicUnderSnaps = MusicUnderSnaps.parse(f.sound.musicUnderSnaps),
        bootSound = f.sound.startupSound,
        gameBootSound = f.sound.gameStartSound,
        slideshowSeconds = f.media.slideshowSeconds.coerceIn(1, 600),
        resumeMusicAfterGames = f.media.resumeMusicAfterGames,
        videoLoop = f.media.loopVideos,
        songSort = dev.xross.launcher.data.SongSort.parse(f.media.songSort),
        albumSort = dev.xross.launcher.data.AlbumSort.parse(f.media.albumSort),
        scrapeAutomatically = f.metadata.scrapeAutomatically,
        scrapeWifiOnly = f.metadata.wifiOnly,
        scrapeVideos = f.metadata.videos,
        scrapeRegion = f.metadata.region.ifBlank { D.scrapeRegion },
        scrapeLanguage = f.metadata.language.ifBlank { D.scrapeLanguage },
        hasheousEnabled = f.metadata.hasheous,
        hiddenApps = f.apps.hidden.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        hideSystemApps = f.apps.hideSystemApps,
        appFolders = f.apps.folders.mapNotNull { (pkg, folder) ->
            val name = FolderNames.normalize(folder) ?: return@mapNotNull null
            pkg.trim().takeIf { it.isNotEmpty() }?.let { it to name }
        }.toMap(),
        autoSortApps = f.apps.autoSort,
        appHomes = f.apps.homes.mapNotNull { (pkg, home) ->
            val h = dev.xross.launcher.model.AppHome.entries.firstOrNull { it.name.equals(home.trim(), ignoreCase = true) } ?: return@mapNotNull null
            pkg.trim().takeIf { it.isNotEmpty() }?.let { it to h }
        }.toMap(),
        photoFolders = cleanFolders(f.photo.customFolders),
        videoFolders = cleanFolders(f.video.customFolders),
        musicFolders = cleanFolders(f.music.customFolders),
        avatarStyle = AvatarStyleLimits.clamp(f.profile.avatarStyle),
        standbyMotion = dev.xross.launcher.data.StandbyMotion.parse(f.performance.standbyMotion),
        fullFrameRate = f.performance.fullFrameRate,
        slowShaders = dev.xross.launcher.data.SlowShaders.parse(f.performance.slowShaders),
    )

    private fun cleanFolders(paths: List<String>) = paths.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()

    fun encodeSettings(s: XrossSettings): String = json.encodeToString(SettingsFile.serializer(), settingsFile(s)) + "\n"

    /** Throws [kotlinx.serialization.SerializationException] (or IllegalArgumentException) on unreadable JSON. */
    fun decodeSettings(base: XrossSettings, text: String): XrossSettings =
        applySettings(base, json.decodeFromString(SettingsFile.serializer(), text))

    // endregion

    // region controllers.json

    fun controllersFile(s: XrossSettings) = ControllersFile(
        default = ControllersFile.Mapping(encodeButtons(s.keyRemap)),
        controllers = s.controllerProfiles.entries.sortedBy { it.key }.associate { (id, profile) ->
            id to ControllersFile.Controller(profile.name, encodeButtons(profile.remap))
        },
    )

    /** Applies controllers.json onto [base]. Unknown keys, actions and controller ids are dropped. */
    fun applyControllers(base: XrossSettings, f: ControllersFile): XrossSettings = base.copy(
        keyRemap = decodeButtons(f.default.buttons),
        controllerProfiles = buildMap {
            for ((id, entry) in f.controllers) {
                val key = ControllerKey.parse(id) ?: continue
                val remap = decodeButtons(entry.buttons)
                if (remap.isNotEmpty()) put(key.id, ControllerProfile(entry.name, remap))
            }
        },
    )

    fun encodeControllers(s: XrossSettings): String = json.encodeToString(ControllersFile.serializer(), controllersFile(s)) + "\n"

    fun decodeControllers(base: XrossSettings, text: String): XrossSettings =
        applyControllers(base, json.decodeFromString(ControllersFile.serializer(), text))

    /** Key codes as Android names ("KEYCODE_BUTTON_A"), sorted so the file diffs cleanly; unnamed codes as numbers. */
    fun encodeButtons(remap: Map<Int, NavAction>): Map<String, String> =
        remap.entries.sortedBy { it.key }.associate { (code, action) -> keyName(code) to action.name }

    fun keyName(code: Int): String = KeyNames.byCode[code] ?: code.toString()

    fun decodeButtons(buttons: Map<String, String>): Map<Int, NavAction> = buildMap {
        for ((key, value) in buttons) {
            val code = parseKeyCode(key) ?: continue
            val action = NavAction.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: continue
            put(code, action)
        }
    }

    /** "KEYCODE_BUTTON_A", "BUTTON_A" or "96"; null for anything Android doesn't know. */
    fun parseKeyCode(raw: String): Int? {
        val text = raw.trim()
        text.toIntOrNull()?.let { return it.takeIf { code -> code > KeyEvent.KEYCODE_UNKNOWN } }
        val name = text.uppercase().let { if (it.startsWith(KeyNames.PREFIX)) it else KeyNames.PREFIX + it }
        return KeyNames.byName[name]
    }

    /**
     * Android's key names, read from KeyEvent's public `KEYCODE_*` constants. Avoids
     * `KeyEvent.keyCodeToString`, which goes through native code and differs between releases.
     */
    private object KeyNames {
        const val PREFIX = "KEYCODE_"

        val byName: Map<String, Int> = KeyEvent::class.java.fields
            .filter { f ->
                f.name.startsWith(PREFIX) && f.type == Int::class.javaPrimitiveType &&
                    java.lang.reflect.Modifier.isStatic(f.modifiers)
            }
            .mapNotNull { f -> runCatching { f.name to f.getInt(null) }.getOrNull() }
            .filter { (_, code) -> code > KeyEvent.KEYCODE_UNKNOWN }
            .toMap()

        /** First name wins for codes with aliases. */
        val byCode: Map<Int, String> = buildMap { for ((name, code) in byName.entries.sortedBy { it.key }) putIfAbsent(code, name) }
    }

    // endregion

    // region systems.json

    fun systemsFile(s: XrossSettings, platforms: List<Platform> = PlatformRegistry.builtIn) = SystemsFile(
        systems = platforms.associate { p ->
            val o = s.systemOverrides[p.id] ?: SystemOverride()
            p.id to SystemsFile.System(
                emulator = s.emulatorChoice[p.id],
                extraFolders = o.extraFolders.sorted(),
                extraExtensions = o.extraExtensions.sorted(),
                builtIn = SystemsFile.BuiltIn(
                    name = p.name,
                    shortName = p.shortName,
                    folders = p.folderAliases.sorted(),
                    extensions = p.extensions.sorted(),
                    emulators = p.emulators.map { it.key },
                ),
            )
        } + s.customSystems.mapValues { (id, c) ->
            SystemsFile.System(
                name = c.name,
                shortName = c.shortName,
                folders = c.folders,
                extensions = c.extensions,
                emulators = c.emulators,
                logo = c.logo.ifEmpty { null },
                source = c.source,
                emulator = s.emulatorChoice[id],
            )
        },
    )

    /** Applies systems.json onto [base]. Unknown systems and emulator choices are dropped. */
    fun applySystems(base: XrossSettings, f: SystemsFile, platforms: List<Platform> = PlatformRegistry.builtIn): XrossSettings {
        val known = platforms.associateBy { it.id }
        val choices = HashMap<String, String>()
        val overrides = HashMap<String, SystemOverride>()
        val custom = LinkedHashMap<String, CustomSystem>()
        for ((id, entry) in f.systems) {
            val platform = known[id]
            if (platform == null) {
                val system = customSystem(id, entry) ?: continue
                custom[id] = system
                // Checked against the merged registry when it is installed.
                entry.emulator?.takeIf { it.isNotBlank() }?.let { choices[id] = it.trim() }
                continue
            }
            // A custom emulator (emulators.json, read before this file) may be chosen for a built-in system.
            entry.emulator?.takeIf { key -> platform.emulators.any { it.key == key } || key in base.customEmulators }?.let { choices[id] = it }
            val o = SystemOverride(
                extraFolders = entry.extraFolders.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                extraExtensions = entry.extraExtensions.map(SystemOverride::normalizeExtension).filter { it.isNotEmpty() }.toSet(),
            )
            if (!o.isEmpty) overrides[id] = o
        }
        return base.copy(emulatorChoice = choices, systemOverrides = overrides, customSystems = custom)
    }

    /** A custom system entry, or null when it has no name (an override of a system this build lacks). */
    fun customSystem(id: String, e: SystemsFile.System): CustomSystem? {
        val name = e.name?.trim().orEmpty()
        if (name.isEmpty() && e.extensions.isNullOrEmpty()) return null
        return CustomSystem(
            name = name.ifEmpty { id },
            shortName = e.shortName?.trim().orEmpty().ifEmpty { id.uppercase().take(4) },
            folders = (e.folders ?: listOf(id)).map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            extensions = e.extensions.orEmpty().map(SystemOverride::normalizeExtension).filter { it.isNotEmpty() }.distinct(),
            emulators = e.emulators.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            logo = e.logo?.trim().orEmpty(),
            source = e.source,
        )
    }

    fun encodeSystems(s: XrossSettings): String = json.encodeToString(SystemsFile.serializer(), systemsFile(s)) + "\n"

    fun decodeSystems(base: XrossSettings, text: String): XrossSettings =
        applySystems(base, json.decodeFromString(SystemsFile.serializer(), text))

    // endregion

    // region emulators.json

    fun decodeEmulators(base: XrossSettings, text: String): XrossSettings =
        base.copy(customEmulators = json.decodeFromString(EmulatorsFile.serializer(), text).emulators.mapKeys { it.key.trim() })

    /**
     * The text of emulators.json for [s]. [previous] is the file as last read or written: when its
     * entries are still what [s] holds it comes back unchanged (comments and layout kept); when
     * only device entries changed, those are spliced in and hand-written text stays as it is.
     */
    fun encodeEmulators(s: XrossSettings, previous: String?): String {
        val wanted = s.customEmulators
        if (previous != null) {
            val before = runCatching { json.decodeFromString(EmulatorsFile.serializer(), previous).emulators }.getOrNull()
            if (before != null) {
                if (before == wanted) return previous
                val handBefore = before.filterValues { !it.fromDevice }
                val handNow = wanted.filterValues { !it.fromDevice }
                if (handBefore == handNow) {
                    val device = wanted.filterValues { it.fromDevice }
                    JsonSplice.replaceMembers(previous, "emulators", drop = before.filterValues { it.fromDevice }.keys) { indent ->
                        device.map { (id, e) -> encodeMember(id, e, indent) }
                    }?.let { out ->
                        val check = runCatching { json.decodeFromString(EmulatorsFile.serializer(), out).emulators }.getOrNull()
                        if (check == wanted) return out
                    }
                }
            }
        }
        return json.encodeToString(EmulatorsFile.serializer(), EmulatorsFile(emulators = wanted)) + "\n"
    }

    private fun encodeMember(id: String, e: CustomEmulator, indent: String): String {
        val body = json.encodeToString(CustomEmulator.serializer(), e).lines()
        val key = json.encodeToString(String.serializer(), id)
        return indent + key + ": " + body.first() + body.drop(1).joinToString("") { "\n" + indent + it }
    }

    // endregion

    private fun Float.finiteOr(fallback: Float) = if (isFinite()) this else fallback

    /** A volume from the file, clamped to 0–1; missing or not a number takes [fallback] (also clamped). */
    private fun Float?.volumeOr(fallback: Float) = (this?.takeIf { it.isFinite() } ?: fallback).coerceIn(0f, 1f)
}
