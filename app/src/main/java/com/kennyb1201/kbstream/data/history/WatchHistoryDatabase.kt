package com.kennyb1201.kbstream.data.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [WatchHistoryEntity::class], version = 2)
abstract class WatchHistoryDatabase : RoomDatabase() {
    abstract fun watchHistoryDao(): WatchHistoryDao

    companion object {
        @Volatile private var instance: WatchHistoryDatabase? = null

        fun getInstance(context: Context): WatchHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WatchHistoryDatabase::class.java,
                    "kbstream_watch_history"
                )
                    // app has no released version yet, so a destructive migration
                    // (wipe + recreate) is fine rather than writing a real migration
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
