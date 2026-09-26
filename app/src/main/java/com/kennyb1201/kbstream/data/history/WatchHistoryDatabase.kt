package com.kennyb1201.kbstream.data.history

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kennyb1201.kbstream.data.db.RoomBusyTimeout
import com.kennyb1201.kbstream.data.db.retryOnDatabaseSwap
import com.kennyb1201.kbstream.data.db.withDatabaseSwapRetry
import com.kennyb1201.kbstream.data.cache.ImdbResolutionDao
import com.kennyb1201.kbstream.data.cache.ImdbResolutionEntity
import com.kennyb1201.kbstream.data.cache.SyncOutboxDao
import com.kennyb1201.kbstream.data.cache.SyncOutboxEntity
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheDao
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheEntity
import com.kennyb1201.kbstream.data.cache.WatchedStatusDao
import com.kennyb1201.kbstream.data.cache.WatchedStatusEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

@Database(
    entities = [
        WatchHistoryEntity::class,
        WatchedStatusEntity::class,
        ImdbResolutionEntity::class,
        TmdbJsonCacheEntity::class,
        SyncOutboxEntity::class
    ],
    version = 12,
    exportSchema = false
)
abstract class WatchHistoryDatabase : RoomDatabase() {
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun watchedStatusDao(): WatchedStatusDao
    abstract fun imdbResolutionDao(): ImdbResolutionDao
    abstract fun tmdbJsonCacheDao(): TmdbJsonCacheDao
    abstract fun syncOutboxDao(): SyncOutboxDao

    companion object {
        private const val TAG = "WATCH_HISTORY_DB"

        @Volatile
        private var instance: WatchHistoryDatabase? = null

        // Retired (to-be-closed) DBs are closed after a short grace period
        // instead of synchronously on profile switch: a query that is
        // mid-flight on the old instance (e.g. a large Continue-Watching
        // cursor waiting for a connection) gets its connection pool yanked
        // shut otherwise and crashes with "connection pool has been closed".
        private const val RETIRE_GRACE_MS = 5_000L
        private val closeExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "room-db-retire").apply { isDaemon = true }
            }

        private val retireSequence = java.util.concurrent.atomic.AtomicLong(0L)

        /** A retired instance plus the retirement it belongs to. */
        private class Retirement(val db: WatchHistoryDatabase, val token: Long)

        /**
         * Instances retired but not yet closed, keyed by the file they belong
         * to — so that "one open instance per file" stays true.
         *
         * Before this, a name mismatch closed the previous instance
         * SYNCHRONOUSLY (`profileInstance?.close()`) while queries could still
         * be running on it ("connection pool has been closed"), and the
         * tombstone path from [closeScopedInstanceForSwitch] reopened the SAME
         * file after only a null check — two live connections to one file, the
         * same shape of failure the guide database hit as
         * "database is locked (code 5 SQLITE_BUSY)" followed by "attempt to
         * re-open an already-closed object".
         *
         * A file asked for again inside the grace window now gets its instance
         * back ([reviveIfPending]) and the pending close is cancelled.
         */
        private val pendingClose = HashMap<String, Retirement>()

        /**
         * Retire [db] — the instance for the file [name] — closing it after the
         * grace period UNLESS that file is asked for again first, which cancels
         * the close (see [pendingClose]).
         */
        private fun retireGracefully(name: String?, db: WatchHistoryDatabase?) {
            if (db == null) return
            val token = retireSequence.incrementAndGet()
            if (name != null) {
                synchronized(this) { pendingClose[name] = Retirement(db, token) }
            }
            closeExecutor.execute {
                try {
                    Thread.sleep(RETIRE_GRACE_MS)
                } catch (_: InterruptedException) {
                    // Fall through and close promptly on interrupt.
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
        private fun reviveIfPending(name: String): WatchHistoryDatabase? =
            synchronized(this) { pendingClose.remove(name)?.db }

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
                    .addMigrations(
                        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12
                    )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    // WAL lets readers and the sync writer proceed in
                    // parallel instead of failing with SQLITE_BUSY.
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .addCallback(RoomBusyTimeout)
                    .build()
                    .also { instance = it }
            }

        /**
         * Profile-scoped DB: every profile gets its own isolated history.
         * Call sites that store per-user data use this; the un-scoped
         * [getInstance] remains for global caches (tmdb_json_cache).
         */
        fun getInstanceScoped(context: Context): WatchHistoryDatabase {
            val dbName = activeFileName(context)
            synchronized(this) {
                profileInstance?.let { if (profileInstanceName == dbName) return it }
                // Same file, retired moments ago and not closed yet: take that
                // instance back instead of opening a second connection to it.
                reviveIfPending(dbName)?.let { revived ->
                    profileInstance = revived
                    profileInstanceName = dbName
                    return revived
                }
                retireGracefully(profileInstanceName, profileInstance)
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
                .addMigrations(
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                    MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(RoomBusyTimeout)
                .build()

        /**
         * Profile-switch isolation: closes the open profile-scoped DB so the
         * next [getInstanceScoped] call reopens against the NEW active
         * profile instead of returning the previous profile's instance.
         */
        fun closeScopedInstance() {
            synchronized(this) {
                retireGracefully(profileInstanceName, profileInstance)
                profileInstance = null
                profileInstanceName = null
            }
        }

        /**
         * The database FILE the ACTIVE profile's history lives in.
         *
         * A caller that holds a DAO across a long operation uses this to
         * notice that the active profile changed (mirrors
         * [com.kennyb1201.kbstream.data.iptv.db.IptvDatabase.activeFileName]).
         */
        fun activeFileName(context: Context): String =
            com.kennyb1201.kbstream.data.sync.ProfileStorage.dbNameForActive(
                context, "kbstream_watch_history"
            )

        /**
         * Runs [block] against the ACTIVE profile's history DAO, re-running it
         * while the failure is the scoped instance being retired underneath it
         * — Room's "connection pool has been closed" / "already-closed
         * object" / SQLITE_BUSY, which is what a profile switch looks like to
         * an operation that is already running (see
         * data/db/DatabaseSwapRetry.kt). The history twin of the guide
         * database's retry: without it the very same race reads as a real
         * failure — a dead Continue Watching rail, a resume point that never
         * loads, or a lost progress row.
         *
         * The active FILE is pinned before the first attempt. A retry after
         * the user switched profile would otherwise resolve the NEW profile's
         * DAO and file the departing profile's row under it — the "poisoned
         * row" PoisonSweep exists to clean up. A switch therefore rethrows
         * instead of retrying.
         */
        suspend fun <T> withScopedDao(
            context: Context,
            block: suspend (WatchHistoryDao) -> T
        ): T {
            val pinnedName = activeFileName(context)
            return withDatabaseSwapRetry(
                onRetry = { attempt, error ->
                    Log.w(TAG, "HISTORY DB RETRY $attempt after database swap", error)
                }
            ) {
                if (activeFileName(context) != pinnedName) {
                    throw IllegalStateException(
                        "profile changed during watch-history operation " +
                            "(was $pinnedName, now ${activeFileName(context)})"
                    )
                }
                block(getInstanceScoped(context).watchHistoryDao())
            }
        }

        /**
         * The [Flow] form of [withScopedDao]. [build] is invoked on every
         * (re-)collection against a DAO resolved then, so a retry re-queries
         * the instance the database layer settled on instead of re-collecting
         * a flow still bound to the retired one.
         *
         * Reads are deliberately NOT pinned to a profile: a genuine switch
         * should re-target them at the new profile's history.
         */
        fun <T> observeScopedDao(
            context: Context,
            build: (WatchHistoryDao) -> Flow<T>
        ): Flow<T> = flow {
            emitAll(build(getInstanceScoped(context).watchHistoryDao()))
        }.retryOnDatabaseSwap { attempt, error ->
            Log.w(TAG, "HISTORY FLOW RETRY $attempt after database swap", error)
        }

        /**
         * Closes the CURRENT scoped DB but leaves a tombstone in
         * [profileInstanceName] so a caller that captured the old DB name
         * (e.g. an in-flight Continue Watching subscription from the
         * profile that was just left) cannot race in and rebuild/reopen
         * the closed profile's database after the switch. Any legitimate
         * caller - which resolved the NEW profile's name via
         * [getInstanceScoped] AFTER the switch - gets a fresh instance
         * because [profileInstance] is null.
         */
        fun closeScopedInstanceForSwitch() {
            synchronized(this) {
                retireGracefully(profileInstanceName, profileInstance)
                profileInstance = null
                // Keep profileInstanceName as the tombstone; do not clear it.
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_watch_history_parentId` " +
                        "ON `watch_history` (`parentId`)"
                )
            }
        }

        // v10: the TMDB detail JSON cached before the episode-title field
        // (TmdbEpisodeAirInfo.name) was added parses back without episode
        // titles, and its 30-day TTL would keep hiding them on the Upcoming
        // rail. Purge only the cache table; watch history stays intact and
        // rows re-enrich from the live API.
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM `tmdb_json_cache`")
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

        // v11: watched_status_cache gains the isPartiallyWatched flag (eye
        // badge for shows started but not finished). Existing rows default
        // to 0 and re-resolve on the next watched preload.
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `watched_status_cache` ADD COLUMN `isPartiallyWatched` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // v12: the sync outbox becomes durable (sync_outbox) so an offline
        // write survives process death instead of relying on the in-memory
        // queue. Created empty; rows are re-enqueued by the normal write
        // paths on the next launch.
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_outbox` (
                        `id` TEXT NOT NULL,
                        `tableName` TEXT NOT NULL,
                        `keyColumn` TEXT NOT NULL,
                        `itemKey` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `enqueuedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
