package dev.wave.launcher.data

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(games: List<GameEntity>)

    @Query(
        "UPDATE games SET platformId = :platformId, title = :title, sizeBytes = :size, " +
            "lastModified = :modified WHERE id = :id",
    )
    suspend fun updateFile(id: Long, platformId: String, title: String, size: Long, modified: Long)

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

    @Query(
        "SELECT games.* FROM games JOIN games_fts ON games.rowid = games_fts.rowid " +
            "WHERE games_fts MATCH :query ORDER BY games.title COLLATE NOCASE LIMIT :limit",
    )
    suspend fun search(query: String, limit: Int = 200): List<GameEntity>
}
