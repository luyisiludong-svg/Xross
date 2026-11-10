package dev.wave.launcher.data

import dev.wave.launcher.storage.RomScanner
import dev.wave.launcher.storage.ScannedRom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class ScanResult(val added: Int, val updated: Int, val removed: Int, val total: Int)

internal data class LibraryDiff(
    val toInsert: List<ScannedRom>,
    val toUpdate: List<Pair<Long, ScannedRom>>,
    val toDelete: List<Long>,
)

class GameRepository(
    private val dao: GameDao,
    private val scanner: RomScanner = RomScanner(),
) {
    private val scanMutex = Mutex()

    val games: Flow<List<GameEntity>> = dao.observeAll()

    suspend fun get(id: Long): GameEntity? = dao.get(id)

    suspend fun toggleFavorite(id: Long) = dao.toggleFavorite(id)

    suspend fun markPlayed(id: Long) = dao.markPlayed(id, System.currentTimeMillis())

    suspend fun addPlayTime(id: Long, durationMs: Long) = dao.addPlayTime(id, durationMs)

    suspend fun setEmulatorOverride(id: Long, key: String?) = dao.setEmulatorOverride(id, key)

    /** Title search on the FTS4 index; [ftsQuery] comes from SearchQuery.toFts. */
    suspend fun search(ftsQuery: String, limit: Int = 60): List<GameEntity> = dao.search(ftsQuery, limit)

    /**
     * Incremental rescan. Games on a configured root that isn't mounted right now are kept,
     * so pulling an SD card doesn't wipe favorites and playtime.
     */
    suspend fun scan(roots: List<File>): ScanResult = scanMutex.withLock {
        withContext(Dispatchers.IO) {
            val mounted = roots.filter { it.isDirectory }
            val scanned = scanner.scan(mounted)
            val diff = computeDiff(
                existing = dao.getAll(),
                scanned = scanned,
                configuredRoots = roots.map { it.absolutePath },
                scannedRoots = mounted.map { it.absolutePath },
            )
            dao.insertAll(diff.toInsert.map { it.toEntity() })
            for ((id, rom) in diff.toUpdate) {
                dao.updateFile(id, rom.platformId, rom.title, rom.sizeBytes, rom.lastModified)
            }
            dao.deleteIds(diff.toDelete)
            ScanResult(diff.toInsert.size, diff.toUpdate.size, diff.toDelete.size, scanned.size)
        }
    }

    companion object {
        internal fun computeDiff(
            existing: List<GameEntity>,
            scanned: List<ScannedRom>,
            configuredRoots: List<String>,
            scannedRoots: List<String>,
        ): LibraryDiff {
            val byPath = existing.associateBy { it.path }
            val scannedPaths = scanned.mapTo(HashSet()) { it.path }

            val toInsert = scanned.filter { it.path !in byPath }
            val toUpdate = scanned.mapNotNull { rom ->
                val old = byPath[rom.path] ?: return@mapNotNull null
                val changed = old.sizeBytes != rom.sizeBytes ||
                    old.lastModified != rom.lastModified ||
                    old.platformId != rom.platformId
                if (changed) old.id to rom else null
            }
            val toDelete = existing.filter { game ->
                if (game.path in scannedPaths) return@filter false
                val underScanned = scannedRoots.any { game.path.isUnder(it) }
                val underConfigured = configuredRoots.any { game.path.isUnder(it) }
                underScanned || !underConfigured
            }.map { it.id }

            return LibraryDiff(toInsert, toUpdate, toDelete)
        }

        private fun String.isUnder(root: String): Boolean =
            this == root || startsWith(root.trimEnd('/') + "/")

        private fun ScannedRom.toEntity() = GameEntity(
            path = path,
            platformId = platformId,
            title = title,
            fileName = fileName,
            sizeBytes = sizeBytes,
            lastModified = lastModified,
        )
    }
}
