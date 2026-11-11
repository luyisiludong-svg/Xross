package dev.xross.launcher.storage

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * The folders under one Xross root. Everything portable lives here, so copying the folder to
 * another device (or moving the SD card) carries the setup with it:
 *
 * ```
 * Xross/
 *   config/   settings.json, controllers.json, systems.json
 *   data/     xross.db (a backup snapshot; the live database stays in app-private storage),
 *             retroachievements.json (public profile cache; the API key is never here)
 *   dats/     No-Intro / Redump DATs
 *   media/    scraped art and videos, avatars/<user>.png (.nomedia)
 *   roms/     optional ROM library, one folder per system
 *   themes/   themes and sounds (.nomedia)
 * ```
 *
 * [portable] is false while Xross can't write shared storage yet (no All files access): the same
 * layout then lives in app-private storage, and moves to shared storage once access is granted.
 */
data class XrossLayout(val root: File, val portable: Boolean) {
    val config: File get() = File(root, CONFIG)
    val data: File get() = File(root, DATA)
    val dats: File get() = File(root, DATS)
    val media: File get() = File(root, MEDIA)
    val roms: File get() = File(root, ROMS)
    val themes: File get() = File(root, THEMES)

    val settingsFile: File get() = File(config, "settings.json")
    val controllersFile: File get() = File(config, "controllers.json")
    val systemsFile: File get() = File(config, "systems.json")
    val configFiles: List<File> get() = listOf(settingsFile, controllersFile, systemsFile)

    /** Backup snapshot of the library database. */
    val databaseBackup: File get() = File(data, "xross.db")

    /** Cached RetroAchievements avatars, `<username>.png`. */
    val avatars: File get() = File(media, "avatars")

    /** Public RetroAchievements profile cache (no API key), shown offline. */
    val profileCache: File get() = File(data, "retroachievements.json")

    companion object {
        const val CONFIG = "config"
        const val DATA = "data"
        const val DATS = "dats"
        const val MEDIA = "media"
        const val ROMS = "roms"
        const val THEMES = "themes"
        const val NOMEDIA = ".nomedia"

        /** Folders that hold images and audio and must never be indexed by MediaStore. */
        val NOMEDIA_DIRS = listOf(MEDIA, THEMES)

        /** Created after the [NOMEDIA_DIRS]. */
        val OTHER_DIRS = listOf(CONFIG, DATA, DATS, ROMS)
    }
}

/**
 * Resolves and prepares the Xross root.
 *
 * Shared storage is used as soon as Xross can write it: `Xross/` on the primary volume, or on the
 * volume picked with [chooseVolume] (an SD card) while it is mounted and writable. Until then, and
 * whenever the chosen location fails, the root falls back to app-private storage so the app keeps
 * working (settings still persist; they move with the next [refresh] that finds shared storage).
 *
 * Every resolved root is bootstrapped first: `.nomedia` markers in `media/` and `themes/` are
 * created before those folders can hold anything (see [bootstrap]).
 */
class XrossEnvironment(
    context: Context,
    private val writeAccess: (Context) -> Boolean = StorageAccess::canWrite,
    private val primaryRoot: () -> File = XrossPaths::root,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** App-private fallback, used before shared storage can be written. */
    val fallbackRoot: File = File(appContext.filesDir, XrossPaths.ROOT_NAME)

    private val _layout = MutableStateFlow(resolve())
    val layout: StateFlow<XrossLayout> = _layout.asStateFlow()
    val current: XrossLayout get() = _layout.value

    /** Volume root the user picked for the Xross folder; null means primary storage. */
    val chosenVolume: File? get() = prefs.getString(KEY_VOLUME, null)?.let(::File)

    /**
     * Re-resolves the root (after a permission grant, a volume being mounted or removed, or a
     * different volume being chosen). Returns true when the layout changed.
     */
    fun refresh(): Boolean {
        val next = resolve()
        if (next == _layout.value) return false
        _layout.value = next
        return true
    }

    /** Keeps the Xross folder on [volumeRoot] (null: primary storage). Returns true when the layout changed. */
    fun chooseVolume(volumeRoot: File?): Boolean {
        prefs.edit { if (volumeRoot == null) remove(KEY_VOLUME) else putString(KEY_VOLUME, volumeRoot.path) }
        return refresh()
    }

    private fun resolve(): XrossLayout {
        if (writeAccess(appContext)) {
            val primary = primaryRoot()
            val chosen = chosenVolume?.let { File(it, XrossPaths.ROOT_NAME) }
            for (candidate in listOfNotNull(chosen, primary).distinct()) {
                if (bootstrap(candidate)) return XrossLayout(candidate, portable = true)
            }
        }
        bootstrap(fallbackRoot)
        return XrossLayout(fallbackRoot, portable = false)
    }

    companion object {
        private const val PREFS = "xross_environment"
        private const val KEY_VOLUME = "volume_root"

        /**
         * Creates the Xross folder tree under [root]. The `.nomedia` markers are created right
         * after their folders and before anything else, so nothing written to `media/` or
         * `themes/` is ever picked up by MediaStore. Returns false when [root] can't be written
         * (unmounted card, read-only volume, missing permission).
         */
        fun bootstrap(root: File): Boolean {
            for (name in XrossLayout.NOMEDIA_DIRS) {
                val dir = File(root, name)
                if (!ensureDir(dir)) return false
                if (!ensureFile(File(dir, XrossLayout.NOMEDIA))) return false
            }
            for (name in XrossLayout.OTHER_DIRS) {
                if (!ensureDir(File(root, name))) return false
            }
            return root.canWrite()
        }

        private fun ensureDir(dir: File): Boolean = dir.isDirectory || dir.mkdirs() || dir.isDirectory

        private fun ensureFile(file: File): Boolean = file.isFile || try {
            file.createNewFile() || file.isFile
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
