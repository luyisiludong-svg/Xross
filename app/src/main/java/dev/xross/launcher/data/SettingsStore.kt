package dev.xross.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.input.ControllerKey
import dev.xross.launcher.input.ControllerProfile
import dev.xross.launcher.input.NavAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject

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
    val waveSpeed: Float = 1f,
    /** [dev.xross.launcher.theme.XrossTheme] name. */
    val theme: String = "LAVENDER",
    /** The pill of button hints in the bottom corner of the home menu. */
    val showButtonHints: Boolean = true,
    val soundEffects: Boolean = true,
    val soundVolume: Float = 0.75f,
    val bootSound: Boolean = true,
    val gameBootSound: Boolean = true,
    /** Fully hash disc images over the size limit (slow on SD cards). */
    val hashLargeImages: Boolean = false,
    /** Folder DATs are imported from; empty means [dev.xross.launcher.storage.XrossPaths.defaultDatFolder]. */
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
)

/** SharedPreferences-backed settings exposed as a StateFlow. */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("xross_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<XrossSettings> = _settings.asStateFlow()
    val current: XrossSettings get() = _settings.value

    fun update(transform: (XrossSettings) -> XrossSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        save(next)
        _settings.value = next
    }

    private fun load() = XrossSettings(
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
    )

    private fun save(s: XrossSettings) = prefs.edit {
        putString(KEY_FOLDERS, s.romFolders.joinToString("\n"))
        putBoolean(KEY_AUTO_DETECT, s.autoDetectFolders)
        putString(KEY_KNOWN_AUTO, s.knownAutoFolders.joinToString("\n"))
        putString(KEY_CONFIRM, s.confirmLayout.name)
        putFloat(KEY_DEADZONE, s.deadzone)
        putFloat(KEY_AXIAL_DEADZONE, s.axialDeadzone)
        putString(KEY_REMAP, encodeMap(encodeRemap(s.keyRemap)))
        putString(KEY_PROFILES, encodeProfiles(s.controllerProfiles))
        putString(KEY_EMULATORS, encodeMap(s.emulatorChoice))
        putBoolean(KEY_WAVE, s.waveEnabled)
        putFloat(KEY_WAVE_SPEED, s.waveSpeed)
        putString(KEY_THEME, s.theme)
        putBoolean(KEY_BUTTON_HINTS, s.showButtonHints)
        putBoolean(KEY_SFX, s.soundEffects)
        putFloat(KEY_SFX_VOLUME, s.soundVolume)
        putBoolean(KEY_BOOT_SOUND, s.bootSound)
        putBoolean(KEY_GAMEBOOT_SOUND, s.gameBootSound)
        putBoolean(KEY_HASH_LARGE, s.hashLargeImages)
        putString(KEY_DAT_FOLDER, s.datFolder)
        putBoolean(KEY_SCRAPE_AUTO, s.scrapeAutomatically)
        putBoolean(KEY_SCRAPE_WIFI, s.scrapeWifiOnly)
        putBoolean(KEY_SCRAPE_VIDEOS, s.scrapeVideos)
        putString(KEY_SCRAPE_REGION, s.scrapeRegion)
        putString(KEY_SCRAPE_LANGUAGE, s.scrapeLanguage)
        putBoolean(KEY_HASHEOUS, s.hasheousEnabled)
        putInt(KEY_SLIDESHOW, s.slideshowSeconds)
        putBoolean(KEY_RESUME_MUSIC, s.resumeMusicAfterGames)
        putBoolean(KEY_VIDEO_LOOP, s.videoLoop)
    }

    internal companion object {
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
