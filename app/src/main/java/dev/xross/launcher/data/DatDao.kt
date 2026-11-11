package dev.xross.launcher.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A content-hash (or set-name) hit between a fingerprinted game and a DAT ROM. */
data class DatCandidate(
    val gameId: Long,
    val romId: Long,
    val datId: Long,
    /** [dev.xross.launcher.data.dat.HashKind] name. */
    val kind: String,
    val romSize: Long?,
    /** Size of what was hashed: the archive member or the file, minus any removed header. */
    val hashedSize: Long?,
    val datPlatformId: String?,
    val datImportedAt: Long,
)

/** Per-DAT summary row for Settings ▸ Library ▸ DAT files. */
data class DatSummary(
    val id: Long,
    val name: String,
    val version: String?,
    val source: String?,
    val platformId: String?,
    val path: String,
    val gameCount: Int,
    val romCount: Int,
    val matched: Int,
)

@Dao
interface DatDao {
    @Insert
    suspend fun insertFile(file: DatFileEntity): Long

    // Blocking variants: an import streams a DAT inside one transaction from a parser callback.
    @Insert
    fun insertFileSync(file: DatFileEntity): Long

    @Insert
    fun insertRomsSync(roms: List<DatRomEntity>)

    @Query("DELETE FROM dat_files WHERE id = :id")
    fun deleteFileSync(id: Long)

    @Query(
        "UPDATE dat_files SET name = :name, description = :description, version = :version, source = :source, " +
            "platformId = :platformId, gameCount = :games, romCount = :roms WHERE id = :id",
    )
    fun finishImportSync(
        id: Long,
        name: String,
        description: String?,
        version: String?,
        source: String?,
        platformId: String?,
        games: Int,
        roms: Int,
    )

    @Query("SELECT * FROM dat_roms WHERE id IN (:ids)")
    suspend fun romsChunk(ids: List<Long>): List<DatRomEntity>

    suspend fun roms(ids: Collection<Long>): List<DatRomEntity> =
        ids.distinct().chunked(500).flatMap { romsChunk(it) }

    @Query("SELECT * FROM dat_files WHERE id IN (:ids)")
    suspend fun filesById(ids: List<Long>): List<DatFileEntity>

    @Query("SELECT * FROM dat_files WHERE path = :path")
    suspend fun fileByPath(path: String): DatFileEntity?

    @Query("SELECT * FROM dat_files ORDER BY name COLLATE NOCASE")
    suspend fun files(): List<DatFileEntity>

    @Query("DELETE FROM dat_files WHERE id = :id")
    suspend fun deleteFile(id: Long)

    @Query("SELECT * FROM dat_roms WHERE id = :id")
    suspend fun rom(id: Long): DatRomEntity?

    @Query("SELECT * FROM dat_files WHERE id = :id")
    suspend fun file(id: Long): DatFileEntity?

    @Query(
        """
        SELECT f.id, f.name, f.version, f.source, f.platformId, f.path, f.gameCount, f.romCount,
            (SELECT COUNT(*) FROM game_metadata m JOIN dat_roms r ON r.id = m.datRomId WHERE r.datId = f.id) AS matched
        FROM dat_files f ORDER BY f.name COLLATE NOCASE
        """,
    )
    fun observeSummaries(): Flow<List<DatSummary>>

    /**
     * Every hash hit for current fingerprints (stale ones, whose file changed since hashing, are
     * left out). One indexed join per hash column; the matcher ranks them.
     */
    @Query(
        """
        SELECT h.gameId, r.id AS romId, r.datId, 'SHA1' AS kind, r.size AS romSize,
            COALESCE(h.contentSize, g.sizeBytes) AS hashedSize, f.platformId AS datPlatformId, f.importedAt AS datImportedAt
        FROM game_hashes h JOIN games g ON g.id = h.gameId JOIN dat_roms r ON r.sha1 = h.sha1 JOIN dat_files f ON f.id = r.datId
        WHERE h.fileSize = g.sizeBytes AND h.fileModified = g.lastModified
        UNION ALL
        SELECT h.gameId, r.id, r.datId, 'SHA1', r.size,
            COALESCE(h.contentSize, g.sizeBytes) - h.headerBytes, f.platformId, f.importedAt
        FROM game_hashes h JOIN games g ON g.id = h.gameId JOIN dat_roms r ON r.sha1 = h.dataSha1 JOIN dat_files f ON f.id = r.datId
        WHERE h.fileSize = g.sizeBytes AND h.fileModified = g.lastModified
        UNION ALL
        SELECT h.gameId, r.id, r.datId, 'MD5', r.size,
            COALESCE(h.contentSize, g.sizeBytes), f.platformId, f.importedAt
        FROM game_hashes h JOIN games g ON g.id = h.gameId JOIN dat_roms r ON r.md5 = h.md5 JOIN dat_files f ON f.id = r.datId
        WHERE h.fileSize = g.sizeBytes AND h.fileModified = g.lastModified
        UNION ALL
        SELECT h.gameId, r.id, r.datId, 'MD5', r.size,
            COALESCE(h.contentSize, g.sizeBytes) - h.headerBytes, f.platformId, f.importedAt
        FROM game_hashes h JOIN games g ON g.id = h.gameId JOIN dat_roms r ON r.md5 = h.dataMd5 JOIN dat_files f ON f.id = r.datId
        WHERE h.fileSize = g.sizeBytes AND h.fileModified = g.lastModified
        UNION ALL
        SELECT h.gameId, r.id, r.datId, 'CRC32', r.size,
            COALESCE(h.contentSize, g.sizeBytes), f.platformId, f.importedAt
        FROM game_hashes h JOIN games g ON g.id = h.gameId JOIN dat_roms r ON r.crc32 = h.crc32 JOIN dat_files f ON f.id = r.datId
        WHERE h.fileSize = g.sizeBytes AND h.fileModified = g.lastModified
        UNION ALL
        SELECT h.gameId, r.id, r.datId, 'CRC32', r.size,
            COALESCE(h.contentSize, g.sizeBytes) - h.headerBytes, f.platformId, f.importedAt
        FROM game_hashes h JOIN games g ON g.id = h.gameId JOIN dat_roms r ON r.crc32 = h.dataCrc32 JOIN dat_files f ON f.id = r.datId
        WHERE h.fileSize = g.sizeBytes AND h.fileModified = g.lastModified
        """,
    )
    suspend fun hashCandidates(): List<DatCandidate>

    /**
     * Arcade sets are identified by set name ("sf2.zip" ↔ game "sf2"). MAME set names are
     * lower-case, and [names] are lowered by the caller, so the gameName index applies.
     */
    @Query(
        """
        SELECT MIN(r.id) AS romId, r.datId, r.gameName, f.platformId AS datPlatformId, f.importedAt AS datImportedAt
        FROM dat_roms r JOIN dat_files f ON f.id = r.datId
        WHERE r.gameName IN (:names)
        GROUP BY r.datId, r.gameName
        """,
    )
    suspend fun setNameCandidates(names: List<String>): List<SetNameHit>

}

data class SetNameHit(
    val romId: Long,
    val datId: Long,
    val gameName: String,
    val datPlatformId: String?,
    val datImportedAt: Long,
)
