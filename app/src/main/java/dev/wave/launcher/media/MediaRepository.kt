package dev.wave.launcher.media

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
)

data class MediaLibrary(
    val photos: List<Photo> = emptyList(),
    val videos: List<Video> = emptyList(),
    val songs: List<Song> = emptyList(),
)

enum class ThumbKind { PHOTO, VIDEO, ALBUM_ART }

/**
 * Photos, music and videos from MediaStore (which already parses ID3/Vorbis tags and EXIF).
 * Reloads automatically when MediaStore reports changes, debounced so a bulk copy triggers one
 * reload.
 */
class MediaRepository(private val context: Context) {
    private val resolver = context.contentResolver
    private val _library = MutableStateFlow(MediaLibrary())
    val library: StateFlow<MediaLibrary> = _library.asStateFlow()

    private val thumbs = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private var observer: ContentObserver? = null
    private var reloadJob: Job? = null

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _library.value = MediaLibrary(
            photos = runCatching { queryPhotos() }.getOrDefault(emptyList()),
            videos = runCatching { queryVideos() }.getOrDefault(emptyList()),
            songs = runCatching { querySongs() }.getOrDefault(emptyList()),
        )
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

    // region Queries

    private fun queryPhotos(): List<Photo> {
        val cols = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.ORIENTATION,
        )
        return resolver.query(IMAGES, cols, null, null, "${MediaStore.Images.Media.DATE_MODIFIED} DESC, ${MediaStore.Images.Media.DISPLAY_NAME} COLLATE NOCASE ASC")?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val taken = c.getLong(3).takeIf { it > 0 } ?: (c.getLong(4) * 1000)
                    add(
                        Photo(
                            id, ContentUris.withAppendedId(IMAGES, id), c.getString(1).orEmpty(),
                            c.getString(2) ?: "Other", taken, c.getInt(5), c.getInt(6), c.getLong(7),
                            orientation = c.getInt(8),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private fun queryVideos(): List<Video> {
        val cols = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
        )
        return resolver.query(VIDEOS, cols, null, null, "${MediaStore.Video.Media.DATE_MODIFIED} DESC, ${MediaStore.Video.Media.DISPLAY_NAME} COLLATE NOCASE ASC")?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    add(
                        Video(
                            id, ContentUris.withAppendedId(VIDEOS, id), c.getString(1).orEmpty(),
                            c.getString(2) ?: "Other", c.getLong(3), c.getInt(4), c.getInt(5), c.getLong(6),
                            c.getLong(7) * 1000,
                        ),
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
    }
}
