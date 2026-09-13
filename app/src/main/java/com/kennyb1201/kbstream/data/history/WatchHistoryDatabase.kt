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
    version = 9,
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

        // Profile-scoped instances: one open DB per active profile, closed
        // when the profile switches.
        @Volatile
        private var profileInstance: WatchHistoryDatabase? = null
        @Volatile
        private var profileInstanceName: String? = null

        fun getInstance(context: Context): WatchHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WatchHistoryDatabase::class.java,
                    "kbstream_watch_history"
                )
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }

        /**
         * Profile-scoped DB: every profile gets its own isolated history.
         * Call sites that store per-user data use this; the un-scoped
         * [getInstance] remains for global caches (tmdb_json_cache).
         */
        fun getInstanceScoped(context: Context): WatchHistoryDatabase {
            val dbName = com.kennyb1201.kbstream.data.sync.ProfileStorage.dbNameForActive(
                context, "kbstream_watch_history"
            )
            if (profileInstanceName == dbName) {
                return profileInstance ?: synchronized(this) {
                    buildScoped(context, dbName).also {
                        profileInstance = it; profileInstanceName = dbName
                    }
                }
            }
            synchronized(this) {
                if (profileInstanceName == dbName && profileInstance != null) {
                    return profileInstance!!
                }
                runCatching { profileInstance?.close() }
                val db = buildScoped(context, dbName)
                profileInstance = db
                profileInstanceName = dbName
                return db
            }
        }

        private fun buildScoped(
            context: Context,
            dbName: String
        ): WatchHistoryDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                WatchHistoryDatabase::class.java,
                dbName
            )
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .fallbackToDestructiveMigration()
                .build()

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_watch_history_parentId` " +
                        "ON `watch_history` (`parentId`)"
                )
            }
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
