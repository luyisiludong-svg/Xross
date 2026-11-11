package dev.xross.launcher.data

import dev.xross.launcher.data.dat.DatFormatException
import dev.xross.launcher.data.dat.DatMatch
import dev.xross.launcher.data.dat.DatMatcher
import dev.xross.launcher.data.dat.DatNames
import dev.xross.launcher.data.dat.DatParser
import dev.xross.launcher.data.dat.MatchKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile

data class DatImportResult(
    val imported: Int,
    val unchanged: Int,
    val removed: Int,
    /** "file name: reason" for every DAT that couldn't be read. */
    val failures: List<String>,
)

data class DatMatchResult(val matched: Int, val cleared: Int)

/**
 * Offline verification against No-Intro / Redump / MAME DATs.
 *
 * Import reads `.dat` / `.xml` files (also inside `.zip`) from a folder; each DAT is replaced
 * atomically in one transaction, so a cancelled or failed import leaves the previous version in
 * place. Matching compares the library's fingerprints with every imported DAT and records the
 * winner in `game_metadata`; the shown title then follows ([GameDao.applyTitles]).
 *
 * All work runs on [Dispatchers.IO]; one mutex serializes imports and matching.
 */
class DatRepository(
    private val db: XrossDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dats = db.dats()
    private val games = db.games()
    private val metadata = db.metadata()
    private val lock = Mutex()

    val summaries = dats.observeSummaries()

    suspend fun files(): List<DatFileEntity> = dats.files()

    suspend fun remove(id: Long): DatMatchResult = lock.withLock {
        withContext(Dispatchers.IO) { dats.deleteFile(id) }
        matchLocked()
    }

    /**
     * Imports every DAT in [dir] (not recursive), skipping ones whose file hasn't changed, and
     * forgets DATs that were imported from [dir] but are gone now. Then re-matches the library.
     */
    suspend fun importFolder(dir: File): DatImportResult = lock.withLock {
        val result = withContext(Dispatchers.IO) {
            val files = dir.listFiles()?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in EXTENSIONS }
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                .orEmpty()
            var imported = 0
            var unchanged = 0
            val failures = ArrayList<String>()
            val seenPaths = HashSet<String>()
            for (file in files) {
                for (source in sourcesOf(file, failures)) {
                    seenPaths += source.path
                    val existing = dats.fileByPath(source.path)
                    if (existing != null && existing.fileSize == source.size && existing.fileModified == source.modified) {
                        unchanged++
                        continue
                    }
                    try {
                        importSource(source, existing?.id)
                        imported++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures += "${source.displayName}: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
            val prefix = dir.absolutePath.trimEnd('/') + "/"
            val gone = dats.files().filter { it.path.startsWith(prefix) && it.path !in seenPaths }
            gone.forEach { dats.deleteFile(it.id) }
            DatImportResult(imported, unchanged, gone.size, failures)
        }
        matchLocked()
        result
    }

    /** Re-checks every fingerprinted game against the imported DATs. */
    suspend fun matchAll(): DatMatchResult = lock.withLock { matchLocked() }

    // region Import

    /** One DAT to read: a plain file, or an entry inside a zip ("pack.zip!/entry.dat"). */
    internal class Source(
        val path: String,
        val displayName: String,
        val size: Long,
        val modified: Long,
        val open: () -> InputStream,
        val close: () -> Unit = {},
    )

    private fun sourcesOf(file: File, failures: MutableList<String>): List<Source> {
        if (file.extension.lowercase(Locale.ROOT) != "zip") {
            return listOf(Source(file.absolutePath, file.name, file.length(), file.lastModified(), { file.inputStream() }))
        }
        return try {
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase(Locale.ROOT) in INNER_EXTENSIONS }
                    .map { entry ->
                        val name = entry.name
                        Source(
                            path = "${file.absolutePath}!/$name",
                            displayName = "${file.name} ▸ ${name.substringAfterLast('/')}",
                            // The zip's own size/date, so editing the archive re-imports its entries.
                            size = file.length(),
                            modified = file.lastModified(),
                            open = {
                                val z = ZipFile(file)
                                val e = z.getEntry(name)
                                object : java.io.FilterInputStream(z.getInputStream(e)) {
                                    override fun close() {
                                        super.close()
                                        z.close()
                                    }
                                }
                            },
                        )
                    }.toList()
            }
        } catch (e: java.io.IOException) {
            failures += "${file.name}: ${e.message ?: "unreadable archive"}"
            emptyList()
        }
    }

    /**
     * Streams one DAT into the database inside a single transaction: the old version (if any) is
     * deleted and the new one inserted together, so readers never see a half-imported DAT.
     */
    private suspend fun importSource(source: Source, replaces: Long?) {
        val job = currentCoroutineContext()[Job]
        val fallbackName = source.displayName.substringBeforeLast('.')
        db.runInTransaction {
            replaces?.let { dats.deleteFileSync(it) }
            val datId = dats.insertFileSync(
                DatFileEntity(
                    path = source.path, name = fallbackName, description = null, version = null, source = null,
                    platformId = null, fileSize = source.size, fileModified = source.modified,
                    importedAt = clock(), gameCount = 0, romCount = 0,
                ),
            )
            val batch = ArrayList<DatRomEntity>(BATCH)
            var gameCount = 0
            var romCount = 0
            val header = source.open().use { input ->
                DatParser.parse(input, fallbackName) { game ->
                    if (job?.isActive == false) throw CancellationException("DAT import cancelled")
                    gameCount++
                    for (rom in game.roms) {
                        romCount++
                        batch += DatRomEntity(
                            datId = datId,
                            gameName = game.name,
                            description = game.description,
                            romName = rom.name,
                            size = rom.size,
                            crc32 = rom.crc32,
                            md5 = rom.md5,
                            sha1 = rom.sha1,
                            serial = rom.serial ?: game.serial,
                        )
                        if (batch.size >= BATCH) {
                            dats.insertRomsSync(batch)
                            batch.clear()
                        }
                    }
                }
            }
            if (batch.isNotEmpty()) dats.insertRomsSync(batch)
            if (gameCount == 0) throw DatFormatException("No games with ROM hashes")
            dats.finishImportSync(
                id = datId,
                name = header.name,
                description = header.description,
                version = header.version,
                source = header.source,
                platformId = DatNames.platformFor(header.name),
                games = gameCount,
                roms = romCount,
            )
        }
    }

    // endregion

    // region Matching

    private suspend fun matchLocked(): DatMatchResult = withContext(Dispatchers.IO) {
        val library = games.getAll()
        val platformById = library.associate { it.id to it.platformId }
        val hashMatches = DatMatcher.resolve(dats.hashCandidates(), platformById::get).associateBy { it.gameId }

        // Arcade sets without a hash match are identified by file name ("sf2.zip" → "sf2").
        val arcade = library
            .filter { it.platformId == "arcade" && it.id !in hashMatches }
            .associate { it.id to it.fileName.substringBeforeLast('.').lowercase(Locale.ROOT) }
        val setMatches = if (arcade.isEmpty()) {
            emptyList()
        } else {
            val hits = arcade.values.distinct().chunked(500).flatMap { dats.setNameCandidates(it) }
            DatMatcher.resolveSetNames(arcade, hits)
        }
        val matches: Map<Long, DatMatch> = hashMatches + setMatches.associateBy { it.gameId }

        val roms = dats.roms(matches.values.map { it.romId }).associateBy { it.id }
        val existing = metadata.getAll().associateBy { it.gameId }
        val changed = ArrayList<GameMetadataEntity>()
        var cleared = 0
        for (game in library) {
            val current = existing[game.id]
            if (current?.locked == true) continue
            val match = matches[game.id]
            val rom = match?.let { roms[it.romId] }
            val next = if (match != null && rom != null) {
                withDatMatch(current ?: GameMetadataEntity(game.id), rom, match.kind)
            } else if (current != null && current.matchKind != null) {
                cleared++
                withoutDatMatch(current)
            } else {
                continue
            }
            if (next != current) changed += next
        }
        changed.chunked(500).forEach { metadata.upsertAll(it) }
        games.applyTitles()
        DatMatchResult(matched = matches.size, cleared = cleared)
    }

    // endregion

    companion object {
        private const val BATCH = 1_000
        private val INNER_EXTENSIONS = setOf("dat", "xml")
        val EXTENSIONS = INNER_EXTENSIONS + "zip"

        internal fun withDatMatch(row: GameMetadataEntity, rom: DatRomEntity, kind: MatchKind): GameMetadataEntity {
            val tags = DatNames.bestTitle(rom.gameName, rom.description)
            return row.copy(
                datRomId = rom.id,
                matchKind = kind.name,
                datTitle = tags.title,
                region = tags.regions.joinToString(", ").ifEmpty { null },
                languages = tags.languages.joinToString(", ").ifEmpty { null },
                revision = tags.revision,
                serial = rom.serial,
            )
        }

        internal fun withoutDatMatch(row: GameMetadataEntity) = row.copy(
            datRomId = null, matchKind = null, datTitle = null, region = null, languages = null, revision = null, serial = null,
        )
    }
}
