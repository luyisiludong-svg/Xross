package dev.xross.launcher.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MetadataDao {
    @Query("SELECT * FROM game_metadata WHERE gameId = :gameId")
    suspend fun get(gameId: Long): GameMetadataEntity?

    @Query("SELECT * FROM game_metadata WHERE gameId = :gameId")
    fun observe(gameId: Long): Flow<GameMetadataEntity?>

    /** Games whose media folders haven't been checked yet (see [GameMetadataEntity.mediaStatus]). */
    @Query("SELECT g.* FROM games g LEFT JOIN game_metadata m ON m.gameId = g.id WHERE m.mediaStatus IS NULL")
    suspend fun mediaStatusPending(): List<GameEntity>

    /** Every media file is gone (Settings ▸ Clear artwork). Pins go with the files they named. */
    @Query("UPDATE game_metadata SET mediaStatus = 0, pinnedArt = NULL")
    suspend fun clearMediaStatus()

    @Query("SELECT * FROM game_metadata")
    fun observeAll(): Flow<List<GameMetadataEntity>>

    @Query("SELECT * FROM game_metadata")
    suspend fun getAll(): List<GameMetadataEntity>

    /**
     * Games still to scrape after [afterId]: never tried, or a failed attempt older than
     * [retryBefore]. Keyset paging so games skipped this pass don't stall the queue.
     */
    @Query(
        """
        SELECT g.* FROM games g LEFT JOIN game_metadata m ON m.gameId = g.id
        WHERE g.id > :afterId AND (
            m.gameId IS NULL OR m.scrapeStatus IS NULL
            OR (m.scrapeStatus = 'ERROR' AND (m.scrapedAt IS NULL OR m.scrapedAt < :retryBefore))
        )
        ORDER BY g.id LIMIT :limit
        """,
    )
    suspend fun pendingScrape(afterId: Long, retryBefore: Long, limit: Int): List<GameEntity>

    @Query(
        """
        SELECT COUNT(*) FROM games g LEFT JOIN game_metadata m ON m.gameId = g.id
        WHERE m.gameId IS NULL OR m.scrapeStatus IS NULL
            OR (m.scrapeStatus = 'ERROR' AND (m.scrapedAt IS NULL OR m.scrapedAt < :retryBefore))
        """,
    )
    suspend fun pendingScrapeCount(retryBefore: Long): Int

    @Query("SELECT uuid FROM games")
    suspend fun allUuids(): List<String>

    @Upsert
    suspend fun upsert(row: GameMetadataEntity)

    @Upsert
    suspend fun upsertAll(rows: List<GameMetadataEntity>)

    /** Reads, transforms and writes one row atomically; creates it when missing. */
    @Transaction
    suspend fun edit(gameId: Long, transform: (GameMetadataEntity) -> GameMetadataEntity) {
        val current = get(gameId) ?: GameMetadataEntity(gameId)
        val next = transform(current)
        if (next != current) upsert(next)
    }
}
