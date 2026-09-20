package com.kennyb1201.kbstream.data.sync

import android.content.Context
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository

/**
 * One-time cross-profile poison sweep (split out of SupabaseSync — that
 * object had grown past the point where a single file stayed editable).
 *
 * The profile-switch races fixed in SupabaseSync let bulk operations that
 * STARTED under profile A land under profile B when the user switched
 * profiles mid-flight. The races are gone, but whatever ALREADY leaked stays
 * behind:
 *
 *   1. watched_status_cache rows inside the WRONG profile's scoped
 *      Room DB — the phantom watched markers (with clean Simkl/
 *      MDBList dashboards, because the data never came from the
 *      trackers),
 *   2. Simkl per-profile disk blobs cached under the wrong
 *      "<profileId>/simkl:*" key — poisoning badges for up to 12h,
 *   3. sync_watched_status rows in the CLOUD stamped under the
 *      wrong profile scope. These re-download on every pull and
 *      would resurrect the phantom markers even after 1 and 2 were
 *      wiped,
 *   4. sync_watch_history rows likewise duplicated into the other
 *      profile's scope — the phantom Continue Watching cards (plus
 *      their local copies, which would be re-pushed otherwise),
 *   5. watched-OVERRIDE sets: overrides sync as a full-replace blob,
 *      so a mid-pull switch copied one profile's entire set onto
 *      another — phantom manual marks that no tracker explains.
 *
 * The sweep runs once per install (flags live in kbstream_sync_meta, a
 * prefs file that never syncs). Rows are only ever deleted when they are
 * provably the same local write under two profile scopes (identical
 * payload or identical payload timestamp) — see [PoisonDetector].
 * Nothing else is touched.
 *
 * Kept the "SUPABASE_SYNC" log tag so existing logcat filters keep working.
 */
internal object PoisonSweep {

    private const val TAG = "SUPABASE_SYNC"

    private const val PREFS = "kbstream_sync_meta"
    // "v2" flags: the sweep was extended (history rows + watched-override
    // blobs), so a device that ran the narrower first version still gets the
    // wider one.
    private const val FLAG_LOCAL = "poison_sweep_v2_local_done"
    private const val FLAG_CLOUD = "poison_sweep_v2_cloud_done"
    // Duplicated override sets found locally, kept until their cloud blobs
    // are deleted too — clearing the local set erases the evidence needed to
    // find them.
    private const val PENDING_OVERRIDE_CLEARS = "poison_sweep_pending_overrides"
    private const val OVERRIDES_PREFS_BASE = "kbstream_watched_overrides"
    private const val OVERRIDES_KEY = "watched_overrides"
    private const val DB_BASE = "kbstream_watch_history"

    // Same wire table names as SupabaseSync (kept local so the sweep does not
    // depend on that object's private constants).
    private const val TABLE_HISTORY = "sync_watch_history"
    private const val TABLE_WATCHED = "sync_watched_status"
    private const val TABLE_PREFS = "sync_prefs"

    private val sweepMutex = Mutex()

    /**
     * One-line cleanup status for the Settings → Sync panel, so the user can
     * tell whether the cross-profile cleanup has run on this device.
     */
    fun status(context: Context): String {
        val flags = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val local = flags.getBoolean(FLAG_LOCAL, false)
        val cloud = flags.getBoolean(FLAG_CLOUD, false)
        return when {
            local && cloud -> "completed"
            local -> "local done, cloud pending (sign in)"
            else -> "not run yet"
        }
    }

    /**
     * Fire-and-forget entry point; safe to call from anywhere (startup,
     * auth events). Concurrent callers are serialized by [sweepMutex] and the
     * flags make each part idempotent.
     */
    fun run(
        context: Context,
        scope: CoroutineScope,
        client: () -> SupabaseClient?,
        isSignedIn: () -> Boolean,
        pullNow: suspend (Context) -> Unit
    ) {
        val appCtx = context.applicationContext
        scope.launch {
            sweepMutex.withLock {
                runCatching { runInternal(appCtx, client, isSignedIn, pullNow) }
                    .onFailure { Log.w(TAG, "poison sweep failed: ${it.message}") }
            }
        }
    }

    private suspend fun runInternal(
        context: Context,
        client: () -> SupabaseClient?,
        isSignedIn: () -> Boolean,
        pullNow: suspend (Context) -> Unit
    ) {
        val flags = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val profiles = ProfileManager.profiles.value.sortedBy { it.createdAt }
        val orderedPids = profiles.map { it.id }
        val localPids = orderedPids.toSet()

        // ── Part 1: local derived caches (no network needed) ─────────
        if (!flags.getBoolean(FLAG_LOCAL, false)) {
            val activePid = ProfileManager.activeProfile.value?.id
            // Wipe the watched-status CACHE for every profile namespace.
            // It is fully derived (Simkl/MDBList/history/overrides/cloud
            // resolve it back within seconds of first use), so clearing is
            // lossless — and it is exactly where the phantom markers live.
            for (profile in profiles) {
                if (profile.id == activePid) {
                    // Active profile's DB may be open by Room — go through
                    // the DAO instead of raw SQL.
                    runCatching {
                        WatchHistoryDatabase.getInstanceScoped(context)
                            .watchedStatusDao().clearAll()
                    }.onFailure {
                        Log.w(TAG, "poison sweep: active watched cache clear failed: ${it.message}")
                    }
                } else {
                    rawClearWatchedCacheTable(context, ProfileStorage.dbName(profile.id, DB_BASE))
                }
            }
            // Legacy (pre-profiles) unscoped DB — same cache table.
            runCatching {
                WatchHistoryDatabase.getInstance(context)
                    .watchedStatusDao().clearAll()
            }
            // Simkl per-profile disk blobs in the SHARED cache table. A
            // mid-flight switch wrote profile A's lists under profile B's
            // key; deleting them forces a clean refetch from each
            // profile's own Simkl account.
            runCatching {
                val keys = buildList {
                    for (profile in profiles) {
                        add("${profile.id}/simkl:all_show_items")
                        add("${profile.id}/simkl:continue_watching")
                        add("${profile.id}/simkl:completed_movies")
                    }
                    // Legacy bare keys (pre-profiles layout).
                    add("simkl:all_show_items")
                    add("simkl:continue_watching")
                    add("simkl:completed_movies")
                }
                WatchHistoryDatabase.getInstance(context).tmdbJsonCacheDao().deleteByKeys(keys)
            }.onFailure {
                Log.w(TAG, "poison sweep: simkl blob clear failed: ${it.message}")
            }
            // Watched-override sets that are an exact copy of an OLDER
            // profile's set: the full-replace blob applier copied one
            // profile's whole set onto another mid-race. Recorded in prefs so
            // the cloud half (which needs a session) can delete the matching
            // blobs even if it runs in a later attempt.
            runCatching {
                val duplicates = PoisonDetector.duplicateOverrideOwners(
                    orderedPids.map { pid -> pid to localOverrideKeys(context, pid) }
                )
                for (pid in duplicates) {
                    context.getSharedPreferences(
                        ProfileStorage.prefsName(pid, OVERRIDES_PREFS_BASE),
                        Context.MODE_PRIVATE
                    ).edit().putStringSet(OVERRIDES_KEY, emptySet()).apply()
                }
                if (duplicates.isNotEmpty()) {
                    val pending = flags.getStringSet(PENDING_OVERRIDE_CLEARS, emptySet()).orEmpty()
                    flags.edit()
                        .putStringSet(PENDING_OVERRIDE_CLEARS, pending + duplicates)
                        .apply()
                    Log.i(TAG, "poison sweep: cleared ${duplicates.size} duplicated watched-override set(s)")
                }
            }.onFailure {
                Log.w(TAG, "poison sweep: override duplicate check failed: ${it.message}")
            }
            // Drop the matching in-memory caches so nothing stale is
            // served from RAM after the disk wipe.
            WatchedStatusRepository.invalidateAllCaches()
            SimklRepository.clearTransientCaches()
            flags.edit().putBoolean(FLAG_LOCAL, true).apply()
            Log.i(TAG, "poison sweep: local watched caches cleared for ${profiles.size} profile(s)")
        }

        // ── Part 2: cloud rows stamped under the wrong profile ───────
        // Needs a signed-in session; skipped (flag NOT set) when signed
        // out so the Authenticated hook retries after sign-in.
        if (!flags.getBoolean(FLAG_CLOUD, false)) {
            val c = client() ?: return
            if (!isSignedIn()) return
            // Cross-profile poison requires ≥2 profiles; with fewer there
            // is nothing to attribute, so mark done.
            if (profiles.size < 2) {
                flags.edit().putBoolean(FLAG_CLOUD, true).apply()
                return
            }

            var deleted = 0

            // (1) watched rows, (2) history rows — same fingerprint in both
            // tables: one local write present under two profile scopes.
            for (table in listOf(TABLE_WATCHED, TABLE_HISTORY)) {
                val rows = readCloudRows(c, table) ?: return
                val poison = PoisonDetector.crossScopeDuplicates(rows, orderedPids, localPids)
                if (poison.isEmpty()) continue
                deleted += deleteCloudKeys(c, table, poison)
                Log.i(TAG, "poison sweep: $table — deleting ${poison.size} duplicated row(s)")
                if (table == TABLE_HISTORY) {
                    // Delete the LOCAL copies too, or the next push re-creates
                    // them from this device (they are what the card renders).
                    for ((pid, keys) in poison.groupBy { SyncKeys.scopeOf(it) ?: "" }) {
                        if (pid.isEmpty()) continue
                        deleteLocalHistoryRows(context, pid, keys.map { SyncKeys.unscoped(it) })
                    }
                }
            }

            // (3) watched-override blobs copied onto a newer profile. Read
            // the pids recorded by part 1 (the local clear erased them from
            // the prefs) plus anything detectable now.
            val overridePids = (flags.getStringSet(PENDING_OVERRIDE_CLEARS, emptySet()).orEmpty() +
                PoisonDetector.duplicateOverrideOwners(
                    orderedPids.map { pid -> pid to localOverrideKeys(context, pid) }
                )).toList()
            if (overridePids.isNotEmpty()) {
                val blobKeys = overridePids.map {
                    SyncKeys.scoped(PrefsPayloadBuilder.KEY_WATCHED_OVERRIDES, it)
                }
                deleted += deleteCloudKeys(c, TABLE_PREFS, blobKeys)
                Log.i(TAG, "poison sweep: deleting ${blobKeys.size} duplicated override blob(s)")
            }

            flags.edit()
                .putBoolean(FLAG_CLOUD, true)
                .remove(PENDING_OVERRIDE_CLEARS)
                .apply()
            Log.i(TAG, "poison sweep: deleted $deleted cross-profile cloud row(s)")
            // Converge local state with the now-clean cloud tables.
            WatchedStatusRepository.invalidateAllCaches()
            runCatching { pullNow(context) }
                .onFailure { Log.w(TAG, "poison sweep: post-clean pull failed: ${it.message}") }
        }
    }

    /**
     * Raw-SQL wipe of watched_status_cache in a profile DB that Room has
     * NOT opened (only the active profile's scoped DB is open at any
     * time; the caller routes that one through the DAO instead).
     */
    private fun rawClearWatchedCacheTable(context: Context, dbName: String) {
        runCatching {
            val file = context.getDatabasePath(dbName)
            if (!file.exists()) return
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )
            try {
                db.execSQL("DELETE FROM watched_status_cache")
            } finally {
                db.close()
            }
        }.onFailure {
            Log.w(TAG, "poison sweep: raw clear of $dbName failed: ${it.message}")
        }
    }

    /** This profile's local watched-override keys (scoped prefs file). */
    private fun localOverrideKeys(context: Context, pid: String): Set<String> =
        runCatching {
            context.getSharedPreferences(
                ProfileStorage.prefsName(pid, OVERRIDES_PREFS_BASE),
                Context.MODE_PRIVATE
            ).getStringSet(OVERRIDES_KEY, emptySet()).orEmpty()
        }.getOrDefault(emptySet())

    /**
     * Reads a whole cloud table for this account as poison-detector rows.
     * Returns null when the read failed (caller leaves the flag unset so the
     * next sign-in retries the sweep).
     */
    private suspend fun readCloudRows(
        c: SupabaseClient,
        table: String
    ): List<PoisonDetector.Row>? =
        runCatching {
            c.from(table).select().decodeList<SyncRowDto>().mapNotNull { row ->
                val stored = row.itemKey ?: row.itemId ?: row.prefKey ?: return@mapNotNull null
                PoisonDetector.Row(stored, row.payload)
            }
        }.onFailure {
            Log.w(TAG, "poison sweep: reading $table failed: ${it.message}")
        }.getOrNull()

    /** Deletes [storedKeys] from [table] in chunks; returns how many went. */
    private suspend fun deleteCloudKeys(
        c: SupabaseClient,
        table: String,
        storedKeys: List<String>
    ): Int {
        if (storedKeys.isEmpty()) return 0
        val keyColumn = when (table) {
            TABLE_HISTORY -> "item_id"
            TABLE_PREFS -> "pref_key"
            else -> "item_key"
        }
        var deleted = 0
        for (chunk in storedKeys.chunked(50)) {
            runCatching {
                c.from(table).delete {
                    filter { isIn(keyColumn, chunk) }
                }
                deleted += chunk.size
            }.onFailure {
                Log.w(TAG, "poison sweep: deleting from $table failed (${it.message})")
                return deleted
            }
        }
        return deleted
    }

    /**
     * Removes poisoned history rows from the profile they were copied INTO.
     * The active profile goes through Room (its DB may be open); every other
     * profile's DB is closed, so a raw delete is safe there.
     */
    private suspend fun deleteLocalHistoryRows(
        context: Context,
        pid: String,
        ids: List<String>
    ) {
        if (ids.isEmpty()) return
        if (pid == ProfileManager.activeProfile.value?.id) {
            runCatching {
                val dao = WatchHistoryDatabase.getInstanceScoped(context).watchHistoryDao()
                ids.forEach { dao.deleteById(it) }
            }.onFailure {
                Log.w(TAG, "poison sweep: active-profile history delete failed: ${it.message}")
            }
            return
        }
        rawDeleteHistoryRows(context, ProfileStorage.dbName(pid, DB_BASE), ids)
    }

    private fun rawDeleteHistoryRows(context: Context, dbName: String, ids: List<String>) {
        runCatching {
            val file = context.getDatabasePath(dbName)
            if (!file.exists()) return
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )
            try {
                db.beginTransaction()
                try {
                    for (id in ids) {
                        db.execSQL("DELETE FROM watch_history WHERE id = ?", arrayOf(id))
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                db.close()
            }
        }.onFailure {
            Log.w(TAG, "poison sweep: raw history delete in $dbName failed: ${it.message}")
        }
    }
}
