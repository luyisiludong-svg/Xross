package dev.xross.launcher.launch

import java.io.File

/** Reads the track list of a `.cue` sheet for emulators that need a track instead ([EmulatorDef.resolveCue]). */
object CueSheet {
    /** `FILE "Game (Track 1).bin" BINARY`; the name may also be unquoted. */
    private val FILE_LINE = Regex("""^\s*FILE\s+(?:"([^"]+)"|(\S+))""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /** Cue sheets are a few hundred bytes; anything bigger isn't one. */
    private const val MAX_BYTES = 64 * 1024

    /** The first track's name as written in [text], without any folder part; null when there is none. */
    fun firstTrackName(text: String): String? {
        val m = FILE_LINE.find(text) ?: return null
        val name = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
        return name.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() }
    }

    /**
     * The track file [cue] starts with, found beside it (by exact name, else ignoring case); null
     * when [cue] isn't a readable cue sheet or the track is missing.
     */
    fun firstTrack(cue: File): File? {
        if (!cue.extension.equals("cue", ignoreCase = true) || !cue.isFile || cue.length() > MAX_BYTES) return null
        val name = runCatching { firstTrackName(cue.readText()) }.getOrNull() ?: return null
        val dir = cue.parentFile ?: return null
        File(dir, name).takeIf { it.isFile }?.let { return it }
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
    }
}
