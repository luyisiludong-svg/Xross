package dev.xross.launcher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.FolderPickers
import dev.xross.launcher.state.LibraryJobs
import dev.xross.launcher.state.LibraryStatus
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.OptionsMenuState
import dev.xross.launcher.state.PortableSetup
import dev.xross.launcher.state.UiEffect
import dev.xross.launcher.state.XmbViewModel
import dev.xross.launcher.storage.VolumeEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The library's background work out of [XmbViewModel] (step 6). `HotSwapTest`,
 * `PortableSettingsTest` and `XmbViewModelTest` still drive scans, hot-swap and backups through
 * the view model; these drive [LibraryJobs] on its own with a fake host.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryJobsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container = AppContainer(context)
    private val scope = MainScope()

    @After fun tearDown() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            idle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private class FakeHost : LibraryJobs.Host {
        val toasts = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val effects = mutableListOf<UiEffect>()
        val menus = mutableListOf<OptionsMenuState>()
        var folderMoves = 0
        override fun toast(message: String) { toasts += message }
        override fun fail(message: String) { failures += message }
        override fun emit(effect: UiEffect) { effects += effect }
        override fun showOptions(menu: OptionsMenuState) { menus += menu }
        override suspend fun onXrossFolderMoved() { folderMoves++ }
    }

    private val host = FakeHost()
    private var granted = false

    private val library = LibraryJobs(
        scope = scope,
        settings = container.settings,
        sounds = container.sounds,
        environment = container.environment,
        volumes = container.volumes,
        games = container.games,
        fingerprints = container.fingerprints,
        scraper = container.scraper,
        dats = container.dats,
        database = container.database,
        backups = container.backups,
        assets = container.assets,
        media = container.media,
        folders = FolderPickers(
            scope, container.settings, container.sounds, container.media, { null }, { container.environment.current },
            object : FolderPickers.Host {
                override fun toast(message: String) = Unit
                override fun emit(effect: UiEffect) = Unit
                override fun storageGranted() = granted
                override fun refreshFolders() = Unit
                override fun refreshFoldersAndRescan() = Unit
                override fun importDats() = Unit
            },
        ),
        storageAccess = { granted },
        host = host,
    )

    @Test fun withoutStorageAccessNothingScansAndTheCommandsSayWhy() {
        assertTrue("the first refresh is the first load", library.refresh())
        assertFalse(library.refresh())
        assertFalse(library.state.value.storageGranted)

        library.onCommand(Command.RequestStorageAccess)
        assertEquals(listOf<UiEffect>(UiEffect.RequestStorageAccess), host.effects)
        library.onCommand(Command.Rescan)
        library.onCommand(Command.RecheckFingerprints)
        library.onCommand(Command.ImportDats)
        assertEquals(List(3) { "Grant All files access first" }, host.failures)
        assertFalse(library.state.value.scanning)
        assertFalse(library.onCommand(Command.ScrapeNow))
    }

    @Test fun grantingAccessScansAndReportsTheResult() {
        library.refresh() // first load without access: nothing to scan
        granted = true
        library.refresh()
        assertTrue(library.state.value.storageGranted)
        await("the scan to finish") { !library.state.value.scanning && library.state.value.scanStatus?.contains("games") == true }
        // Access granted after the first load: the scan is announced.
        assertEquals(listOf(library.state.value.scanStatus), host.toasts)

        library.onCommand(Command.RequestStorageAccess)
        assertEquals("Already granted", host.toasts.last())
    }

    @Test fun aHotSwapWhileAGameIsInFrontWaitsForTheReturn() {
        library.refresh()
        library.setForeground(false)
        library.onVolumeEvent(VolumeEvent.Mounted("/storage/1234-ABCD"))
        idle()
        assertEquals(emptyList<String>(), host.failures)

        // Back in front: the deferred rescan runs (and says why it can't, here).
        library.setForeground(true)
        library.resumeBackgroundWork()
        assertEquals(listOf("Grant All files access first"), host.failures)
        library.resumeBackgroundWork()
        assertEquals("only once", 1, host.failures.size)
    }

    @Test fun datWorkRunsOneJobAtATimeAndReportsErrors() {
        val gate = CompletableDeferred<String>()
        library.datWork { gate.await() }
        idle()
        assertTrue(library.state.value.datWork)
        library.datWork { "second" }
        assertEquals(listOf("Still working on DATs…"), host.toasts)

        gate.complete("Imported 2 DATs")
        await("the first job") { !library.state.value.datWork }
        assertEquals("Imported 2 DATs", host.toasts.last())

        library.datWork { error("boom") }
        await("the failing job") { !library.state.value.datWork && host.toasts.size == 3 }
        assertEquals("DAT error: boom", host.toasts.last())
    }

    @Test fun restoreNeedsABackup() {
        library.onCommand(Command.ConfirmRestore)
        library.onCommand(Command.RestoreBackup)
        idle()
        assertEquals(listOf("No library backup in the Xross folder"), host.toasts)
        assertEquals(emptyList<OptionsMenuState>(), host.menus)
        assertEquals(emptyList<UiEffect>(), host.effects)
    }

    @Test fun theStatusGoesIntoTheMenusSnapshot() {
        val status = LibraryStatus(
            storageGranted = true,
            mountedRoots = listOf("/storage/emulated/0"),
            scanStatus = "3 games · +3 −0",
            portable = PortableSetup(root = "/storage/emulated/0/Xross", portable = true),
            scanning = true,
        )
        val menu = status.into(MenuSnapshot(versionName = "1.0", storageGranted = false))
        assertEquals("1.0", menu.versionName)
        assertTrue(menu.storageGranted)
        assertEquals(status.mountedRoots, menu.mountedRoots)
        assertEquals(status.scanStatus, menu.scanStatus)
        assertEquals(status.portable, menu.portable)
    }
}
