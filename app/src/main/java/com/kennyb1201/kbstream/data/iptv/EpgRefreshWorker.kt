package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kennyb1201.kbstream.data.reporting.Redaction
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.work.WorkPolicies
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

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
            // Bounded per source, so one wedged source cannot spend the pass's
            // whole window on its own (see
            // WorkPolicies.GUIDE_SOURCE_BUDGET_MS). A source that runs out of
            // budget is not a success - it must not write the freshness marker
            // for a guide it never stored - but it is not the run's failure
            // either: the sources after it still import.
            runCatchingCancellable { importWithinBudget(repository, url) }
                .onSuccess { outcome ->
                    // LANDED only: a `304`, a refused empty feed and a
                    // zero-program promote all complete without throwing, and
                    // treating them as an import is what stamped the guide
                    // fresh on a pass that stored nothing - the suppression that
                    // kept a blank guide blank until a manual reimport. See
                    // GuideImportOutcome.landed.
                    if (outcome != null && outcome.landed) {
                        imported++
                        // Recorded PER SOURCE, and the global marker is then
                        // re-derived as the OLDEST source stamp (see
                        // [stampImportedSource]). Per source, because a worker
                        // killed mid-pass must keep the credit for a source it
                        // really stored - each has its own 8-minute budget and
                        // two slow ones exceed the job's window. Oldest, because
                        // stamping the SHARED key per source made "source B
                        // failed" invisible: A's stamp marked the whole set
                        // fresh for the full 12 h window, so B's channels had no
                        // programs until the day's next pass.
                        stampImportedSource(prefs, url, epgUrls)
                    } else if (outcome != null) {
                        // Not an error - the source answered and had nothing to
                        // store (or honoured a 304 over an empty guide). Named
                        // so an empty run is not a silent zero: the importer's
                        // own refusal line explains the cause.
                        Log.i(
                            TAG,
                            "GUIDE REFRESH NOT LANDED source=${Redaction.url(url)} " +
                                "outcome=${outcome.javaClass.simpleName}"
                        )
                    }
                }
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
            // box that lost the network) must not hammer: the bound below is
            // what stops a permanently dead set from re-downloading itself
            // forever on every backoff.
            //
            // It must NOT stop the SCHEDULE though. This used to return
            // Result.failure() at the bound, and failure cancels periodic work
            // outright - so a bad week quietly ended the install's guide
            // refreshes, and the only thing that ever brought them back was the
            // next app launch re-enqueueing (UPDATE). A guide could then go
            // blank with nothing anywhere saying why, which is the field report
            // this whole change answers. Retry keeps WorkManager's backoff and
            // the schedule alive; the run of empty passes is recorded instead,
            // and Playlist Setup shows it (§3.6).
            val consecutive = prefs.getInt(KEY_EPG_EMPTY_RUNS, 0) + 1
            val failing = consecutive >= MAX_EMPTY_RUNS
            prefs.edit()
                .putInt(KEY_EPG_EMPTY_RUNS, consecutive)
                .putBoolean(KEY_EPG_REFRESH_FAILING, failing)
                .apply()
            if (failing) {
                Log.w(
                    TAG,
                    "GUIDE REFRESH FAILING after $consecutive empty runs; " +
                        "schedule kept, manual IMPORT GUIDE is the recovery"
                )
            }
            return Result.retry()
        }

        // The marker itself was written by the loop, per imported source (see
        // the comment there): a run that imported nothing left the guide marked
        // stale, so the next chance retries it.
        return Result.success()
    }

    /**
     * Records [url] as imported now and republishes the global freshness
     * marker as the oldest stamp among [urls].
     *
     * A source with no stamp counts as 0, i.e. never imported, which is what
     * keeps a failed or newly added source from being served "fresh" because a
     * sibling imported successfully. The per-source stamps are deliberately
     * durable (plain prefs, written as soon as a source lands) so a job that is
     * killed partway through does not lose the credit for what it stored.
     */
    private fun stampImportedSource(
        prefs: SharedPreferences,
        url: String,
        urls: List<String>
    ) {
        val stamps = GuideSourceStamps.recordSuccess(prefs, url)
        val oldest = GuideSourceStamps.oldest(stamps, urls)
        prefs.edit()
            .putLong(KEY_EPG_UPDATED_AT, oldest)
            .putString(KEY_EPG_DB_NAME, activeGuideName())
            .putInt(KEY_EPG_EMPTY_RUNS, 0)
            // Any source landing is what clears the "refresh failing" state:
            // the state is about the SET importing nothing, not about one
            // provider being broken.
            .putBoolean(KEY_EPG_REFRESH_FAILING, false)
            .apply()
    }

    /**
     * Imports one guide source, giving it the per-source time budget.
     *
     * Returns the source's [GuideImportOutcome], or null when the budget ran out
     * - the outcome used to be discarded here, which is how a pass that stored
     * nothing still counted as a success and wrote the freshness marker. The
     * caller decides what to make of it through [GuideImportOutcome.landed].
     * Only [TimeoutCancellationException] is absorbed (a timeout is not a landed
     * guide, and the caller has always treated it as "did not import"): a
     * cancellation from WorkManager stopping the job is a different exception and
     * still propagates, which is what lets the worker shut the import down
     * cleanly.
     */
    private suspend fun importWithinBudget(
        repository: IptvRepository,
        url: String
    ): GuideImportOutcome? = try {
        withTimeout(WorkPolicies.GUIDE_SOURCE_BUDGET_MS) {
            repository.importGuide(url)
        }
    } catch (timeout: TimeoutCancellationException) {
        Log.w(
            TAG,
            "GUIDE REFRESH ABANDONED source=${Redaction.url(url)} after " +
                "${WorkPolicies.GUIDE_SOURCE_BUDGET_MS / 60_000}min"
        )
        null
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

        /** Consecutive all-sources-failed runs before the failing state is shown. */
        const val KEY_EPG_EMPTY_RUNS = "epg_empty_runs"
        const val MAX_EMPTY_RUNS = 5

        /**
         * The durable "guide refresh failing" state (§3.6).
         *
         * Shared with `IptvViewModel`, which shows it in Playlist Setup beside
         * the button that retries. Spelled out here as well as there because the
         * worker runs outside any screen - the two keys must stay identical.
         */
        const val KEY_EPG_REFRESH_FAILING = "epg_refresh_failing"
    }
}
