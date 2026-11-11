package dev.xross.launcher.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** An imported Logiqx / clrmamepro DAT file (No-Intro, Redump, MAME…). */
@Entity(tableName = "dat_files", indices = [Index(value = ["path"], unique = true)])
data class DatFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Source file; a DAT inside a zip is "archive.zip!/entry.dat". */
    val path: String,
    /** Header name, e.g. "Nintendo - Super Nintendo Entertainment System". */
    val name: String,
    val description: String?,
    val version: String?,
    /** Header homepage/author, e.g. "No-Intro" or "redump.org". */
    val source: String?,
    /** Platform inferred from [name]; null when the system isn't one Xross knows. */
    val platformId: String?,
    val fileSize: Long,
    val fileModified: Long,
    val importedAt: Long,
    val gameCount: Int,
    val romCount: Int,
)

/** One ROM (or disc track) listed in a DAT. Hashes are lower-case hex. */
@Entity(
    tableName = "dat_roms",
    foreignKeys = [
        ForeignKey(entity = DatFileEntity::class, parentColumns = ["id"], childColumns = ["datId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("datId"), Index("crc32"), Index("md5"), Index("sha1"), Index("gameName")],
)
data class DatRomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val datId: Long,
    /** The DAT game's name: "Chrono Trigger (USA)", or a MAME set name like "sf2". */
    val gameName: String,
    val description: String?,
    val romName: String,
    val size: Long?,
    val crc32: String?,
    val md5: String?,
    val sha1: String?,
    val serial: String?,
)

/**
 * Everything learned about a game beyond its file: the DAT match, scraped metadata, provider ids
 * and the user's manual overrides. One row per game, created on first need.
 */
@Entity(
    tableName = "game_metadata",
    foreignKeys = [
        ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = DatRomEntity::class, parentColumns = ["id"], childColumns = ["datRomId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("datRomId")],
)
data class GameMetadataEntity(
    @PrimaryKey val gameId: Long,
    // DAT verification
    val datRomId: Long? = null,
    /** [dev.xross.launcher.data.dat.MatchKind] name. */
    val matchKind: String? = null,
    val datTitle: String? = null,
    val region: String? = null,
    val languages: String? = null,
    val revision: String? = null,
    val serial: String? = null,
    // Manual override
    val titleOverride: String? = null,
    /** The user chose this game's identity; automatic matching and scraping won't replace it. */
    @ColumnInfo(defaultValue = "0") val locked: Boolean = false,
    // Scraped
    val scrapedTitle: String? = null,
    val synopsis: String? = null,
    val releaseDate: String? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val genre: String? = null,
    val players: String? = null,
    /** 0–1. */
    val rating: Float? = null,
    val screenScraperId: String? = null,
    val steamGridDbId: String? = null,
    val hasheousId: String? = null,
    /** [dev.xross.launcher.scrape.ScrapeStatus] name; null = never tried. */
    val scrapeStatus: String? = null,
    val scrapeDetail: String? = null,
    val scrapedAt: Long? = null,
)
