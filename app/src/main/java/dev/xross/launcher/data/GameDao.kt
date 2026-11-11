package dev.xross.launcher.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    @Query("SELECT * FROM games ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games")
    suspend fun getAll(): List<GameEntity>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun get(id: Long): GameEntity?

    @Query("SELECT COUNT(*) FROM games")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(games: List<GameEntity>)

    /** A changed file gets its scanner title back; [applyTitles] then re-applies any better title. */
    @Query(
        "UPDATE games SET platformId = :platformId, title = :title, fileTitle = :title, sizeBytes = :size, " +
            "lastModified = :modified WHERE id = :id",
    )
    suspend fun updateFile(id: Long, platformId: String, title: String, size: Long, modified: Long)

    /**
     * Sets every game's shown title to the best one known: manual override, DAT title, scraped
     * title, then the scanner's file title. Only rows that change are written, so the FTS
     * triggers fire for those alone.
     */
    @Query(
        """
        UPDATE games SET title = COALESCE(
            (SELECT COALESCE(m.titleOverride, m.datTitle, m.scrapedTitle) FROM game_metadata m WHERE m.gameId = games.id),
            NULLIF(fileTitle, ''),
            title)
        WHERE title IS NOT COALESCE(
            (SELECT COALESCE(m.titleOverride, m.datTitle, m.scrapedTitle) FROM game_metadata m WHERE m.gameId = games.id),
            NULLIF(fileTitle, ''),
            title)
        """,
    )
    suspend fun applyTitles(): Int

    @Query("DELETE FROM games WHERE id IN (:ids)")
    suspend fun deleteIdsChunk(ids: List<Long>)

    @Transaction
    suspend fun deleteIds(ids: List<Long>) {
        // Stay under SQLite's bound-parameter limit.
        ids.chunked(500).forEach { deleteIdsChunk(it) }
    }

    @Query("UPDATE games SET favorite = NOT favorite WHERE id = :id")
    suspend fun toggleFavorite(id: Long)

    @Query("UPDATE games SET lastPlayed = :at, playCount = playCount + 1 WHERE id = :id")
    suspend fun markPlayed(id: Long, at: Long)

    @Query("UPDATE games SET emulatorOverride = :key WHERE id = :id")
    suspend fun setEmulatorOverride(id: Long, key: String?)

    @Query("UPDATE games SET playTimeMs = playTimeMs + :durationMs WHERE id = :id")
    suspend fun addPlayTime(id: Long, durationMs: Long)

    @Query(
        "SELECT games.* FROM games JOIN games_fts ON games.rowid = games_fts.rowid " +
            "WHERE games_fts MATCH :query ORDER BY games.title COLLATE NOCASE LIMIT :limit",
    )
    suspend fun search(query: String, limit: Int = 200): List<GameEntity>
}
