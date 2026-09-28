package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kennyb1201.kbstream.data.iptv.db.IptvDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One guide database found on disk.
 *
 * [bytes] is the whole footprint — the main file plus its `-wal`/`-shm`
 * sidecars, because an EPG import is thousands of inserts and the rows sit in
 * the write-ahead log until SQLite checkpoints them back (see
 * [com.kennyb1201.kbstream.data.reporting.Diagnostics]). [lastModifiedMs] is
 * the last write, i.e. when THIS profile's guide was last refreshed.
 */
internal data class GuideFile(
    val name: String,
    val bytes: Long,
    val lastModifiedMs: Long
)

/**
 * What one guide sweep did, for logging and the diagnostics report.
 */
internal data class GuideSweep(
    /** Guide files removed outright, by name. */
    val deleted: List<String> = emptyList(),
    val deletedBytes: Long = 0L,
    /** Guide files whose `VACUUM` handed free pages back, by name. */
    val reclaimed: List<String> = emptyList(),
    /** Programme rows dropped for being outside [EpgWindow], on either edge. */
    val prunedRows: Int = 0,
    /** Footprint of the guides that were kept, before and after. */
    val beforeBytes: Long = 0L,
    val afterBytes: Long = 0L,
    val vacuumed: Int = 0,
    val busy: Int = 0
)

/**
 * Which guide files a maintenance pass may DELETE outright, and why.
 *
 * Only two kinds qualify, and neither is a guess about the user's data:
 *
 *  - **The legacy/global file** (`iptv_epg.db`). It only exists on installs
 *    that ran before profiles did (or which touched the guide before a profile
 *    was chosen). With a profile-scoped guide on disk it can never be read
 *    again — [IptvDatabase.getInstance] resolves the active profile's own file
 *    — so it is dead weight measured in hundreds of megabytes on the very
 *    installs this report is about.
 *  - **A non-active profile's guide that has not been refreshed for [idleMs].**
 *    Each profile keeps its own copy of the provider's guide, and a profile
 *    that has not opened Live TV in a fortnight is holding a multi-hundred-
 *    megabyte cache for nobody. It is a cache, not data: the refresh worker
 *    rebuilds it from the provider the next time that profile is used (the same
 *    import that runs every [REFRESH_PERIOD_HOURS] hours for the active one).
 *
 * The ACTIVE file is never returned, however old it is, because deleting the
 * guide out from under the screen that is showing it is not a space fix.
 */
internal fun guideFilesToDelete(
    files: List<GuideFile>,
    activeFileName: String,
    now: Long,
    idleMs: Long = GuideStorage.IDLE_SWEEP_MS
): List<String> {
    val scoped = files.filter { it.name.endsWith(GuideStorage.DB_SUFFIX) }
    return buildList {
        if (scoped.isNotEmpty() && files.any { it.name == GuideStorage.LEGACY_DB_NAME }) {
            add(GuideStorage.LEGACY_DB_NAME)
        }
        scoped
            .filter { it.name != activeFileName && now - it.lastModifiedMs >= idleMs }
            .forEach { add(it.name) }
    }
}

/**
 * Keeps the IPTV guides from being the app's largest unbounded store.
 *
 * Diagnostics on the field TV reports 1.34 GB of app data with ~980 MB of it in
 * four `iptv_epg.db` files — one per profile, plus the legacy global one, at
 * 456 / 271 / 173 / 82 MB. Every other store on that device is capped by
 * design: the player's read-ahead cache at a share of the free space (a fixed
 * 256 MB when that report was taken — see
 * [com.kennyb1201.kbstream.data.player.StreamDiskCache]), Coil's posters at 2%
 * of usable storage, and the TMDB JSON cache at 64 MB (see
 * [com.kennyb1201.kbstream.data.cache.TmdbJsonCacheMaintenance]). The guide had
 * no ceiling, no age sweep and no reclaim at all, and it is the one store that
 * is *replaced* wholesale on every refresh — a guide that shrank (a channel
 * list trimmed, a provider shipping a shorter window) kept its high-water mark
 * forever, because SQLite does not shrink a file when rows are deleted.
 *
 * [sweep] is the missing maintenance, in the order the savings arrive:
 *
 *  1. delete the legacy/global guide once a scoped one exists, and any
 *     non-active profile's guide idle past [IDLE_SWEEP_MS] — whole files, so
 *     the space returns immediately. A guide whose profile IS in use is never
 *     touched here: it is live data, and its size is the window's business,
 *     not this pass's;
 *  2. prune programmes outside [EpgWindow] — the whole point. A guide written
 *     by a build whose import window was 48 hours holds programmes the guide
 *     cannot render (its grid and search read 8 hours ahead) and cannot be
 *     found again, so those rows are pure disk. Pruning them here is what
 *     shrinks an ALREADY-LARGE guide on the next pass, rather than waiting for
 *     each profile's next import to replace its schedule; passing the same
 *     window the importer now uses is what keeps the two from being a
 *     surprise to each other. The past edge is pruned for the same reason —
 *     nothing behind it is displayable, and a guide that stopped refreshing
 *     keeps its last rows forever otherwise;
 *  3. checkpoint the write-ahead log, and `VACUUM` when most of the file is
 *     free pages, which is what actually hands the space back.
 */
internal object GuideStorage {

    private const val TAG = "GUIDE_STORAGE"

    /** The unreachable pre-profile guide file. */
    const val LEGACY_DB_NAME = "iptv_epg.db"

    /** Suffix of a profile-scoped guide: `<profileId>.iptv_epg.db`. */
    const val DB_SUFFIX = ".iptv_epg.db"

    /**
     * How long a non-active profile's guide may go unrefreshed before it is
     * dropped. The refresh runs every six hours for whichever profile is
     * active ([EpgRefreshScheduler]), so a fortnight of silence means that
     * profile has not been used for a fortnight.
     */
    const val IDLE_SWEEP_MS = 14L * 24L * 60L * 60L * 1000L

    /**
     * Only rewrite a file when at least this much of it is free pages.
     *
     * A VACUUM takes an exclusive lock and rewrites the whole file, so the pass
     * is not worth it for the few megabytes of slack a freshly imported guide
     * has. Sized lower than the TMDB cache's threshold because a guide is
     * itself measured in hundreds of megabytes.
     */
    private const val RECLAIM_MIN_FREE_BYTES = 32L * 1024 * 1024

    /** And never below this file size, so small guides never pay for it. */
    private const val RECLAIM_MIN_FILE_BYTES = 64L * 1024 * 1024

    /**
     * Free storage a VACUUM must leave behind: it builds the rewritten file
     * beside the original, so it needs the live size again as free space. The
     * headroom keeps a device with just-barely room from filling up entirely
     * on a maintenance pass.
     */
    private const val VACUUM_SPACE_HEADROOM = 16L * 1024 * 1024

    /**
     * Runs one maintenance pass over every guide database on the device.
     *
     * Called from [com.kennyb1201.kbstream.work.CacheMaintenanceWorker] — a
     * background pass, deliberately: `VACUUM` needs an exclusive lock and
     * temporarily as much free storage as the file, so it cannot run from a
     * screen that may be reading the guide. Nothing here is user-visible and
     * nothing is lost by skipping: a failure (including `SQLITE_BUSY` because
     * the guide is open) is logged and retried on the next pass.
     */
    suspend fun sweep(context: Context): GuideSweep = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dir = appContext.getDatabasePath(LEGACY_DB_NAME).parentFile
            ?: return@withContext GuideSweep()

        val guides = dir.listFiles().orEmpty()
            .filter { it.isFile && isGuideDb(it.name) }
            .map { GuideFile(it.name, dbBytes(it), it.lastModified()) }
        if (guides.isEmpty()) return@withContext GuideSweep()

        val activeFileName = IptvDatabase.activeFileName(appContext)
        val now = System.currentTimeMillis()

        // The legacy file is only removable while nothing is holding it open:
        // deleteDatabase() unlinks the path, and an open connection would keep
        // the inode — and every byte of it — alive for the rest of the session.
        // The same instance makes it unmaintainable too (a second connection on
        // a file Room has open is the shape this app has been bitten by), so it
        // is left entirely for a pass that starts with a profile already known.
        val legacyOpen = IptvDatabase.legacyInstanceOpen()
        val doomed = guideFilesToDelete(guides, activeFileName, now).toMutableList()
        if (LEGACY_DB_NAME in doomed && legacyOpen) {
            Log.i(TAG, "legacy guide still open this session; leaving it for the next pass")
            doomed.remove(LEGACY_DB_NAME)
        }

        var deletedBytes = 0L
        doomed.forEach { name ->
            val bytes = guides.first { it.name == name }.bytes
            if (runCatching { appContext.deleteDatabase(name) }.getOrDefault(false)) {
                deletedBytes += bytes
                Log.i(TAG, "deleted $name (${bytes / 1_048_576} MB)")
            }
        }

        var prunedRows = 0
        var before = 0L
        var after = 0L
        var vacuumed = 0
        var busy = 0
        val reclaimed = mutableListOf<String>()

        guides.filterNot { it.name in doomed }.forEach { guide ->
            val file = File(dir, guide.name)
            if (!file.exists()) return@forEach
            if (guide.name == LEGACY_DB_NAME && legacyOpen) return@forEach
            val outcome = if (guide.name == activeFileName) {
                // The active guide is open by the app itself: go through the
                // connection Room already holds rather than opening a second
                // one on the same file (see IptvDatabase's retirement notes).
                val db = runCatching { IptvDatabase.getInstance(appContext) }.getOrNull()
                val sqlite = db?.let {
                    runCatching { it.openHelper.writableDatabase }.getOrNull()
                }
                if (sqlite == null) null else reclaim(file, now, RoomSql(sqlite))
            } else {
                // A non-active profile's guide has no instance open (the scoped
                // singleton only ever holds one), so a plain connection is the
                // process's only one to this file.
                val raw = runCatching {
                    SQLiteDatabase.openDatabase(
                        file.path, null, SQLiteDatabase.OPEN_READWRITE
                    )
                }.getOrNull()
                if (raw == null) null else {
                    try {
                        reclaim(file, now, RawSql(raw))
                    } finally {
                        runCatching { raw.close() }
                    }
                }
            }
            before += guide.bytes
            val afterBytes = dbBytes(file)
            after += afterBytes
            when (outcome?.result) {
                ReclaimResult.VACUUMED -> {
                    vacuumed++
                    reclaimed += guide.name
                    Log.i(
                        TAG,
                        "reclaim ${guide.name}: ${guide.bytes / 1_048_576} MB -> " +
                            "${afterBytes / 1_048_576} MB"
                    )
                }

                ReclaimResult.BUSY -> busy++
                else -> Unit
            }
            prunedRows += outcome?.prunedRows ?: 0
        }

        GuideSweep(
            deleted = doomed,
            deletedBytes = deletedBytes,
            reclaimed = reclaimed,
            prunedRows = prunedRows,
            beforeBytes = before,
            afterBytes = after,
            vacuumed = vacuumed,
            busy = busy
        )
    }

    /**
     * Deletes the programmes [predicate] selects [bound] for, returning how
     * many went.
     *
     * One statement, with the row count coming back from the delete itself.
     * Counting first would mean a second pass over a table that holds hundreds
     * of thousands of rows in a large guide, and neither predicate can use the
     * table's composite index on its own — on a four-guide device that is a
     * second of scanning at every launch, for a prune that usually has nothing
     * to do. [bound] is this pass's own computed timestamp, inlined rather than
     * bound, so it cannot carry anything with it.
     */
    private fun prune(sql: Sql, predicate: String, bound: Long): Int =
        runCatching { sql.delete("DELETE FROM epg_programs WHERE $predicate $bound") }
            .getOrDefault(0)

    /** True for a guide database, scoped or legacy — never for any other file. */
    private fun isGuideDb(name: String): Boolean =
        name == LEGACY_DB_NAME || name.endsWith(DB_SUFFIX)

    private enum class ReclaimResult { UNCHANGED, VACUUMED, BUSY }

    private data class Reclaim(val result: ReclaimResult, val prunedRows: Int)

    /**
     * Prunes, checkpoints and (when it is worth it) VACUUMs one guide file.
     *
     * [Sql] is the thin surface shared by Room's connection and a raw one, so
     * the eight statements below are written once instead of twice.
     */
    private fun reclaim(
        file: File,
        now: Long,
        sql: Sql
    ): Reclaim {
        // Prune first, and on BOTH edges of [EpgWindow]. On the installs this
        // exists for, most of the space to reclaim is held by live rows from a
        // build that stored 48 hours of a schedule the guide can only render 8
        // of — so a freelist check first would see a busy file and skip the
        // very reclaim that matters.
        var prunedRows = prune(sql, "endUtcMillis <", now - EpgWindow.PAST_MS)
        prunedRows += prune(sql, "startUtcMillis >", now + EpgWindow.FUTURE_MS)

        // A big import lands in the write-ahead log, and it is part of the
        // footprint the report shows until it is checkpointed back.
        runCatching { sql.exec("PRAGMA wal_checkpoint(TRUNCATE)") }

        val length = file.length()
        if (length < RECLAIM_MIN_FILE_BYTES) return Reclaim(ReclaimResult.UNCHANGED, prunedRows)
        // A VACUUM while the viewer is watching is exactly the contention the
        // guide's own write gate exists to avoid; the next pass picks it up.
        if (EpgWriteGate.isPlayerActive) return Reclaim(ReclaimResult.UNCHANGED, prunedRows)

        val freeBytes = sql.freePageBytes() ?: return Reclaim(ReclaimResult.UNCHANGED, prunedRows)
        if (freeBytes < RECLAIM_MIN_FREE_BYTES) return Reclaim(ReclaimResult.UNCHANGED, prunedRows)
        val usable = file.parentFile?.usableSpace ?: return Reclaim(ReclaimResult.UNCHANGED, prunedRows)
        if (usable < length + VACUUM_SPACE_HEADROOM) {
            Log.i(TAG, "not enough room to rewrite ${file.name} (${length / 1_048_576} MB file)")
            return Reclaim(ReclaimResult.UNCHANGED, prunedRows)
        }

        val vacuum = runCatching { sql.exec("VACUUM") }
        if (vacuum.isFailure) {
            // SQLITE_BUSY: the guide screen is reading, or an import is in
            // flight. Pure space reclamation, so this only has to try again.
            Log.w(TAG, "vacuum ${file.name} skipped: ${vacuum.exceptionOrNull()?.message}")
            return Reclaim(ReclaimResult.BUSY, prunedRows)
        }
        return Reclaim(ReclaimResult.VACUUMED, prunedRows)
    }

    /** The two connections a guide can be reached through, as one surface. */
    private interface Sql {
        fun exec(sql: String, args: Array<Any?> = emptyArray())

        /** Runs [sql] and reports how many rows it changed. */
        fun delete(sql: String): Int

        /** Bytes on the freelist, or null when the pragmas will not answer. */
        fun freePageBytes(): Long? {
            val free = queryLong("PRAGMA freelist_count") ?: return null
            val page = queryLong("PRAGMA page_size") ?: return null
            return free * page
        }

        fun queryLong(statement: String): Long?
    }

    /** Room's own connection — the active guide's. */
    private class RoomSql(private val db: SupportSQLiteDatabase) : Sql {
        override fun exec(sql: String, args: Array<Any?>) = db.execSQL(sql, args)

        override fun delete(sql: String): Int =
            db.compileStatement(sql).use { it.executeUpdateDelete() }

        override fun queryLong(statement: String): Long? =
            db.query(statement).use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    /** A plain connection — another profile's guide, which nothing else holds. */
    private class RawSql(private val db: SQLiteDatabase) : Sql {
        override fun exec(sql: String, args: Array<Any?>) = db.execSQL(sql, args)

        override fun delete(sql: String): Int =
            db.compileStatement(sql).use { it.executeUpdateDelete() }

        override fun queryLong(statement: String): Long? =
            db.rawQuery(statement, null).use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    /** Whole footprint of [file]: the database plus its WAL sidecars. */
    private fun dbBytes(file: File): Long =
        listOf(file, File("${file.path}-wal"), File("${file.path}-shm"))
            .sumOf { if (it.exists()) it.length() else 0L }
}
