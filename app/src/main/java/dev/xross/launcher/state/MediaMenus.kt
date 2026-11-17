package dev.xross.launcher.state

import dev.xross.launcher.model.GlyphKey
import dev.xross.launcher.data.AlbumSort
import dev.xross.launcher.media.MediaLibrary
import dev.xross.launcher.media.NowPlaying
import dev.xross.launcher.media.Photo
import dev.xross.launcher.media.Playlist
import dev.xross.launcher.media.Song
import dev.xross.launcher.data.SongSort
import dev.xross.launcher.data.WallpaperFit
import dev.xross.launcher.media.ThumbKind
import dev.xross.launcher.media.Video
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Photo, Music and Video categories. Collections ([photos], [songs], [videos]) back both the
 * XMB lists and the full-screen viewers, so a viewer opened from a list steps through exactly
 * what the list shows.
 *
 * [storageGranted] tells an empty library apart from one Xross isn't allowed to read: media is
 * read through All files access (the same grant as the ROM library), and without it MediaStore
 * returns nothing, which must not be presented as "no photos".
 */
class MediaMenus(
    private val media: MediaLibrary,
    private val storageGranted: Boolean = true,
    /** `Xross/playlists/<name>.m3u8`, as file paths; resolved against the library here. */
    private val playlists: List<Playlist> = emptyList(),
    private val songSort: SongSort = SongSort.TITLE,
    private val albumSort: AlbumSort = AlbumSort.TITLE,
    /** For the photos' Options ▸ Wallpaper fit. */
    private val wallpaperFit: WallpaperFit = WallpaperFit.FILL,
) {

    private val photosByFolder by lazy { media.photos.groupBy { it.folder } }
    private val videosByFolder by lazy { media.videos.groupBy { it.folder } }
    private val songsSorted by lazy { sorted(media.songs, songSort) }
    private val songsByPath by lazy { media.songs.filter { it.path.isNotEmpty() }.associateBy { it.path } }

    /** A playlist's songs in its order; entries whose file is gone are left out. */
    private fun resolve(p: Playlist): List<Song> = p.paths.mapNotNull { songsByPath[it] }
    /**
     * Albums: MediaStore's album, split by folder when one album id spans several folders. The
     * same album ripped twice (FLAC and MP3 with identical tags) shares an id, and would otherwise
     * list every track twice; each copy becomes its own album, labelled by format or folder.
     */
    private val albums: List<AlbumGroup> by lazy {
        media.songs.groupBy { it.albumId }.flatMap { (id, all) ->
            val byFolder = all.groupBy { it.folder }
            byFolder.map { (folder, songs) ->
                AlbumGroup(
                    key = if (byFolder.size == 1) ALBUM + id else "$ALBUM$id$COPY$folder",
                    songs = songs.sortedWith(compareBy({ it.track }, { it.title.lowercase() })),
                    copyLabel = if (byFolder.size == 1) null else copyLabel(songs, byFolder.values),
                )
            }
        }.sortedWith(albumOrder())
    }

    private fun sorted(songs: List<Song>, sort: SongSort): List<Song> {
        val byTitle = compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        return when (sort) {
            SongSort.TITLE -> songs.sortedWith(byTitle)
            SongSort.TITLE_DESC -> songs.sortedWith(byTitle.reversed())
            SongSort.ARTIST -> songs.sortedWith(
                compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }.thenBy { it.track }.then(byTitle),
            )
            SongSort.ALBUM -> songs.sortedWith(compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.album }.thenBy { it.track }.then(byTitle))
            SongSort.RECENT -> songs.sortedWith(compareByDescending<Song> { it.addedAt }.then(byTitle))
        }
    }

    private fun albumOrder(): Comparator<AlbumGroup> {
        val byTitle = compareBy<AlbumGroup, String>(String.CASE_INSENSITIVE_ORDER) { it.songs.first().album }.thenBy { it.copyLabel.orEmpty() }
        return when (albumSort) {
            AlbumSort.TITLE -> byTitle
            AlbumSort.TITLE_DESC -> byTitle.reversed()
            AlbumSort.ARTIST -> compareBy<AlbumGroup, String>(String.CASE_INSENSITIVE_ORDER) { it.songs.first().artist }.then(byTitle)
            AlbumSort.RECENT -> compareByDescending<AlbumGroup> { g -> g.songs.maxOf { it.addedAt } }.then(byTitle)
        }
    }

    /** One album on screen, possibly one copy of an album that exists in several folders. */
    private data class AlbumGroup(val key: String, val songs: List<Song>, val copyLabel: String?) {
        val title: String get() = songs.first().album + (copyLabel?.let { " · $it" } ?: "")
    }

    /** Song id → its album copy's label, for songs whose album exists in several folders. */
    private val copyOfSong: Map<Long, String> by lazy {
        albums.filter { it.copyLabel != null }.flatMap { g -> g.songs.map { it.id to g.copyLabel!! } }.toMap()
    }

    /** "FLAC" when the copies differ in format, else the folder name ("Disc rip", "Music"). */
    private fun copyLabel(songs: List<Song>, copies: Collection<List<Song>>): String {
        fun dominant(s: List<Song>) = s.groupingBy { it.format }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        val format = dominant(songs)
        val formats = copies.map(::dominant)
        return if (format.isNotEmpty() && formats.count { it == format } == 1) format else songs.first().folder.substringAfterLast('/')
    }
    private val artists by lazy {
        media.songs.groupBy { it.artist }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .mapValues { (_, songs) -> songs.sortedWith(compareBy({ it.album.lowercase() }, { it.track }, { it.title.lowercase() })) }
    }

    fun handles(key: String) = key.startsWith("photos:") || key.startsWith("music:") || key.startsWith("videos:")

    fun list(key: String): List<XmbNode> = when {
        key == PHOTOS_ALL || key.startsWith(PHOTO_FOLDER) -> photos(key).mapIndexed { i, p -> photoNode(p, key, i) }
        key == VIDEOS_ALL || key.startsWith(VIDEO_FOLDER) -> videos(key).mapIndexed { i, v -> videoNode(v, key, i) }
        key == SONGS_ALL || key.startsWith(ALBUM) || key.startsWith(ARTIST) ->
            songs(key).mapIndexed { i, s -> songNode(s, key, i, showAlbum = !key.startsWith(ALBUM)) }
        key.startsWith(PLAYLIST) -> playlistRows(key)
        key == ALBUMS -> albums.map(::albumNode)
        key == ARTISTS -> artists.map { (name, songs) -> artistNode(name, songs) }
        key == PLAYLISTS -> playlistsRows()
        else -> emptyList()
    }

    // region Collections

    fun photos(key: String): List<Photo> = when {
        key == PHOTOS_ALL -> media.photos
        key.startsWith(PHOTO_FOLDER) -> photosByFolder[key.removePrefix(PHOTO_FOLDER)].orEmpty()
        else -> emptyList()
    }

    fun videos(key: String): List<Video> = when {
        key == VIDEOS_ALL -> media.videos
        key.startsWith(VIDEO_FOLDER) -> videosByFolder[key.removePrefix(VIDEO_FOLDER)].orEmpty()
        else -> emptyList()
    }

    fun songs(key: String): List<Song> = when {
        key == SONGS_ALL -> songsSorted
        key.startsWith(ALBUM) -> albums.firstOrNull { it.key == key }?.songs
            // A key without a folder (saved before albums were split) opens the first copy.
            ?: key.removePrefix(ALBUM).substringBefore(COPY).toLongOrNull()
                ?.let { id -> albums.firstOrNull { it.songs.first().albumId == id } }?.songs.orEmpty()
        key.startsWith(ARTIST) -> artists[key.removePrefix(ARTIST)].orEmpty()
        key.startsWith(PLAYLIST) -> playlist(key)?.let(::resolve).orEmpty()
        else -> emptyList()
    }

    val allSongs: List<Song> get() = songsSorted

    private fun playlist(key: String): Playlist? = playlistNamed(key.removePrefix(PLAYLIST))

    fun playlistNamed(name: String): Playlist? = playlists.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** The songs of playlist [name] that are still in the library. */
    fun playlistSongs(name: String): List<Song> = playlistNamed(name)?.let(::resolve).orEmpty()

    /** Whether [path] is a song in the library (playlist moves skip the missing ones). */
    fun hasSong(path: String) = path in songsByPath

    // endregion

    // region Category roots

    fun photoRoot(): List<XmbNode> {
        if (media.photos.isEmpty()) return listOf(empty("photo", "photos", "No photos found", "Pictures in DCIM, Pictures and other folders appear here"))
        return buildList {
            add(folder(PHOTOS_ALL, "All photos", count(media.photos.size, "photo"), thumb(ThumbKind.PHOTO, media.photos.first().id, GlyphKey.PHOTO)))
            for ((name, photos) in photosByFolder.toSortedMap(String.CASE_INSENSITIVE_ORDER)) {
                add(folder(PHOTO_FOLDER + name, name, count(photos.size, "photo"), thumb(ThumbKind.PHOTO, photos.first().id, GlyphKey.FOLDER)))
            }
        }
    }

    fun videoRoot(): List<XmbNode> {
        if (media.videos.isEmpty()) return listOf(empty("video", "videos", "No videos found", "Videos in Movies, DCIM and other folders appear here"))
        return buildList {
            add(folder(VIDEOS_ALL, "All videos", count(media.videos.size, "video"), thumb(ThumbKind.VIDEO, media.videos.first().id, GlyphKey.VIDEO)))
            for ((name, videos) in videosByFolder.toSortedMap(String.CASE_INSENSITIVE_ORDER)) {
                add(folder(VIDEO_FOLDER + name, name, count(videos.size, "video"), thumb(ThumbKind.VIDEO, videos.first().id, GlyphKey.FOLDER)))
            }
        }
    }

    /** Not cached: the Now Playing row follows playback. */
    fun musicRoot(now: NowPlaying): List<XmbNode> = buildList {
        now.song?.let { song ->
            add(
                XmbNode(
                    id = "music:now",
                    title = if (now.isPlaying) "Now playing" else "Paused",
                    subtitle = "${song.title} · ${song.artist}",
                    icon = thumb(ThumbKind.ALBUM_ART, song.id, GlyphKey.MUSIC),
                    action = NodeAction.Run(Command.OpenNowPlaying),
                ),
            )
        }
        if (media.songs.isEmpty()) {
            add(empty("music", "music", "No music found", "MP3, FLAC, OGG, WAV and AAC files in Music and other folders appear here"))
            return@buildList
        }
        add(XmbNode("music:shuffle", "Shuffle all", count(media.songs.size, "song"), NodeIcon.Glyph(GlyphKey.SHUFFLE), NodeAction.Run(Command.ShuffleAllSongs)))
        add(folder(SONGS_ALL, "All songs", "${count(media.songs.size, "song")} · ${songSort.label}", NodeIcon.Glyph(GlyphKey.MUSIC)).copy(menu = songSortMenu()))
        add(folder(ALBUMS, "Albums", "${count(albums.size, "album")} · ${albumSort.label}", NodeIcon.Glyph(GlyphKey.ALBUM)).copy(menu = albumSortMenu()))
        add(folder(ARTISTS, "Artists", count(artists.size, "artist"), NodeIcon.Glyph(GlyphKey.ARTIST)))
        add(folder(PLAYLISTS, "Playlists", count(playlists.size, "playlist"), NodeIcon.Glyph(GlyphKey.FAVORITES)))
    }

    // endregion

    // region Nodes

    private fun photoNode(p: Photo, key: String, i: Int) = XmbNode(
        id = "photo:${p.id}",
        title = p.name.substringBeforeLast('.'),
        subtitle = listOfNotNull(
            p.takenAt.takeIf { it > 0 }?.let { DATE.format(Date(it)) },
            if (p.width > 0 && p.height > 0) "${p.width}×${p.height}" else null,
        ).joinToString(" · "),
        icon = thumb(ThumbKind.PHOTO, p.id, GlyphKey.PHOTO),
        action = NodeAction.OpenMedia(MediaKind.PHOTO, key, i),
        menu = photoOptions(p.id, customWallpaper = false, fit = wallpaperFit),
    )

    private fun videoNode(v: Video, key: String, i: Int) = XmbNode(
        id = "video:${v.id}",
        title = v.name.substringBeforeLast('.'),
        subtitle = listOfNotNull(
            duration(v.durationMs),
            if (v.width > 0 && v.height > 0) "${v.width}×${v.height}" else null,
            MenuTree.formatSize(v.sizeBytes),
        ).joinToString(" · "),
        icon = thumb(ThumbKind.VIDEO, v.id, GlyphKey.VIDEO),
        action = NodeAction.OpenMedia(MediaKind.VIDEO, key, i),
    )

    private fun songNode(s: Song, key: String, i: Int, showAlbum: Boolean) = XmbNode(
        id = "song:${s.id}",
        title = s.title,
        subtitle = listOfNotNull(s.artist, s.album.takeIf { showAlbum }, copyOfSong[s.id], duration(s.durationMs)).joinToString(" · "),
        icon = thumb(ThumbKind.ALBUM_ART, s.id, GlyphKey.MUSIC),
        action = NodeAction.OpenMedia(MediaKind.SONG, key, i),
        menu = songMenu(s, key),
    )

    private fun songMenu(s: Song, key: String): List<MenuOption> = buildList {
        val inPlaylist = if (key.startsWith(PLAYLIST)) playlist(key) else null
        if (inPlaylist != null) {
            add(MenuOption("Move up", command = Command.MoveInPlaylist(inPlaylist.name, s.path, -1)))
            add(MenuOption("Move down", command = Command.MoveInPlaylist(inPlaylist.name, s.path, 1)))
            add(MenuOption("Remove from playlist", command = Command.RemoveFromPlaylist(inPlaylist.name, s.path)))
        }
        if (s.path.isNotEmpty()) add(addToPlaylist("Add to playlist", listOf(s.path), except = inPlaylist?.name))
        if (key == SONGS_ALL) add(songSortSubmenu())
    }

    /** "Add to playlist" ▸ each playlist (but [except], the one open), then New playlist…. */
    private fun addToPlaylist(label: String, paths: List<String>, except: String? = null) = MenuOption(
        label,
        submenu = playlists.filter { it.name != except }.map { p ->
            val has = paths.all { it in p.paths }
            MenuOption(p.name, detail = if (has) "Already in it" else count(p.paths.size, "song"), command = Command.AddToPlaylist(p.name, paths))
        } + MenuOption("New playlist…", command = Command.NewPlaylist(paths)),
    )

    private fun songSortSubmenu() = MenuOption(
        "Sort songs",
        detail = songSort.label,
        submenu = SongSort.entries.map { MenuOption(it.label, command = Command.SetSongSort(it), selected = it == songSort) },
    )

    private fun albumSortSubmenu() = MenuOption(
        "Sort albums",
        detail = albumSort.label,
        submenu = AlbumSort.entries.map { MenuOption(it.label, command = Command.SetAlbumSort(it), selected = it == albumSort) },
    )

    private fun songSortMenu() = listOf(songSortSubmenu())

    private fun albumSortMenu() = listOf(albumSortSubmenu())

    private fun playlistsRows(): List<XmbNode> = buildList {
        add(XmbNode("music:playlist-new", "New playlist", "Then add songs from their Options", NodeIcon.Glyph(GlyphKey.ADD), NodeAction.Run(Command.NewPlaylist())))
        for (p in playlists) {
            val songs = resolve(p)
            val missing = p.paths.size - songs.size
            add(
                XmbNode(
                    id = PLAYLIST + p.name,
                    title = p.name,
                    subtitle = count(songs.size, "song") + (if (missing > 0) " · $missing missing" else "") +
                        (duration(songs.sumOf { it.durationMs })?.let { " · $it" } ?: ""),
                    icon = songs.firstOrNull()?.let { thumb(ThumbKind.ALBUM_ART, it.id, GlyphKey.FAVORITES) } ?: NodeIcon.Glyph(GlyphKey.FAVORITES),
                    action = NodeAction.OpenList(PLAYLIST + p.name),
                    menu = buildList {
                        if (songs.isNotEmpty()) {
                            add(MenuOption("Play", command = Command.PlayPlaylist(p.name, shuffle = false)))
                            add(MenuOption("Shuffle", command = Command.PlayPlaylist(p.name, shuffle = true)))
                        }
                        add(
                            MenuOption(
                                "Delete playlist",
                                submenu = listOf(
                                    MenuOption("Delete \"${p.name}\"", detail = "Removes the file; the songs stay", command = Command.DeletePlaylist(p.name)),
                                ),
                            ),
                        )
                    },
                ),
            )
        }
    }

    private fun playlistRows(key: String): List<XmbNode> {
        val p = playlist(key) ?: return emptyList()
        val songs = resolve(p)
        if (songs.isEmpty()) {
            val subtitle = if (p.paths.isEmpty()) "Add songs with Options ▸ Add to playlist" else "Its ${count(p.paths.size, "song")} aren't in the library"
            return listOf(XmbNode("music:playlist-empty", "This playlist is empty", subtitle, NodeIcon.Glyph(GlyphKey.INFO)))
        }
        return songs.mapIndexed { i, s -> songNode(s, key, i, showAlbum = true) }
    }

    private fun albumNode(album: AlbumGroup): XmbNode {
        val first = album.songs.first()
        return folder(album.key, album.title, "${first.artist} · ${count(album.songs.size, "song")}", thumb(ThumbKind.ALBUM_ART, first.id, GlyphKey.ALBUM))
            .copy(menu = listOfNotNull(pathsOf(album.songs)?.let { addToPlaylist("Add album to playlist", it) }, albumSortSubmenu()))
    }

    private fun pathsOf(songs: List<Song>) = songs.map { it.path }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }

    private fun artistNode(name: String, songs: List<Song>): XmbNode {
        val albumCount = songs.map { it.albumId to it.folder }.distinct().size
        return folder(ARTIST + name, name, "${count(albumCount, "album")} · ${count(songs.size, "song")}", NodeIcon.Glyph(GlyphKey.ARTIST))
            .copy(menu = listOfNotNull(pathsOf(songs)?.let { addToPlaylist("Add all to playlist", it) }))
    }

    private fun folder(key: String, title: String, subtitle: String, icon: NodeIcon) =
        XmbNode(key, title, subtitle, icon, NodeAction.OpenList(key))

    /** Empty-category row; without storage access it asks for it instead, and opens the grant. */
    private fun empty(id: String, noun: String, title: String, subtitle: String) =
        if (storageGranted) {
            XmbNode("$id:empty", title, subtitle, NodeIcon.Glyph(GlyphKey.INFO))
        } else {
            XmbNode(
                "$id:access",
                "Storage access needed",
                "Press to allow Xross to show your $noun",
                NodeIcon.Glyph(GlyphKey.INFO),
                NodeAction.Run(Command.RequestStorageAccess),
            )
        }

    private fun thumb(kind: ThumbKind, id: Long, fallback: GlyphKey) = NodeIcon.Thumb(kind, id, fallback)

    // endregion

    companion object {
        /**
         * A photo's Options: Xross's own wallpaper (behind the menu) or the Android one, and, while
         * a Xross wallpaper is set ([customWallpaper]), back to the theme's.
         */
        fun photoOptions(photoId: Long, customWallpaper: Boolean, fit: WallpaperFit = WallpaperFit.FILL): List<MenuOption> = buildList {
            add(MenuOption("Set as Xross wallpaper", detail = "Behind the menu", command = Command.SetXrossWallpaper(photoId)))
            add(MenuOption("Set as Android wallpaper", command = Command.SetWallpaper(photoId)))
            if (customWallpaper) add(MenuOption("Use the theme's wallpaper", command = Command.ResetXrossWallpaper))
        }

        const val PHOTOS_ALL = "photos:all"
        const val PHOTO_FOLDER = "photos:folder:"
        const val VIDEOS_ALL = "videos:all"
        const val VIDEO_FOLDER = "videos:folder:"
        const val SONGS_ALL = "music:songs"
        const val ALBUMS = "music:albums"
        const val ARTISTS = "music:artists"
        const val ALBUM = "music:album:"
        /** Separates the album id from the folder in the key of one copy of a split album. */
        const val COPY = "|"
        const val ARTIST = "music:artist:"
        const val PLAYLISTS = "music:playlists"
        const val PLAYLIST = "music:playlist:"

        private val DATE: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

            add(wallpaperFitMenu(fit))
        fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

        fun duration(ms: Long): String? {
            if (ms <= 0) return null
        /** Xross wallpaper ▸ Wallpaper fit: Fill, Fit, Stretch or Center, the current one marked. */
        fun wallpaperFitMenu(fit: WallpaperFit) = MenuOption(
            "Wallpaper fit",
            detail = fit.label,
            submenu = WallpaperFit.entries.map { MenuOption(it.label, detail = it.detail, command = Command.SetWallpaperFit(it), selected = it == fit) },
        )

            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }
}
