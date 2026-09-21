package com.kennyb1201.kbstream.data.iptv.db

import android.util.Log
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Full-text index over EPG programme titles, used by the guide-wide search.
 *
 * The search used to be a leading-wildcard `LIKE '%q%'` across every programme
 * row. That cannot use an index, so SQLite scanned and sorted all programmes
 * that had not finished yet — the reason the search is gated behind a minimum
 * query length at all.
 *
 * Why raw SQL instead of a Room `@Fts4` entity: an FTS entity belongs to Room's
 * schema, and introducing it means a version bump. This database is built with
 * `fallbackToDestructiveMigration(dropAllTables = true)`, so that bump would
 * wipe every user's cached playlist and imported guide (a 14,000-channel
 * playlist and a multi-hundred-megabyte guide on the TV this was tuned on) to
 * add an index. Creating the table from a Room callback leaves the schema
 * version, and therefore all of that cached data, untouched.
 *
 * The index is a *standalone* FTS4 table (no `content=`), so it keeps its own
 * copy of each title. That makes it immune to the failure mode of an
 * external-content index: a stale entry can never make the FTS engine read a
 * row that no longer exists. Rows are kept in sync by triggers, and the search
 * always joins back to `epg_programs` on `id`, so an entry whose programme is
 * gone — including one an `INSERT OR REPLACE` removed without firing SQLite's
 * delete trigger — simply drops out of the results.
 */
object EpgSearchIndex {

    /** FTS table name; also referenced by the raw search query. */
    const val TABLE = "epg_programs_fts"

    private const val CONTENT_TABLE = "epg_programs"
    private const val TAG = "EPG_SEARCH_INDEX"

    /**
     * Creates the index and its triggers when they are missing, backfilling
     * from the programmes already in the table. Safe to call on every database
     * open: the DDL is `IF NOT EXISTS` and the backfill only runs when the
     * index is first created.
     *
     * The triggers are (re)created unconditionally. A destructive migration
     * drops `epg_programs` and its triggers but leaves this table behind, so a
     * "table exists" check alone would leave later imports unindexed.
     *
     * Never throws — a failure here must not stop the database from opening,
     * because a database that cannot open takes the whole guide with it. A
     * missing index just means the search falls back to the `LIKE` scan.
     */
    fun ensure(db: SupportSQLiteDatabase) {
        try {
            val created = !tableExists(db)
            if (created) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `$TABLE` USING FTS4(`title`)"
                )
            }
            createTriggers(db)
            if (created) {
                // Programmes imported before this index existed are not covered
                // by the triggers, so index the existing rows now.
                db.execSQL(
                    "INSERT INTO `$TABLE`(rowid, title) " +
                        "SELECT id, title FROM `$CONTENT_TABLE`"
                )
                Log.w(TAG, "INDEX BUILT rows=${countOf(db, TABLE)}")
            }
        } catch (t: Throwable) {
            // Also catches the (unlikely) case of an SQLite build without
            // FTS4: the search then keeps using the LIKE scan.
            Log.e(TAG, "INDEX UNAVAILABLE — guide search falls back to LIKE", t)
        }
    }

    private fun createTriggers(db: SupportSQLiteDatabase) {
        // The FTS row's `rowid` is the programme's `id`, which is what the
        // search joins on. DELETE-then-INSERT on UPDATE keeps a retitled
        // programme from leaving its old title searchable.
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS ${TABLE}_ai AFTER INSERT ON `$CONTENT_TABLE` " +
                "BEGIN INSERT INTO `$TABLE`(rowid, title) VALUES (new.id, new.title); END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS ${TABLE}_ad AFTER DELETE ON `$CONTENT_TABLE` " +
                "BEGIN DELETE FROM `$TABLE` WHERE rowid = old.id; END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS ${TABLE}_au AFTER UPDATE ON `$CONTENT_TABLE` " +
                "BEGIN DELETE FROM `$TABLE` WHERE rowid = old.id; " +
                "INSERT INTO `$TABLE`(rowid, title) VALUES (new.id, new.title); END"
        )
    }

    private fun tableExists(db: SupportSQLiteDatabase): Boolean =
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf<Any?>(TABLE)
        ).use { it.moveToFirst() }

    private fun countOf(db: SupportSQLiteDatabase, table: String): Long =
        db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
}

/**
 * Registers [EpgSearchIndex] with a database instance. Attached to every
 * builder in [IptvDatabase] — the index lives in each profile's own database
 * file, so each one needs its own index.
 *
 * Runs on `onOpen` rather than `onCreate` so an existing database (an upgrade
 * from a build without the index) gets one too, and so a database recreated by
 * a destructive migration has its triggers restored.
 */
object EpgSearchIndexCallback : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        EpgSearchIndex.ensure(db)
    }
}
