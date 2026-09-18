package com.kennyb1201.kbstream.data.iptv.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.kennyb1201.kbstream.data.db.RoomBusyTimeout
import java.util.concurrent.Executors

@Database(
    entities = [
        EpgChannelEntity::class,
        EpgProgramEntity::class,
        CachedPlaylistChannelEntity::class,
        PlaylistEpgMatchEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class IptvDatabase : RoomDatabase() {
    abstract fun iptvDao(): IptvDao

    companion object {
        @Volatile
        private var INSTANCE: IptvDatabase? = null

        // Retired DBs close after a grace period: a big EPG import or guide
        // query mid-flight on the old instance must finish (or hit SQLite's
        // busy timeout) instead of getting its connection pool closed
        // underneath it ("connection pool has been closed" — Sentry 7736349317).
        private const val RETIRE_GRACE_MS = 5_000L
        private val closeExecutor =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "iptv-db-retire").apply { isDaemon = true }
            }

        private fun retireGracefully(db: IptvDatabase?) {
            if (db == null) return
            closeExecutor.execute {
                try {
                    Thread.sleep(RETIRE_GRACE_MS)
                } catch (_: InterruptedException) {
                    // Close promptly on interrupt.
                }
                runCatching { db.close() }
            }
        }

        fun getInstance(context: Context): IptvDatabase {
            val dbName = com.kennyb1201.kbstream.data.sync.ProfileStorage.dbNameForActive(
                context, "iptv_epg.db"
            )
            if (dbName == "iptv_epg.db") {
                // No active profile yet → legacy/global instance.
                return INSTANCE ?: synchronized(this) {
                    INSTANCE ?: Room.databaseBuilder(
                        context.applicationContext,
                        IptvDatabase::class.java,
                        "iptv_epg.db"
                    ).fallbackToDestructiveMigration(dropAllTables = true)
                        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                        .addCallback(RoomBusyTimeout)
                        .build()
                        .also { INSTANCE = it }
                }
            }
            synchronized(this) {
                if (scopedName == dbName && scopedInstance != null) return scopedInstance!!
                retireGracefully(scopedInstance)
                scopedInstance = Room.databaseBuilder(
                    context.applicationContext,
                    IptvDatabase::class.java,
                    dbName
                ).fallbackToDestructiveMigration(dropAllTables = true)
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .addCallback(RoomBusyTimeout)
                    .build()
                scopedName = dbName
                return scopedInstance!!
            }
        }

        @Volatile private var scopedInstance: IptvDatabase? = null
        @Volatile private var scopedName: String? = null

        /**
         * Closes the CURRENT scoped DB but leaves a tombstone in
         * [scopedName] so a caller that resolved the old profile's name
         * cannot race in and rebuild/reopen the closed profile's EPG
         * database after the switch (mirrors WatchHistoryDatabase).
         */
        fun closeScopedInstanceForSwitch() {
            synchronized(this) {
                retireGracefully(scopedInstance)
                scopedInstance = null
                // Keep scopedName as the tombstone; do not clear it.
            }
        }
    }
}
