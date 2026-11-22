package dev.xross.launcher.state

import android.net.Uri
import dev.xross.launcher.audio.UiSound
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.data.SettingsStore
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.media.MediaFolders
import dev.xross.launcher.media.MediaRepository
import dev.xross.launcher.storage.SystemFolders
import dev.xross.launcher.storage.XrossLayout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The folders the library and the media categories read, split out of [XmbViewModel]
 * (Architecture Assessment §7.1, step 6): ROM folders and auto-detect, the base ROMs folder and
 * its system folders, the DAT folder, and the Photo, Video and Music folders. It answers the folder
 * pickers, runs the Settings ▸ Library and Settings ▸ Media folder commands, and keeps the media
 * library on the folder lists in the settings.
 *
 * Scanning is not its job: when the ROM folders change it asks the [Host] to rescan, and a new DAT
 * folder is imported by the host.
 */
class FolderPickers(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val sounds: UiSounds,
    private val media: MediaRepository,
    /** A picked document tree as a local path; null when it isn't one (a cloud provider). */
    private val treeToPath: (Uri) -> String?,
    /** The Xross folder in use, whose `roms/` and `dats/` are the defaults. */
    private val layout: () -> XrossLayout,
    private val host: Host,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** What the controller needs from the menu around it. */
    interface Host {
        fun toast(message: String)
        fun emit(effect: UiEffect)
        /** Shared storage can be read (All files access). */
        fun storageGranted(): Boolean
        /** Recompute the library's folders (mounted volumes, auto-detected ones). */
        fun refreshFolders()
        /** Recompute the library's folders and scan them. */
        fun refreshFoldersAndRescan()
        /** Import the DATs in the DAT folder (just changed). */
        fun importDats()
    }

    /** Applies the folder lists to the media library now, then whenever the settings change. */
    fun watch() {
        syncMediaFolders(settings.current)
        scope.launch { settings.settings.collect(::syncMediaFolders) }
    }

    /** The base ROMs folder setting, or `roms/` in the Xross folder in use. */
    fun baseRomsFolder(setting: String = settings.current.baseRomsFolder): File =
        if (setting.isBlank()) layout().roms else File(setting)

    /** The DAT folder setting, or `dats/` in the Xross folder in use. */
    fun datFolder(setting: String = settings.current.datFolder): File =
        if (setting.isBlank()) layout().dats else File(setting)

    /** The folder commands; false for any other command. */
    fun onCommand(command: Command): Boolean {
        when (command) {
            Command.AddFolder -> host.emit(UiEffect.PickFolder)
            is Command.RemoveFolder -> {
                settings.update { it.copy(romFolders = it.romFolders - command.path) }
                host.toast("Removed ${command.path.substringAfterLast('/')}")
                host.refreshFoldersAndRescan()
            }
            Command.ToggleAutoDetect -> {
                settings.update { it.copy(autoDetectFolders = !it.autoDetectFolders) }
                host.refreshFoldersAndRescan()
            }
            Command.PickBaseRomsFolder -> host.emit(UiEffect.PickBaseRomsFolder)
            Command.ResetBaseRomsFolder -> {
                settings.update { it.copy(baseRomsFolder = "") }
                host.toast("Base ROMs folder: ${baseRomsFolder("").absolutePath}")
                host.refreshFoldersAndRescan()
            }
            Command.CreateSystemFolders -> createSystemFolders()
            Command.PickDatFolder -> host.emit(UiEffect.PickDatFolder)
            is Command.AddMediaFolder -> host.emit(UiEffect.PickMediaFolder(command.kind))
            is Command.RemoveMediaFolder -> {
                updateMediaFolders(command.kind) { it - command.path }
                host.toast("Removed ${command.path.substringAfterLast('/')}")
            }
            is Command.ClearMediaFolders -> {
                updateMediaFolders(command.kind) { emptyList() }
                host.toast("${command.kind.label} shows everything again")
            }
            else -> return false
        }
        return true
    }

    // region Picker answers

    /** A ROM folder to add to the library. */
    fun onFolderPicked(uri: Uri?) {
        val path = localPath(uri) ?: return
        settings.update { it.copy(romFolders = (it.romFolders + path).distinct()) }
        host.refreshFoldersAndRescan()
    }

    fun onBaseRomsFolderPicked(uri: Uri?) {
        val path = localPath(uri) ?: return
        settings.update { it.copy(baseRomsFolder = path) }
        host.toast("Base ROMs folder: $path")
        host.refreshFoldersAndRescan()
    }

    fun onDatFolderPicked(uri: Uri?) {
        val path = localPath(uri) ?: return
        settings.update { it.copy(datFolder = path) }
        host.importDats()
    }

    fun onMediaFolderPicked(kind: MediaFolderKind, uri: Uri?) {
        val path = localPath(uri)?.trimEnd('/') ?: return
        updateMediaFolders(kind) { (it + path).distinct() }
        host.toast("${kind.label} now includes $path")
    }

    /** The picked tree as a path; null (with a toast when there was a pick) when there is none. */
    private fun localPath(uri: Uri?): String? {
        uri ?: return null
        return treeToPath(uri) ?: run {
            host.toast("That location isn't a local folder")
            null
        }
    }

    // endregion

    /** Settings ▸ Library ▸ Create system folders: one folder per platform in the base ROMs folder. */
    private fun createSystemFolders() {
        if (!host.storageGranted() || !layout().portable) {
            host.emit(UiEffect.RequestStorageAccess)
            return
        }
        scope.launch {
            val base = baseRomsFolder()
            val result = withContext(io) { SystemFolders.create(base) }
            when {
                !result.ok -> {
                    sounds.play(UiSound.ERROR)
                    host.toast("Couldn't create ${countOf(result.failed.size, "folder")} in ${base.absolutePath}")
                }
                result.created.isEmpty() -> host.toast("All ${result.existing.size} system folders are already in ${base.absolutePath}")
                else -> {
                    sounds.play(UiSound.OK)
                    host.toast("Created ${countOf(result.created.size, "folder")} in ${base.absolutePath}")
                }
            }
            host.refreshFolders()
        }
    }

    private fun updateMediaFolders(kind: MediaFolderKind, transform: (List<String>) -> List<String>) {
        settings.update {
            when (kind) {
                MediaFolderKind.PHOTO -> it.copy(photoFolders = transform(it.photoFolders))
                MediaFolderKind.VIDEO -> it.copy(videoFolders = transform(it.videoFolders))
                MediaFolderKind.MUSIC -> it.copy(musicFolders = transform(it.musicFolders))
            }
        }
    }

    /** Applies the folder lists to the media library and reloads it when they changed. */
    private fun syncMediaFolders(s: XrossSettings) {
        if (media.setFolders(MediaFolders(s.photoFolders, s.videoFolders, s.musicFolders))) {
            scope.launch { media.refresh() }
        }
    }

    private companion object {
        fun countOf(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
    }
}
