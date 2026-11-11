package dev.xross.launcher.scrape

import java.util.Locale

/** What [MediaNames] needs to know about a game. */
data class MediaGame(val id: Long, val uuid: String, val systemId: String, val fileName: String)

/**
 * Where a game's art lives: `<kind>/<system>/<name>.<ext>` under `Xross/media`, with [names]
 * tried in order. The first is where new files go.
 */
data class MediaSlot(val systemId: String, val names: List<String>) {
    val name: String get() = names.first()
}

/**
 * ES-DE-style, FAT-safe media file names.
 *
 * A game's art is named after its ROM file without the extension (`Super Mario World (USA).sfc`
 * → `covers/snes/Super Mario World (USA).png`), so media survives a library rebuild and media
 * downloaded for ES-DE can be reused. Names are cleaned for FAT32/exFAT: `\ / : * ? " < > |` and
 * control characters are removed, trailing dots and spaces trimmed, reserved device names
 * (`CON`, `NUL`, `COM1`…) prefixed, and length capped.
 *
 * FAT compares names without case, so two ROMs of one system whose cleaned names differ only in
 * case (or in extension: `Game.zip` and `Game.7z`) would share files. The game added first (lowest
 * id) keeps the plain name; the others get their UUID's first 8 characters appended:
 * `Game [1a2b3c4d]`. If the first one is later removed, the next one still finds its suffixed
 * files and moves to the plain name when it's next scraped.
 */
object MediaNames {
    /** Characters FAT32/exFAT (and Windows) reject in file names. */
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")
    private val SPACES = Regex("\\s+")
    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
    private val SYSTEM_ID = Regex("[a-z0-9_-]+")

    /** Leaves room for a disambiguating suffix and the extension within FAT's 255-character limit. */
    const val MAX_STEM = 200

    /** The ROM's file name without its extension. */
    fun romStem(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    /** A FAT32/exFAT-safe version of [stem]; never empty. */
    fun sanitize(stem: String): String {
        var s = stem.replace(ILLEGAL, "").replace(SPACES, " ").trim().trimEnd('.', ' ')
        if (s.length > MAX_STEM) {
            s = s.take(MAX_STEM)
            if (s.last().isHighSurrogate()) s = s.dropLast(1)
            s = s.trimEnd('.', ' ')
        }
        if (s.isEmpty()) return "_"
        if (s.substringBefore('.').uppercase(Locale.ROOT) in RESERVED) s = "_$s"
        return s
    }

    /** Folder name for a system; ids are already safe, anything else is cleaned. */
    fun systemFolder(systemId: String): String =
        systemId.lowercase(Locale.ROOT).takeIf { SYSTEM_ID.matches(it) } ?: sanitize(systemId).lowercase(Locale.ROOT)

    fun suffixed(stem: String, uuid: String): String = "$stem [${uuid.take(8)}]"

    /** Slots for every game, resolving name clashes within each system (see the class comment). */
    fun assign(games: Collection<MediaGame>): Map<String, MediaSlot> {
        val out = HashMap<String, MediaSlot>(games.size * 2)
        games.groupBy { systemFolder(it.systemId) to sanitize(romStem(it.fileName)).lowercase(Locale.ROOT) }
            .forEach { (key, clashing) ->
                val sorted = clashing.sortedWith(compareBy({ it.id }, { it.uuid }))
                sorted.forEachIndexed { i, game ->
                    val stem = sanitize(romStem(game.fileName))
                    val names = if (i == 0) listOf(stem, suffixed(stem, game.uuid)) else listOf(suffixed(stem, game.uuid))
                    out[game.uuid] = MediaSlot(key.first, names)
                }
            }
        return out
    }
}
