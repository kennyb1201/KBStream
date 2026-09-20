package com.kennyb1201.kbstream.data.notifications

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import java.time.LocalDate
import java.time.ZoneId

/**
 * Decides which followed shows have a newly aired episode, and alerts once
 * per episode.
 *
 * The source of "new episode" is TMDB's `last_episode_to_air` — the newest
 * episode that has actually aired — for the shows this profile watches. The
 * per-profile snapshot in [NewEpisodeStore] is what makes this fire exactly
 * once: without it every run would re-notify the same episode forever.
 *
 * Runs for the ACTIVE profile only. Checking every profile would need their
 * per-profile databases opened in parallel and would post alerts that
 * deep-link into a show under the wrong profile, so an inactive profile is
 * simply picked up the next time it is the active one.
 */
internal class NewEpisodeChecker(private val context: Context) {

    suspend fun run() {
        val appContext = context.applicationContext
        if (!AppPreferences.getNewEpisodeNotifications(appContext)) {
            Log.i(TAG, "new episode notifications off; skipping check")
            return
        }
        // No point spending one TMDB request per show when the OS will drop
        // every alert (notifications revoked in system settings).
        if (!NotificationCenter.canPost(appContext)) {
            Log.i(TAG, "notifications not permitted; skipping check")
            return
        }

        val profileAtStart = ProfileManager.activeProfile.value?.id
        val store = NewEpisodeStore(appContext)
        val followed = followedShowIds(appContext)

        if (followed.isEmpty()) {
            store.retainOnly(emptySet())
            Log.i(TAG, "no followed shows; nothing to check")
            return
        }
        // Forget shows that were dropped, so the snapshot can't grow forever
        // and a re-added show doesn't get compared against months-old state.
        store.retainOnly(followed.toSet())

        val tmdb = TmdbRepository.getInstance(appContext)
        val now = System.currentTimeMillis()
        var checked = 0
        var notified = 0

        for (showId in followed.take(MAX_SHOWS_PER_RUN)) {
            // A preload takes seconds and a profile switch can land mid-round:
            // keep checking that we are still writing to the profile we
            // started with (same guard the watched/sync preloads use).
            if (ProfileManager.activeProfile.value?.id != profileAtStart) {
                Log.i(TAG, "new episode check aborted: profile switched mid-flight")
                return
            }

            val detail = runCatching { tmdb.fetchEnrichedMeta(showId, "series") }.getOrNull()
                ?: continue
            val last = detail.lastEpisodeToAir ?: continue
            val episodeKey = NewEpisodeRules.episodeKey(last.seasonNumber, last.episodeNumber)
                ?: continue
            checked++

            val previous = store.lastSeen(showId)
            if (previous == null) {
                // First time we ever see this show: record the baseline so a
                // fresh install doesn't alert for the whole back catalogue.
                store.record(showId, episodeKey)
                continue
            }

            val airDateMs = last.airDate?.let(::parseAirDateMs)
            if (!NewEpisodeRules.shouldNotify(previous, episodeKey, airDateMs, now)) continue

            val title = detail.name ?: detail.title ?: showId
            if (NotificationCenter.newEpisode(appContext, showId, title, episodeKey, last.name)) {
                // Only recorded on a successful post: if the OS swallowed the
                // alert, the next run should try again rather than assume the
                // user was told.
                store.record(showId, episodeKey)
                notified++
            }
        }

        Log.i(TAG, "new episode check: followed=${followed.size} checked=$checked notified=$notified")
    }

    /**
     * Shows this profile follows, newest-activity first. Two sources, because
     * either alone leaves a hole: continue-watching rows only cover shows with
     * an episode in progress (a show you finished drops out), while the
     * watched-status cache only knows titles that were already resolved
     * against a tracker.
     */
    private suspend fun followedShowIds(appContext: Context): List<String> {
        val ids = LinkedHashSet<String>()
        val db = runCatching { WatchHistoryDatabase.getInstanceScoped(appContext) }.getOrNull()
            ?: return emptyList()

        runCatching { db.watchHistoryDao().getContinueWatchingParentsSnapshot() }
            .getOrNull()
            .orEmpty()
            .filter { it.parentId.isNotBlank() && NewEpisodeRules.isSeriesType(it.type) }
            .forEach { ids.add(it.parentId) }

        runCatching { db.watchedStatusDao().getRefreshTargets() }
            .getOrNull()
            .orEmpty()
            .filter { it.imdbId.isNotBlank() && NewEpisodeRules.isSeriesType(it.mediaType) }
            .forEach { ids.add(it.imdbId) }

        return ids.toList()
    }

    /** TMDB air dates are plain `yyyy-MM-dd`; unknown/unparsable dates stay null. */
    private fun parseAirDateMs(raw: String): Long? =
        runCatching {
            LocalDate.parse(raw.trim())
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()

    private companion object {
        const val TAG = "NEW_EPISODES"

        /**
         * One TMDB request per show, so the round is capped: a profile with a
         * hundred tracked shows would otherwise spend minutes of radio time
         * every run. The rest are checked on the following run.
         */
        const val MAX_SHOWS_PER_RUN = 30
    }
}
