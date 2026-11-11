package dev.xross.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.input.NavAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class XrossSettings(
    val romFolders: List<String> = emptyList(),
    val autoDetectFolders: Boolean = true,
    /** Auto-detected folders seen so far, kept so games survive their volume being unplugged. */
    val knownAutoFolders: List<String> = emptyList(),
    val confirmLayout: ConfirmLayout = ConfirmLayout.AUTO,
    val deadzone: Float = 0.35f,
    val keyRemap: Map<Int, NavAction> = emptyMap(),
    /** platformId → [dev.xross.launcher.storage.EmulatorOption.key] */
    val emulatorChoice: Map<String, String> = emptyMap(),
    val waveEnabled: Boolean = true,
    val waveSpeed: Float = 1f,
    /** [dev.xross.launcher.theme.XrossTheme] name. */
    val theme: String = "LAVENDER",
    val soundEffects: Boolean = true,
    val soundVolume: Float = 0.75f,
    val bootSound: Boolean = true,
    val gameBootSound: Boolean = true,
    /** Fully hash disc images over the size limit (slow on SD cards). */
    val hashLargeImages: Boolean = false,
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
        keyRemap = decodeMap(prefs.getString(KEY_REMAP, null)).mapNotNull { (k, v) ->
            val code = k.toIntOrNull() ?: return@mapNotNull null
            val action = runCatching { NavAction.valueOf(v) }.getOrNull() ?: return@mapNotNull null
            code to action
        }.toMap(),
        emulatorChoice = decodeMap(prefs.getString(KEY_EMULATORS, null)),
        waveEnabled = prefs.getBoolean(KEY_WAVE, true),
        waveSpeed = prefs.getFloat(KEY_WAVE_SPEED, 1f),
        theme = prefs.getString(KEY_THEME, null) ?: "LAVENDER",
        soundEffects = prefs.getBoolean(KEY_SFX, true),
        soundVolume = prefs.getFloat(KEY_SFX_VOLUME, 0.75f),
        bootSound = prefs.getBoolean(KEY_BOOT_SOUND, true),
        gameBootSound = prefs.getBoolean(KEY_GAMEBOOT_SOUND, true),
        hashLargeImages = prefs.getBoolean(KEY_HASH_LARGE, false),
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
        putString(KEY_REMAP, encodeMap(s.keyRemap.map { (k, v) -> k.toString() to v.name }.toMap()))
        putString(KEY_EMULATORS, encodeMap(s.emulatorChoice))
        putBoolean(KEY_WAVE, s.waveEnabled)
        putFloat(KEY_WAVE_SPEED, s.waveSpeed)
        putString(KEY_THEME, s.theme)
        putBoolean(KEY_SFX, s.soundEffects)
        putFloat(KEY_SFX_VOLUME, s.soundVolume)
        putBoolean(KEY_BOOT_SOUND, s.bootSound)
        putBoolean(KEY_GAMEBOOT_SOUND, s.gameBootSound)
        putBoolean(KEY_HASH_LARGE, s.hashLargeImages)
        putInt(KEY_SLIDESHOW, s.slideshowSeconds)
        putBoolean(KEY_RESUME_MUSIC, s.resumeMusicAfterGames)
        putBoolean(KEY_VIDEO_LOOP, s.videoLoop)
    }

    private companion object {
        const val KEY_FOLDERS = "rom_folders"
        const val KEY_AUTO_DETECT = "auto_detect_folders"
        const val KEY_KNOWN_AUTO = "known_auto_folders"
        const val KEY_CONFIRM = "confirm_layout"
        const val KEY_DEADZONE = "deadzone"
        const val KEY_REMAP = "key_remap"
        const val KEY_EMULATORS = "emulator_choice"
        const val KEY_WAVE = "wave_enabled"
        const val KEY_WAVE_SPEED = "wave_speed"
        const val KEY_THEME = "theme"
        const val KEY_SFX = "sound_effects"
        const val KEY_SFX_VOLUME = "sound_volume"
        const val KEY_BOOT_SOUND = "boot_sound"
        const val KEY_GAMEBOOT_SOUND = "gameboot_sound"
        const val KEY_HASH_LARGE = "hash_large_images"
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

        inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
            name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
    }
}
