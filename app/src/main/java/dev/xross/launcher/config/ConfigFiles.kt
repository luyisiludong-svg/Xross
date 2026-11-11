package dev.xross.launcher.config

import android.view.KeyEvent
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.input.ControllerKey
import dev.xross.launcher.input.ControllerProfile
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.storage.Platform
import dev.xross.launcher.storage.PlatformRegistry
import dev.xross.launcher.storage.SystemOverride
import dev.xross.launcher.theme.WaveLimits
import dev.xross.launcher.theme.WaveTint
import dev.xross.launcher.theme.pack.ThemeManifest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
) {
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
    )

    /** `#RRGGBB` gradient for the CUSTOM wave tint. */
    @Serializable
    data class WaveColors(
        val top: String = D.waveColors.top,
        val bottom: String = D.waveColors.bottom,
        val wave: String = D.waveColors.wave,
    )

    @Serializable
    data class Sound(
        val effects: Boolean = D.soundEffects,
        val effectsVolume: Float = D.soundVolume,
        val startupSound: Boolean = D.bootSound,
        val gameStartSound: Boolean = D.gameBootSound,
    )

    @Serializable
    data class Media(
        val slideshowSeconds: Int = D.slideshowSeconds,
        val resumeMusicAfterGames: Boolean = D.resumeMusicAfterGames,
        val loopVideos: Boolean = D.videoLoop,
    )

    @Serializable
    data class Apps(
        /** Package names hidden from Apps and Games ▸ Android Games. */
        val hidden: List<String> = D.hiddenApps.toList(),
        val hideSystemApps: Boolean = D.hideSystemApps,
        /** Package name → folder name in Apps. */
        val folders: Map<String, String> = D.appFolders,
    )

    /** Empty [customFolders]: every photo or video MediaStore knows. Otherwise only these folders. */
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
    @Serializable
    data class System(
        val emulator: String? = null,
        val extraFolders: List<String> = emptyList(),
        val extraExtensions: List<String> = emptyList(),
        @SerialName("builtIn") val builtIn: BuiltIn? = null,
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

/** Converts between [XrossSettings] and the three config files. */
object ConfigCodec {
    const val VERSION = 1

    val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        coerceInputValues = true
        isLenient = true
    }

    // region settings.json

    fun settingsFile(s: XrossSettings) = SettingsFile(
        library = SettingsFile.Library(s.baseRomsFolder, s.romFolders, s.autoDetectFolders, s.knownAutoFolders, s.hashLargeImages, s.datFolder),
        controls = SettingsFile.Controls(s.confirmLayout.name, s.deadzone, s.axialDeadzone),
        display = SettingsFile.Display(
            theme = s.theme,
            themePack = s.themePack,
            wave = s.waveEnabled,
            waveSpeed = s.waveSpeed,
            waveLines = s.waveLineCount,
            waveTurbulence = s.waveTurbulence,
            waveThickness = s.waveThickness,
            waveTint = s.waveTint,
            waveColors = SettingsFile.WaveColors(s.waveColors.top, s.waveColors.bottom, s.waveColors.wave),
            buttonHints = s.showButtonHints,
            allGamesFolder = s.allGamesFolder,
        ),
        sound = SettingsFile.Sound(s.soundEffects, s.soundVolume, s.bootSound, s.gameBootSound),
        media = SettingsFile.Media(s.slideshowSeconds, s.resumeMusicAfterGames, s.videoLoop),
        metadata = SettingsFile.Metadata(
            s.scrapeAutomatically, s.scrapeWifiOnly, s.scrapeVideos, s.scrapeRegion, s.scrapeLanguage, s.hasheousEnabled,
        ),
        apps = SettingsFile.Apps(s.hiddenApps.sorted(), s.hideSystemApps, s.appFolders.toSortedMap()),
        photo = SettingsFile.MediaFolders(s.photoFolders),
        video = SettingsFile.MediaFolders(s.videoFolders),
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
        themePack = f.display.themePack.trim().ifBlank { D.themePack },
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
        soundEffects = f.sound.effects,
        soundVolume = f.sound.effectsVolume.finiteOr(D.soundVolume).coerceIn(0f, 1f),
        bootSound = f.sound.startupSound,
        gameBootSound = f.sound.gameStartSound,
        slideshowSeconds = f.media.slideshowSeconds.coerceIn(1, 600),
        resumeMusicAfterGames = f.media.resumeMusicAfterGames,
        videoLoop = f.media.loopVideos,
        scrapeAutomatically = f.metadata.scrapeAutomatically,
        scrapeWifiOnly = f.metadata.wifiOnly,
        scrapeVideos = f.metadata.videos,
        scrapeRegion = f.metadata.region.ifBlank { D.scrapeRegion },
        scrapeLanguage = f.metadata.language.ifBlank { D.scrapeLanguage },
        hasheousEnabled = f.metadata.hasheous,
        hiddenApps = f.apps.hidden.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        hideSystemApps = f.apps.hideSystemApps,
        appFolders = f.apps.folders.mapNotNull { (pkg, folder) ->
            val name = dev.xross.launcher.state.AppFolders.normalizeName(folder) ?: return@mapNotNull null
            pkg.trim().takeIf { it.isNotEmpty() }?.let { it to name }
        }.toMap(),
        photoFolders = cleanFolders(f.photo.customFolders),
        videoFolders = cleanFolders(f.video.customFolders),
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
        },
    )

    /** Applies systems.json onto [base]. Unknown systems and emulator choices are dropped. */
    fun applySystems(base: XrossSettings, f: SystemsFile, platforms: List<Platform> = PlatformRegistry.builtIn): XrossSettings {
        val known = platforms.associateBy { it.id }
        val choices = HashMap<String, String>()
        val overrides = HashMap<String, SystemOverride>()
        for ((id, entry) in f.systems) {
            val platform = known[id] ?: continue
            entry.emulator?.takeIf { key -> platform.emulators.any { it.key == key } }?.let { choices[id] = it }
            val o = SystemOverride(
                extraFolders = entry.extraFolders.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                extraExtensions = entry.extraExtensions.map(SystemOverride::normalizeExtension).filter { it.isNotEmpty() }.toSet(),
            )
            if (!o.isEmpty) overrides[id] = o
        }
        return base.copy(emulatorChoice = choices, systemOverrides = overrides)
    }

    fun encodeSystems(s: XrossSettings): String = json.encodeToString(SystemsFile.serializer(), systemsFile(s)) + "\n"

    fun decodeSystems(base: XrossSettings, text: String): XrossSettings =
        applySystems(base, json.decodeFromString(SystemsFile.serializer(), text))

    // endregion

    private fun Float.finiteOr(fallback: Float) = if (isFinite()) this else fallback
}
