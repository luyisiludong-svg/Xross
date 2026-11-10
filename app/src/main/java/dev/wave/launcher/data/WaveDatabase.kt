package dev.wave.launcher.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [GameEntity::class, GameFts::class, GameHashEntity::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)], // v2: game_hashes
)
abstract class WaveDatabase : RoomDatabase() {
    abstract fun games(): GameDao
    abstract fun hashes(): GameHashDao

    companion object {
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

        /** Every hand-written migration, in order. No destructive fallback: an upgrade never drops data. */
        val MIGRATIONS = arrayOf<Migration>(MIGRATION_2_3, MIGRATION_3_4)

        fun create(context: Context): WaveDatabase =
            Room.databaseBuilder(context, WaveDatabase::class.java, "wave.db")
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
