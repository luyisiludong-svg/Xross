package dev.wave.launcher.state

import dev.wave.launcher.media.MediaLibrary
import dev.wave.launcher.media.NowPlaying
import dev.wave.launcher.media.Photo
import dev.wave.launcher.media.Song
import dev.wave.launcher.media.ThumbKind
import dev.wave.launcher.media.Video
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Photo, Music and Video categories. Collections ([photos], [songs], [videos]) back both the
 * XMB lists and the full-screen viewers, so a viewer opened from a list steps through exactly
 * what the list shows.
 *
 * [storageGranted] tells an empty library apart from one Wave isn't allowed to read: media is
 * read through All files access (the same grant as the ROM library), and without it MediaStore
 * returns nothing, which must not be presented as "no photos".
 */
class MediaMenus(private val media: MediaLibrary, private val storageGranted: Boolean = true) {

    private val photosByFolder by lazy { media.photos.groupBy { it.folder } }
    private val videosByFolder by lazy { media.videos.groupBy { it.folder } }
    private val songsSorted by lazy { media.songs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }) }
    private val albums by lazy {
        media.songs.groupBy { it.albumId }.values
            .map { songs -> songs.sortedWith(compareBy({ it.track }, { it.title.lowercase() })) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first().album })
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
        key == ALBUMS -> albums.map(::albumNode)
        key == ARTISTS -> artists.map { (name, songs) -> artistNode(name, songs) }
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
        key.startsWith(ALBUM) -> key.removePrefix(ALBUM).toLongOrNull()
            ?.let { id -> albums.firstOrNull { it.first().albumId == id } }.orEmpty()
        key.startsWith(ARTIST) -> artists[key.removePrefix(ARTIST)].orEmpty()
        else -> emptyList()
    }

    val allSongs: List<Song> get() = songsSorted

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
        add(folder(SONGS_ALL, "All songs", count(media.songs.size, "song"), NodeIcon.Glyph(GlyphKey.MUSIC)))
        add(folder(ALBUMS, "Albums", count(albums.size, "album"), NodeIcon.Glyph(GlyphKey.ALBUM)))
        add(folder(ARTISTS, "Artists", count(artists.size, "artist"), NodeIcon.Glyph(GlyphKey.ARTIST)))
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
        menu = listOf(MenuOption("Set as wallpaper", command = Command.SetWallpaper(p.id))),
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
        subtitle = listOfNotNull(s.artist, s.album.takeIf { showAlbum }, duration(s.durationMs)).joinToString(" · "),
        icon = thumb(ThumbKind.ALBUM_ART, s.id, GlyphKey.MUSIC),
        action = NodeAction.OpenMedia(MediaKind.SONG, key, i),
    )

    private fun albumNode(songs: List<Song>): XmbNode {
        val first = songs.first()
        return folder(ALBUM + first.albumId, first.album, "${first.artist} · ${count(songs.size, "song")}", thumb(ThumbKind.ALBUM_ART, first.id, GlyphKey.ALBUM))
    }

    private fun artistNode(name: String, songs: List<Song>): XmbNode {
        val albumCount = songs.map { it.albumId }.distinct().size
        return folder(ARTIST + name, name, "${count(albumCount, "album")} · ${count(songs.size, "song")}", NodeIcon.Glyph(GlyphKey.ARTIST))
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
                "Press to allow Wave to show your $noun",
                NodeIcon.Glyph(GlyphKey.INFO),
                NodeAction.Run(Command.RequestStorageAccess),
            )
        }

    private fun thumb(kind: ThumbKind, id: Long, fallback: GlyphKey) = NodeIcon.Thumb(kind, id, fallback)

    // endregion

    companion object {
        const val PHOTOS_ALL = "photos:all"
        const val PHOTO_FOLDER = "photos:folder:"
        const val VIDEOS_ALL = "videos:all"
        const val VIDEO_FOLDER = "videos:folder:"
        const val SONGS_ALL = "music:songs"
        const val ALBUMS = "music:albums"
        const val ARTISTS = "music:artists"
        const val ALBUM = "music:album:"
        const val ARTIST = "music:artist:"

        private val DATE: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

        fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

        fun duration(ms: Long): String? {
            if (ms <= 0) return null
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }
}
