package com.kennyb1201.kbstream.data.kitsu

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.anime.AnimeIdHub
import java.util.concurrent.ConcurrentHashMap

/**
 * The player-facing Kitsu write: one call per completed episode, and one per
 * rating (spec 2026-11-13 phase 2, F4/F5).
 *
 * It exists so the three engines say "this episode finished" in exactly one way
 * and every rule about WHEN a Kitsu write may happen lives here:
 *
 *  - no session, no call - a Kitsu leg is gated on the connection, never on a
 *    setting, and never queues anything while disconnected;
 *  - no Kitsu id, no call - a title the ID hub cannot place is simply not
 *    written, silently (the other trackers are unaffected);
 *  - the session's profile must still be the active one - the same TOCTOU guard
 *    Simkl, Trakt and MDBList use, because the token is the ACTIVE profile's;
 *  - once per episode per process - a completion can be reported by two paths
 *    (the end of playback and the advance write), and Kitsu's progress is an
 *    authoritative count, so a duplicate call is harmless but pointless;
 *  - only for series with an episode number: Kitsu's progress is episodes
 *    watched, and a movie has no count to send.
 *
 * Every failure is a log line and an outcome nothing acts on. There is no toast,
 * no banner and no retry loop: the write is a mirror of what the viewer did
 * locally, and the local write already succeeded.
 */
internal object KitsuWrites {

    private const val TAG = "KitsuWrites"

    /** `kitsuId:episode` pairs already written this process. */
    private val written = ConcurrentHashMap.newKeySet<String>()

    /** Test hook: forgets what has been written. */
    internal fun resetForTest() {
        written.clear()
    }

    /**
     * An episode finished: send the authoritative count.
     *
     * [episode] is the count, not an increment (Kitsu's `progress` is "episodes
     * watched"), and [totalEpisodes] decides whether this completion finishes the
     * title. Both null-safe: no episode number means nothing to send.
     */
    suspend fun markEpisodeWatched(
        context: Context,
        showTitle: String?,
        episode: Int?,
        totalEpisodes: Int? = null,
        sessionProfileId: String? = null
    ): KitsuWriteOutcome? {
        val episodeNumber = episode?.takeIf { it > 0 } ?: return null
        if (!connected(context)) return null
        if (sessionProfileId != null && !sessionStillActive(context, sessionProfileId)) return null

        val kitsuId = kitsuIdFor(showTitle) ?: return null
        val key = "$kitsuId:$episodeNumber"
        if (!written.add(key)) return null

        return try {
            KitsuLibrary.pushProgress(
                context = context,
                kitsuAnimeId = kitsuId,
                progress = episodeNumber,
                totalEpisodes = totalEpisodes
            ).also { outcome -> note(kitsuId, "progress S$episodeNumber", outcome) }
        } catch (error: Exception) {
            // A failed write must never fail the player's completion path.
            written.remove(key)
            Log.w(TAG, "Kitsu progress write failed: ${error.javaClass.simpleName}")
            null
        }
    }

    /** A title-level rating, on Kitsu's 0-20 scale. */
    suspend fun rate(
        context: Context,
        showTitle: String?,
        score: Int
    ): KitsuWriteOutcome? {
        if (!connected(context)) return null
        val kitsuId = kitsuIdFor(showTitle) ?: return null
        return try {
            KitsuLibrary.pushRating(context, kitsuId, score)
                .also { outcome -> note(kitsuId, "rating $score", outcome) }
        } catch (error: Exception) {
            Log.w(TAG, "Kitsu rating write failed: ${error.javaClass.simpleName}")
            null
        }
    }

    /** A title status change, by KBStream's own spelling. */
    suspend fun setStatus(
        context: Context,
        showTitle: String?,
        appStatus: String
    ): KitsuWriteOutcome? {
        if (!connected(context)) return null
        val kitsuId = kitsuIdFor(showTitle) ?: return null
        return try {
            KitsuLibrary.pushStatus(context, kitsuId, appStatus)
                .also { outcome -> note(kitsuId, "status $appStatus", outcome) }
        } catch (error: Exception) {
            Log.w(TAG, "Kitsu status write failed: ${error.javaClass.simpleName}")
            null
        }
    }

    /** True when this profile holds a Kitsu session that may write. */
    fun connected(context: Context): Boolean =
        KitsuAuth.connectionState(context) == KitsuConnectionState.Connected

    /**
     * The show's Kitsu id from the ID hub (phase 1), which resolves once per
     * title per process. A hub failure is "no id" and nothing else.
     */
    private suspend fun kitsuIdFor(showTitle: String?): String? {
        val title = showTitle?.trim().orEmpty()
        if (title.isEmpty()) return null
        return runCatching { AnimeIdHub.resolve(title).kitsuId }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun sessionStillActive(context: Context, sessionProfileId: String): Boolean =
        com.kennyb1201.kbstream.data.history.PlaybackHistoryWriter
            .sessionStillActive(context, sessionProfileId)

    private fun note(kitsuId: String, what: String, outcome: KitsuWriteOutcome) {
        when (outcome) {
            is KitsuWriteOutcome.Success -> Log.d(TAG, "Kitsu $what ok for $kitsuId")
            is KitsuWriteOutcome.NeedsReconnect ->
                Log.w(TAG, "Kitsu $what stopped: reconnect needed")
            is KitsuWriteOutcome.EntryGone ->
                Log.i(TAG, "Kitsu $what: entry gone, will re-create")
            is KitsuWriteOutcome.RateLimited ->
                Log.i(TAG, "Kitsu $what parked until ${outcome.parkedUntilMs}")
            is KitsuWriteOutcome.Failed ->
                Log.w(TAG, "Kitsu $what failed: ${outcome.reason}")
        }
    }
}
