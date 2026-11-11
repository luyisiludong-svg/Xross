package dev.xross.launcher.media

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Photo(
    val id: Long,
    val uri: Uri,
    val name: String,
    val folder: String,
    val takenAt: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    /** EXIF rotation in degrees; 90/270 swap the displayed width and height. */
    val orientation: Int = 0,
) {
    val displayWidth: Int get() = if (orientation % 180 == 0) width else height
    val displayHeight: Int get() = if (orientation % 180 == 0) height else width
}

data class Video(
    val id: Long,
    val uri: Uri,
    val name: String,
    val folder: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

data class Song(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val track: Int,
    val durationMs: Long,
    /** Folder the file is in; tells copies of one album apart (a FLAC rip next to an MP3 one). */
    val folder: String = "",
    /** "FLAC", "MP3", "AAC"… from the MIME type; empty when unknown. */
    val format: String = "",
)

data class MediaLibrary(
    val photos: List<Photo> = emptyList(),
    val videos: List<Video> = emptyList(),
    val songs: List<Song> = emptyList(),
)

enum class ThumbKind { PHOTO, VIDEO, ALBUM_ART }

/** Custom folders for Photo and Video; an empty list means everything MediaStore knows. */
data class MediaFolders(val photo: List<String> = emptyList(), val video: List<String> = emptyList())

/**
 * Photos, music and videos from MediaStore (which already parses ID3/Vorbis tags and EXIF).
 * Reloads automatically when MediaStore reports changes, debounced so a bulk copy triggers one
 * reload.
 *
 * With custom [MediaFolders], Photo and Video show only those folders: MediaStore is filtered by
 * path, and a direct walk adds files it doesn't index (`.nomedia` and hidden folders, some USB
 * drives). Walked files get negative ids, a `content://` URI from Xross's FileProvider (so the
 * viewer, the player and "Set as wallpaper" work as for any photo), and thumbnails made here.
 */
class MediaRepository(private val context: Context) {
    private val resolver = context.contentResolver

    @Volatile private var folders = MediaFolders()

    /** Files found by the folder walk, by their synthetic id, for thumbnails. */
    private val walked = java.util.concurrent.ConcurrentHashMap<Long, java.io.File>()

    /** Sets the custom folders; returns true when they changed (the caller refreshes). */
    fun setFolders(next: MediaFolders): Boolean {
        if (next == folders) return false
        folders = next
        return true
    }
    private val _library = MutableStateFlow(MediaLibrary())
    val library: StateFlow<MediaLibrary> = _library.asStateFlow()

    private val thumbs = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private var observer: ContentObserver? = null
    private var reloadJob: Job? = null

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val f = folders
        _library.value = MediaLibrary(
            photos = runCatching { photosIn(f.photo) }.getOrDefault(emptyList()),
            videos = runCatching { videosIn(f.video) }.getOrDefault(emptyList()),
            songs = runCatching { querySongs() }.getOrDefault(emptyList()),
        )
    }

    private fun photosIn(custom: List<String>): List<Photo> {
        if (custom.isEmpty()) return queryPhotos(null).map { it.first }
        val indexed = queryPhotos(custom)
        val known = indexed.mapNotNullTo(HashSet()) { it.second }
        val extra = MediaFolderScan.walk(custom, MediaFolderScan.PHOTO_EXTENSIONS)
            .filter { it.absolutePath !in known }
            .mapNotNull(::photoFromFile)
        return (indexed.map { it.first } + extra).sortedByDescending { it.takenAt }
    }

    private fun videosIn(custom: List<String>): List<Video> {
        if (custom.isEmpty()) return queryVideos(null).map { it.first }
        val indexed = queryVideos(custom)
        val known = indexed.mapNotNullTo(HashSet()) { it.second }
        val extra = MediaFolderScan.walk(custom, MediaFolderScan.VIDEO_EXTENSIONS)
            .filter { it.absolutePath !in known }
            .mapNotNull(::videoFromFile)
        return (indexed.map { it.first } + extra).sortedByDescending { it.modifiedAt }
    }

    private fun shareUri(file: java.io.File): Uri? = runCatching {
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }.getOrNull()

    private fun photoFromFile(file: java.io.File): Photo? {
        val uri = shareUri(file) ?: return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { android.graphics.BitmapFactory.decodeFile(file.path, bounds) }
        val orientation = runCatching {
            when (android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
        val id = MediaFolderScan.syntheticId(file.absolutePath)
        walked[id] = file
        return Photo(
            id, uri, file.name, file.parentFile?.name ?: "Other", file.lastModified(),
            bounds.outWidth.coerceAtLeast(0), bounds.outHeight.coerceAtLeast(0), file.length(), orientation,
        )
    }

    private fun videoFromFile(file: java.io.File): Video? {
        val uri = shareUri(file) ?: return null
        var duration = 0L
        var width = 0
        var height = 0
        runCatching {
            android.media.MediaMetadataRetriever().run {
                try {
                    setDataSource(file.path)
                    duration = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    width = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    height = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                } finally {
                    release()
                }
            }
        }
        val id = MediaFolderScan.syntheticId(file.absolutePath)
        walked[id] = file
        return Video(id, uri, file.name, file.parentFile?.name ?: "Other", duration, width, height, file.length(), file.lastModified())
    }

    /** Starts watching MediaStore; call once with a long-lived scope. */
    fun watch(scope: CoroutineScope) {
        if (observer != null) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reloadJob?.cancel()
                reloadJob = scope.launch {
                    delay(1500)
                    refresh()
                }
            }
        }.also { obs ->
            for (uri in listOf(IMAGES, VIDEOS, AUDIO)) resolver.registerContentObserver(uri, true, obs)
        }
    }

    suspend fun thumbnail(kind: ThumbKind, id: Long, sizePx: Int): ImageBitmap? {
        val key = "$kind:$id:$sizePx"
        thumbs.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { loadThumb(kind, id, sizePx) }.getOrNull()?.asImageBitmap()
        }?.also { thumbs.put(key, it) }
    }

    @Suppress("DEPRECATION")
    private fun loadThumb(kind: ThumbKind, id: Long, sizePx: Int): Bitmap? {
        if (id < 0) return walked[id]?.let { localThumb(kind, it, sizePx) }
        val uri = when (kind) {
            ThumbKind.PHOTO -> ContentUris.withAppendedId(IMAGES, id)
            ThumbKind.VIDEO -> ContentUris.withAppendedId(VIDEOS, id)
            ThumbKind.ALBUM_ART -> ContentUris.withAppendedId(AUDIO, id)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return resolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
        }
        return when (kind) {
            ThumbKind.PHOTO -> MediaStore.Images.Thumbnails.getThumbnail(resolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
            ThumbKind.VIDEO -> MediaStore.Video.Thumbnails.getThumbnail(resolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            ThumbKind.ALBUM_ART -> android.media.MediaMetadataRetriever().run {
                try {
                    setDataSource(context, uri)
                    embeddedPicture?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                } finally {
                    release()
                }
            }
        }
    }

    /** Thumbnail for a walked file: a downsampled, EXIF-rotated decode, or a video frame. */
    private fun localThumb(kind: ThumbKind, file: java.io.File, sizePx: Int): Bitmap? = when (kind) {
        ThumbKind.PHOTO -> {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            val degrees = runCatching {
                when (android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }.getOrDefault(0f)
            if (bitmap == null || degrees == 0f) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, android.graphics.Matrix().apply { postRotate(degrees) }, true)
            }
        }
        ThumbKind.VIDEO -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.media.ThumbnailUtils.createVideoThumbnail(file, Size(sizePx, sizePx), null)
        } else {
            android.media.MediaMetadataRetriever().run {
                try {
                    setDataSource(file.path)
                    frameAtTime?.let { frame ->
                        val scale = sizePx.toFloat() / maxOf(frame.width, frame.height)
                        if (scale >= 1f) frame else Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true)
                    }
                } finally {
                    release()
                }
            }
        }
        ThumbKind.ALBUM_ART -> null
    }

    // region Queries

    /** Photos with their file path (null when MediaStore doesn't report one); [inFolders] null = all. */
    @Suppress("DEPRECATION") // DATA is the only path column on Android 9; readable with All files access
    private fun queryPhotos(inFolders: List<String>?): List<Pair<Photo, String?>> {
        val cols = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.ORIENTATION, MediaStore.Images.Media.DATA,
        )
        val selection = inFolders?.let { MediaFolderScan.selection(MediaStore.Images.Media.DATA, it) }
        val args = inFolders?.let { MediaFolderScan.selectionArgs(it) }
        return resolver.query(IMAGES, cols, selection, args, "${MediaStore.Images.Media.DATE_MODIFIED} DESC, ${MediaStore.Images.Media.DISPLAY_NAME} COLLATE NOCASE ASC")?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val taken = c.getLong(3).takeIf { it > 0 } ?: (c.getLong(4) * 1000)
                    add(
                        Photo(
                            id, ContentUris.withAppendedId(IMAGES, id), c.getString(1).orEmpty(),
                            c.getString(2) ?: "Other", taken, c.getInt(5), c.getInt(6), c.getLong(7),
                            orientation = c.getInt(8),
                        ) to c.getString(9),
                    )
                }
            }
        }.orEmpty()
    }

    @Suppress("DEPRECATION")
    private fun queryVideos(inFolders: List<String>?): List<Pair<Video, String?>> {
        val cols = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.DATA,
        )
        val selection = inFolders?.let { MediaFolderScan.selection(MediaStore.Video.Media.DATA, it) }
        val args = inFolders?.let { MediaFolderScan.selectionArgs(it) }
        return resolver.query(VIDEOS, cols, selection, args, "${MediaStore.Video.Media.DATE_MODIFIED} DESC, ${MediaStore.Video.Media.DISPLAY_NAME} COLLATE NOCASE ASC")?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    add(
                        Video(
                            id, ContentUris.withAppendedId(VIDEOS, id), c.getString(1).orEmpty(),
                            c.getString(2) ?: "Other", c.getLong(3), c.getInt(4), c.getInt(5), c.getLong(6),
                            c.getLong(7) * 1000,
                        ) to c.getString(8),
                    )
                }
            }
        }.orEmpty()
    }

    private fun querySongs(): List<Song> {
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DISPLAY_NAME,
            @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.MIME_TYPE,
        )
        // Music only: skip ringtones, notification sounds and voice notes.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        return resolver.query(AUDIO, cols, selection, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val fileName = c.getString(7).orEmpty().substringBeforeLast('.')
                    add(
                        Song(
                            id, ContentUris.withAppendedId(AUDIO, id),
                            title = c.getString(1)?.takeIf { it.isNotBlank() } ?: fileName,
                            artist = c.getString(2).cleanTag() ?: UNKNOWN_ARTIST,
                            album = c.getString(3).cleanTag() ?: UNKNOWN_ALBUM,
                            albumId = c.getLong(4),
                            // MediaStore encodes disc*1000 + track.
                            track = c.getInt(5) % 1000,
                            durationMs = c.getLong(6),
                            folder = c.getString(8).orEmpty().substringBeforeLast('/', ""),
                            format = formatOf(c.getString(9)),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    // endregion

    companion object {
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val UNKNOWN_ALBUM = "Unknown album"

        val IMAGES: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val VIDEOS: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val AUDIO: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        private fun String?.cleanTag(): String? = this?.takeIf { it.isNotBlank() && it != "<unknown>" }

        /** Short format label for an audio MIME type ("audio/flac" → "FLAC"). */
        fun formatOf(mime: String?): String = when (mime?.lowercase()?.substringAfter('/')) {
            null, "" -> ""
            "flac", "x-flac" -> "FLAC"
            "mpeg", "mp3", "mpeg3", "x-mpeg" -> "MP3"
            "mp4", "aac", "x-m4a", "m4a", "aac-adts" -> "AAC"
            "ogg", "vorbis", "x-vorbis+ogg" -> "OGG"
            "opus" -> "Opus"
            "wav", "x-wav", "vnd.wave" -> "WAV"
            "x-ms-wma" -> "WMA"
            "alac" -> "ALAC"
            "x-aiff", "aiff" -> "AIFF"
            else -> mime.substringAfter('/').removePrefix("x-").uppercase()
        }
    }
}
