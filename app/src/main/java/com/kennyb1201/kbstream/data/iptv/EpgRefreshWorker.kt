package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kennyb1201.kbstream.data.reporting.Redaction
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.work.WorkPolicies

class EpgRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // WorkManager fires outside any profile context, so resolve the
        // ACTIVE profile's scoped store at run time - otherwise the worker
        // would always read the legacy/global config even after profiles
        // exist (and could import the wrong account's EPG into a scoped DB).
        val prefs = applicationContext.getSharedPreferences(
            com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(
                applicationContext,
                PREFS_NAME
            ),
            Context.MODE_PRIVATE
        )

        // EVERY configured source, not just the primary. A second guide URL
        // (the extras textarea) is a first-class source in the lineup - it has
        // its own rows keyed by its own URL - so refreshing only the primary
        // left the extras to age in the database indefinitely: their channels
        // kept showing the last window that happened to be imported in-app.
        val epgUrls = guideUrls(prefs)
        if (epgUrls.isEmpty()) {
            return Result.success()
        }

        // Two gates before the download, both reading state that already
        // exists rather than keeping a second copy of it:
        //
        //  - Freshness. The guide lives in the database, and a re-import is a
        //    multi-minute download plus a few hundred thousand inserts, so a
        //    run landing an hour or less after the last successful one can only
        //    re-store what is already there. `epg_updated_at` is exactly that
        //    marker: the same key the guide screen's own staleness check reads
        //    and that the in-app "IMPORT EPG" action writes, so a manual import
        //    and this worker agree on what "fresh" means instead of each
        //    holding its own idea of it.
        //  - Playback. The import competes with the decoder and the UI for CPU
        //    and heap - the field log had a guide rebuild finishing underneath
        //    a starting player, with 750 ms GC pauses and a 6.9 s rebuffer. A
        //    viewer is the one thing that must not be interrupted, and the
        //    signal for it already exists: EpgWriteGate is set by both player
        //    activities (and the hero trailer), so the gate is read here rather
        //    than tracked again. It defers the whole run, where EpgWriteGate's
        //    own hold inside the importer only paces the writes.
        //
        // A deferral is Result.retry() and never Result.failure(): failure
        // cancels the periodic work outright, so a long film would cost the
        // install its guide refreshes rather than merely postponing one.
        val now = System.currentTimeMillis()
        val lastImport = prefs.getLong(KEY_EPG_UPDATED_AT, 0L)
        when (WorkPolicies.epgRefreshDecision(lastImport, now, EpgWriteGate.isPlayerActive)) {
            WorkPolicies.EpgRefreshDecision.FRESH -> {
                Log.i(TAG, "EPG refresh skipped: data fresh (age=${(now - lastImport) / 60_000}min)")
                return Result.success()
            }

            WorkPolicies.EpgRefreshDecision.DEFER -> {
                Log.i(TAG, "EPG refresh deferred: playback active")
                return Result.retry()
            }

            WorkPolicies.EpgRefreshDecision.IMPORT -> Unit
        }

        // The shared instance, so a refresh that lands while the guide screen
        // (or the player) is holding the snapshot updates the caches that are
        // actually being read instead of a private copy of them.
        val repository = IptvRepository.shared(applicationContext)
        var imported = 0
        epgUrls.forEach { url ->
            runCatchingCancellable { repository.importGuide(url) }
                .onSuccess { imported++ }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.w(
                        TAG,
                        "GUIDE REFRESH FAILED source=${Redaction.url(url)}",
                        Redaction.throwable(error)
                    )
                }
        }

        // One dead source must not fail the whole refresh: the sources that
        // did import are stored, and retrying would re-download every one of
        // them (a multi-minute import) to re-attempt the broken one, which the
        // next periodic run covers anyway.
        if (imported == 0) {
            // A source list that is entirely dead (an expired playlist host, a
            // box that lost the network) used to retry forever: every attempt
            // re-downloads the whole guide only to fail again, and WorkManager
            // backs off but never stops, so the box kept waking to burn battery
            // for a guide that was never coming back. Stop after a bounded run
            // of consecutive empty imports; the periodic schedule still fires,
            // and a source that recovers simply imports again and resets this.
            val consecutive = prefs.getInt(KEY_EPG_EMPTY_RUNS, 0) + 1
            prefs.edit().putInt(KEY_EPG_EMPTY_RUNS, consecutive).apply()
            if (consecutive >= MAX_EMPTY_RUNS) {
                Log.w(
                    TAG,
                    "GUIDE REFRESH GIVING UP after $consecutive empty runs; " +
                        "waiting for the next scheduled run"
                )
                return Result.failure()
            }
            return Result.retry()
        }

        // Same key the guide screen's own staleness check reads, and written
        // only on success: a run that imported nothing must leave the guide
        // marked stale so the next chance retries it.
        prefs.edit()
            .putLong(KEY_EPG_UPDATED_AT, System.currentTimeMillis())
            .putString(KEY_EPG_DB_NAME, activeGuideName())
            .putInt(KEY_EPG_EMPTY_RUNS, 0)
            .apply()

        return Result.success()
    }

    /**
     * The guide file this run imported into.
     *
     * Recorded beside the freshness marker, because the marker is only about a
     * file: a profile whose playlist is shared reads a guide named after the
     * playlist rather than the profile (see [GuideFiles]), and a marker that did
     * not say which file it was about would leave the in-app staleness check
     * unable to tell a freshly imported guide from an empty one under a new name
     * (see IptvViewModel.guideFileMoved).
     */
    private fun activeGuideName(): String =
        com.kennyb1201.kbstream.data.iptv.db.IptvDatabase.activeFileName(applicationContext)

    /**
     * The configured guide sources, assembled exactly as
     * `IptvViewModel.allEpgUrls()` assembles them: the primary URL first, then
     * the extras textarea (one per line, or `;`-separated), trimmed, blanks
     * dropped, duplicates collapsed.
     */
    private fun guideUrls(prefs: SharedPreferences): List<String> = buildList {
        prefs.getString(KEY_EPG_URL, "")
            .orEmpty()
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let(::add)

        addAll(
            prefs.getString(KEY_EXTRA_EPG_URLS, "")
                .orEmpty()
                .split('\n', ';')
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        )
    }.distinct()

    private companion object {
        const val TAG = "EpgRefreshWorker"

        // Keys of the profile-scoped "iptv_prefs" store; must stay in step
        // with IptvViewModel's KEY_EPG_URL / KEY_EXTRA_EPG_URLS /
        // KEY_EPG_UPDATED_AT / KEY_EPG_DB_NAME.
        const val PREFS_NAME = "iptv_prefs"
        const val KEY_EPG_URL = "epg_url"
        const val KEY_EXTRA_EPG_URLS = "extra_epg_urls"
        const val KEY_EPG_UPDATED_AT = "epg_updated_at"
        const val KEY_EPG_DB_NAME = "epg_db_name"

        /** Consecutive all-sources-failed runs before this worker stops retrying. */
        const val KEY_EPG_EMPTY_RUNS = "epg_empty_runs"
        const val MAX_EMPTY_RUNS = 5
    }
}
