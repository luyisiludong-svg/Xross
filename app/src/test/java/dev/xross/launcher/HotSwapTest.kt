package dev.xross.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.state.FolderEntry
import dev.xross.launcher.state.LibraryFolders
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.storage.StorageVolumes
import dev.xross.launcher.storage.VolumeEvent
import dev.xross.launcher.storage.VolumeInfo
import dev.xross.launcher.storage.VolumeMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class HotSwapTest {
    private val internal = "/storage/emulated/0"
    private val sd = "/storage/1A2B-3C4D"
    private val usb = "/storage/USB1-0001"

    // region Volumes

    @Test fun storageManagerVolumesWinAndAppDirRootsFillGaps() {
        val merged = StorageVolumes.mergeVolumes(
            fromManager = listOf(
                VolumeInfo(File(internal), "Internal storage", removable = false, primary = true, uuid = null),
                VolumeInfo(File(usb), "SanDisk USB drive", removable = true, primary = false, uuid = "USB1-0001"),
            ),
            appDirRoots = listOf(File(internal), File(sd)),
        )
        assertEquals(listOf(internal, usb, sd), merged.map { it.root.path })
        assertEquals("SanDisk USB drive", merged[1].label)
        // Only known from getExternalFilesDirs: still usable, labelled from its mount name.
        assertEquals("1A2B-3C4D", merged[2].label)
        assertTrue(merged[2].removable)
    }

    @Test fun usbOtgDriveReportedByStorageManagerIsListed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(android.os.storage.StorageManager::class.java)
        val usbDir = File(usb)
        shadowOf(manager).addStorageVolume(
            org.robolectric.shadows.StorageVolumeBuilder("USB1-0001", usbDir, "SanDisk USB drive", android.os.Process.myUserHandle(), android.os.Environment.MEDIA_MOUNTED)
                .setIsRemovable(true)
                .build(),
        )
        shadowOf(manager).addStorageVolume(
            org.robolectric.shadows.StorageVolumeBuilder("EJECTED", File("/storage/DEAD-BEEF"), "Old card", android.os.Process.myUserHandle(), android.os.Environment.MEDIA_UNMOUNTED)
                .setIsRemovable(true)
                .build(),
        )
        val volumes = StorageVolumes(context).volumes()
        val drive = volumes.single { it.root == usbDir }
        assertEquals("SanDisk USB drive", drive.label)
        assertTrue(drive.removable)
        // Unmounted volumes are not offered for scanning.
        assertTrue(volumes.none { it.root.path == "/storage/DEAD-BEEF" })
    }

    @Test fun volumeRootIsDerivedFromAPath() {
        assertEquals(internal, StorageVolumes.volumeRootOf("$internal/ROMs/snes/a.sfc"))
        assertEquals(sd, StorageVolumes.volumeRootOf("$sd/ROMs"))
        assertEquals("/mnt/media_rw/USB1-0001", StorageVolumes.volumeRootOf("/mnt/media_rw/USB1-0001/Games/x.iso"))
        assertNull(StorageVolumes.volumeRootOf("/data/local/tmp/x"))
    }

    // endregion

    // region Folder planning

    @Test fun autoDetectedFolderIsRememberedWhileItsVolumeIsUnplugged() {
        // First pass: SD card in, ROMs found on both volumes.
        val first = LibraryFolders.plan(
            custom = emptyList(), discovered = listOf("$internal/ROMs", "$sd/ROMs"),
            knownAuto = emptyList(), mountedRoots = listOf(internal, sd), autoDetect = true,
        )
        assertEquals(listOf("$internal/ROMs", "$sd/ROMs"), first.knownAuto)
        assertTrue(first.folders.all { it.connected })

        // Card pulled: its folder is no longer discovered but must stay configured.
        val pulled = LibraryFolders.plan(
            custom = emptyList(), discovered = listOf("$internal/ROMs"),
            knownAuto = first.knownAuto, mountedRoots = listOf(internal), autoDetect = true,
        )
        assertEquals(listOf("$internal/ROMs", "$sd/ROMs"), pulled.folders.map { it.path })
        assertEquals(FolderEntry("$sd/ROMs", autoDetected = true, connected = false), pulled.folders[1])
        assertEquals(first.knownAuto, pulled.knownAuto)
    }

    @Test fun autoFolderDeletedOnAMountedVolumeIsForgotten() {
        val plan = LibraryFolders.plan(
            custom = emptyList(), discovered = listOf("$internal/ROMs"),
            knownAuto = listOf("$internal/ROMs", "$sd/ROMs"), mountedRoots = listOf(internal, sd), autoDetect = true,
        )
        assertEquals(listOf("$internal/ROMs"), plan.folders.map { it.path })
        assertEquals(listOf("$internal/ROMs"), plan.knownAuto)
    }

    @Test fun customFoldersStayAndReportConnectionWithoutAutoDetect() {
        val plan = LibraryFolders.plan(
            custom = listOf("$usb/Games", "$internal/Roms/"), discovered = listOf("$internal/Roms"),
            knownAuto = listOf("$sd/ROMs"), mountedRoots = listOf(internal), autoDetect = false,
        )
        assertEquals(listOf("$usb/Games", "$internal/Roms/"), plan.folders.map { it.path })
        assertFalse(plan.folders[0].connected)
        assertTrue(plan.folders[1].connected)
        assertTrue(plan.folders.none { it.autoDetected })
        // Remembered folders survive auto-detect being switched off and on again.
        assertEquals(listOf("$sd/ROMs"), plan.knownAuto)
    }

    @Test fun customFolderIsNotListedTwiceWhenAlsoDiscovered() {
        val plan = LibraryFolders.plan(
            custom = listOf("$internal/ROMs"), discovered = listOf("$internal/ROMs/"),
            knownAuto = emptyList(), mountedRoots = listOf(internal), autoDetect = true,
        )
        assertEquals(1, plan.folders.size)
        assertFalse(plan.folders.single().autoDetected)
    }

    // endregion

    // region Broadcasts

    @Test fun broadcastActionsMapToVolumeEvents() {
        assertEquals(VolumeEvent.Mounted(sd), VolumeMonitor.eventFor(Intent.ACTION_MEDIA_MOUNTED, sd))
        for (action in listOf(Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL)) {
            assertEquals(action, VolumeEvent.Removed(sd), VolumeMonitor.eventFor(action, sd))
        }
        assertNull(VolumeMonitor.eventFor(Intent.ACTION_MEDIA_SCANNER_FINISHED, sd))
    }

    @Test fun registeredMonitorReceivesMountAndUnmount() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val events = mutableListOf<VolumeEvent>()
        val monitor = VolumeMonitor { events += it }
        monitor.register(context)
        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_MOUNTED, Uri.parse("file://$sd")))
        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_UNMOUNTED, Uri.parse("file://$sd")))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(VolumeEvent.Mounted(sd), VolumeEvent.Removed(sd)), events)

        monitor.unregister(context)
        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_MOUNTED, Uri.parse("file://$sd")))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, events.size)
    }

    // endregion

    // region UI state

    @Test fun gamesAndFoldersOnAnUnpluggedVolumeAreKeptAndMarked() {
        val games = listOf(
            GameEntity(1, "$internal/ROMs/snes/a.sfc", "snes", "Here", "a.sfc", 10, 0),
            GameEntity(2, "$sd/ROMs/snes/b.sfc", "snes", "On Card", "b.sfc", 10, 0, favorite = true),
        )
        val tree = MenuTree(
            MenuSnapshot(
                games = games,
                mountedRoots = listOf(internal),
                folders = listOf(
                    FolderEntry("$internal/ROMs", autoDetected = true, connected = true),
                    FolderEntry("$sd/ROMs", autoDetected = true, connected = false),
                ),
            ),
        )
        val list = tree.list("platform:snes")
        assertEquals(listOf("Here", "On Card"), list.map { it.title })
        assertFalse(list[0].subtitle!!.contains("Not connected"))
        assertTrue(list[1].subtitle!!.startsWith("Not connected"))
        // Still listed in Favorites: nothing is dropped while the card is out.
        assertEquals(listOf("On Card"), tree.list(MenuTree.FAVORITES).map { it.title })

        val folderRows = tree.list(MenuTree.LIBRARY).filter { it.id.startsWith("lib:folder:") }
        assertEquals(listOf("Auto-detected", "Auto-detected · not connected"), folderRows.map { it.subtitle })
    }

    // endregion
}
