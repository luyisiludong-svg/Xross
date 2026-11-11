package dev.xross.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.xross.launcher.config.AtomicFiles
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.config.ConfigWriter
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.input.ControllerKey
import dev.xross.launcher.input.ControllerProfile
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.storage.PlatformRegistry
import dev.xross.launcher.storage.SystemOverride
import dev.xross.launcher.storage.XrossEnvironment
import dev.xross.launcher.storage.XrossLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class XrossSettings(
    val romFolders: List<String> = emptyList(),
    val autoDetectFolders: Boolean = true,
    /** Auto-detected folders seen so far, kept so games survive their volume being unplugged. */
    val knownAutoFolders: List<String> = emptyList(),
    val confirmLayout: ConfirmLayout = ConfirmLayout.AUTO,
    /** Radial deadzone: stick deflection (0–1 of full travel) below which nothing registers. */
    val deadzone: Float = 0.35f,
    /** Axial deadzone: per-axis deflection ignored before the radial check; 0 turns it off. */
    val axialDeadzone: Float = 0f,
    /** Global mapping: keyboards, unidentified devices, and controllers without their own profile. */
    val keyRemap: Map<Int, NavAction> = emptyMap(),
    /** [ControllerKey.id] → that controller model's own mapping. */
    val controllerProfiles: Map<String, ControllerProfile> = emptyMap(),
    /** platformId → [dev.xross.launcher.storage.EmulatorOption.key] */
    val emulatorChoice: Map<String, String> = emptyMap(),
    val waveEnabled: Boolean = true,
    /** Wave shader uniforms (Settings ▸ Theme ▸ Wave settings); ranges in [dev.xross.launcher.theme.WaveLimits]. */
    val waveSpeed: Float = 1f,
    /** Fine strands riding the main ribbon (0–12). */
    val waveLineCount: Int = dev.xross.launcher.theme.WaveLimits.DEFAULT_LINES,
    /** Wave amplitude multiplier (0 = flat, 2 = stormy). */
    val waveTurbulence: Float = dev.xross.launcher.theme.WaveLimits.DEFAULT_TURBULENCE,
    /** Ribbon crest and halo width multiplier. */
    val waveThickness: Float = dev.xross.launcher.theme.WaveLimits.DEFAULT_THICKNESS,
    /** [dev.xross.launcher.theme.WaveTint] name: the theme's colors, a preset, or [waveColors]. */
    val waveTint: String = dev.xross.launcher.theme.WaveTint.THEME.name,
    /** Gradient used when [waveTint] is CUSTOM. */
    val waveColors: dev.xross.launcher.theme.WaveColors = dev.xross.launcher.theme.WaveColors(),
    /** Active theme pack: "default" (built in) or a folder name in `Xross/themes/`. */
    val themePack: String = dev.xross.launcher.theme.pack.ThemePackLoader.DEFAULT_ID,
    /** [dev.xross.launcher.theme.XrossTheme] name, or [dev.xross.launcher.theme.PACK_THEME] for the theme pack's own colors. */
    val theme: String = "LAVENDER",
    /** The pill of button hints in the bottom corner of the home menu. */
    val showButtonHints: Boolean = true,
    val soundEffects: Boolean = true,
    val soundVolume: Float = 0.75f,
    val bootSound: Boolean = true,
    val gameBootSound: Boolean = true,
    /** Fully hash disc images over the size limit (slow on SD cards). */
    val hashLargeImages: Boolean = false,
    /** Folder DATs are imported from; empty means `dats/` in the Xross folder ([dev.xross.launcher.storage.XrossLayout.dats]). */
    val datFolder: String = "",
    /** Scrape new games in the background while Xross is open. Off until the user opts in. */
    val scrapeAutomatically: Boolean = false,
    /** Only scrape on unmetered networks. */
    val scrapeWifiOnly: Boolean = true,
    /** Download video snaps (large). */
    val scrapeVideos: Boolean = false,
    /** ScreenScraper region code for names, dates and box art. */
    val scrapeRegion: String = "us",
    /** Language code for descriptions and genres. */
    val scrapeLanguage: String = "en",
    val hasheousEnabled: Boolean = true,
    val slideshowSeconds: Int = 5,
    /** Pause music when a game starts and pick it up again on return. */
    val resumeMusicAfterGames: Boolean = true,
    val videoLoop: Boolean = false,
    /**
     * Platform folders live inside one "All Games" folder (true), or sit directly in the Games
     * category next to Search and the collections (false: the classic, flatter PS3-style list).
     */
    val allGamesFolder: Boolean = true,
    /** platformId → the user's extra folder names and extensions (systems.json). */
    val systemOverrides: Map<String, SystemOverride> = emptyMap(),
    /** Package names hidden from Apps and Games ▸ Android Games. */
    val hiddenApps: Set<String> = emptySet(),
    /** Also hide apps that came with the system (preinstalled or updated system apps). */
    val hideSystemApps: Boolean = false,
    /** Package name → the Apps folder it's in. */
    val appFolders: Map<String, String> = emptyMap(),
    /** The library's home folder; empty means `roms/` in the Xross folder ([dev.xross.launcher.storage.XrossLayout.roms]). */
    val baseRomsFolder: String = "",
    /** Photo shows only these folders; empty means everything MediaStore knows. */
    val photoFolders: List<String> = emptyList(),
    /** Video shows only these folders; empty means everything MediaStore knows. */
    val videoFolders: List<String> = emptyList(),
)

/**
 * Settings as a StateFlow, persisted as three hand-editable JSON files in `Xross/config/`
 * ([XrossLayout.settingsFile], [XrossLayout.controllersFile], [XrossLayout.systemsFile]), so a
 * setup travels with the Xross folder. Writes are atomic and happen off the main thread
 * ([ConfigWriter]).
 *
 * Settings saved by earlier versions in SharedPreferences are migrated once, then cleared.
 * Account names, passwords and API keys are not settings: they stay in [CredentialStore], in
 * app-private storage, and never reach these files.
 */
class SettingsStore(
    context: Context,
    private val environment: XrossEnvironment = XrossEnvironment(context),
) {
    private val legacy: SharedPreferences = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile private var layout: XrossLayout = environment.current

    /**
     * Text last read from or queued for each config file. A file whose text differs from this was
     * changed outside Xross.
     */
    private val known = ConcurrentHashMap<String, String>()

    private val _settings = MutableStateFlow(open(layout))
    val settings: StateFlow<XrossSettings> = _settings.asStateFlow()
    val current: XrossSettings get() = _settings.value

    /** Folder the config files live in right now. */
    val configFolder: File get() = layout.config

    fun update(transform: (XrossSettings) -> XrossSettings) = synchronized(lock) {
        val previous = _settings.value
        val next = transform(previous)
        if (next == previous) return
        apply(next, previous)
        persist(layout, next)
    }

    /**
     * The Xross folder moved (storage access granted, another volume chosen). Config files
     * already in the new folder win, so plugging in an SD card from another device brings its
     * setup along; otherwise the current settings are written there. Returns true when settings
     * were loaded from the new folder. Blocking file I/O.
     */
    fun relocate(next: XrossLayout): Boolean = synchronized(lock) {
        if (next == layout) return false
        ConfigWriter.awaitIdle()
        layout = next
        known.clear()
        val adopt = next.configFiles.any { it.isFile }
        val result = if (adopt) read(next, _settings.value) else _settings.value
        apply(result, _settings.value)
        persist(next, result)
        adopt
    }

    /**
     * Picks up config files edited outside Xross since they were last read or written.
     * Blocking file I/O: call off the main thread. Returns true when settings changed.
     */
    fun reloadIfChanged(): Boolean = synchronized(lock) {
        ConfigWriter.awaitIdle()
        val edited = layout.configFiles.any { file ->
            val text = AtomicFiles.readText(file)
            text == null || text != known[file.absolutePath]
        }
        if (!edited) return false
        val before = _settings.value
        val result = read(layout, before)
        apply(result, before)
        persist(layout, result)
        result != before
    }

    /** Blocks until pending writes are on disk (tests, and before copying the folder). */
    fun flush() = ConfigWriter.awaitIdle()

    /** Publishes [next] (except while the store is still being constructed) and applies its system overrides. */
    private fun apply(next: XrossSettings, previous: XrossSettings?) {
        if (previous == null || next.systemOverrides != previous.systemOverrides) {
            PlatformRegistry.applyOverrides(next.systemOverrides)
        }
        if (previous != null) _settings.value = next
    }

    private fun open(at: XrossLayout): XrossSettings {
        ConfigWriter.awaitIdle()
        val migrated = if (at.configFiles.none { it.isFile }) legacySettings() else null
        val loaded = migrated ?: read(at, XrossSettings())
        apply(loaded, null)
        persist(at, loaded)
        if (migrated != null) {
            // Clear the old preferences only once the JSON files are safely on disk.
            ConfigWriter.awaitIdle()
            if (at.configFiles.all { it.isFile }) legacy.edit { clear().putBoolean(KEY_MIGRATED, true) }
        }
        return loaded
    }

    /**
     * Reads the three files onto [base]. A missing file keeps [base]'s part; an unreadable one is
     * kept aside as `<name>.bad` (so a hand edit with a typo isn't lost) and its part keeps [base].
     */
    private fun read(at: XrossLayout, base: XrossSettings): XrossSettings {
        var s = base
        s = readOne(at.settingsFile, s, ConfigCodec::decodeSettings)
        s = readOne(at.controllersFile, s, ConfigCodec::decodeControllers)
        s = readOne(at.systemsFile, s, ConfigCodec::decodeSystems)
        return s
    }

    private fun readOne(file: File, base: XrossSettings, decode: (XrossSettings, String) -> XrossSettings): XrossSettings {
        val text = try {
            AtomicFiles.readText(file)
        } catch (_: IOException) {
            null
        } ?: return base
        return try {
            decode(base, text).also { known[file.absolutePath] = text }
        } catch (_: SerializationException) {
            quarantine(file)
            base
        } catch (_: IllegalArgumentException) {
            quarantine(file)
            base
        }
    }

    private fun quarantine(file: File) {
        val bad = File(file.parentFile, file.name + BAD_SUFFIX)
        bad.delete()
        if (!file.renameTo(bad)) file.delete()
        known.remove(file.absolutePath)
    }

    /** Queues a write for each file whose content changed. */
    private fun persist(at: XrossLayout, s: XrossSettings) {
        write(at.settingsFile, ConfigCodec.encodeSettings(s))
        write(at.controllersFile, ConfigCodec.encodeControllers(s))
        write(at.systemsFile, ConfigCodec.encodeSystems(s))
    }

    private fun write(file: File, text: String) {
        val key = file.absolutePath
        if (known[key] == text && file.isFile) return
        known[key] = text
        ConfigWriter.write(file, text)
    }

    /** Settings saved by versions before the JSON files, or null when there are none to migrate. */
    private fun legacySettings(): XrossSettings? {
        if (legacy.getBoolean(KEY_MIGRATED, false)) return null
        if (legacy.all.isEmpty()) return null
        return loadLegacy(legacy)
    }

    internal companion object {
        /** SharedPreferences file of versions before the JSON config files; emptied after migration. */
        const val LEGACY_PREFS = "xross_settings"
        const val KEY_MIGRATED = "migrated_to_json"
        const val BAD_SUFFIX = ".bad"

        const val KEY_FOLDERS = "rom_folders"
        const val KEY_AUTO_DETECT = "auto_detect_folders"
        const val KEY_KNOWN_AUTO = "known_auto_folders"
        const val KEY_CONFIRM = "confirm_layout"
        const val KEY_DEADZONE = "deadzone"
        const val KEY_AXIAL_DEADZONE = "axial_deadzone"
        const val KEY_REMAP = "key_remap"
        const val KEY_PROFILES = "controller_profiles"
        const val KEY_EMULATORS = "emulator_choice"
        const val KEY_WAVE = "wave_enabled"
        const val KEY_WAVE_SPEED = "wave_speed"
        const val KEY_THEME = "theme"
        const val KEY_BUTTON_HINTS = "show_button_hints"
        const val KEY_SFX = "sound_effects"
        const val KEY_SFX_VOLUME = "sound_volume"
        const val KEY_BOOT_SOUND = "boot_sound"
        const val KEY_GAMEBOOT_SOUND = "gameboot_sound"
        const val KEY_HASH_LARGE = "hash_large_images"
        const val KEY_DAT_FOLDER = "dat_folder"
        const val KEY_SCRAPE_AUTO = "scrape_automatically"
        const val KEY_SCRAPE_WIFI = "scrape_wifi_only"
        const val KEY_SCRAPE_VIDEOS = "scrape_videos"
        const val KEY_SCRAPE_REGION = "scrape_region"
        const val KEY_SCRAPE_LANGUAGE = "scrape_language"
        const val KEY_HASHEOUS = "hasheous_enabled"
        const val KEY_SLIDESHOW = "slideshow_seconds"
        const val KEY_RESUME_MUSIC = "resume_music_after_games"
        const val KEY_VIDEO_LOOP = "video_loop"
        const val KEY_ALL_GAMES_FOLDER = "all_games_folder"

        /** Reads settings the way versions before the JSON config files stored them. */
        fun loadLegacy(prefs: SharedPreferences) = XrossSettings(
            romFolders = prefs.getString(KEY_FOLDERS, null)
                ?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
            autoDetectFolders = prefs.getBoolean(KEY_AUTO_DETECT, true),
            knownAutoFolders = prefs.getString(KEY_KNOWN_AUTO, null)
                ?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
            confirmLayout = enumOrDefault(prefs.getString(KEY_CONFIRM, null), ConfirmLayout.AUTO),
            deadzone = prefs.getFloat(KEY_DEADZONE, 0.35f),
            axialDeadzone = prefs.getFloat(KEY_AXIAL_DEADZONE, 0f),
            keyRemap = decodeRemap(decodeMap(prefs.getString(KEY_REMAP, null))),
            controllerProfiles = decodeProfiles(prefs.getString(KEY_PROFILES, null)),
            emulatorChoice = decodeMap(prefs.getString(KEY_EMULATORS, null)),
            waveEnabled = prefs.getBoolean(KEY_WAVE, true),
            waveSpeed = prefs.getFloat(KEY_WAVE_SPEED, 1f),
            theme = prefs.getString(KEY_THEME, null) ?: "LAVENDER",
            showButtonHints = prefs.getBoolean(KEY_BUTTON_HINTS, true),
            soundEffects = prefs.getBoolean(KEY_SFX, true),
            soundVolume = prefs.getFloat(KEY_SFX_VOLUME, 0.75f),
            bootSound = prefs.getBoolean(KEY_BOOT_SOUND, true),
            gameBootSound = prefs.getBoolean(KEY_GAMEBOOT_SOUND, true),
            hashLargeImages = prefs.getBoolean(KEY_HASH_LARGE, false),
            datFolder = prefs.getString(KEY_DAT_FOLDER, null).orEmpty(),
            scrapeAutomatically = prefs.getBoolean(KEY_SCRAPE_AUTO, false),
            scrapeWifiOnly = prefs.getBoolean(KEY_SCRAPE_WIFI, true),
            scrapeVideos = prefs.getBoolean(KEY_SCRAPE_VIDEOS, false),
            scrapeRegion = prefs.getString(KEY_SCRAPE_REGION, null) ?: "us",
            scrapeLanguage = prefs.getString(KEY_SCRAPE_LANGUAGE, null) ?: "en",
            hasheousEnabled = prefs.getBoolean(KEY_HASHEOUS, true),
            slideshowSeconds = prefs.getInt(KEY_SLIDESHOW, 5),
            resumeMusicAfterGames = prefs.getBoolean(KEY_RESUME_MUSIC, true),
            videoLoop = prefs.getBoolean(KEY_VIDEO_LOOP, false),
            allGamesFolder = prefs.getBoolean(KEY_ALL_GAMES_FOLDER, true),
        )

        // Keys and values here never contain '=' or newlines (ints, enum names, ids).
        fun encodeMap(map: Map<String, String>) = map.entries.joinToString("\n") { "${it.key}=${it.value}" }

        fun decodeMap(raw: String?): Map<String, String> = raw.orEmpty().lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()

        fun encodeRemap(remap: Map<Int, NavAction>): Map<String, String> =
            remap.map { (k, v) -> k.toString() to v.name }.toMap()

        /** Drops entries whose key code or action no longer parses (renamed actions, corruption). */
        fun decodeRemap(raw: Map<String, String>): Map<Int, NavAction> = raw.mapNotNull { (k, v) ->
            val code = k.toIntOrNull() ?: return@mapNotNull null
            val action = runCatching { NavAction.valueOf(v) }.getOrNull() ?: return@mapNotNull null
            code to action
        }.toMap()

        /**
         * Profiles as JSON, because device names are free text (any character, including the
         * separators the line format above relies on):
         * `{"054c:0ce6": {"name": "DualSense Wireless Controller", "remap": {"96": "CONFIRM"}}}`
         */
        fun encodeProfiles(profiles: Map<String, ControllerProfile>): String {
            val root = JSONObject()
            for ((id, profile) in profiles) {
                val remap = JSONObject()
                for ((code, action) in profile.remap) remap.put(code.toString(), action.name)
                root.put(id, JSONObject().put("name", profile.name).put("remap", remap))
            }
            return root.toString()
        }

        /** Unreadable data yields no profiles rather than a crash; the global mapping still applies. */
        fun decodeProfiles(raw: String?): Map<String, ControllerProfile> {
            if (raw.isNullOrBlank()) return emptyMap()
            val root = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return emptyMap()
            }
            val out = LinkedHashMap<String, ControllerProfile>()
            for (id in root.keys()) {
                if (ControllerKey.parse(id) == null) continue
                val entry = root.optJSONObject(id) ?: continue
                val remapJson = entry.optJSONObject("remap") ?: continue
                val pairs = HashMap<String, String>()
                for (code in remapJson.keys()) pairs[code] = remapJson.optString(code)
                val remap = decodeRemap(pairs)
                if (remap.isNotEmpty()) out[id] = ControllerProfile(entry.optString("name"), remap)
            }
            return out
        }

        inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
            name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
    }
}
