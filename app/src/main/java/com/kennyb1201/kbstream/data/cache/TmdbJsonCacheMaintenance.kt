package com.kennyb1201.kbstream.data.cache

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What one trim pass did, for logging and the diagnostics report.
 */
internal data class TmdbJsonCacheTrim(
    val agedOut: Int = 0,
    val evicted: Int = 0,
    val rows: Int = 0,
    val bytes: Long = 0L
)

/**
 * Keeps `tmdb_json_cache` inside a fixed budget — and gives the database file
 * back the space that deleting rows does not.
 *
 * The table is the app's only unbounded disk store, and it grew to gigabytes
 * on real devices for three compounding reasons:
 *
 *  - **Every enriched title writes one row.** [com.kennyb1201.kbstream.data.tmdb.TmdbRepository]
 *    caches the FULL appended TMDB detail response, and that response is
 *    dominated by the parts a rail never reads back: measured against the
 *    live API, one row is 65-280 KB, of which images/credits/videos/
 *    recommendations/reviews/keywords are ~90% (a slim core of the same
 *    titles is 5-20 KB). Home, Search browse, KB folders and the Library all
 *    enrich per card, so a browse-heavy month is thousands of rows.
 *  - **The only eviction was by age** (30 days), and `updatedAt` is refreshed
 *    on every re-fetch — so anything the user keeps looking at never expired,
 *    and there was no ceiling on the sum. A table with no byte budget has no
 *    upper bound no matter how short its TTL is.
 *  - **Deleting rows does not shrink a SQLite file.** Freed pages go on the
 *    freelist and are reused, but the file keeps its high-water mark, so the
 *    space the age prune "freed" was never returned to the device.
 *
 * [trim] addresses the first two, [reclaimDatabaseSpace] the third.
 */
internal object TmdbJsonCacheMaintenance {

    private const val TAG = "TMDB_CACHE_MAINT"

    /**
     * Payload budget for the whole table.
     *
     * 64 MB holds roughly 250-400 detail rows (or ~1,000 season rows) — far
     * more than the rails and a browsing session actually re-ask for, because
     * the window that matters is what the user saw in the last few days and
     * the rest is a re-fetch at worst. Sized against the observed 65-280 KB
     * rows rather than by feel; the previous behaviour was unbounded.
     */
    const val MAX_BYTES = 64L * 1024 * 1024

    /**
     * Row ceiling. Secondary to [MAX_BYTES] — its job is to keep
     * [TmdbJsonCacheDao.sizeIndex] (which the budget needs to read in full)
     * cheap even when the rows are small season/genre entries.
     */
    const val MAX_ROWS = 4_000

    /**
     * Age cutoff. Rows past this are stale data rather than a cache: the
     * underlying TMDB records (ratings, cast, artwork, recommendations) have
     * moved on. Matches the 30-day disk TTL the repositories read with.
     */
    const val MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L

    /** SQLite's bind-parameter ceiling is 999; stay well clear of it. */
    private const val DELETE_CHUNK = 500

    /**
     * Only rewrite the file when at least this much of it is free pages
     * (see [reclaimDatabaseSpace]).
     *
     * A VACUUM takes an exclusive lock and rewrites the whole file, so it is
     * not worth doing for a few megabytes of slack — which is all a
     * well-behaved install ever has.
     */
    private const val RECLAIM_MIN_FREE_BYTES = 64L * 1024 * 1024

    /** And never below this file size, so small installs never pay for it. */
    private const val RECLAIM_MIN_FILE_BYTES = 96L * 1024 * 1024

    /**
     * Ages out stale rows, then evicts by size until the table fits the
     * budget. Returns what it did.
     *
     * Safe to call from a write path and cheap while the table is inside its
     * budget: the only work is one index read, and the eviction it usually
     * computes is empty.
     */
    suspend fun trim(dao: TmdbJsonCacheDao): TmdbJsonCacheTrim = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        val agedOut = runCatching { dao.deleteOlderThan(cutoff) }.getOrDefault(0)

        val rows = runCatching { dao.sizeIndex() }.getOrNull()
            ?: return@withContext TmdbJsonCacheTrim(agedOut = agedOut)

        val evicted = jsonCacheEvictions(
            rows = rows.map { JsonCacheEntry(it.key, it.bytes, it.updatedAt) },
            maxBytes = MAX_BYTES,
            maxEntries = MAX_ROWS
        )

        var deleted = 0
        evicted.chunked(DELETE_CHUNK).forEach { chunk ->
            if (runCatching { dao.deleteByKeys(chunk) }.isSuccess) deleted += chunk.size
        }

        // `rows` was read AFTER the age prune, so it already excludes
        // everything `agedOut` counted; only the size evictions are still in it.
        val gone = evicted.toHashSet()
        val result = TmdbJsonCacheTrim(
            agedOut = agedOut,
            evicted = deleted,
            rows = rows.size - deleted,
            bytes = rows.asSequence().filterNot { gone.contains(it.key) }.sumOf { it.bytes }
        )
        if (agedOut > 0 || deleted > 0) {
            Log.i(
                TAG,
                "trim: agedOut=$agedOut evicted=$deleted left=${result.rows} row(s) " +
                    "(${result.bytes / 1_048_576} MB of ${MAX_BYTES / 1_048_576} MB budget)"
            )
        }
        result
    }

    /**
     * Rewrites the history database when most of it is free pages, returning
     * whether it did.
     *
     * The age prune has always deleted rows, and the file has always kept the
     * pages: on an install that had grown past a gigabyte, trimming the table
     * down to its budget left every byte of that on disk, so the fix would have
     * looked like it did nothing. This is the half that actually hands the
     * space back.
     *
     * Deliberately conservative, because a VACUUM needs an exclusive lock and
     * temporarily up to the size of the file itself in free space:
     *  - it only runs when the freelist is at least [RECLAIM_MIN_FREE_BYTES]
     *    and the file is at least [RECLAIM_MIN_FILE_BYTES], so the common case
     *    (a file that is mostly live rows) never pays for it;
     *  - it is called from a background worker rather than from a screen, so
     *    no in-flight Home/guide read is holding a connection;
     *  - a failure (including `SQLITE_BUSY`, or not enough free storage for
     *    the rewrite) is reported and swallowed — it is pure space
     *    reclamation, and the next run retries.
     */
    suspend fun reclaimDatabaseSpace(context: Context, dao: TmdbJsonCacheDao): Boolean =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val file = appContext.getDatabasePath(WATCH_HISTORY_DB_NAME)
            if (!file.exists()) return@withContext false

            val before = file.length()
            if (before < RECLAIM_MIN_FILE_BYTES) return@withContext false

            // Read the pragmas through Room's own connection rather than
            // opening a second one on the file: two connections to a WAL
            // database is the shape that produced SQLITE_BUSY and
            // "attempt to re-open an already-closed object" elsewhere in this
            // app (see WatchHistoryDatabase's retirement comments).
            val db = runCatching { WatchHistoryDatabase.getInstance(appContext) }.getOrNull()
                ?: return@withContext false
            val sqlite = runCatching { db.openHelper.writableDatabase }.getOrNull()
                ?: return@withContext false

            // Trim BEFORE measuring: on the installs this exists for, the
            // space to reclaim is held by LIVE rows the budget is about to
            // delete, not by pages an earlier delete already freed - so a
            // freelist check first would see a healthy file and skip the very
            // reclaim that matters. Trimming first also avoids rewriting the
            // file twice to free pages that were about to be deleted anyway.
            runCatching { trim(dao) }

            val freeBytes = freePageBytes(sqlite)
            if (freeBytes < RECLAIM_MIN_FREE_BYTES) return@withContext false

            val vacuumed = runCatching { sqlite.execSQL("VACUUM") }
            val after = file.length()
            if (vacuumed.isSuccess) {
                Log.i(
                    TAG,
                    "reclaim: ${before / 1_048_576} MB -> ${after / 1_048_576} MB " +
                        "(free pages were ${freeBytes / 1_048_576} MB)"
                )
            } else {
                Log.w(
                    TAG,
                    "reclaim failed (lock or space): " +
                        "${vacuumed.exceptionOrNull()?.message ?: "unknown"}"
                )
            }
            vacuumed.isSuccess
        }

    /**
     * Bytes sitting on the database's freelist — pages a delete has released
     * but the file still occupies.
     *
     * `PRAGMA` rather than arithmetic over the table sizes on purpose: the
     * question is about the whole FILE (the history, watched-status, outbox
     * and imdb-resolution tables all share it), and SQLite already tracks the
     * answer exactly.
     */
    private fun freePageBytes(sqlite: SupportSQLiteDatabase): Long {
        val freePages = sqlite.pragma("PRAGMA freelist_count") ?: return 0L
        val pageSize = sqlite.pragma("PRAGMA page_size") ?: return 0L
        return freePages * pageSize
    }

    private fun SupportSQLiteDatabase.pragma(statement: String): Long? = runCatching {
        query(statement).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }.getOrNull()

    /**
     * The un-scoped history database. This table lives in the GLOBAL file on
     * purpose (a cached TMDB response is not profile data — see
     * [WatchHistoryDatabase.getInstance]), so it is that file, not the active
     * profile's, whose size this maintenance is about.
     */
    private const val WATCH_HISTORY_DB_NAME = "kbstream_watch_history"
}
