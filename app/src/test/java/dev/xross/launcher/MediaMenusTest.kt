package dev.xross.launcher

import android.net.Uri
import dev.xross.launcher.media.MediaLibrary
import dev.xross.launcher.media.NowPlaying
import dev.xross.launcher.media.Photo
import dev.xross.launcher.media.Song
import dev.xross.launcher.media.ThumbKind
import dev.xross.launcher.media.Video
import dev.xross.launcher.state.Categories
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MediaKind
import dev.xross.launcher.state.MediaMenus
import dev.xross.launcher.state.MediaOverlayController
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.NodeIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaMenusTest {
    private val uri = Uri.parse("content://media/external/x")

    private fun photo(id: Long, folder: String, w: Int = 4000, h: Int = 3000, orientation: Int = 0) =
        Photo(id, uri, "IMG_$id.jpg", folder, 1_700_000_000_000 + id, w, h, 1000, orientation)

    private fun song(id: Long, title: String, artist: String, album: String, albumId: Long, track: Int) =
        Song(id, uri, title, artist, album, albumId, track, 185_000)

    private val library = MediaLibrary(
        photos = listOf(photo(1, "Trips"), photo(2, "Screenshots"), photo(3, "Trips")),
        videos = listOf(Video(10, uri, "Clip.mp4", "Movies", 3_725_000, 1920, 1080, 5 shl 20, 0)),
        songs = listOf(
            song(20, "Tide Lines", "Xross Ensemble", "Blue Hours", 1, 2),
            song(21, "Ocean Drift", "Xross Ensemble", "Blue Hours", 1, 1),
            song(22, "Ember", "Crimson Trio", "Red Shift", 2, 1),
        ),
    )
    private val menus = MediaMenus(library)

    @Test fun photoRootGroupsFoldersWithThumbnails() {
        val root = menus.photoRoot()
        assertEquals(listOf("All photos", "Screenshots", "Trips"), root.map { it.title })
        assertEquals("3 photos", root[0].subtitle)
        assertEquals(NodeIcon.Thumb(ThumbKind.PHOTO, 2, dev.xross.launcher.state.GlyphKey.FOLDER), root[1].icon)
        val trips = menus.list(MediaMenus.PHOTO_FOLDER + "Trips")
        assertEquals(2, trips.size)
        assertEquals(NodeAction.OpenMedia(MediaKind.PHOTO, MediaMenus.PHOTO_FOLDER + "Trips", 1), trips[1].action)
        assertEquals(listOf(Command.SetWallpaper(3)), trips[1].menu.map { it.command })
        assertEquals("Set as wallpaper", trips[1].menu.single().label)
    }

    @Test fun albumsSortTracksAndArtistsGroupAlbums() {
        val album = menus.songs(MediaMenus.ALBUM + 1)
        assertEquals(listOf("Ocean Drift", "Tide Lines"), album.map { it.title })
        assertEquals(listOf("Blue Hours", "Red Shift"), menus.list(MediaMenus.ALBUMS).map { it.title })
        val artists = menus.list(MediaMenus.ARTISTS)
        assertEquals(listOf("Crimson Trio", "Xross Ensemble"), artists.map { it.title })
        assertEquals("1 album · 2 songs", artists[1].subtitle)
        assertEquals(listOf("Ember", "Ocean Drift", "Tide Lines"), menus.allSongs.map { it.title })
    }

    @Test fun musicRootShowsNowPlayingOnlyWhileSomethingIsLoaded() {
        assertEquals(listOf("Shuffle all", "All songs", "Albums", "Artists"), menus.musicRoot(NowPlaying()).map { it.title })
        val playing = menus.musicRoot(NowPlaying(song = library.songs[2], isPlaying = true))
        assertEquals("Now playing", playing.first().title)
        assertEquals("Ember · Crimson Trio", playing.first().subtitle)
        assertEquals(NodeAction.Run(Command.OpenNowPlaying), playing.first().action)
    }

    @Test fun videosShowDurationAndResolution() {
        val v = menus.list(MediaMenus.VIDEOS_ALL).single()
        assertEquals("Clip", v.title)
        assertEquals("1:02:05 · 1920×1080 · 5.0 MB", v.subtitle)
    }

    @Test fun emptyLibrariesExplainWhereMediaComesFrom() {
        val empty = MediaMenus(MediaLibrary())
        assertEquals("No photos found", empty.photoRoot().single().title)
        assertEquals("No videos found", empty.videoRoot().single().title)
        assertEquals("No music found", empty.musicRoot(NowPlaying()).single().title)
    }

    @Test fun withoutStorageAccessMediaAsksForItInsteadOfLookingEmpty() {
        val noAccess = MediaMenus(MediaLibrary(), storageGranted = false)
        val rows = listOf(noAccess.photoRoot(), noAccess.videoRoot(), noAccess.musicRoot(NowPlaying())).map { it.single() }
        for (row in rows) {
            assertEquals("Storage access needed", row.title)
            assertEquals(NodeAction.Run(Command.RequestStorageAccess), row.action)
        }
        assertEquals(
            listOf("Press to allow Xross to show your photos", "Press to allow Xross to show your videos", "Press to allow Xross to show your music"),
            rows.map { it.subtitle },
        )
        // Distinct ids from the empty-library rows, so the list re-enters once access is granted.
        assertEquals(listOf("photo:access", "video:access", "music:access"), rows.map { it.id })
    }

    @Test fun menuTreePassesStorageAccessToTheMediaRoots() {
        fun photoRoot(granted: Boolean) =
            MenuTree(MenuSnapshot(storageGranted = granted)).list(Categories.rootKey(Categories.PHOTO)).single().title
        assertEquals("Storage access needed", photoRoot(granted = false))
        assertEquals("No photos found", photoRoot(granted = true))
    }

    @Test fun categoriesIncludeTheMediaHub() {
        assertEquals(listOf("Settings", "Photo", "Music", "Video", "Games", "Apps"), Categories.all.map { it.title })
        val tree = MenuTree(MenuSnapshot(media = library))
        assertEquals(3, tree.list(Categories.rootKey(Categories.PHOTO)).size)
        assertTrue(tree.list(MediaMenus.SONGS_ALL).all { it.action is NodeAction.OpenMedia })
    }

    @Test fun exifRotationSwapsDisplaySize() {
        val portrait = photo(9, "x", w = 4000, h = 3000, orientation = 90)
        assertEquals(3000, portrait.displayWidth)
        assertEquals(4000, portrait.displayHeight)
    }

    @Test fun panLimitsKeepPhotoEdgesOutsideTheScreen() {
        // 4:3 photo on a 20:9 screen: pillarboxed, so no horizontal pan until zoomed past the width.
        val (x1, y1) = MediaOverlayController.panLimits(4000f, 3000f, 2400f, 1080f, 1f)
        assertEquals(0f, x1, 1e-4f)
        assertEquals(0f, y1, 1e-4f)
        val (x2, y2) = MediaOverlayController.panLimits(4000f, 3000f, 2400f, 1080f, 2.25f)
        // Fitted 1440×1080 → 3240×2430 at 2.25×: overflow 420 px / (2400×2.25) and 675 / (1080×2.25).
        assertEquals(420f / (2400f * 2.25f), x2, 1e-4f)
        assertEquals(675f / (1080f * 2.25f), y2, 1e-4f)
    }
}
