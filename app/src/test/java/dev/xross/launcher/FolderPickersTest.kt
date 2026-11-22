package dev.xross.launcher

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.FolderPickers
import dev.xross.launcher.state.MediaFolderKind
import dev.xross.launcher.state.UiEffect
import dev.xross.launcher.state.XmbViewModel
import dev.xross.launcher.storage.XrossLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The library and media folders out of [XmbViewModel] (step 6): the picker answers, the folder
 * commands and the system folders, with a fake host and a temporary Xross folder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FolderPickersTest {
    @get:Rule val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container = AppContainer(context)
    private val settings = container.settings

    private class FakeHost : FolderPickers.Host {
        val toasts = mutableListOf<String>()
        val effects = mutableListOf<UiEffect>()
        var granted = true
        var refreshes = 0
        var rescans = 0
        var datImports = 0
        override fun toast(message: String) { toasts += message }
        override fun emit(effect: UiEffect) { effects += effect }
        override fun storageGranted() = granted
        override fun refreshFolders() { refreshes++ }
        override fun refreshFoldersAndRescan() { rescans++ }
        override fun importDats() { datImports++ }
    }

    private val host = FakeHost()
    private var portable = true

    /** Document trees the fake provider resolves; anything else is "not a local folder". */
    private val trees = mapOf(
        "content://tree/roms" to "/storage/emulated/0/ROMs",
        "content://tree/dats" to "/storage/emulated/0/DATs",
        "content://tree/photos" to "/storage/1234-ABCD/Pictures/",
    )

    private fun TestScope.pickers(scope: CoroutineScope = backgroundScope) = FolderPickers(
        scope = scope,
        settings = settings,
        sounds = container.sounds,
        media = container.media,
        treeToPath = { trees[it.toString()] },
        layout = { XrossLayout(File(temp.root, "Xross"), portable) },
        host = host,
        io = StandardTestDispatcher(testScheduler),
    )

    @Test fun aTreeThatIsNotLocalChangesNothing() = runTest {
        val folders = pickers()
        val before = settings.current
        folders.onFolderPicked(null) // picker cancelled: silent
        assertEquals(emptyList<String>(), host.toasts)
        folders.onFolderPicked(Uri.parse("content://cloud/drive"))
        folders.onDatFolderPicked(Uri.parse("content://cloud/drive"))
        folders.onMediaFolderPicked(MediaFolderKind.PHOTO, Uri.parse("content://cloud/drive"))
        assertEquals(List(3) { "That location isn't a local folder" }, host.toasts)
        assertEquals(before, settings.current)
        assertEquals(0, host.rescans + host.datImports)
    }

    @Test fun romFoldersAreAddedOnceAndEveryChangeRescans() = runTest {
        val folders = pickers()
        folders.onFolderPicked(Uri.parse("content://tree/roms"))
        folders.onFolderPicked(Uri.parse("content://tree/roms"))
        assertEquals(listOf("/storage/emulated/0/ROMs"), settings.current.romFolders)
        assertTrue(folders.onCommand(Command.RemoveFolder("/storage/emulated/0/ROMs")))
        assertEquals(emptyList<String>(), settings.current.romFolders)
        val autoDetect = settings.current.autoDetectFolders
        folders.onCommand(Command.ToggleAutoDetect)
        assertEquals(!autoDetect, settings.current.autoDetectFolders)
        assertEquals(4, host.rescans)
    }

    @Test fun baseRomsAndDatFoldersFallBackToTheXrossFolder() = runTest {
        val folders = pickers()
        val xross = File(temp.root, "Xross")
        assertEquals(File(xross, "roms"), folders.baseRomsFolder(""))
        assertEquals(File(xross, "dats"), folders.datFolder(""))

        folders.onDatFolderPicked(Uri.parse("content://tree/dats"))
        assertEquals(File("/storage/emulated/0/DATs"), folders.datFolder())
        assertEquals(1, host.datImports)

        folders.onBaseRomsFolderPicked(Uri.parse("content://tree/roms"))
        assertEquals(File("/storage/emulated/0/ROMs"), folders.baseRomsFolder())
        folders.onCommand(Command.ResetBaseRomsFolder)
        assertEquals(File(xross, "roms"), folders.baseRomsFolder())
        assertEquals("Base ROMs folder: ${File(xross, "roms").absolutePath}", host.toasts.last())
        assertEquals(2, host.rescans)
    }

    @Test fun mediaFoldersTrimTheirSlashAndClear() = runTest {
        val folders = pickers()
        folders.onMediaFolderPicked(MediaFolderKind.PHOTO, Uri.parse("content://tree/photos"))
        folders.onMediaFolderPicked(MediaFolderKind.PHOTO, Uri.parse("content://tree/photos"))
        assertEquals(listOf("/storage/1234-ABCD/Pictures"), settings.current.photoFolders)
        assertEquals(emptyList<String>(), settings.current.videoFolders)
        folders.onCommand(Command.ClearMediaFolders(MediaFolderKind.PHOTO))
        assertEquals(emptyList<String>(), settings.current.photoFolders)
        assertEquals(0, host.rescans)

        folders.onMediaFolderPicked(MediaFolderKind.MUSIC, Uri.parse("content://tree/photos"))
        assertEquals(listOf("/storage/1234-ABCD/Pictures"), settings.current.musicFolders)
        assertEquals("Music now includes /storage/1234-ABCD/Pictures", host.toasts.last())
        folders.onCommand(Command.ClearMediaFolders(MediaFolderKind.MUSIC))
        assertEquals(emptyList<String>(), settings.current.musicFolders)
    }

    @Test fun pickerCommandsAskTheActivityAndOthersAreNotTaken() = runTest {
        val folders = pickers()
        assertTrue(folders.onCommand(Command.AddFolder))
        assertTrue(folders.onCommand(Command.PickDatFolder))
        assertTrue(folders.onCommand(Command.PickBaseRomsFolder))
        assertTrue(folders.onCommand(Command.AddMediaFolder(MediaFolderKind.VIDEO)))
        assertEquals(
            listOf(UiEffect.PickFolder, UiEffect.PickDatFolder, UiEffect.PickBaseRomsFolder, UiEffect.PickMediaFolder(MediaFolderKind.VIDEO)),
            host.effects,
        )
        assertFalse(folders.onCommand(Command.Rescan))
    }

    @Test fun systemFoldersNeedWritableStorageAndAreCreatedOnce() = runTest {
        val folders = pickers(this) // advanceUntilIdle doesn't wait for backgroundScope work
        host.granted = false
        folders.onCommand(Command.CreateSystemFolders)
        host.granted = true
        portable = false
        folders.onCommand(Command.CreateSystemFolders)
        assertEquals(List(2) { UiEffect.RequestStorageAccess }, host.effects)

        portable = true
        folders.onCommand(Command.CreateSystemFolders)
        advanceUntilIdle()
        val base = File(temp.root, "Xross/roms")
        val made = base.listFiles().orEmpty().count { it.isDirectory }
        assertTrue("one folder per platform", made > 10)
        assertEquals("Created $made folders in ${base.absolutePath}", host.toasts.last())

        folders.onCommand(Command.CreateSystemFolders)
        advanceUntilIdle()
        assertEquals("All $made system folders are already in ${base.absolutePath}", host.toasts.last())
        assertEquals(2, host.refreshes)
    }
}
