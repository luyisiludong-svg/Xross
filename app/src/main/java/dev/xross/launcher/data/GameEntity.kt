package dev.xross.launcher.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "games",
    indices = [Index(value = ["path"], unique = true), Index("platformId"), Index(value = ["uuid"], unique = true)],
)
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val platformId: String,
    /**
     * Title shown everywhere (and indexed for search): a manual override, the DAT title, the
     * scraped title or [fileTitle], in that order. Maintained by [GameDao.applyTitles].
     */
    val title: String,
    val fileName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val favorite: Boolean = false,
    val lastPlayed: Long? = null,
    val playCount: Int = 0,
    /** Total time played, accumulated from launcher-measured sessions. */
    @ColumnInfo(defaultValue = "0") val playTimeMs: Long = 0,
    /** Per-game emulator ([dev.xross.launcher.storage.EmulatorOption.key]); null = platform default. */
    val emulatorOverride: String? = null,
    /** Title derived from the file name by the scanner; what [title] falls back to. */
    @ColumnInfo(defaultValue = "") val fileTitle: String = title,
    /** Stable identity for on-disk assets (box art, videos); survives rescans of the same path. */
    @ColumnInfo(defaultValue = "") val uuid: String = java.util.UUID.randomUUID().toString(),
)

/** External-content FTS4 index over game titles; Room keeps it in sync with triggers. */
@Fts4(contentEntity = GameEntity::class)
@Entity(tableName = "games_fts")
data class GameFts(val title: String)
