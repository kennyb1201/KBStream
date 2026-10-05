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

/**
 * The watch-history schema version.
 *
 * A named constant rather than a literal in the annotation, because the
 * migration chain is only meaningful next to the version it has to reach: the
 * test in data/history reads this, [WatchHistoryDatabase.migrations] and
 * [WatchHistoryDatabase.legacyWipeVersions] together and fails if a version
 * bump arrives without the Migration that carries it — the mistake that would
 * otherwise only show up as a wipe on a user's device.
 */
internal const val WATCH_HISTORY_DB_VERSION = 12

@Database(
    entities = [
        WatchHistoryEntity::class,
        WatchedStatusEntity::class,
        ImdbResolutionEntity::class,
        TmdbJsonCacheEntity::class,
        SyncOutboxEntity::class
    ],
    version = WATCH_HISTORY_DB_VERSION,
    // Was false, which is what made the migration list unverifiable after the
    // fact: nothing recorded what each version's tables actually looked like,
    // so there was no way to review a Migration (or to notice it was missing)
    // except by reading the entities at the time. The KSP argument in
    // app/build.gradle.kts now writes every released version's shape under
    // app/schemas/, which is committed alongside the Migration it belongs to.
    exportSchema = true
)
abstract class WatchHistoryDatabase : RoomDatabase() {
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun watchedStatusDao(): WatchedStatusDao
    abstract fun imdbResolutionDao(): ImdbResolutionDao
    abstract fun tmdbJsonCacheDao(): TmdbJsonCacheDao
    abstract fun syncOutboxDao(): SyncOutboxDao

    companion object {
        private const val TAG = "WATCH_HISTORY_DB"

        /**
         * Every real migration, in one place.
         *
         * Both builders below used to spell the list out separately, so a
         * migration added to one and not the other was a silent divergence
         * between the global and the profile-scoped database: whichever path
         * the user happened to take met "A migration from N to N+1 was
         * required but not found".
         *
         * A getter rather than a val because the migration objects are
         * declared further down this companion: a property initializer here
         * would read them before they exist.
         */
        internal val migrations: List<Migration>
            get() = listOf(
                MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12
            )

        /**
         * The legacy versions that are still wiped once, on open.
         *
         * They predate the migration list — they are the versions from before
         * it existed — and wiping them is how those installs have always
         * behaved. Everything from the first real migration onwards has to
         * migrate instead. The guard test asserts that this range never
         * reaches the oldest migration, because widening it is the one change
         * that silently drops the user's watch history (resume points and
         * watched state) together with the durable sync_outbox.
         */
        internal val legacyWipeVersions: IntArray
            get() = intArrayOf(1, 2, 3, 4, 5)

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
         * back ([reviveIfPending]) and the pending close is canceled.
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
         * taking it out of retirement (i.e. canceling its close).
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
                    .addMigrations(*migrations.toTypedArray())
                    // Destructive fallback is scoped to the LEGACY versions
                    // (see [legacyWipeVersions]): an install still on one of
                    // those is wiped once, which is how it has always behaved.
                    // Upgrades from 6+ MUST have a real Migration — a missing
                    // one now throws during open (loud, and caught before it
                    // ships by the chain test in data/history) instead of
                    // silently dropping every table, which would take the
                    // local watch history and the durable sync_outbox with it.
                    // Downgrades (an older APK installed over a newer DB) still
                    // wipe, as they must: the alternative is an exception on
                    // every open, i.e. an app that cannot show history at all,
                    // and the rows come back from the account's cloud copy
                    // (SupabaseSync) once the newer build is back.
                    // dropAllTables = true is exactly what the deprecated
                    // no-arg overloads did, so the wipe behaviour is unchanged -
                    // only the signature is the current one.
                    .fallbackToDestructiveMigrationFrom(true, *legacyWipeVersions)
                    .fallbackToDestructiveMigrationOnDowngrade(true)
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
            synchronized(this) {
                // Resolve the active file INSIDE the monitor: reading it
                // outside left a window where a profile switch retargeted the
                // open while the caller still pinned the old file.
                val dbName = activeFileName(context)
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

        /**
         * A ONE-SHOT handle on a SPECIFIC profile's history file, for the case
         * where the row belongs to a profile that is no longer active (see
         * [PlaybackHistoryWriter.write]'s redirect branch).
         *
         * Deliberately outside the active-instance bookkeeping ([profileInstance]
         * / [pendingClose]): it is opened, used and closed by the caller, so the
         * same swap-guard shape as [withScopedDao] (pin the file, throw on a
         * mid-operation switch) is not needed and the active instance's
         * tombstone/revive logic is left untouched. [buildScoped] supplies the
         * same migrations, journal mode and busy-timeout callback as any other
         * scoped handle, so the file it opens is one the app could open itself.
         */
        internal fun openForProfile(
            context: Context,
            profileId: String
        ): WatchHistoryDatabase =
            buildScoped(
                context,
                com.kennyb1201.kbstream.data.sync.ProfileStorage
                    .dbName(profileId, "kbstream_watch_history")
            )

        private fun buildScoped(
            context: Context,
            dbName: String
        ): WatchHistoryDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                WatchHistoryDatabase::class.java,
                dbName
            )
                .addMigrations(*migrations.toTypedArray())
                // Same scoping as getInstance(): wipe only the legacy versions,
                // require a real migration from the first one onwards.
                .fallbackToDestructiveMigrationFrom(true, *legacyWipeVersions)
                .fallbackToDestructiveMigrationOnDowngrade(true)
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
                // Resolve-and-verify under the SAME monitor the profile switch
                // uses (closeScopedInstance). Pinning the file and then
                // resolving the DAO in two steps left a window where a switch
                // between them resolved the NEW profile's DAO while the
                // departing profile's row was still written to it - the
                // poisoned row this guard exists to prevent. The resolved
                // instance's own name is checked against the pin, so a switch
                // anywhere in the resolve still rethrows instead of retrying.
                val dao = synchronized(this) {
                    val resolved = getInstanceScoped(context)
                    if (profileInstanceName != pinnedName) {
                        throw IllegalStateException(
                            "profile changed during watch-history operation " +
                                "(was $pinnedName, now $profileInstanceName)"
                        )
                    }
                    resolved.watchHistoryDao()
                }
                block(dao)
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
