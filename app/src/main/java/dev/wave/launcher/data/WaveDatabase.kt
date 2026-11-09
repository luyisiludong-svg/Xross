package dev.wave.launcher.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [GameEntity::class, GameFts::class, GameHashEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)], // v2: game_hashes
)
abstract class WaveDatabase : RoomDatabase() {
    abstract fun games(): GameDao
    abstract fun hashes(): GameHashDao

    companion object {
        fun create(context: Context): WaveDatabase =
            Room.databaseBuilder(context, WaveDatabase::class.java, "wave.db").build()
    }
}
