package com.kennyb1201.kbstream.data.iptv.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.kennyb1201.kbstream.data.db.RoomBusyTimeout
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

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

        private val retireSequence = AtomicLong(0L)

        /** A retired instance plus the retirement it belongs to. */
        private class Retirement(val db: IptvDatabase, val token: Long)

        /**
         * Instances retired but not yet closed, keyed by the database FILE they
         * belong to.
         *
         * This is what makes "at most one open instance per file" true, and it
         * is the difference between a working guide import and this crash:
         *
         *   IllegalStateException: attempt to re-open an already-closed object:
         *     SQLiteDatabase: /data/.../<profile>.iptv_epg.db
         *   SQLiteDatabaseLockedException: database is locked (code 5 SQLITE_BUSY)
         *
         * The guard used to be `scopedName != dbName`, and a retirement left
         * `scopedInstance = null` with the name as a tombstone. Asking for the
         * same file again inside the grace window therefore REBUILT it, so two
         * Room instances were open on one file: the second open's DDL
         * ([EpgSearchIndexCallback]) needs the schema lock the first instance's
         * in-flight writer holds, which is the SQLITE_BUSY — and when the grace
         * expired the new instance's caller was fine while anyone still holding
         * the old one got "already-closed object". A long import holds one for
         * minutes, so an EPG import on a profile whose instance had just been
         * retired was the case that died.
         *
         * Now a file asked for again inside the grace window gets its instance
         * BACK ([reviveIfPending]) and the pending close is cancelled.
         */
        private val pendingClose = HashMap<String, Retirement>()

        private fun build(context: Context, dbName: String): IptvDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                IptvDatabase::class.java,
                dbName
            ).fallbackToDestructiveMigration(dropAllTables = true)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(RoomBusyTimeout)
                .addCallback(EpgSearchIndexCallback)
                .build()

        /**
         * Retire [db] — the instance for the file [name] — closing it after the
         * grace period UNLESS that file is asked for again first, which cancels
         * the close (see [pendingClose]).
         */
        private fun retireGracefully(name: String?, db: IptvDatabase?) {
            if (db == null) return
            val token = retireSequence.incrementAndGet()
            if (name != null) {
                synchronized(this) { pendingClose[name] = Retirement(db, token) }
            }
            closeExecutor.execute {
                try {
                    Thread.sleep(RETIRE_GRACE_MS)
                } catch (_: InterruptedException) {
                    // Close promptly on interrupt.
                }
                // Close only if THIS retirement is still the current one for
                // that file: a revival removes the entry, and a later
                // retirement of the same file replaces it with its own token.
                val closeIt = if (name == null) {
                    true
                } else {
                    synchronized(this) {
                        val current = pendingClose[name]
                        if (current == null || current.token != token || current.db !== db) {
                            false
                        } else {
                            pendingClose.remove(name)
                            true
                        }
                    }
                }
                if (closeIt) runCatching { db.close() }
            }
        }

        /**
         * The retired-but-still-open instance for [name], if there is one,
         * taking it out of retirement (i.e. cancelling its close).
         */
        private fun reviveIfPending(name: String): IptvDatabase? =
            synchronized(this) { pendingClose.remove(name)?.db }

        fun getInstance(context: Context): IptvDatabase {
            val dbName = activeFileName(context)
            if (dbName == "iptv_epg.db") {
                // No active profile yet → legacy/global instance.
                return INSTANCE ?: synchronized(this) {
                    INSTANCE ?: build(context, "iptv_epg.db").also { INSTANCE = it }
                }
            }
            synchronized(this) {
                scopedInstance?.let { if (scopedName == dbName) return it }
                // Same file, retired moments ago and not closed yet: take that
                // instance back instead of opening a second connection to it.
                reviveIfPending(dbName)?.let { revived ->
                    scopedInstance = revived
                    scopedName = dbName
                    return revived
                }
                retireGracefully(scopedName, scopedInstance)
                val fresh = build(context, dbName)
                scopedInstance = fresh
                scopedName = dbName
                return fresh
            }
        }

        @Volatile private var scopedInstance: IptvDatabase? = null
        @Volatile private var scopedName: String? = null

        /**
         * Closes the CURRENT scoped DB but leaves a tombstone in
         * [scopedName] so a caller that resolved the old profile's name
         * cannot race in and rebuild/reopen the closed profile's EPG
         * database after the switch (mirrors WatchHistoryDatabase).
         *
         * The close is deferred and cancellable: returning to this same profile
         * inside the grace window revives the instance instead of reopening the
         * file, so a guide import that is still running keeps its connection.
         */
        fun closeScopedInstanceForSwitch() {
            synchronized(this) {
                retireGracefully(scopedName, scopedInstance)
                scopedInstance = null
                // Keep scopedName as the tombstone; do not clear it.
            }
        }

        /**
         * The database file the ACTIVE profile's guide lives in.
         *
         * A caller that holds a DAO across a long operation (the XMLTV import)
         * uses this to notice that the active profile changed and abort, rather
         * than promoting one profile's guide into another profile's file.
         */
        fun activeFileName(context: Context): String =
            com.kennyb1201.kbstream.data.sync.ProfileStorage.dbNameForActive(
                context, "iptv_epg.db"
            )
    }
}
