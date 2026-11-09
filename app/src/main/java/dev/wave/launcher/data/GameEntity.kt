package dev.wave.launcher.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "games",
    indices = [Index(value = ["path"], unique = true), Index("platformId")],
)
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val platformId: String,
    val title: String,
    val fileName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val favorite: Boolean = false,
    val lastPlayed: Long? = null,
    val playCount: Int = 0,
)

/** External-content FTS4 index over game titles; Room keeps it in sync with triggers. */
@Fts4(contentEntity = GameEntity::class)
@Entity(tableName = "games_fts")
data class GameFts(val title: String)
