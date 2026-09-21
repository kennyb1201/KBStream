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
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
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
    // "v4" flags: the sweep was extended again, so a device that ran any
    // earlier version still gets the wider one.
    //
    //   v1 → v2: history rows + watched-override blobs.
    //   v2 → v3: a THIRD source of the same phantom markers — the MDBList
    //            watched snapshot was cached per PROCESS while the API key
    //            is per PROFILE, so the first preload after a profile switch
    //            resolved the new profile's titles from the previous
    //            profile's watch history and stamped those eye badges into
    //            the new profile's own cache (again with clean Simkl/MDBList
    //            dashboards). The resolver is fixed (MdbListClient stamps the
    //            snapshot with the key that produced it); this re-run clears
    //            the rows it already wrote, which would otherwise sit out
    //            their full 6h TTL and keep the badges wrong.
    //   v3 → v4: the sweep was DEVICE-ASYMMETRIC. The cloud pass deletes the
    //            poisoned cloud row (and this device's own local copy) on
    //            whichever device runs first; on another device the surviving
    //            local history row then has no cloud twin left, so
    //            [PoisonDetector.crossScopeDuplicates] can never attribute it
    //            and clearing the derived caches doesn't stick — the eye
    //            badge is re-derived from that local resume row every time.
    //            Part 1 now compares the profile databases on THIS device
    //            (see [PoisonDetector.localHistoryDuplicates]) and queues the
    //            matched cloud rows for part 2.
    //   v4 → v5: the FINGERPRINT. v4 matched a copy only by its write stamp
    //            (or an identical payload), so a copy that was re-stamped on
    //            its way into the newer profile stayed invisible — and once
    //            the other device had deleted the poisoned cloud row (see
    //            v3 → v4), no rule in the sweep could see it at all: the
    //            badges came back from the re-derived cache every time.
    //            Rows are now also matched on the PLAYBACK they record
    //            (episode coordinates, position, duration, completion and
    //            the source URL), and the cloud pass gained the mirror rule —
    //            a cloud row under a newer scope witnessed by an older
    //            profile's LOCAL row is a copy too. The report prefs below
    //            record what each part actually found, so the cleanup can be
    //            read off Settings → Sync instead of inferred from logcat.
    private const val FLAG_LOCAL = "poison_sweep_v5_local_done"
    private const val FLAG_CLOUD = "poison_sweep_v5_cloud_done"
    // "history=2 overrides=0" / "rows=4 local=1" — surfaced by [status].
    private const val REPORT_LOCAL = "poison_sweep_v5_report_local"
    private const val REPORT_CLOUD = "poison_sweep_v5_report_cloud"
    // Duplicated override sets found locally, kept until their cloud blobs
    // are deleted too — clearing the local set erases the evidence needed to
    // find them.
    private const val PENDING_OVERRIDE_CLEARS = "poison_sweep_pending_overrides"
    // Same idea for local history rows the part-1 scan identified: their
    // cloud twin may already be gone (the device-asymmetric case above), so
    // the keys are carried over for the cloud pass to delete directly.
    private const val PENDING_HISTORY_CLEARS = "poison_sweep_pending_history"
    // Parents whose LAST local history row part 1 deleted as a copy. Their
    // eye-badge marker under that profile exists only because of the copied
    // row, so part 2 removes it too — otherwise the very next pull re-applies
    // the marker from the cloud and re-draws the badge.
    private const val PENDING_MARKER_PARENTS = "poison_sweep_pending_marker_parents"
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
        val base = when {
            local && cloud -> "completed"
            local -> "local done, cloud pending (sign in)"
            else -> "not run yet"
        }
        // What the pass found, so a device that still shows phantom markers
        // can be told apart from one whose rows were never attributable.
        val detail = listOfNotNull(
            flags.getString(REPORT_LOCAL, null),
            flags.getString(REPORT_CLOUD, null)
        ).joinToString(" · ")
        return if (detail.isEmpty()) base else "$base — $detail"
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
            var clearedOverrides = 0
            var clearedHistoryRows = 0
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
                    clearedOverrides = duplicates.size
                    Log.i(TAG, "poison sweep: cleared ${duplicates.size} duplicated watched-override set(s)")
                }
            }.onFailure {
                Log.w(TAG, "poison sweep: override duplicate check failed: ${it.message}")
            }
            // History rows duplicated into a NEWER profile LOCAL database.
            // The cloud pass can only pair scopes it can still see, so once
            // the poisoned cloud row was deleted on another device the local
            // copy left here had no twin to pair with and survived every
            // cache wipe — re-drawing the phantom eye badge and re-pushing
            // itself to the cloud. Comparing the profile DBs on this device
            // is the only way to attribute it.
            runCatching {
                val perProfile =
                    orderedPids.map { pid -> pid to localHistoryWrites(context, pid) }
                val doomed =
                    PoisonDetector.localHistoryDuplicates(perProfile)
                for ((pid, ids) in doomed.groupBy({ it.first }, { it.second })) {
                    deleteLocalHistoryRows(context, pid, ids)
                }
                if (doomed.isNotEmpty()) {
                    val pending =
                        flags.getStringSet(PENDING_HISTORY_CLEARS, emptySet()).orEmpty()
                    // The parent each copied row was feeding, for the marker
                    // cleanup in part 2.
                    val writesByProfile = perProfile.toMap()
                    val parents = doomed.mapNotNull { (pid, id) ->
                        writesByProfile[pid]
                            ?.firstOrNull { it.id == id }
                            ?.parentId
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "$pid|$it" }
                    }.toSet()
                    val pendingParents =
                        flags.getStringSet(PENDING_MARKER_PARENTS, emptySet()).orEmpty()
                    flags.edit()
                        .putStringSet(
                            PENDING_HISTORY_CLEARS,
                            pending + doomed.map { (pid, id) -> SyncKeys.scoped(id, pid) }
                        )
                        .putStringSet(PENDING_MARKER_PARENTS, pendingParents + parents)
                        .apply()
                    clearedHistoryRows = doomed.size
                    // Row-level detail: the fastest way to see WHAT was
                    // attributed when a device still shows markers.
                    Log.i(
                        TAG,
                        "poison sweep: cleared ${doomed.size} duplicated local history " +
                            "row(s): " + doomed.joinToString { "${it.first}/${it.second}" }
                    )
                }
            }.onFailure {
                Log.w(TAG, "poison sweep: local history duplicate check failed: ${it.message}")
            }
            // Drop the matching in-memory caches so nothing stale is
            // served from RAM after the disk wipe.
            WatchedStatusRepository.invalidateAllCaches()
            SimklRepository.clearTransientCaches()
            flags.edit()
                .putBoolean(FLAG_LOCAL, true)
                .putString(
                    REPORT_LOCAL,
                    "history=$clearedHistoryRows overrides=$clearedOverrides"
                )
                .apply()
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

            // Kept for step (5): the local-witness pass needs the history
            // rows this loop already read, and reading them twice would just
            // be another full-table fetch.
            var cloudHistoryRows: List<PoisonDetector.Row>? = null

            // (1) watched rows, (2) history rows — same fingerprint in both
            // tables: one local write present under two profile scopes.
            for (table in listOf(TABLE_WATCHED, TABLE_HISTORY)) {
                val rows = readCloudRows(c, table) ?: return
                if (table == TABLE_HISTORY) cloudHistoryRows = rows
                // History rows use the playback-aware identity: the two
                // scopes' payloads may have been re-stamped independently, so
                // a stamp-only match (all the watched table can use, and all
                // that is safe there) leaves them behind.
                val poison = if (table == TABLE_HISTORY) {
                    PoisonDetector.crossScopeDuplicates(
                        rows,
                        orderedPids,
                        localPids,
                        PoisonDetector::sameHistoryWrite
                    )
                } else {
                    PoisonDetector.crossScopeDuplicates(rows, orderedPids, localPids)
                }
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

            // (4) history rows the part-1 LOCAL scan identified. Their cloud
            // twin may already have been deleted on another device (which is
            // why the duplicate scan above can't see them), but the row still
            // exists in the cloud under the newer profile and would re-
            // download and re-poison this device on the next pull. Delete by
            // the key part 1 recorded.
            val localHistoryKeys =
                flags.getStringSet(PENDING_HISTORY_CLEARS, emptySet()).orEmpty().toList()
            if (localHistoryKeys.isNotEmpty()) {
                deleted += deleteCloudKeys(c, TABLE_HISTORY, localHistoryKeys)
                // ...and make sure the local copy is gone as well: part 1
                // deleted it, but a pull that ran between the two parts can
                // have re-downloaded the row from the cloud before this pass
                // deleted it, which would otherwise leave it in place forever
                // (part 1 never runs again).
                for ((pid, keys) in localHistoryKeys.groupBy { SyncKeys.scopeOf(it) ?: "" }) {
                    if (pid.isEmpty()) continue
                    deleteLocalHistoryRows(context, pid, keys.map { SyncKeys.unscoped(it) })
                }
                Log.i(
                    TAG,
                    "poison sweep: deleting ${localHistoryKeys.size} " +
                        "local-identified history row(s) from the cloud"
                )
            }

            // (5) local rows whose owner copy is only in the CLOUD. The race
            // could apply a pull's rows to the wrong database outright, so
            // profile A's copy was never written locally at all — part 1 then
            // has nothing to pair with, while A's cloud row (the very source
            // of the pull) is still there. Matching the local rows against
            // each OTHER profile's cloud scope closes that gap.
            val historyRowsForLocalScan = cloudHistoryRows
            var localCopiesRemoved = 0
            if (historyRowsForLocalScan != null) {
                runCatching {
                    // One read of every profile's local rows, shared by the
                    // two directions below.
                    val perProfile =
                        orderedPids.map { pid -> pid to localHistoryWrites(context, pid) }
                    val copies = PoisonDetector.localCopiesOfOlderScopes(
                        historyRowsForLocalScan,
                        perProfile,
                        orderedPids
                    )
                    if (copies.isNotEmpty()) {
                        for ((pid, ids) in copies.groupBy({ it.first }, { it.second })) {
                            deleteLocalHistoryRows(context, pid, ids)
                        }
                        deleted += deleteCloudKeys(
                            c,
                            TABLE_HISTORY,
                            copies.map { (pid, id) -> SyncKeys.scoped(id, pid) }
                        )
                        localCopiesRemoved += copies.size
                        Log.i(
                            TAG,
                            "poison sweep: removed ${copies.size} local row(s) " +
                                "copied from an older profile's cloud scope: " +
                                copies.joinToString { "${it.first}/${it.second}" }
                        )
                    }

                    // (6) the mirror of (5): CLOUD rows under a newer scope
                    // that an older profile's LOCAL row witnesses. Here the
                    // owner's copy is the local one, so nothing in the cloud
                    // pairs with it and the row re-downloads on every pull —
                    // this is the case that survives every other rule.
                    val witnessed = PoisonDetector.cloudCopiesOfOlderLocalWitnesses(
                        historyRowsForLocalScan,
                        perProfile,
                        orderedPids
                    )
                    if (witnessed.isNotEmpty()) {
                        for ((pid, ids) in witnessed.groupBy({ it.first }, { it.second })) {
                            deleteLocalHistoryRows(context, pid, ids)
                        }
                        deleted += deleteCloudKeys(
                            c,
                            TABLE_HISTORY,
                            witnessed.map { (pid, id) -> SyncKeys.scoped(id, pid) }
                        )
                        localCopiesRemoved += witnessed.size
                        Log.i(
                            TAG,
                            "poison sweep: removed ${witnessed.size} cloud row(s) " +
                                "copied from an older profile's local history: " +
                                witnessed.joinToString { "${it.first}/${it.second}" }
                        )
                    }
                }.onFailure {
                    Log.w(TAG, "poison sweep: local-vs-cloud history check failed: ${it.message}")
                }
            }

            // (7) the eye-badge markers left behind by those history rows.
            // A marker row is what the badge renders from, and the pull
            // re-applies it on every start, so removing the copied history
            // row alone is not enough — the badge reappears seconds later
            // and the cleanup looks like it did nothing.
            val pendingParents =
                flags.getStringSet(PENDING_MARKER_PARENTS, emptySet()).orEmpty()
            var markersChecked = pendingParents.isEmpty()
            var markersRemoved = 0
            if (pendingParents.isNotEmpty()) {
                runCatching {
                    val wanted = pendingParents
                        .mapNotNull { entry ->
                            val pid = entry.substringBefore('|')
                            val parent = entry.substringAfter('|', "")
                            if (pid.isEmpty() || parent.isEmpty()) null else pid to parent
                        }
                        .groupBy({ it.first }, { it.second })
                    // Only the parents with NO local evidence left: a profile
                    // still holding its own in-progress row keeps its badge.
                    val orphaned = wanted.mapValues { (pid, parents) ->
                        val remaining = localHistoryWrites(context, pid)
                            .mapNotNull { it.parentId }
                            .toSet()
                        parents.filterNot { it in remaining }.toSet()
                    }.filterValues { it.isNotEmpty() }

                    val watchedRows = readCloudRows(c, TABLE_WATCHED)
                    if (watchedRows == null) return@runCatching
                    val keys = PoisonDetector.orphanedPartialMarkers(
                        watchedRows,
                        orphaned,
                        localPids
                    )
                    markersChecked = true
                    if (keys.isNotEmpty()) {
                        deleted += deleteCloudKeys(c, TABLE_WATCHED, keys)
                        markersRemoved = keys.size
                        // Drop the local cache rows too: they carry the very
                        // partial flag the badge reads, and without this they
                        // sit out their full TTL and keep the badge wrong.
                        for ((pid, scopedKeys) in keys.groupBy { SyncKeys.scopeOf(it) ?: "" }) {
                            if (pid.isEmpty()) continue
                            deleteLocalWatchedKeys(
                                context,
                                pid,
                                scopedKeys.map { SyncKeys.unscoped(it) }
                            )
                        }
                        Log.i(
                            TAG,
                            "poison sweep: removed ${keys.size} orphaned eye-badge " +
                                "marker row(s): " + keys.joinToString()
                        )
                    }
                }.onFailure {
                    Log.w(TAG, "poison sweep: orphaned marker check failed: ${it.message}")
                }
            }

            val editor = flags.edit()
                .putBoolean(FLAG_CLOUD, true)
                .putString(
                    REPORT_CLOUD,
                    "rows=$deleted local=$localCopiesRemoved markers=$markersRemoved"
                )
                .remove(PENDING_OVERRIDE_CLEARS)
                .remove(PENDING_HISTORY_CLEARS)
            // Keep the marker work queued while it has not actually run (the
            // cloud read failed), so a later attempt still cleans it up.
            if (markersChecked) editor.remove(PENDING_MARKER_PARENTS)
            editor.apply()
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
     * This profile's LOCAL watch-history rows as poison-detector writes. The
     * active profile may have its DB open (Room), so it goes through the DAO;
     * every other profile's DB is closed, so it is read raw.
     */
    private suspend fun localHistoryWrites(
        context: Context,
        pid: String
    ): List<PoisonDetector.LocalWrite> {
        if (pid == ProfileManager.activeProfile.value?.id) {
            return runCatching {
                WatchHistoryDatabase.getInstanceScoped(context)
                    .watchHistoryDao()
                    .getAll()
                    .map { it.toPoisonWrite() }
            }.onFailure {
                Log.w(TAG, "poison sweep: active-history read failed: ${it.message}")
            }.getOrDefault(emptyList())
        }
        return rawReadHistoryWrites(context, ProfileStorage.dbName(pid, DB_BASE))
    }

    /**
     * The columns the fingerprint needs; ids are item-based. The playback
     * fields travel too — a copy that was re-stamped on the way in can only
     * be attributed by what it PLAYS (see [PoisonDetector.samePlayback]).
     */
    private fun rawReadHistoryWrites(
        context: Context,
        dbName: String
    ): List<PoisonDetector.LocalWrite> =
        runCatching {
            val file = context.getDatabasePath(dbName)
            if (!file.exists()) return emptyList()
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            try {
                val out = mutableListOf<PoisonDetector.LocalWrite>()
                db.rawQuery(
                    "SELECT id, updatedAt, parentId, season, episode, " +
                        "positionMs, durationMs, isCompleted, streamUrl " +
                        "FROM watch_history",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        fun longOrNull(name: String): Long? =
                            c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }
                                ?.let { c.getLong(it) }
                        fun intOrNull(name: String): Int? =
                            c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }
                                ?.let { c.getInt(it) }
                        fun boolOrNull(name: String): Boolean? =
                            c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }
                                ?.let { c.getInt(it) != 0 }
                        fun strOrNull(name: String): String? =
                            c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }
                                ?.let { c.getString(it) }
                        out.add(
                            PoisonDetector.LocalWrite(
                                id = c.getString(c.getColumnIndexOrThrow("id")),
                                updatedAt = c.getLong(c.getColumnIndexOrThrow("updatedAt")),
                                parentId = strOrNull("parentId"),
                                season = intOrNull("season"),
                                episode = intOrNull("episode"),
                                positionMs = longOrNull("positionMs"),
                                durationMs = longOrNull("durationMs"),
                                isCompleted = boolOrNull("isCompleted"),
                                streamUrl = strOrNull("streamUrl")
                            )
                        )
                    }
                }
                out
            } finally {
                db.close()
            }
        }.onFailure {
            Log.w(TAG, "poison sweep: raw history read of $dbName failed: ${it.message}")
        }.getOrDefault(emptyList())

    /**
     * Removes poisoned history rows from the profile they were copied INTO.
     * The active profile goes through Room (its DB may be open); every other
     * profile's DB is closed, so a raw delete is safe there.
     */
    /**
     * Removes specific watched-marker rows from a profile's local cache.
     * Active profile through Room, every other profile's closed DB raw —
     * same split as the history helpers above.
     */
    private suspend fun deleteLocalWatchedKeys(
        context: Context,
        pid: String,
        keys: List<String>
    ) {
        if (keys.isEmpty()) return
        if (pid == ProfileManager.activeProfile.value?.id) {
            runCatching {
                val dao = WatchHistoryDatabase.getInstanceScoped(context).watchedStatusDao()
                keys.forEach { dao.deleteByKey(it) }
            }.onFailure {
                Log.w(TAG, "poison sweep: active watched-cache delete failed: ${it.message}")
            }
            return
        }
        runCatching {
            val file = context.getDatabasePath(ProfileStorage.dbName(pid, DB_BASE))
            if (!file.exists()) return
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )
            try {
                db.beginTransaction()
                try {
                    for (key in keys) {
                        db.execSQL("DELETE FROM watched_status_cache WHERE key = ?", arrayOf(key))
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                db.close()
            }
        }.onFailure {
            Log.w(TAG, "poison sweep: raw watched-cache delete in $pid failed: ${it.message}")
        }
    }

    /** One local history row as the poison detector compares it. */
    private fun WatchHistoryEntity.toPoisonWrite() =
        PoisonDetector.LocalWrite(
            id = id,
            updatedAt = updatedAt,
            parentId = parentId,
            season = season,
            episode = episode,
            positionMs = positionMs,
            durationMs = durationMs,
            isCompleted = isCompleted,
            streamUrl = streamUrl
        )

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
