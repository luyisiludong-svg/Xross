package dev.xross.launcher.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        GameEntity::class,
        GameFts::class,
        GameHashEntity::class,
        DatFileEntity::class,
        DatRomEntity::class,
        GameMetadataEntity::class,
    ],
    version = XrossDatabase.VERSION,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)], // v2: game_hashes
)
abstract class XrossDatabase : RoomDatabase() {
    abstract fun games(): GameDao
    abstract fun hashes(): GameHashDao
    abstract fun dats(): DatDao
    abstract fun metadata(): MetadataDao

    companion object {
        const val NAME = "xross.db"
        const val VERSION = 5

        /** v3: accumulated playtime per game. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN playTimeMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v4: per-game emulator override. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN emulatorOverride TEXT")
            }
        }

        /**
         * v5: metadata pipeline.
         *  - games.fileTitle keeps the scanner's title so the shown title can be overridden and
         *    restored; existing rows copy their current title.
         *  - games.uuid keys on-disk assets; existing rows get a random (version 4) UUID.
         *  - dat_files / dat_roms hold imported DATs; game_metadata holds DAT matches, scraped
         *    fields and manual overrides.
         * Additive only: no existing column or row is changed beyond the two backfills.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN fileTitle TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE games ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE games SET fileTitle = title")
                db.execSQL(
                    "UPDATE games SET uuid = lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || " +
                        "substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', 1 + (abs(random()) % 4), 1) || " +
                        "substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6)))",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_games_uuid` ON `games` (`uuid`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dat_files` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`path` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `version` TEXT, `source` TEXT, " +
                        "`platformId` TEXT, `fileSize` INTEGER NOT NULL, `fileModified` INTEGER NOT NULL, " +
                        "`importedAt` INTEGER NOT NULL, `gameCount` INTEGER NOT NULL, `romCount` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_dat_files_path` ON `dat_files` (`path`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dat_roms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`datId` INTEGER NOT NULL, `gameName` TEXT NOT NULL, `description` TEXT, `romName` TEXT NOT NULL, " +
                        "`size` INTEGER, `crc32` TEXT, `md5` TEXT, `sha1` TEXT, `serial` TEXT, " +
                        "FOREIGN KEY(`datId`) REFERENCES `dat_files`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                for (column in listOf("datId", "crc32", "md5", "sha1", "gameName")) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_dat_roms_$column` ON `dat_roms` (`$column`)")
                }

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `game_metadata` (`gameId` INTEGER NOT NULL, `datRomId` INTEGER, " +
                        "`matchKind` TEXT, `datTitle` TEXT, `region` TEXT, `languages` TEXT, `revision` TEXT, `serial` TEXT, " +
                        "`titleOverride` TEXT, `locked` INTEGER NOT NULL DEFAULT 0, `scrapedTitle` TEXT, `synopsis` TEXT, " +
                        "`releaseDate` TEXT, `developer` TEXT, `publisher` TEXT, `genre` TEXT, `players` TEXT, `rating` REAL, " +
                        "`screenScraperId` TEXT, `steamGridDbId` TEXT, `hasheousId` TEXT, `scrapeStatus` TEXT, " +
                        "`scrapeDetail` TEXT, `scrapedAt` INTEGER, PRIMARY KEY(`gameId`), " +
                        "FOREIGN KEY(`gameId`) REFERENCES `games`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`datRomId`) REFERENCES `dat_roms`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_metadata_datRomId` ON `game_metadata` (`datRomId`)")
            }
        }

        /** Every hand-written migration, in order. No destructive fallback: an upgrade never drops data. */
        val MIGRATIONS = arrayOf<Migration>(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        /**
         * Opens the live database (app-private storage only; see [DatabaseBackupManager] for the
         * copy in the Xross folder). A restore staged by the previous run is applied first.
         */
        fun create(context: Context): XrossDatabase {
            DatabaseBackupManager.applyStagedRestore(context)
            return Room.databaseBuilder(context, XrossDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
        }
    }
}
