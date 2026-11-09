package dev.wave.launcher.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.wave.launcher.storage.Fingerprint

/**
 * Content fingerprint of a game file. Valid while [fileSize]/[fileModified] match the game row;
 * a rescan that sees the file change makes it stale and it is recomputed.
 */
@Entity(
    tableName = "game_hashes",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["gameId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("crc32"), Index("sha1"), Index("md5")],
)
data class GameHashEntity(
    @PrimaryKey val gameId: Long,
    val fileSize: Long,
    val fileModified: Long,
    /** [dev.wave.launcher.storage.HashSource] name. */
    val source: String,
    val crc32: String?,
    val md5: String?,
    val sha1: String?,
    val dataCrc32: String?,
    val dataMd5: String?,
    val dataSha1: String?,
    @ColumnInfo(defaultValue = "0") val headerBytes: Int,
    val entryName: String?,
    val contentSize: Long?,
    /** [dev.wave.launcher.storage.SkipReason] name when [source] is SKIPPED. */
    val skipReason: String?,
    val detail: String?,
    val hashedAt: Long,
) {
    companion object {
        fun from(game: GameEntity, fp: Fingerprint, now: Long = System.currentTimeMillis()) = GameHashEntity(
            gameId = game.id,
            fileSize = game.sizeBytes,
            fileModified = game.lastModified,
            source = fp.source.name,
            crc32 = fp.crc32,
            md5 = fp.md5,
            sha1 = fp.sha1,
            dataCrc32 = fp.dataCrc32,
            dataMd5 = fp.dataMd5,
            dataSha1 = fp.dataSha1,
            headerBytes = fp.headerBytes,
            entryName = fp.entryName,
            contentSize = fp.contentSize,
            skipReason = fp.skipReason?.name,
            detail = fp.detail,
            hashedAt = now,
        )
    }
}

data class FingerprintStats(
    val total: Int,
    /** Identified by content hash (file, archive member or CHD header). */
    val hashed: Int,
    /** Arcade sets: identified by set name. */
    val byName: Int,
    val skipped: Int,
) {
    val pending: Int get() = (total - hashed - byName - skipped).coerceAtLeast(0)
}
