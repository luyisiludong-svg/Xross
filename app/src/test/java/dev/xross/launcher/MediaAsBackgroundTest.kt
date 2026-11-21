package dev.xross.launcher

import android.net.Uri
import dev.xross.launcher.config.ConfigCodec
import dev.xross.launcher.data.ThemeDefaults
import dev.xross.launcher.data.ThemeMusic
import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.media.MediaLibrary
import dev.xross.launcher.media.Photo
import dev.xross.launcher.media.Playlist
import dev.xross.launcher.media.Song
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MediaMenus
import dev.xross.launcher.state.MenuSnapshot
import dev.xross.launcher.state.MenuTree
import dev.xross.launcher.state.WallpaperRotator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** A Photo album as the rotating Xross wallpaper, and a Music playlist as theme music. */
@RunWith(RobolectricTestRunner::class)
class MediaAsBackgroundTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun photo(id: Long, folder: String, path: String) =
        Photo(id, Uri.parse("content://media/external/images/media/$id"), File(path).name, folder, id, 100, 100, 10, path = path)

    private val photos = listOf(
        photo(1, "Walls", "/sdcard/Pictures/Walls/b.jpg"),
        photo(2, "Walls", "/sdcard/DCIM/Walls/a.png"),
        photo(3, "Camera", "/sdcard/DCIM/Camera/c.jpg"),
        photo(4, "Walls", ""), // a picture MediaStore gave no path for
        photo(5, "Walls", "/sdcard/Pictures/Walls/clip.heic"),
    )

    private fun song(id: Long, path: String) = Song(id, Uri.parse("content://media/external/audio/media/$id"), "T$id", "A", "Al", 1, 1, 1000, path = path)

    // region Album as wallpaper

    @Test fun anAlbumsPicturesTakeTurnsByFileNameAcrossItsFolders() {
        val paths = photos.filter { it.folder == "Walls" }.map { it.path }
        assertEquals(listOf("/sdcard/DCIM/Walls/a.png", "/sdcard/Pictures/Walls/b.jpg"), WallpaperRotator.album(paths))
    }

    @Test fun theAlbumIsSavedAndResetClearsIt() {
        val s = XrossSettings(wallpaperAlbum = "Walls", wallpaperInterval = "FRONT")
        val back = ConfigCodec.decodeSettings(XrossSettings(), ConfigCodec.encodeSettings(s))
        assertEquals("Walls", back.wallpaperAlbum)
        assertFalse(ThemeDefaults.isDefault(s))
        assertEquals("", ThemeDefaults.reset(s).wallpaperAlbum)
    }

    @Test fun anAlbumsOptionsUseItAsWallpaperAndSayWhenItIs() {
        val menus = MediaMenus(MediaLibrary(photos = photos))
        val walls = menus.photoRoot().first { it.title == "Walls" }
        assertEquals(Command.SetWallpaperAlbum("Walls"), walls.menu.single().command)
        val inUse = MediaMenus(MediaLibrary(photos = photos), wallpaperAlbum = "Walls").photoRoot().first { it.title == "Walls" }
        assertTrue(inUse.subtitle!!.endsWith("Xross wallpaper"))
        assertEquals(Command.ResetXrossWallpaper, inUse.menu.single().command)
    }

    @Test fun settingsXrossWallpaperListsTheAlbums() {
        val tree = MenuTree(MenuSnapshot(media = MediaLibrary(photos = photos), settings = XrossSettings(wallpaperAlbum = "Walls")))
        val row = tree.list(MenuTree.DISPLAY_BACKGROUND).first { it.id == "disp:wallpaper" }
        assertTrue(row.subtitle!!.contains("Walls album"))
        val albums = row.menu.first { it.label == "An album" }
        assertEquals(listOf("Camera", "Walls"), albums.submenu.map { it.label })
        assertTrue(albums.submenu.first { it.label == "Walls" }.selected)
    }

    // endregion

    // region Playlist as theme music

    @Test fun aPlaylistPlaysItsSongsInItsOwnOrder() {
        val b = tmp.newFile("b.mp3").apply { writeBytes(ByteArray(8)) }
        val a = tmp.newFile("a.ogg").apply { writeBytes(ByteArray(8)) }
        val odd = tmp.newFile("c.wma").apply { writeBytes(ByteArray(8)) }
        val list = listOf(b.path, a.path, odd.path, "/gone/x.mp3")
        val choice = ThemeMusic.resolve(ThemeMusic.playlist("Chill"), ThemeMusic.IN_ORDER) { name -> list.takeIf { name == "Chill" } }
        val track = (choice as ThemeMusic.Choice.Own).track!!
        assertEquals(listOf("b.mp3", "a.ogg"), track.files.map { it.name })
        assertFalse(track.shuffle)
        assertEquals(2, choice.skipped)
        // A playlist that was deleted plays nothing (and doesn't fall back to the pack's).
        assertNull((ThemeMusic.resolve(ThemeMusic.playlist("Gone"), "") { null } as ThemeMusic.Choice.Own).track)
    }

    @Test fun thePlaylistChoiceRoundTripsAndDescribesItself() {
        val s = XrossSettings(themeMusic = ThemeMusic.playlist("Chill"))
        assertEquals(s.themeMusic, ConfigCodec.decodeSettings(XrossSettings(), ConfigCodec.encodeSettings(s)).themeMusic)
        assertEquals("Chill", ThemeMusic.playlistName(s.themeMusic))
        assertEquals("Playlist Chill · 3 songs, shuffled", ThemeMusic.describe(s.themeMusic, ThemeMusic.SHUFFLE, "P", true) { 3 })
        assertEquals("Playlist Chill (missing)", ThemeMusic.describe(s.themeMusic, ThemeMusic.SHUFFLE, "P", true) { null })
    }

    @Test fun aPlaylistsOptionsUseItAsThemeMusic() {
        val songs = listOf(song(1, "/m/a.mp3"), song(2, "/m/b.mp3"))
        val chill = Playlist("Chill", songs.map { it.path })
        val tree = MenuTree(MenuSnapshot(media = MediaLibrary(songs = songs), playlists = listOf(chill)))
        val row = tree.list(MediaMenus.PLAYLISTS).first { it.title == "Chill" }
        assertEquals(Command.SetThemeMusic("playlist:Chill"), row.menu.first { it.label == "Use as theme music" }.command)

        val playing = MenuTree(MenuSnapshot(media = MediaLibrary(songs = songs), playlists = listOf(chill), settings = XrossSettings(themeMusic = "playlist:Chill")))
        val inUse = playing.list(MediaMenus.PLAYLISTS).first { it.title == "Chill" }
        assertEquals(Command.SetThemeMusic(""), inUse.menu.first { it.label == "Stop using as theme music" }.command)
        val musicRow = playing.list(MenuTree.DISPLAY_BACKGROUND).first { it.id == "disp:music" }
        assertEquals("Playlist Chill · 2 songs, shuffled", musicRow.subtitle)
        assertTrue(musicRow.menu.first { it.label == "A playlist" }.submenu.single().selected)
    }

    // endregion
}
