package dev.xross.launcher

import android.net.Uri
import dev.xross.launcher.data.AlbumSort
import dev.xross.launcher.media.M3u
import dev.xross.launcher.media.MediaLibrary
import dev.xross.launcher.media.NowPlaying
import dev.xross.launcher.media.Playlist
import dev.xross.launcher.media.PlaylistStore
import dev.xross.launcher.media.Song
import dev.xross.launcher.data.SongSort
import dev.xross.launcher.state.Command
import dev.xross.launcher.state.MediaKind
import dev.xross.launcher.state.MediaMenus
import dev.xross.launcher.state.NodeAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PlaylistsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun song(id: Long, title: String, artist: String, album: String, albumId: Long, track: Int, added: Long = 0) =
        Song(id, Uri.EMPTY, title, artist, album, albumId, track, 60_000, folder = "/m/$album", path = "/m/$album/$title.mp3", addedAt = added)

    private val songs = listOf(
        song(1, "Tide Lines", "Xross Ensemble", "Blue Hours", 1, 2, added = 300),
        song(2, "Ocean Drift", "Xross Ensemble", "Blue Hours", 1, 1, added = 100),
        song(3, "Ember", "Crimson Trio", "Red Shift", 2, 1, added = 200),
    )

    @Test fun m3uIsPathsPerLineAndReadsOtherPlayersFiles() {
        assertEquals("#EXTM3U\n/a.mp3\n/b.flac\n", M3u.encode(listOf("/a.mp3", "/b.flac")))
        val base = File("/storage/emulated/0/Xross/playlists")
        val text = "\uFEFF#EXTM3U\r\n#EXTINF:123,Song\r\n../../Music/x.mp3\r\n\r\nC:\\ignored\\no\r\n/abs/y.ogg\r\n/abs/y.ogg\r\n"
        assertEquals(
            listOf("/storage/emulated/0/Music/x.mp3", "/storage/emulated/0/Xross/playlists/C:/ignored/no", "/abs/y.ogg"),
            M3u.decode(text, base),
        )
    }

    @Test fun namesAreSafeFileNames() {
        assertEquals("Road trip mix", PlaylistStore.normalizeName("  Road/trip:mix  "))
        assertNull(PlaylistStore.normalizeName(" ?* "))
        assertEquals(PlaylistStore.MAX_NAME, PlaylistStore.normalizeName("x".repeat(99))!!.length)
    }

    @Test fun storeAddsSkipsDuplicatesMovesRemovesAndDeletes() = runBlocking {
        val dir = File(tmp.root, "playlists")
        val store = PlaylistStore { dir }
        assertEquals(2, store.add("Chill", listOf("/a", "/b")))
        assertEquals(1, store.add("chill", listOf("/b", "/c"))) // same playlist, any case
        assertEquals(listOf("/a", "/b", "/c"), store.find("Chill")!!.paths)
        assertEquals("#EXTM3U\n/a\n/b\n/c\n", File(dir, "Chill.m3u8").readText())
        // Moving past a missing song: /b isn't in the library, so /c jumps over it.
        assertTrue(store.move("Chill", "/c", -1) { it != "/b" })
        assertEquals(listOf("/c", "/a", "/b"), store.find("Chill")!!.paths)
        assertTrue(store.remove("Chill", "/a"))
        assertEquals(listOf("/c", "/b"), store.find("Chill")!!.paths)
        // Files written elsewhere are picked up.
        File(dir, "Other.m3u").writeText("/z\n")
        store.reload()
        assertEquals(listOf("Chill", "Other"), store.playlists.value.map { it.name })
        assertEquals(1, store.add("Other", listOf("/y")))
        assertTrue(File(dir, "Other.m3u").readText().endsWith("/z\n/y\n")) // kept its .m3u
        assertTrue(store.delete("Chill"))
        assertEquals(listOf("Other"), store.playlists.value.map { it.name })
    }

    @Test fun playlistsListPlaysInOrderAndCountsMissingSongs() {
        val pl = Playlist("Mix", listOf(songs[2].path, "/gone.mp3", songs[0].path))
        val menus = MediaMenus(MediaLibrary(songs = songs), playlists = listOf(pl, Playlist("Empty", emptyList())))
        val rows = menus.list(MediaMenus.PLAYLISTS)
        assertEquals(listOf("New playlist", "Favorites", "Mix", "Empty"), rows.map { it.title })
        assertEquals(NodeAction.Run(Command.NewPlaylist()), rows[0].action)
        assertEquals("2 songs · 1 missing · 2:00", rows[2].subtitle)
        val key = MediaMenus.PLAYLIST + "Mix"
        assertEquals(listOf("Ember", "Tide Lines"), menus.songs(key).map { it.title })
        val inMix = menus.list(key)
        assertEquals(NodeAction.OpenMedia(MediaKind.SONG, key, 1), inMix[1].action)
        assertEquals(listOf("Move up", "Move down", "Remove from playlist", "Add to Favorites", "Add to playlist"), inMix[0].menu.map { it.label })
        assertEquals(Command.RemoveFromPlaylist("Mix", songs[2].path), inMix[0].menu[2].command)
        // Adding from inside Mix offers the other playlists, not Mix itself.
        assertEquals(listOf("Empty", "New playlist…"), inMix[0].menu[4].submenu.map { it.label })
        assertEquals("This playlist is empty", menus.list(MediaMenus.PLAYLIST + "Empty").single().title)
        assertEquals("Playlists", menus.musicRoot(NowPlaying()).last().title)
    }

    @Test fun favoritesIsAlwaysThereAndOnePressAway() {
        val none = MediaMenus(MediaLibrary(songs = songs))
        // No file yet: still listed (first), empty, and not deletable.
        val fav = none.list(MediaMenus.PLAYLISTS)[1]
        assertEquals("Favorites", fav.title)
        assertTrue(fav.menu.isEmpty())
        assertEquals("Add songs with Options ▸ Add to Favorites", none.list(MediaMenus.PLAYLIST + "Favorites").single().subtitle)
        assertEquals("1 playlist", none.musicRoot(NowPlaying()).last().subtitle)
        val ember = none.list(MediaMenus.SONGS_ALL).first { it.title == "Ember" }
        assertEquals(MenuOptionLabel("Add to Favorites", Command.AddToPlaylist("Favorites", listOf(songs[2].path))), ember.menu.first().let { MenuOptionLabel(it.label, it.command) })
        // A favorite offers the way back; its file can be named in any case.
        val some = MediaMenus(MediaLibrary(songs = songs), playlists = listOf(Playlist("favorites", listOf(songs[2].path))))
        val again = some.list(MediaMenus.SONGS_ALL).first { it.title == "Ember" }
        assertEquals(Command.RemoveFromPlaylist("favorites", songs[2].path), again.menu.first().command)
        assertEquals(listOf("Ember"), some.songs(MediaMenus.PLAYLIST + "Favorites").map { it.title })
        val inFav = some.list(MediaMenus.PLAYLIST + "favorites").single()
        assertEquals(listOf("Move up", "Move down", "Remove from playlist", "Add to playlist"), inFav.menu.map { it.label })
        val favRow = some.list(MediaMenus.PLAYLISTS)[1]
        assertEquals(listOf("Play", "Shuffle", "Use as theme music"), favRow.menu.map { it.label })
        // Albums add to Favorites through their playlist list.
        val album = some.list(MediaMenus.ALBUMS).first()
        assertEquals("favorites", album.menu[0].submenu[0].label)
    }

    private data class MenuOptionLabel(val label: String, val command: Command?)

    @Test fun songsAlbumsAndArtistsAddToPlaylists() {
        val menus = MediaMenus(MediaLibrary(songs = songs), playlists = listOf(Playlist("Mix", listOf(songs[0].path))))
        val song = menus.list(MediaMenus.SONGS_ALL).first { it.title == "Tide Lines" }
        val add = song.menu.first { it.label == "Add to playlist" }
        assertEquals("Already in it", add.submenu[0].detail)
        assertEquals(Command.NewPlaylist(listOf(songs[0].path)), add.submenu.last().command)
        val album = menus.list(MediaMenus.ALBUMS).first { it.title == "Blue Hours" }
        // Album order (track 1 first).
        assertEquals(Command.AddToPlaylist("Mix", listOf(songs[1].path, songs[0].path)), album.menu[0].submenu[1].command)
        val artist = menus.list(MediaMenus.ARTISTS).first { it.title == "Crimson Trio" }
        assertEquals("Add all to playlist", artist.menu.single().label)
    }

    @Test fun sortOrdersFollowTheSettings() {
        fun titles(sort: SongSort) = MediaMenus(MediaLibrary(songs = songs), songSort = sort).list(MediaMenus.SONGS_ALL).map { it.title }
        assertEquals(listOf("Ember", "Ocean Drift", "Tide Lines"), titles(SongSort.TITLE))
        assertEquals(listOf("Tide Lines", "Ocean Drift", "Ember"), titles(SongSort.TITLE_DESC))
        assertEquals(listOf("Ember", "Ocean Drift", "Tide Lines"), titles(SongSort.ARTIST))
        assertEquals(listOf("Tide Lines", "Ember", "Ocean Drift"), titles(SongSort.RECENT))
        fun albums(sort: AlbumSort) = MediaMenus(MediaLibrary(songs = songs), albumSort = sort).list(MediaMenus.ALBUMS).map { it.title }
        assertEquals(listOf("Blue Hours", "Red Shift"), albums(AlbumSort.TITLE))
        assertEquals(listOf("Red Shift", "Blue Hours"), albums(AlbumSort.TITLE_DESC))
        assertEquals(listOf("Red Shift", "Blue Hours"), albums(AlbumSort.ARTIST))
        assertEquals(listOf("Blue Hours", "Red Shift"), albums(AlbumSort.RECENT))
        // The sort lives in Options on All songs (and its songs) and on Albums.
        val root = MediaMenus(MediaLibrary(songs = songs), songSort = SongSort.RECENT).musicRoot(NowPlaying())
        val all = root.first { it.title == "All songs" }
        assertEquals("3 songs · Recently added", all.subtitle)
        val sort = all.menu.single()
        assertEquals(Command.SetSongSort(SongSort.TITLE_DESC), sort.submenu[1].command)
        assertTrue(sort.submenu.single { it.selected }.label == "Recently added")
        assertEquals("Sort albums", root.first { it.title == "Albums" }.menu.single().label)
        assertEquals(SongSort.ARTIST, SongSort.parse("artist"))
        assertEquals(SongSort.TITLE, SongSort.parse("bogus"))
    }
}
