package com.kennyb1201.kbstream.data.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.kennyb1201.kbstream.data.cache.ImdbResolutionDao
import com.kennyb1201.kbstream.data.cache.ImdbResolutionEntity
import com.kennyb1201.kbstream.data.cache.WatchedStatusDao
import com.kennyb1201.kbstream.data.cache.WatchedStatusEntity

@Database(
    entities = [
        WatchHistoryEntity::class,
        WatchedStatusEntity::class,
        ImdbResolutionEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class WatchHistoryDatabase : RoomDatabase() {
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun watchedStatusDao(): WatchedStatusDao
    abstract fun imdbResolutionDao(): ImdbResolutionDao

    companion object {
        @Volatile
        private var instance: WatchHistoryDatabase? = null

        fun getInstance(context: Context): WatchHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WatchHistoryDatabase::class.java,
                    "kbstream_watch_history"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
