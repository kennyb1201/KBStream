package com.kennyb1201.kbstream.data.notifications

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.sync.KidsMode
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.certification
import com.kennyb1201.kbstream.data.settings.AppPreferences
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
        // Runs when either kind of follow exists: the global new-episode toggle,
        // or an explicitly flagged air reminder. A viewer who flagged a show
        // but left the global toggle off still gets that one show checked.
        val flagged = AirReminderStore(appContext).flagged()
        if (!AppPreferences.getNewEpisodeNotifications(appContext) && flagged.isEmpty()) {
            Log.i(TAG, "new episode notifications off and no air reminders; skipping check")
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

        // A rotating WINDOW, not the first thirty: the followed list is built in
        // a deterministic order, so `take(MAX_SHOWS_PER_RUN)` checked the same
        // thirty shows every run and silently never looked at the rest - no
        // alert ever, on any profile following more than thirty titles. The
        // cursor persists across runs and wraps, so every followed show gets a
        // turn.
        val window = roundWindow(followed, store.startOffset())

        for (showId in window) {
            // A preload takes seconds and a profile switch can land mid-round:
            // keep checking that we are still writing to the profile we
            // started with (same guard the watched/sync preloads use).
            if (ProfileManager.activeProfile.value?.id != profileAtStart) {
                Log.i(TAG, "new episode check aborted: profile switched mid-flight")
                return
            }

            val detail = runCatchingCancellable { tmdb.fetchEnrichedMeta(showId, "series") }.getOrNull()
                ?: continue
            // M9 (audit): a kids profile is only told about shows it may watch.
            // The followed set is built from watch history, which can carry an
            // adult show watched before the ceiling was set (or reached through
            // a gap since closed), and the alert names the show AND the episode
            // in the system shade - visible to anyone holding the remote, and a
            // tap away from Detail. Vetted before the baseline is recorded too,
            // so the store never learns about titles this profile cannot see.
            val ceiling = tmdb.kidsMaxAge()
            if (ceiling != null &&
                !KidsMode.allowed(ceiling, detail.certification(isMovie = false))
            ) {
                continue
            }
            val last = detail.lastEpisodeToAir ?: continue
            val episodeKey = NewEpisodeRules.episodeKey(last.seasonNumber, last.episodeNumber)
                ?: continue
            checked++

            val previous = store.lastSeen(showId)
            if (previous == null) {
                // First time we ever see this show: record the baseline so a
                // fresh install doesn't alert for the whole back catalog.
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

        // Only advanced on a COMPLETED round: an aborted one (profile switch)
        // did not check its window, so the next run must start there again.
        store.setStartOffset(
            if (followed.size <= MAX_SHOWS_PER_RUN) 0
            else (store.startOffset() + MAX_SHOWS_PER_RUN) % followed.size
        )

        Log.i(
            TAG,
            "new episode check: followed=${followed.size} checked=$checked " +
                "notified=$notified start=${store.startOffset()}"
        )
    }

    /**
     * The [MAX_SHOWS_PER_RUN] shows this round checks, starting at [start] in
     * [followed] and wrapping at the end.
     *
     * Pure, so the rotation is pinned by a test without a device: `start` past
     * the end (a list that shrank since the cursor was written) wraps instead of
     * producing an empty round, and a list short enough to fit is returned whole.
     */
    internal fun roundWindow(followed: List<String>, start: Int): List<String> {
        if (followed.size <= MAX_SHOWS_PER_RUN) return followed
        val offset = ((start % followed.size) + followed.size) % followed.size
        return (0 until MAX_SHOWS_PER_RUN).map { i -> followed[(offset + i) % followed.size] }
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
        // Explicitly flagged air reminders first and always: they must survive a
        // history database that will not open, which is exactly the state a
        // fresh profile is in.
        ids.addAll(AirReminderStore(appContext).flagged())

        val db = runCatching { WatchHistoryDatabase.getInstanceScoped(appContext) }.getOrNull()
        if (db != null) {
            runCatchingCancellable { db.watchHistoryDao().getContinueWatchingParentsSnapshot() }
                .getOrNull()
                .orEmpty()
                .filter { it.parentId.isNotBlank() && NewEpisodeRules.isSeriesType(it.type) }
                .forEach { ids.add(it.parentId) }

            runCatchingCancellable { db.watchedStatusDao().getRefreshTargets() }
                .getOrNull()
                .orEmpty()
                .filter { it.imdbId.isNotBlank() && NewEpisodeRules.isSeriesType(it.mediaType) }
                .forEach { ids.add(it.imdbId) }
        }

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
         * every run. The rest are checked on the following run - which is what
         * [roundWindow] and the store's cursor make true.
         */
        internal const val MAX_SHOWS_PER_RUN = 30
    }
}
