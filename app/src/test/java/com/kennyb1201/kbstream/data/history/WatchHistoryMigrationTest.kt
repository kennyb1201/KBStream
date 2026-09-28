package com.kennyb1201.kbstream.data.history

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real migration, on a real database.
 *
 * [WatchHistoryMigrationChainTest] proves the chain is complete on paper; this
 * one proves a Migration actually carries a database across, which is the part
 * a missing or mis-typed statement gets wrong. It runs as a JVM test under
 * Robolectric, so it is part of `./gradlew testDebugUnitTest` and needs no
 * device.
 *
 * What it does, in order:
 *  1. builds a version 11 database by hand, from the v11 shape written out
 *     below, and puts a watch-history row and a watched-status row in it;
 *  2. runs [WatchHistoryDatabase.migrations] through
 *     [MigrationTestHelper.runMigrationsAndValidate], which executes them and
 *     validates the result against the EXPORTED schema
 *     (app/schemas/…/12.json, wired into the test assets) - so a migration
 *     that produces the wrong columns fails here rather than on a device;
 *  3. asserts the user's rows are still there afterwards, and that v12's new
 *     table arrived.
 *
 * v11 is deliberately the version tested: it is the step this database most
 * recently added, the one whose absence would have thrown on open for every
 * install (see the comment on [WatchHistoryDatabase.migrations]). The v11 shape
 * is frozen here on purpose - if a future change alters one of these tables,
 * this file must NOT be updated to match, because it is describing the past.
 */
@RunWith(AndroidJUnit4::class)
class WatchHistoryMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WatchHistoryDatabase::class.java
    )

    @Test
    fun `the v12 migration keeps the rows a v11 install had`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        seedV11Database(context)

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            WATCH_HISTORY_DB_VERSION,
            true,
            *WatchHistoryDatabase.migrations.toTypedArray()
        )

        assertEquals(
            "the resume point must survive the migration",
            1,
            count(db, "watch_history")
        )
        db.query("SELECT positionMs, durationMs, isCompleted FROM watch_history").use { cursor ->
            assertTrue("the history row is missing", cursor.moveToFirst())
            assertEquals(120_000L, cursor.getLong(0))
            assertEquals(2_400_000L, cursor.getLong(1))
            assertEquals(0L, cursor.getLong(2))
        }
        assertEquals(
            "the eye-badge / watched state rows must survive too",
            1,
            count(db, "watched_status_cache")
        )
        assertTrue(
            "v12's new sync_outbox table must exist after migrating",
            hasTable(db, "sync_outbox")
        )
    }

    /** A v11 database with one history row and one watched-status row in it. */
    private fun seedV11Database(context: Context) {
        context.deleteDatabase(DB_NAME)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(V11) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    V11_DDL.forEach(db::execSQL)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // No upgrades: this helper only ever writes version 11.
                }
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { openHelper ->
            openHelper.writableDatabase.use { db ->
                db.execSQL(
                    "INSERT INTO watch_history " +
                        "(id, parentId, type, name, positionMs, durationMs, updatedAt, isCompleted) " +
                        "VALUES ('ep1:show1', 'show1', 'episode', 'Pilot', 120000, 2400000, " +
                        "1700000000000, 0)"
                )
                db.execSQL(
                    "INSERT INTO watched_status_cache " +
                        "(key, imdbId, mediaType, isWatched, isPartiallyWatched, updatedAt) " +
                        "VALUES ('series::tt1', 'tt1', 'series', 0, 1, 1700000000000)"
                )
            }
        }
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else -1
        }

    private fun hasTable(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table)
        ).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 1
        }

    private companion object {
        const val DB_NAME = "watch_history_migration_test"
        const val V11 = 11

        /**
         * The version 11 schema: every table v12 has EXCEPT sync_outbox, which
         * is the one table v11 -> v12 creates. Taken from the exported schema
         * (app/schemas/…WatchHistoryDatabase/12.json); those four tables are
         * unchanged between 11 and 12, which is exactly what makes this a valid
         * starting point for the migration.
         */
        val V11_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `watch_history` (`id` TEXT NOT NULL, " +
                "`parentId` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`episodeTitle` TEXT, `overview` TEXT, `clearLogo` TEXT, `backdropUrl` TEXT, " +
                "`totalEpisodesInSeason` INTEGER, `poster` TEXT, `streamUrl` TEXT, " +
                "`season` INTEGER, `episode` INTEGER, `episodeStreamId` TEXT, " +
                "`positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `isCompleted` INTEGER NOT NULL, " +
                "`completedAt` INTEGER, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_watch_history_parentId` " +
                "ON `watch_history` (`parentId`)",
            "CREATE TABLE IF NOT EXISTS `watched_status_cache` (`key` TEXT NOT NULL, " +
                "`imdbId` TEXT NOT NULL, `mediaType` TEXT NOT NULL, " +
                "`isWatched` INTEGER NOT NULL, `isPartiallyWatched` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
            "CREATE TABLE IF NOT EXISTS `imdb_resolution_cache` (`key` TEXT NOT NULL, " +
                "`tmdbId` INTEGER NOT NULL, `mediaType` TEXT NOT NULL, " +
                "`imdbId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
            "CREATE TABLE IF NOT EXISTS `tmdb_json_cache` (`key` TEXT NOT NULL, " +
                "`json` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))"
        )
    }
}
