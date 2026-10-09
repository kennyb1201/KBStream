package com.kennyb1201.kbstream.data.iptv.db

import android.util.Log
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Full-text index over EPG program titles AND descriptions.
 *
 * Descriptions are indexed for one consumer: the sports hub's tier-1 lookup,
 * which needs to find a game whose guide TITLE is generic ("NHL Hockey") but
 * whose DESCRIPTION names the two teams. The guide-wide search deliberately
 * reads titles only - see [IptvRepository.searchPrograms], which scopes its
 * `MATCH` to the title column - so that viewer-facing search is unchanged.
 *
 * The search used to be a leading-wildcard `LIKE '%q%'` across every program
 * row. That cannot use an index, so SQLite scanned and sorted all programs
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
 * always joins back to `epg_programs` on `id`, so an entry whose program is
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
     * from the programs already in the table. Safe to call on every database
     * open: the DDL is `IF NOT EXISTS`, and the backfill runs only when the
     * index was just created, widened (title-only to title+description), or
     * rebuilt after an orphaned migration.
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
            // An index built before descriptions were indexed has a title-only
            // schema. SQLite cannot add a column to an FTS4 table, so the only
            // way to widen it is to drop and rebuild it - the backfill below
            // then refills both columns in one pass. One-time, on the first
            // open after the upgrade; after that the schema check is a single
            // sqlite_master read.
            val widened = !created && !hasDescriptionColumn(db)
            if (created || widened) {
                if (widened) {
                    Log.w(TAG, "INDEX TITLE-ONLY — rebuilding to index descriptions")
                    // The old triggers write the title column alone, so they
                    // must go with the old table or the rebuild would leave new
                    // imports with an empty description column.
                    dropTriggers(db)
                    db.execSQL("DROP TABLE IF EXISTS `$TABLE`")
                }
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `$TABLE` USING FTS4(`title`, `description`)"
                )
            }
            // An index whose programs are gone is a destructive migration that
            // did not take it along (see [drop]). It cannot answer anything —
            // the search joins back to `epg_programs` — but it is still holding
            // a copy of every title the old guide had, which is the space the
            // migration was there to reclaim. Both checks are O(1) (an EXISTS
            // with LIMIT 1), so this costs nothing on the normal open.
            val rebuilt = !created && !widened && hasRows(db, TABLE) &&
                !hasRows(db, CONTENT_TABLE)
            if (rebuilt) {
                Log.w(TAG, "INDEX ORPHANED by a destructive migration — rebuilding")
                db.execSQL("DROP TABLE IF EXISTS `$TABLE`")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `$TABLE` USING FTS4(`title`, `description`)"
                )
            }
            createTriggers(db)
            if (created || widened || rebuilt) {
                // Programs imported before this index existed are not covered
                // by the triggers, so index the existing rows now.
                db.execSQL(
                    "INSERT INTO `$TABLE`(rowid, title, description) " +
                        "SELECT id, title, description FROM `$CONTENT_TABLE`"
                )
                Log.w(TAG, "INDEX BUILT rows=${countOf(db, TABLE)}")
            }
        } catch (t: Throwable) {
            // Also catches the (unlikely) case of an SQLite build without
            // FTS4: the search then keeps using the LIKE scan.
            Log.e(TAG, "INDEX UNAVAILABLE — guide search falls back to LIKE", t)
        }
    }

    /**
     * Removes the index and its triggers.
     *
     * Called when Room performs a destructive migration. That is the one path
     * that drops `epg_programs` WITHOUT going through SQLite's own drop of this
     * table, because a virtual table created by raw SQL is not part of Room's
     * schema: the programs go and the index — a standalone FTS4 table, so it
     * holds a second copy of every title plus its own term index — stays behind
     * holding all of them. That is tens of megabytes per guide that the
     * migration was supposed to reclaim, still on disk and now unreachable, and
     * the backfill below would never run over it again (it only fires when the
     * table is first created).
     *
     * Dropping it here means `ensure()` sees no table on the following open,
     * rebuilds an empty index over the new (empty) programs table, and the
     * maintenance pass VACUUMs the space back.
     */
    fun drop(db: SupportSQLiteDatabase) {
        runCatching { db.execSQL("DROP TABLE IF EXISTS `$TABLE`") }
            .onFailure { Log.w(TAG, "index drop failed: ${it.message}") }
        runCatching { dropTriggers(db) }
            .onFailure { Log.w(TAG, "trigger drop failed: ${it.message}") }
    }

    private fun dropTriggers(db: SupportSQLiteDatabase) {
        listOf("ai", "ad", "au").forEach { suffix ->
            db.execSQL("DROP TRIGGER IF EXISTS ${TABLE}_$suffix")
        }
    }

    private fun createTriggers(db: SupportSQLiteDatabase) {
        // The FTS row's `rowid` is the program's `id`, which is what the
        // search joins on. DELETE-then-INSERT on UPDATE keeps a retitled
        // program from leaving its old title searchable. Both indexed columns
        // are written, so the description a program was imported with is
        // searchable as readily as its title.
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS ${TABLE}_ai AFTER INSERT ON `$CONTENT_TABLE` " +
                "BEGIN INSERT INTO `$TABLE`(rowid, title, description) " +
                "VALUES (new.id, new.title, new.description); END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS ${TABLE}_ad AFTER DELETE ON `$CONTENT_TABLE` " +
                "BEGIN DELETE FROM `$TABLE` WHERE rowid = old.id; END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS ${TABLE}_au AFTER UPDATE ON `$CONTENT_TABLE` " +
                "BEGIN DELETE FROM `$TABLE` WHERE rowid = old.id; " +
                "INSERT INTO `$TABLE`(rowid, title, description) " +
                "VALUES (new.id, new.title, new.description); END"
        )
    }

    /**
     * Whether the index was built to hold descriptions as well as titles.
     *
     * Read from the table's own DDL: how a virtual table reports columns to a
     * `PRAGMA` is an implementation detail, whereas the CREATE statement is
     * exactly what decides whether the description column exists.
     */
    private fun hasDescriptionColumn(db: SupportSQLiteDatabase): Boolean =
        db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf<Any?>(TABLE)
        ).use { cursor ->
            cursor.moveToFirst() && !cursor.isNull(0) && cursor.getString(0).contains("description")
        }

    private fun tableExists(db: SupportSQLiteDatabase): Boolean =
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf<Any?>(TABLE)
        ).use { it.moveToFirst() }

    /**
     * Whether [table] holds any row at all, in O(1) — a plain `LIMIT 1` EXISTS,
     * never a `COUNT(*)` on a guide with hundreds of thousands of programs.
     */
    private fun hasRows(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT EXISTS(SELECT 1 FROM `$table` LIMIT 1)").use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 1
        }

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

    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
        // A migration that drops the guide must take the index with it: it is
        // outside Room's schema, so nothing else would. See [EpgSearchIndex.drop].
        EpgSearchIndex.drop(db)
    }
}
