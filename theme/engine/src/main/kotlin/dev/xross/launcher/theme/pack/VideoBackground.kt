package dev.xross.launcher.theme.pack

import androidx.compose.runtime.Immutable
import java.io.File
import java.util.Locale

/**
 * How a background video is placed on the screen (`effects.videoFit`, Live backgrounds § 2). The
 * same four placements as the user's wallpaper ([dev.xross.launcher.data.WallpaperFit] in `:app`);
 * `COVER` is accepted for [FILL].
 */
enum class VideoFit {
    /** Covers the screen; the edges may be cut off (the default). */
    FILL,

    /** The whole video, centered; the theme gradient shows around it. */
    FIT,

    /** Covers the screen, distorted to fit. */
    STRETCH,

    /** Actual size (in render pixels), centered; shrinks when larger than the screen. */
    CENTER,
    ;

    /**
     * Where a [videoW]×[videoH] frame lands, centered, in a [boxW]×[boxH] target: (left, bottom,
     * width, height) in target pixels. Pure, so the VIDEO preset's placement is unit tested.
     */
    fun place(videoW: Float, videoH: Float, boxW: Float, boxH: Float): FloatArray {
        if (videoW <= 0f || videoH <= 0f || this == STRETCH) return floatArrayOf(0f, 0f, boxW, boxH)
        val cover = maxOf(boxW / videoW, boxH / videoH)
        val contain = minOf(boxW / videoW, boxH / videoH)
        val scale = when (this) {
            FILL -> cover
            FIT -> contain
            CENTER -> minOf(1f, contain)
            STRETCH -> 1f
        }
        val w = videoW * scale
        val h = videoH * scale
        return floatArrayOf((boxW - w) / 2, (boxH - h) / 2, w, h)
    }

    companion object {
        /** `FILL`, `cover`, `Fit`; null for anything else. */
        fun byName(text: String?): VideoFit? {
            val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (t.equals("COVER", ignoreCase = true)) return FILL
            return entries.firstOrNull { it.name.equals(t, ignoreCase = true) }
        }
    }
}

/**
 * A looping, muted background video (Live backgrounds § 2): `effects.videoPath` from the pack,
 * placed by `effects.videoFit`. [path] null means "no video" (a variant, override or rule turning
 * the pack's off with `"videoPath": "none"`). It cascades like the wallpaper: the base, variants,
 * category and system overrides and rules may each pick another file or none, and the last layer
 * that names one wins. A layer that sets only `videoFit` keeps the video and changes its fit.
 *
 * With the look's shader left at the wave, the built-in VIDEO preset draws it; a CUSTOM shader
 * reads it as `uVideo` instead. Folder packs only, at most [MAX_BYTES].
 */
@Immutable
data class VideoRef(val path: String?, val fit: VideoFit? = null) {
    /** The placement drawn: [fit], else [VideoFit.FILL]. */
    val shownFit: VideoFit get() = fit ?: VideoFit.FILL

    /** [patch] over this: its file when it names one (or none), its fit when it sets one. */
    fun over(patch: VideoRef?): VideoRef = when {
        patch == null -> this
        patch.isFitOnly -> copy(fit = patch.fit)
        else -> VideoRef(patch.path, patch.fit ?: fit)
    }

    /** A layer that sets `videoFit` without `videoPath`. */
    internal val isFitOnly: Boolean get() = path == FIT_ONLY

    companion object {
        val NONE = VideoRef(null)

        /** Video types a background video may be: H.264 / HEVC in MP4, VP9 in WebM. */
        val TYPES = listOf("mp4", "m4v", "webm")

        /** Same cap as the boot video: an `.xtheme` is 50 MB in all, so loops stay short. */
        const val MAX_BYTES = BootVideoRef.MAX_BYTES

        /** Larger frames are decoded but flagged (the copy into GL is capped at 1280 on the long side). */
        const val MAX_SIDE = 1920

        /** The marker [path] of a fit-only layer, never a real file. */
        private const val FIT_ONLY = "\u0000fit"

        /**
         * `effects.videoPath` / `effects.videoFit` as written at [where]: null when neither is set,
         * [NONE] for `none` / `off`, else a pack-relative video path (and its fit). A path that
         * leaves the pack or isn't a video, and an unknown fit, are reported in [problems] and
         * treated as unset.
         */
        fun of(where: String, text: String?, fitText: String?, problems: MutableList<String>): VideoRef? {
            val fit = fitText?.trim()?.takeIf { it.isNotEmpty() }?.let { f ->
                VideoFit.byName(f) ?: null.also {
                    problems += "$where.effects.videoFit: no fit named $f (use ${VideoFit.entries.joinToString { it.name }})"
                }
            }
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return fit?.let { VideoRef(FIT_ONLY, it) }
            if (t.equals("none", ignoreCase = true) || t.equals("off", ignoreCase = true)) return NONE
            val path = t.replace('\\', '/').trimStart('/')
            if (path.split('/').any { it == ".." }) {
                problems += "$where.effects.videoPath: $t must stay inside the pack"
                return fit?.let { VideoRef(FIT_ONLY, it) }
            }
            if (path.substringAfterLast('.', "").lowercase(Locale.ROOT) !in TYPES) {
                problems += "$where.effects.videoPath: $t isn't an MP4 or WebM video"
                return fit?.let { VideoRef(FIT_ONLY, it) }
            }
            return VideoRef(path, fit)
        }

        /** The real file paths among [refs] (no fit-only layers, no NONE). */
        internal fun paths(refs: Collection<VideoRef>): List<String> =
            refs.filterNot { it.isFitOnly }.mapNotNull { it.path }.distinct()
    }
}

/**
 * The files of every video [refs] name, checked inside the folder pack [dir] (missing, oversized
 * and, for the built-in pack, any file are reported and left out, so the look shows no video).
 */
internal fun videoFiles(dir: File?, refs: Collection<VideoRef>, problems: MutableList<String>): Map<String, File> {
    val out = LinkedHashMap<String, File>()
    for (path in VideoRef.paths(refs)) {
        if (dir == null) {
            problems += "Video $path: only folder packs can bring a background video"
            continue
        }
        val file = ThemePackLoader.inside(dir, path)?.takeIf { it.isFile }
        when {
            file == null -> problems += "Video $path not found in the pack"
            file.length() > VideoRef.MAX_BYTES -> problems += "Video $path is larger than ${VideoRef.MAX_BYTES shr 20} MB"
            else -> out[path] = file
        }
    }
    return out
}

/**
 * The look's background video as the app plays it: the file and its placement. [key] changes
 * when the file may have (a hot reload replacing it), so the player restarts.
 */
@Immutable
data class VideoSource(val file: File, val fit: VideoFit, val modified: Long = file.lastModified(), val bytes: Long = file.length()) {
    val key: String get() = "video:${file.path}:$modified:$bytes"
}
