package dev.wave.launcher.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [GameEntity::class, GameFts::class], version = 1, exportSchema = true)
abstract class WaveDatabase : RoomDatabase() {
    abstract fun games(): GameDao

    companion object {
        fun create(context: Context): WaveDatabase =
            Room.databaseBuilder(context, WaveDatabase::class.java, "wave.db").build()
    }
}
