package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

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

        val repository = IptvRepository(applicationContext)
        var imported = 0
        epgUrls.forEach { url ->
            runCatching { repository.importGuide(url) }
                .onSuccess { imported++ }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.w(TAG, "GUIDE REFRESH FAILED source=$url", error)
                }
        }

        // One dead source must not fail the whole refresh: the sources that
        // did import are stored, and retrying would re-download every one of
        // them (a multi-minute import) to re-attempt the broken one, which the
        // next periodic run covers anyway.
        if (imported == 0) return Result.retry()

        // Same key the guide screen's own staleness check reads, and written
        // only on success: a run that imported nothing must leave the guide
        // marked stale so the next chance retries it.
        prefs.edit()
            .putLong(KEY_EPG_UPDATED_AT, System.currentTimeMillis())
            .apply()

        return Result.success()
    }

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
        // KEY_EPG_UPDATED_AT.
        const val PREFS_NAME = "iptv_prefs"
        const val KEY_EPG_URL = "epg_url"
        const val KEY_EXTRA_EPG_URLS = "extra_epg_urls"
        const val KEY_EPG_UPDATED_AT = "epg_updated_at"
    }
}
