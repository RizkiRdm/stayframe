package com.example.data.registry

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DiscoveredPlayerEntity::class],
    version = 1,
    exportSchema = false
)
abstract class StayFrameDatabase : RoomDatabase() {
    abstract fun discoveredPlayerDao(): DiscoveredPlayerDao

    companion object {
        @Volatile
        private var INSTANCE: StayFrameDatabase? = null

        fun getInstance(context: Context): StayFrameDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StayFrameDatabase::class.java,
                    "stayframe_registry.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { INSTANCE = it }
            }
        }
    }
}
