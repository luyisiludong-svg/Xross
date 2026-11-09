package dev.wave.launcher.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GameHashDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(hash: GameHashEntity)

    @Query("SELECT * FROM game_hashes WHERE gameId = :gameId")
    suspend fun get(gameId: Long): GameHashEntity?

    /**
     * Games without a current fingerprint, in id order after [afterId] (keyset paging, so files
     * that can't be read right now don't stall the queue). [includeLarge] re-queues images that
     * were skipped for size once large-image hashing is enabled.
     */
    @Query(
        """
        SELECT g.* FROM games g LEFT JOIN game_hashes h ON h.gameId = g.id
        WHERE g.id > :afterId AND (
            h.gameId IS NULL
            OR h.fileSize != g.sizeBytes
            OR h.fileModified != g.lastModified
            OR (:includeLarge AND h.skipReason = 'LARGE')
        )
        ORDER BY g.id LIMIT :limit
        """,
    )
    suspend fun pending(afterId: Long, includeLarge: Boolean, limit: Int): List<GameEntity>

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM games) AS total,
            (SELECT COUNT(*) FROM game_hashes h JOIN games g ON g.id = h.gameId
                WHERE h.source IN ('FILE', 'ARCHIVE_ENTRY', 'CHD_HEADER')
                AND h.fileSize = g.sizeBytes AND h.fileModified = g.lastModified) AS hashed,
            (SELECT COUNT(*) FROM game_hashes h JOIN games g ON g.id = h.gameId
                WHERE h.source = 'ARCHIVE_SET'
                AND h.fileSize = g.sizeBytes AND h.fileModified = g.lastModified) AS byName,
            (SELECT COUNT(*) FROM game_hashes h JOIN games g ON g.id = h.gameId
                WHERE h.source = 'SKIPPED'
                AND h.fileSize = g.sizeBytes AND h.fileModified = g.lastModified) AS skipped
        """,
    )
    fun observeStats(): Flow<FingerprintStats>

    /** Exact-content lookup for DAT matching and scrapers. */
    @Query("SELECT * FROM game_hashes WHERE crc32 = :crc OR dataCrc32 = :crc")
    suspend fun findByCrc(crc: String): List<GameHashEntity>
}
