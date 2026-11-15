package dev.xross.launcher.state

import android.util.Log
import dev.xross.launcher.BuildConfig
import dev.xross.launcher.audio.UiSound
import dev.xross.launcher.audio.UiSounds
import dev.xross.launcher.data.BackupInfo
import dev.xross.launcher.data.BackupResult
import dev.xross.launcher.data.DatRepository
import dev.xross.launcher.data.DatabaseBackupManager
import dev.xross.launcher.data.FingerprintQueue
import dev.xross.launcher.data.GameRepository
import dev.xross.launcher.data.SettingsStore
import dev.xross.launcher.data.XrossDatabase
import dev.xross.launcher.media.MediaRepository
import dev.xross.launcher.scrape.AssetCache
import dev.xross.launcher.scrape.ScrapeOrchestrator
import dev.xross.launcher.storage.StorageVolumes
import dev.xross.launcher.storage.VolumeEvent
import dev.xross.launcher.storage.XrossEnvironment
import dev.xross.launcher.storage.XrossLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the library's background work knows, for the menu ([MenuSnapshot]) and the busy indicator. */
data class LibraryStatus(
    /** Shared storage can be read (All files access). */
    val storageGranted: Boolean = false,
    /** The folders the library scans (see [LibraryFolders]). */
    val folders: List<FolderEntry> = emptyList(),
    /** Roots of the mounted volumes. */
    val mountedRoots: List<String> = emptyList(),
    /** The last scan's summary ("1,204 games · +3 −0"), or "Scanning…". */
    val scanStatus: String? = null,
    /** The Xross folder, its backups and a restorable one. */
    val portable: PortableSetup = PortableSetup(),
    /** A library scan is running (fingerprinting is tracked by [FingerprintQueue.current]). */
    val scanning: Boolean = false,
    /** DATs are being imported or matched, or a scraper lookup runs ([LibraryJobs.datWork]). */
    val datWork: Boolean = false,
) {
    /** This status in the menu's snapshot. */
    fun into(snapshot: MenuSnapshot): MenuSnapshot = snapshot.copy(
        storageGranted = storageGranted,
        folders = folders,
        mountedRoots = mountedRoots,
        scanStatus = scanStatus,
        portable = portable,
    )
}

/**
 * The library's background work, split out of [XmbViewModel] (Architecture Assessment §7.1, step
 * 6): storage access, the folder plan and the library scan, storage hot-swap, fingerprinting and
 * automatic scraping while Xross is in front, DAT jobs, and the Xross folder with its backups
 * (following it to another drive, backup after leaving, restore).
 *
 * The view model calls the lifecycle hooks ([setForeground], [refresh], [resumeBackgroundWork],
 * [onBackground], [onStopped]) and routes the library commands here; [state] goes into the menu.
 */
class LibraryJobs(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val sounds: UiSounds,
    private val environment: XrossEnvironment,
    private val volumes: StorageVolumes,
    private val games: GameRepository,
    private val fingerprints: FingerprintQueue,
    private val scraper: ScrapeOrchestrator,
    private val dats: DatRepository,
    private val database: XrossDatabase,
    private val backups: DatabaseBackupManager,
    private val assets: AssetCache,
    private val media: MediaRepository,
    private val folders: FolderPickers,
    /** Whether shared storage can be read; a seam so tests don't depend on platform app-ops. */
    private val storageAccess: () -> Boolean,
    private val host: Host,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** What the controller needs from the menu around it. */
    interface Host {
        fun toast(message: String)
        /** The error sound, then [message]. */
        fun fail(message: String)
        fun emit(effect: UiEffect)
        /** Opens an Options menu (the restore confirmation). */
        fun showOptions(menu: OptionsMenuState)
        /** The Xross folder moved: what lives in it (the RetroAchievements profile cache) is read again. */
        suspend fun onXrossFolderMoved()
    }

    private val _state = MutableStateFlow(LibraryStatus())
    val state: StateFlow<LibraryStatus> = _state.asStateFlow()

    private var foreground = false
    private var environmentLoaded = false
    private var scanJob: Job? = null
    private var datJob: Job? = null
    private var backupJob: Job? = null
    private var volumeJob: Job? = null

    /** A volume was mounted or removed while a game was in front; rescan on the next resume. */
    private var volumesChangedInBackground = false

    /** Last known label per volume root, so a removal can still name the drive. */
    private val volumeLabels = HashMap<String, String>()

    private val scanRunning: Boolean get() = scanJob?.isActive == true

    /** Keeps the backup status in [state]. */
    fun watch() {
        backups.watch()
        scope.launch { backups.status.collect { status -> _state.update { it.copy(portable = it.portable.copy(backup = status)) } } }
    }

    /** The library commands; false for any other command. */
    fun onCommand(command: Command): Boolean {
        when (command) {
            Command.RequestStorageAccess ->
                if (_state.value.storageGranted) host.toast("Already granted") else host.emit(UiEffect.RequestStorageAccess)
            Command.Rescan -> refreshFoldersAndRescan()
            Command.ImportDats -> importDats()
            Command.MatchDats -> datWork { gamesVerified(dats.matchAll().matched) }
            is Command.RemoveDat -> datWork {
                dats.remove(command.id)
                "DAT removed"
            }
            Command.ToggleLargeImageHashing -> {
                settings.update { it.copy(hashLargeImages = !it.hashLargeImages) }
                if (foreground && !scanRunning) fingerprints.restart(scope)
            }
            Command.RecheckFingerprints -> {
                if (!_state.value.storageGranted) {
                    host.fail("Grant All files access first")
                } else if (!scanRunning) {
                    fingerprints.restart(scope)
                    host.toast("Checking for files without fingerprints")
                }
            }
            Command.CycleXrossVolume -> cycleXrossVolume()
            Command.BackupNow -> scope.launch {
                when (val result = backups.backupNow()) {
                    is BackupResult.Done -> {
                        sounds.play(UiSound.OK)
                        host.toast("Library backed up (${countOf(result.info.games, "game")})")
                    }
                    is BackupResult.Skipped -> host.toast(result.reason)
                    is BackupResult.Failed -> {
                        sounds.play(UiSound.ERROR)
                        host.toast("Backup failed: ${result.reason}")
                    }
                }
                publishPortable()
            }
            Command.ConfirmRestore -> confirmRestore()
            Command.RestoreBackup -> restoreBackup()
            else -> return false
        }
        return true
    }

    // region Lifecycle

    /** The Activity resumed or paused; scans finishing in the background leave hashing stopped. */
    fun setForeground(inFront: Boolean) {
        foreground = inFront
    }

    /**
     * Re-reads storage access and the library's folders. Scans quietly on the first call, and with
     * a toast when access has just been granted. True on the first call (see [syncXrossFolder]).
     */
    fun refresh(): Boolean {
        val wasGranted = _state.value.storageGranted
        val granted = storageAccess()
        val firstLoad = !environmentLoaded
        environmentLoaded = true
        _state.update { it.copy(storageGranted = granted) }
        refreshFolders()
        if (granted && (firstLoad || !wasGranted)) rescan(quiet = firstLoad)
        return firstLoad
    }

    /** Back in front: the rescan a hot-swap deferred, then fingerprinting and automatic scraping. */
    fun resumeBackgroundWork() {
        if (volumesChangedInBackground) {
            volumesChangedInBackground = false
            refreshFoldersAndRescan(quiet = true)
        }
        if (_state.value.storageGranted && !scanRunning) fingerprints.start(scope)
        if (settings.current.scrapeAutomatically && !scanRunning) scraper.start(scope)
    }

    /** A game or another app is in front: stop background I/O. */
    fun onBackground() {
        foreground = false
        fingerprints.stop()
        scraper.stop()
    }

    /** The launcher is no longer visible: back up the library shortly after, if it changed. */
    fun onStopped() {
        backupJob?.cancel()
        backupJob = scope.launch {
            delay(BACKUP_DELAY_MS)
            backups.backupIfChanged()
            publishPortable()
        }
    }

    // endregion

    // region Folders and scanning

    /**
     * Storage hot-swap (microSD / USB-OTG mounted or removed). In front: rescan quietly after the
     * burst of mount broadcasts settles. With a game running: do nothing now, rescan on return.
     * Navigation state is never touched, and games on a removed volume stay in the library.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onVolumeEvent(event: VolumeEvent) {
        if (!foreground) {
            volumesChangedInBackground = true
            return
        }
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(VOLUME_SETTLE_MS)
            val before = _state.value.mountedRoots.toSet()
            refreshFolders()
            val after = _state.value.mountedRoots.toSet()
            val label = { root: String -> volumeLabels[root] ?: root.substringAfterLast('/') }
            (after - before).firstOrNull()?.let { host.toast("${label(it)} connected") }
            (before - after).firstOrNull()?.let { host.toast("${label(it)} removed — its games are kept") }
            if (_state.value.storageGranted) rescan(quiet = true)
            // Custom Photo/Video folders on that drive appear or disappear.
            media.refresh()
            // The Xross folder itself may have been on that drive.
            syncXrossFolder(firstLoad = false)
        }
    }

    /** Recomputes mounted volumes and the library's folders (see [LibraryFolders]). */
    fun refreshFolders() {
        val granted = _state.value.storageGranted
        val mounted = volumes.volumes()
        mounted.forEach { volumeLabels[it.root.absolutePath] = it.label }
        val roots = mounted.map { it.root.absolutePath }
        val s = settings.current
        // Without storage access nothing can be discovered; keep what we know instead of forgetting it.
        val list = if (granted) {
            val plan = LibraryFolders.plan(
                custom = s.romFolders,
                discovered = if (s.autoDetectFolders) volumes.discoverRomFolders().map { it.absolutePath } else emptyList(),
                knownAuto = s.knownAutoFolders,
                mountedRoots = roots,
                autoDetect = s.autoDetectFolders,
                base = folders.baseRomsFolder(s.baseRomsFolder).absolutePath,
            )
            if (plan.knownAuto != s.knownAutoFolders) settings.update { it.copy(knownAutoFolders = plan.knownAuto) }
            plan.folders
        } else {
            LibraryFolders.plan(
                s.romFolders, emptyList(), s.knownAutoFolders, roots, autoDetect = false,
                base = folders.baseRomsFolder(s.baseRomsFolder).absolutePath,
            ).folders
        }
        _state.update { it.copy(folders = list, mountedRoots = roots) }
    }

    fun refreshFoldersAndRescan(quiet: Boolean = false) {
        refreshFolders()
        rescan(quiet)
    }

    private fun rescan(quiet: Boolean) {
        if (!_state.value.storageGranted) {
            host.fail("Grant All files access first")
            return
        }
        scanJob?.cancel()
        fingerprints.stop() // resume after the scan, against the updated library
        scanJob = scope.launch {
            val self = coroutineContext[Job]
            _state.update { it.copy(scanning = true) }
            try {
                scan(quiet)
            } finally {
                // A newer scan that replaced this one owns the flag now.
                if (scanJob == null || scanJob === self) _state.update { it.copy(scanning = false) }
            }
        }
    }

    /** One library scan: runs the repository scan (on Dispatchers.IO), then reports and resumes hashing. */
    private suspend fun scan(quiet: Boolean) {
        _state.update { it.copy(scanStatus = "Scanning…") }
        val roots = _state.value.folders.map { File(it.path) }
        val started = System.nanoTime()
        val result = runCatching { games.scan(roots) }
        // A newer scan replaced this one: not a failure, nothing to report.
        if (result.exceptionOrNull() is CancellationException) return
        if (BuildConfig.PERF_LOG) {
            val ms = (System.nanoTime() - started) / 1_000_000
            Log.i("XrossScan", "scan ${result.getOrNull() ?: result.exceptionOrNull()} in $ms ms")
        }
        val status = result.fold(
            onSuccess = { "${it.total} games · +${it.added} −${it.removed}" },
            onFailure = { "Scan failed: ${it.message}" },
        )
        _state.update { it.copy(scanStatus = status) }
        if (!quiet || result.isFailure) host.toast(status)
        if (foreground) {
            fingerprints.start(scope)
            if (settings.current.scrapeAutomatically) scraper.start(scope)
        }
    }

    // endregion

    // region DATs

    /** Runs DAT (or scraper lookup) work with the busy indicator on and toasts its summary; one job at a time. */
    fun datWork(work: suspend () -> String) {
        if (datJob?.isActive == true) {
            host.toast("Still working on DATs…")
            return
        }
        datJob = scope.launch {
            _state.update { it.copy(datWork = true) }
            try {
                host.toast(work())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sounds.play(UiSound.ERROR)
                host.toast("DAT error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _state.update { it.copy(datWork = false) }
            }
        }
    }

    /** Imports the DATs in the DAT folder and matches the library against them. */
    fun importDats() {
        if (!_state.value.storageGranted) {
            host.fail("Grant All files access first")
            return
        }
        val dir = folders.datFolder()
        datWork {
            withContext(io) { dir.mkdirs() }
            val result = dats.importFolder(dir)
            val verified = database.metadata().getAll().count { it.matchKind != null }
            when {
                result.failures.isNotEmpty() -> "Couldn't read ${result.failures.first()}"
                result.imported + result.unchanged == 0 -> "No DATs in ${dir.absolutePath}"
                else -> buildList {
                    if (result.imported > 0) add("Imported ${result.imported} DAT" + if (result.imported == 1) "" else "s")
                    if (result.imported == 0) add("DATs up to date")
                    if (result.removed > 0) add("${result.removed} removed")
                    add(gamesVerified(verified))
                }.joinToString(" · ")
            }
        }
    }

    // endregion

    // region Xross folder and backups

    /** Follows the Xross folder in the background; see [syncXrossFolder]. */
    fun launchFolderSync(firstLoad: Boolean) {
        scope.launch { syncXrossFolder(firstLoad) }
    }

    /**
     * Follows the Xross folder: once shared storage can be written it moves out of app storage,
     * and config already there (an SD card from another device, a reinstall) is adopted. Config
     * files edited outside Xross are picked up. On a clean install a backup found in the folder
     * is offered for restore.
     */
    private suspend fun syncXrossFolder(firstLoad: Boolean) {
        val previous = environment.current
        val moved = withContext(io) {
            val changed = environment.refresh()
            if (changed) settings.relocate(environment.current) else settings.reloadIfChanged()
            changed
        }
        if (moved) {
            moveArtFrom(previous)
            host.onXrossFolderMoved()
        }
        publishPortable()
        if (firstLoad || moved) {
            val restorable = backups.findRestorable()
            _state.update { it.copy(portable = it.portable.copy(restorable = restorable)) }
            if (restorable != null && backups.announceOnce(restorable)) {
                host.toast("Found a library backup with ${countOf(restorable.games, "game")} — restore it from Games")
            }
        }
    }

    /** Art follows the Xross folder to its new place, in the background. */
    private fun moveArtFrom(previous: XrossLayout) {
        if (previous.media.absolutePath == environment.current.media.absolutePath) return
        scope.launch {
            val report = runCatching { assets.relocateFrom(previous.media) }.getOrNull() ?: return@launch
            if (report.moved > 0) host.toast("Moved ${countOf(report.moved, "art file")} to the new Xross folder")
        }
    }

    private suspend fun publishPortable() {
        val layout = environment.current
        val onDisk = backups.currentBackup()
        _state.update {
            it.copy(portable = it.portable.copy(root = layout.root.absolutePath, portable = layout.portable, backupOnDisk = onDisk))
        }
    }

    /** Settings ▸ Library ▸ Xross folder: the next writable drive. */
    private fun cycleXrossVolume() {
        val layout = environment.current
        if (!layout.portable) {
            host.emit(UiEffect.RequestStorageAccess)
            return
        }
        scope.launch {
            val mounted = withContext(io) { volumes.volumes() }.sortedByDescending { it.primary }
            if (mounted.size < 2) {
                host.toast("No other drive is connected")
                return@launch
            }
            val previous = environment.current
            val currentVolume = StorageVolumes.volumeRootOf(layout.root.absolutePath)
            val index = mounted.indexOfFirst { it.root.path.trimEnd('/') == currentVolume }
            val next = mounted[(index + 1) % mounted.size]
            val moved = withContext(io) {
                environment.chooseVolume(if (next.primary) null else next.root).also { changed ->
                    if (changed) settings.relocate(environment.current)
                }
            }
            val now = environment.current
            if (moved && now.root.absolutePath.startsWith(next.root.path)) {
                sounds.play(UiSound.OK)
                host.toast("Xross folder: ${now.root.absolutePath}")
                moveArtFrom(previous)
                backups.backupNow()
            } else {
                sounds.play(UiSound.ERROR)
                host.toast("${next.label} can't be written; the Xross folder stays at ${now.root.absolutePath}")
            }
            publishPortable()
        }
    }

    private fun backupToRestore(): BackupInfo? =
        _state.value.portable.restorable ?: _state.value.portable.backupOnDisk

    private fun confirmRestore() {
        val backup = backupToRestore() ?: return host.toast("No library backup in the Xross folder")
        sounds.play(UiSound.OPTION)
        val option = MenuOption(
            "Replace library and restart",
            detail = "${countOf(backup.games, "game")} · saved ${MenuTree.formatTime(backup.modifiedAt)}",
            command = Command.RestoreBackup,
        )
        host.showOptions(OptionsMenuState.open("Restore library backup", listOf(option)))
    }

    private fun restoreBackup() {
        val backup = backupToRestore() ?: return
        scope.launch {
            fingerprints.stop()
            scraper.stop()
            withContext(io) { settings.flush() }
            when (val result = backups.stageRestore(backup)) {
                is BackupResult.Done -> {
                    sounds.play(UiSound.OK)
                    host.toast("Restoring ${countOf(result.info.games, "game")}…")
                    delay(RESTART_DELAY_MS)
                    host.emit(UiEffect.RestartApp)
                }
                is BackupResult.Skipped -> host.toast(result.reason)
                is BackupResult.Failed -> {
                    sounds.play(UiSound.ERROR)
                    host.toast("Restore failed: ${result.reason}")
                }
            }
        }
    }

    // endregion

    private companion object {
        const val VOLUME_SETTLE_MS = 1200L
        const val BACKUP_DELAY_MS = 4_000L
        const val RESTART_DELAY_MS = 900L

        fun countOf(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

        fun gamesVerified(n: Int) = if (n == 1) "1 game verified" else "$n games verified"
    }
}
