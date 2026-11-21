package dev.xross.launcher.data

import dev.xross.launcher.theme.pack.BgmRef
import dev.xross.launcher.theme.pack.BgmTrack
import java.io.File
import java.util.Locale

/**
 * Settings ▸ Theme ▸ Theme music (0.2.2.0 § 5): the user's choice over the pack's music. A file
 * loops gaplessly; a folder plays its audio files (at most [BgmRef.MAX_TRACKS], each at most
 * [BgmRef.MAX_BYTES]) as a list. Blocking file I/O in [resolve].
 */
object ThemeMusic {
    const val OFF = "none"
    const val SHUFFLE = "SHUFFLE"
    const val IN_ORDER = "IN_ORDER"
    /** `display.bgm` of a Music playlist: this prefix and its name. */
    const val PLAYLIST = "playlist:"

    fun playlist(name: String) = PLAYLIST + name
    fun playlistName(choice: String): String? = choice.trim().takeIf { it.startsWith(PLAYLIST) }?.removePrefix(PLAYLIST)

    /** What plays instead of the pack's music. */
    sealed interface Choice {
        /** Nothing chosen: the pack's cascade decides. */
        data object Theme : Choice
        data object Off : Choice
        /** The chosen file or folder; [track] is null when nothing in it can play. */
        data class Own(val track: BgmTrack?, val skipped: Int = 0) : Choice
    }

    fun normalizeOrder(text: String?): String =
        if (text?.trim()?.uppercase(Locale.ROOT)?.replace(" ", "_") == IN_ORDER) IN_ORDER else SHUFFLE

    fun isAudio(file: File) = file.extension.lowercase(Locale.ROOT) in BgmRef.TYPES

    /**
     * [playlist] gives a Music playlist's file paths by name (null when there is none). A playlist
     * plays its songs as a list in its own order (or shuffled); songs that are gone, too large or
     * of a type ExoPlayer can't decode here are left out.
     */
    fun resolve(choice: String, order: String, playlist: (String) -> List<String>? = { null }): Choice {
        val c = choice.trim()
        if (c.isEmpty()) return Choice.Theme
        if (c.equals(OFF, ignoreCase = true)) return Choice.Off
        playlistName(c)?.let { name ->
            val all = playlist(name) ?: return Choice.Own(null)
            val ok = all.map(::File).filter { it.isFile && isAudio(it) && it.length() in 1..BgmRef.MAX_BYTES }.distinctBy { it.path }.take(BgmRef.MAX_TRACKS)
            return Choice.Own(BgmTrack.of(ok, shuffle = normalizeOrder(order) == SHUFFLE), skipped = all.size - ok.size)
        }
        val f = File(c)
        if (f.isDirectory) {
            val all = f.listFiles().orEmpty().filter { it.isFile && isAudio(it) && !it.name.startsWith(".") }.sortedBy { it.name.lowercase(Locale.ROOT) }
            val ok = all.filter { it.length() in 1..BgmRef.MAX_BYTES }.take(BgmRef.MAX_TRACKS)
            return Choice.Own(BgmTrack.of(ok, shuffle = normalizeOrder(order) == SHUFFLE), skipped = all.size - ok.size)
        }
        if (f.isFile && isAudio(f) && f.length() in 1..BgmRef.MAX_BYTES) return Choice.Own(BgmTrack(f))
        return Choice.Own(null, skipped = 1)
    }

    /** "A folder · 12 tracks, shuffled", for the Settings row. */
    fun describe(choice: String, order: String, packName: String, packHasMusic: Boolean, playlistSize: (String) -> Int? = { null }): String {
        val c = choice.trim()
        val name = playlistName(c)
        return when {
            c.isEmpty() -> if (packHasMusic) "From $packName" else "$packName has none"
            c.equals(OFF, ignoreCase = true) -> "Off"
            name != null -> when (val n = playlistSize(name)) {
                null -> "Playlist $name (missing)"
                else -> "Playlist $name · " + (if (n == 1) "1 song" else "$n songs") +
                    if (n > 1) (if (normalizeOrder(order) == SHUFFLE) ", shuffled" else ", in order") else ""
            }
            File(c).isDirectory -> {
                val n = File(c).listFiles().orEmpty().count { it.isFile && isAudio(it) }.coerceAtMost(BgmRef.MAX_TRACKS)
                "${File(c).name}/ · " + (if (n == 1) "1 track" else "$n tracks") +
                    if (n > 1) (if (normalizeOrder(order) == SHUFFLE) ", shuffled" else ", in order") else ""
            }
            File(c).isFile -> File(c).name
            else -> "${File(c).name} (missing)"
        }
    }
}
