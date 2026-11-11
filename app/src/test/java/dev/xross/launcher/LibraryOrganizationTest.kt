package dev.xross.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.media.MediaFolderScan
import dev.xross.launcher.media.MediaFolders
import dev.xross.launcher.media.MediaRepository
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.FolderEntry
import dev.xross.launcher.state.LibraryFolders
import dev.xross.launcher.state.MediaFolderKind
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.storage.PlatformRegistry
import dev.xross.launcher.storage.SystemFolders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Base ROMs folder, system folder scaffolding and custom Photo/Video folders. */
@RunWith(RobolectricTestRunner::class)
class LibraryOrganizationTest {
    @get:Rule val tmp = TemporaryFolder()

    // region Base ROMs folder

    @Test fun createSystemFoldersMakesOneFolderPerSystemNamedLikeEsDe() {
        val base = File(tmp.root, "Xross/roms")
        val result = SystemFolders.create(base)
        assertTrue(result.ok)
        assertEquals(22, PlatformRegistry.builtIn.size)
        assertEquals(PlatformRegistry.builtIn.map { it.id }, result.created)
        for (id in listOf("nes", "snes", "psx", "ps2", "n3ds", "switch", "arcade")) {
            assertTrue("$id/", File(base, id).isDirectory)
            // Every scaffolded folder is recognised by the scanner.
            assertEquals(id, PlatformRegistry.forFolder(id)?.id)
        }
    }

    @Test fun createSystemFoldersIsSafeToRunAgain() {
        val base = tmp.newFolder("roms")
        File(base, "snes").mkdirs()
        val keep = File(base, "snes/Chrono Trigger.sfc").apply { writeText("rom") }
        val first = SystemFolders.create(base)
        assertEquals(listOf("snes"), first.existing)
        assertEquals(21, first.created.size)
        val second = SystemFolders.create(base)
        assertTrue(second.created.isEmpty())
        assertEquals(22, second.existing.size)
        assertEquals("rom", keep.readText())
    }

    @Test fun createSystemFoldersReportsWhatFailed() {
        val blocked = File(tmp.root, "file-not-folder").apply { writeText("x") }
        val result = SystemFolders.create(blocked)
        assertFalse(result.ok)
        assertEquals(22, result.failed.size)
    }

    @Test fun theBaseFolderIsScannedFirstBesideAutoDetection() {
        val plan = LibraryFolders.plan(
            custom = listOf("/storage/emulated/0/Custom", "/storage/emulated/0/Xross/roms/"),
            discovered = listOf("/storage/emulated/0/ROMs"),
            knownAuto = emptyList(),
            mountedRoots = listOf("/storage/emulated/0"),
            autoDetect = true,
            base = "/storage/emulated/0/Xross/roms",
        )
        assertEquals(
            listOf(
                FolderEntry("/storage/emulated/0/Xross/roms", autoDetected = false, base = true),
                FolderEntry("/storage/emulated/0/Custom", autoDetected = false),
                FolderEntry("/storage/emulated/0/ROMs", autoDetected = true),
            ),
            plan.folders,
        )
    }

    @Test fun libraryRowsShowTheBaseFolder() {
        val rows = MenuTree(
            MenuSnapshot(
                storageGranted = true,
                baseRomsFolder = "/storage/emulated/0/Xross/roms",
                folders = listOf(FolderEntry("/storage/emulated/0/Xross/roms", autoDetected = false, base = true)),
            ),
        ).list(MenuTree.LIBRARY)
        val base = rows.single { it.id == "lib:base" }
        assertEquals("/storage/emulated/0/Xross/roms (default)", base.subtitle)
        assertEquals(NodeAction.Run(Command.PickBaseRomsFolder), base.action)
        assertEquals(NodeAction.Run(Command.CreateSystemFolders), rows.single { it.id == "lib:sysfolders" }.action)
        val folderRow = rows.single { it.id == "lib:folder:/storage/emulated/0/Xross/roms" }
        assertEquals("Base ROMs folder", folderRow.subtitle)
        assertTrue(folderRow.menu.isEmpty()) // changed through the Base ROMs folder row, not removed here

        val custom = MenuTree(MenuSnapshot(settings = XrossSettings(baseRomsFolder = "/storage/1A2B-3C4D/ROMs"), baseRomsFolder = "/storage/1A2B-3C4D/ROMs"))
            .list(MenuTree.LIBRARY).single { it.id == "lib:base" }
        assertEquals("/storage/1A2B-3C4D/ROMs", custom.subtitle)
        assertTrue(custom.menu.any { it.command == Command.ResetBaseRomsFolder })
    }

    // endregion

    // region Custom Photo / Video folders

    @Test fun pathsBelongToAFolderOnlyAtFolderBoundaries() {
        val folders = listOf("/storage/emulated/0/Pictures/Shots/")
        assertTrue(MediaFolderScan.isUnder("/storage/emulated/0/Pictures/Shots/a.png", folders))
        assertTrue(MediaFolderScan.isUnder("/storage/emulated/0/Pictures/Shots/2026/b.png", folders))
        assertFalse(MediaFolderScan.isUnder("/storage/emulated/0/Pictures/Shots2/c.png", folders))
        assertFalse(MediaFolderScan.isUnder("/storage/emulated/0/Pictures/d.png", folders))
    }

    @Test fun mediaStoreFilterEscapesLikeWildcards() {
        val folders = listOf("/storage/emulated/0/My_Pics", "/storage/emulated/0/100%")
        assertEquals("(_data LIKE ? ESCAPE '\\' OR _data LIKE ? ESCAPE '\\')", MediaFolderScan.selection("_data", folders))
        assertArrayEquals(
            arrayOf("/storage/emulated/0/My\\_Pics/%", "/storage/emulated/0/100\\%/%"),
            MediaFolderScan.selectionArgs(folders),
        )
    }

    @Test fun walkFindsFilesMediaStoreSkips() {
        val root = tmp.newFolder("Shots")
        File(root, ".nomedia").writeText("")
        File(root, "a.png").writeText("x")
        File(root, "notes.txt").writeText("x")
        File(root, ".hidden").mkdirs()
        File(root, ".hidden/b.JPG").writeText("x")
        File(root, "deep/1/2/3").mkdirs()
        File(root, "deep/1/2/3/c.webp").writeText("x")
        val found = MediaFolderScan.walk(listOf(root.path), MediaFolderScan.PHOTO_EXTENSIONS).map { it.relativeTo(root).path }
        assertEquals(listOf("a.png", ".hidden/b.JPG", "deep/1/2/3/c.webp"), found)
        // Depth and count limits hold.
        assertEquals(listOf("a.png"), MediaFolderScan.walk(listOf(root.path), MediaFolderScan.PHOTO_EXTENSIONS, maxDepth = 0).map { it.name })
        assertEquals(1, MediaFolderScan.walk(listOf(root.path), MediaFolderScan.PHOTO_EXTENSIONS, maxFiles = 1).size)
        // Missing folders (an unplugged card) are skipped.
        assertTrue(MediaFolderScan.walk(listOf("/storage/GONE-0000/DCIM"), MediaFolderScan.PHOTO_EXTENSIONS).isEmpty())
    }

    @Test fun walkedFilesGetStableNegativeIds() {
        val a = MediaFolderScan.syntheticId("/storage/emulated/0/Pictures/a.png")
        assertTrue(a < 0)
        assertEquals(a, MediaFolderScan.syntheticId("/storage/emulated/0/Pictures/a.png"))
        assertNotEquals(a, MediaFolderScan.syntheticId("/storage/emulated/0/Pictures/b.png"))
    }

    @Test fun customFoldersLimitTheLibraryAndIncludeUnindexedFiles() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val photos = tmp.newFolder("Pictures", "Shots")
        File(photos, ".nomedia").writeText("")
        File(photos, "Boss fight.png").writeBytes(ByteArray(32))
        val videos = tmp.newFolder("Movies", "Clips")
        File(videos, "Speedrun.mp4").writeBytes(ByteArray(32))
        File(videos, "cover.png").writeBytes(ByteArray(32)) // not a video

        val repo = MediaRepository(context)
        assertTrue(repo.setFolders(MediaFolders(photo = listOf(photos.path), video = listOf(videos.path))))
        assertFalse(repo.setFolders(MediaFolders(photo = listOf(photos.path), video = listOf(videos.path))))
        repo.refresh()
        val library = repo.library.value
        val photo = library.photos.single()
        assertEquals("Boss fight.png", photo.name)
        assertEquals("Shots", photo.folder)
        assertTrue(photo.id < 0)
        assertEquals("content", photo.uri.scheme) // shareable with the viewer, the player and the wallpaper picker
        assertEquals("Speedrun.mp4", library.videos.single().name)
    }

    @Test fun mediaSettingsListTheFolders() {
        val s = XrossSettings(photoFolders = listOf("/storage/emulated/0/Pictures/Shots"))
        val tree = MenuTree(MenuSnapshot(settings = s))
        val media = tree.list(MenuTree.MEDIA_SETTINGS).associateBy { it.title }
        assertEquals("Only 1 folder", media.getValue("Photo folders").subtitle)
        assertEquals("All videos on this device", media.getValue("Video folders").subtitle)

        val photoRows = tree.list(MenuTree.PHOTO_FOLDERS)
        assertEquals(listOf("Add folder…", "Show all photos again", "/storage/emulated/0/Pictures/Shots"), photoRows.map { it.title })
        assertEquals(NodeAction.Run(Command.AddMediaFolder(MediaFolderKind.PHOTO)), photoRows[0].action)
        assertEquals(
            Command.RemoveMediaFolder(MediaFolderKind.PHOTO, "/storage/emulated/0/Pictures/Shots"),
            photoRows.last().menu.single().command,
        )
        assertEquals(listOf("Add folder…", "All videos"), tree.list(MenuTree.VIDEO_FOLDERS).map { it.title })
    }

    // endregion
}
