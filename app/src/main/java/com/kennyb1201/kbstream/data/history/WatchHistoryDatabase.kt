package com.kennyb1201.kbstream.data.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kennyb1201.kbstream.data.cache.ImdbResolutionDao
import com.kennyb1201.kbstream.data.cache.ImdbResolutionEntity
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheDao
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheEntity
import com.kennyb1201.kbstream.data.cache.WatchedStatusDao
import com.kennyb1201.kbstream.data.cache.WatchedStatusEntity

@Database(
    entities = [
        WatchHistoryEntity::class,
        WatchedStatusEntity::class,
        ImdbResolutionEntity::class,
        TmdbJsonCacheEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class WatchHistoryDatabase : RoomDatabase() {
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun watchedStatusDao(): WatchedStatusDao
    abstract fun imdbResolutionDao(): ImdbResolutionDao
    abstract fun tmdbJsonCacheDao(): TmdbJsonCacheDao

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
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tmdb_json_cache` (
                        `key` TEXT NOT NULL,
                        `json` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `watch_history` ADD COLUMN `backdropUrl` TEXT"
                )
            }
        }
    }
}
