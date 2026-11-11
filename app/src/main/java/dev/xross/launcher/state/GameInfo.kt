package dev.xross.launcher.state

import androidx.compose.runtime.Immutable
import dev.xross.launcher.data.DatFileEntity
import dev.xross.launcher.data.DatRomEntity
import dev.xross.launcher.data.GameEntity
import dev.xross.launcher.data.GameHashEntity
import dev.xross.launcher.data.GameMetadataEntity
import dev.xross.launcher.data.dat.MatchKind
import dev.xross.launcher.storage.HashSource
import dev.xross.launcher.storage.Platform
import dev.xross.launcher.storage.SkipReason
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** One label/value line of the Information screen. [monospace] for hashes, so digits line up. */
@Immutable
data class InfoRow(val label: String, val value: String, val monospace: Boolean = false)

/**
 * The Information screen opened from a game's Options menu: everything Xross knows about the
 * file. [index] is the highlighted row; moving it scrolls long lists (hashes run past one screen
 * on small handhelds).
 */
@Immutable
data class GameInfoState(
    val title: String,
    val subtitle: String?,
    val rows: List<InfoRow>,
    val index: Int = 0,
) {
    fun move(delta: Int): GameInfoState {
        if (rows.isEmpty()) return this
        return copy(index = (index + delta).coerceIn(0, rows.lastIndex))
    }

    fun moveTo(i: Int): GameInfoState = move(i - index)
}

/** Builds [GameInfoState] from database rows. Pure: dates, locale and file checks are passed in. */
object GameInfo {

    fun build(
        game: GameEntity,
        hash: GameHashEntity?,
        platform: Platform?,
        /** Emulator that would launch this game, e.g. "DuckStation · this game only"; null if none. */
        emulator: String?,
        /** The file's drive (SD card, USB) is unplugged. */
        offline: Boolean,
        /** Whether the file is on disk right now; null when it couldn't be checked (offline). */
        fileExists: Boolean?,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        /** DAT match and scraped fields, when known. */
        metadata: GameMetadataEntity? = null,
        datRom: DatRomEntity? = null,
        datFile: DatFileEntity? = null,
        /** Whether any DAT is imported (so "not in any DAT" means something). */
        anyDats: Boolean = false,
    ): GameInfoState {
        val dates = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zone)
        fun date(ms: Long) = dates.format(Instant.ofEpochMilli(ms))
        val numbers = NumberFormat.getIntegerInstance(locale)

        val rows = buildList {
            add(InfoRow("Platform", platform?.name ?: game.platformId))
            add(InfoRow("File", game.fileName))
            add(InfoRow("Folder", game.path.substringBeforeLast('/', missingDelimiterValue = "")))
            add(InfoRow("Status", status(offline, fileExists)))
            add(InfoRow("Size", "${MenuTree.formatSize(game.sizeBytes)} (${numbers.format(game.sizeBytes)} bytes)"))
            add(InfoRow("Modified", date(game.lastModified)))
            add(InfoRow("Favorite", if (game.favorite) "Yes" else "No"))
            add(InfoRow("Playtime", playtime(game)))
            add(
                InfoRow(
                    "Times played",
                    when (game.playCount) {
                        0 -> "Never"
                        1 -> "Once"
                        else -> "${numbers.format(game.playCount.toLong())} times"
                    },
                ),
            )
            add(InfoRow("Last played", game.lastPlayed?.let(::date) ?: "Never"))
            emulator?.let { add(InfoRow("Emulator", it)) }
            addAll(datRows(metadata, datRom, datFile, anyDats))
            addAll(fingerprintRows(game, hash, numbers, ::date))
        }
        return GameInfoState(title = game.title, subtitle = platform?.name, rows = rows)
    }

    private fun datRows(
        meta: GameMetadataEntity?,
        rom: DatRomEntity?,
        file: DatFileEntity?,
        anyDats: Boolean,
    ): List<InfoRow> = buildList {
        val kind = meta?.matchKind?.let { runCatching { MatchKind.valueOf(it) }.getOrNull() }
        if (kind == null) {
            when {
                meta?.locked == true -> add(InfoRow("Verified", "Identity set manually"))
                anyDats -> add(InfoRow("Verified", "Not in any imported DAT"))
            }
            return@buildList
        }
        val from = file?.let { f -> listOfNotNull(f.source, f.name).distinct().joinToString(": ") }
        add(InfoRow("Verified", "Matched by ${kind.label}" + (from?.let { " · $it" } ?: "")))
        rom?.let { add(InfoRow("DAT entry", it.gameName)) }
        meta.region?.let { add(InfoRow("Region", it)) }
        meta.languages?.let { add(InfoRow("Languages", it)) }
        meta.revision?.let { add(InfoRow("Revision", it)) }
        meta.serial?.let { add(InfoRow("Serial", it, monospace = true)) }
    }

    private fun status(offline: Boolean, exists: Boolean?): String = when {
        offline -> "Drive not connected"
        exists == false -> "File missing — rescan the library"
        else -> "Available"
    }

    private fun playtime(game: GameEntity): String =
        MenuTree.formatPlaytime(game.playTimeMs)
            ?: if (game.playCount > 0) "Under a minute" else "Not played yet"

    private fun fingerprintRows(
        game: GameEntity,
        hash: GameHashEntity?,
        numbers: NumberFormat,
        date: (Long) -> String,
    ): List<InfoRow> = buildList {
        if (hash == null) {
            add(InfoRow("Fingerprint", "Waiting — fingerprints are taken in the background while Xross is open"))
            return@buildList
        }
        if (hash.fileSize != game.sizeBytes || hash.fileModified != game.lastModified) {
            add(InfoRow("Fingerprint", "The file changed since it was fingerprinted; it will be checked again"))
            return@buildList
        }
        val source = runCatching { HashSource.valueOf(hash.source) }.getOrNull()
        add(InfoRow("Identified by", identifiedBy(source, hash)))
        hash.contentSize?.takeIf { source == HashSource.ARCHIVE_ENTRY }?.let {
            add(InfoRow("Uncompressed size", "${MenuTree.formatSize(it)} (${numbers.format(it)} bytes)"))
        }
        hash.crc32?.let { add(InfoRow("CRC32", it.uppercase(Locale.ROOT), monospace = true)) }
        hash.md5?.let { add(InfoRow("MD5", it.uppercase(Locale.ROOT), monospace = true)) }
        hash.sha1?.let { add(InfoRow("SHA-1", it.uppercase(Locale.ROOT), monospace = true)) }
        if (hash.headerBytes > 0) {
            add(InfoRow("Header", "${numbers.format(hash.headerBytes.toLong())} bytes, excluded below"))
            hash.dataCrc32?.let { add(InfoRow("CRC32 (no header)", it.uppercase(Locale.ROOT), monospace = true)) }
            hash.dataMd5?.let { add(InfoRow("MD5 (no header)", it.uppercase(Locale.ROOT), monospace = true)) }
            hash.dataSha1?.let { add(InfoRow("SHA-1 (no header)", it.uppercase(Locale.ROOT), monospace = true)) }
        }
        add(InfoRow("Fingerprinted", date(hash.hashedAt)))
    }

    private fun identifiedBy(source: HashSource?, hash: GameHashEntity): String = when (source) {
        HashSource.FILE -> "File contents"
        HashSource.ARCHIVE_ENTRY -> "Archive member" + (hash.entryName?.let { ": $it" } ?: "")
        HashSource.ARCHIVE_SET -> "Arcade set name"
        HashSource.CHD_HEADER -> "CHD header (SHA-1 of the disc data)"
        HashSource.SKIPPED -> "Not fingerprinted — " + skipReason(hash)
        null -> "Unknown (${hash.source})"
    }

    private fun skipReason(hash: GameHashEntity): String =
        when (hash.skipReason?.let { runCatching { SkipReason.valueOf(it) }.getOrNull() }) {
            SkipReason.LARGE -> "over the size limit; turn on Settings ▸ Library ▸ Fingerprint large disc images"
            SkipReason.COMPRESSED_IMAGE -> "compressed disc image; its bytes don't match DAT files"
            SkipReason.MULTI_FILE -> "multi-track disc sheet; tracks aren't hashed yet"
            SkipReason.NO_ROM_IN_ARCHIVE -> "no playable file in the archive"
            SkipReason.ERROR -> "the file couldn't be read" + (hash.detail?.let { " ($it)" } ?: "")
            null -> hash.detail ?: "unknown reason"
        }
}
