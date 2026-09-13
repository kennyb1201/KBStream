package com.kennyb1201.kbstream.data.iptv.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
                    ).fallbackToDestructiveMigration()
                        .build()
                        .also { INSTANCE = it }
                }
            }
            synchronized(this) {
                if (scopedName == dbName && scopedInstance != null) return scopedInstance!!
                runCatching { scopedInstance?.close() }
                scopedInstance = Room.databaseBuilder(
                    context.applicationContext,
                    IptvDatabase::class.java,
                    dbName
                ).fallbackToDestructiveMigration()
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
                runCatching { scopedInstance?.close() }
                scopedInstance = null
                // Keep scopedName as the tombstone; do not clear it.
            }
        }
    }
}
