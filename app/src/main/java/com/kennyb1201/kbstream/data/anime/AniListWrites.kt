package com.kennyb1201.kbstream.data.anime

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * The player-facing AniList write: one call per completed episode, and one per
 * rating or status change (spec 2026-11-13 phase 2, F3/F5).
 *
 * THE TERMS GATE, DECIDED 2026-11-13. AniList's terms prohibit API use "within
 * competing, non-complementary services of the same nature ... including ...
 * anime and manga list or tracker services". KBStream is a player: it mirrors the
 * progress the viewer already made locally INTO AniList, which is the use
 * AniList's own auth docs invite, and it is complementary to a list service
 * rather than a competing one. The gate is therefore cleared for this leg, and
 * it is recorded here because it is the reason these writes exist at all.
 *
 * It exists so the three engines say "this episode finished" in exactly one way,
 * and every rule about WHEN an AniList write may happen lives here:
 *
 *  - no session, no call - the leg is gated on the connection, never on a
 *    setting, and never queues anything while disconnected;
 *  - no AniList id, no call - a title the ID hub cannot place is simply not
 *    written, silently (the other trackers are unaffected, nothing is shown);
 *  - the session's profile must still be the active one - the same TOCTOU guard
 *    Simkl, Trakt, MDBList and Kitsu use, because the token is the ACTIVE
 *    profile's;
 *  - once per episode per process - a completion can be reported by two paths,
 *    and AniList's progress is an authoritative count, so a duplicate is
 *    harmless but pointless;
 *  - only for a series episode: AniList's progress is episodes watched, and a
 *    film has no count to send.
 *
 * Every failure is a log line and an outcome nothing acts on. There is no toast,
 * no banner and no retry loop here: the write mirrors what the viewer did
 * locally, and the local write already succeeded. Retrying is the rating queue's
 * business, and a 429 park is the client's.
 */
internal object AniListWrites {

    private const val TAG = "AniListWrites"

    /** `mediaId:episode` pairs already written this process. */
    private val written = ConcurrentHashMap.newKeySet<String>()

    /** Test hook: forgets what has been written. */
    internal fun resetForTest() {
        written.clear()
        AniListLibrary.resetForTest()
    }

    /**
     * An episode finished: send the authoritative count.
     *
     * [episode] is the count, not an increment (AniList's `progress` is "episodes
     * watched"), and [totalEpisodes] decides whether this completion finishes the
     * title. Both null-safe: no episode number means nothing to send.
     */
    suspend fun markEpisodeWatched(
        context: Context,
        showTitle: String?,
        episode: Int?,
        totalEpisodes: Int? = null,
        sessionProfileId: String? = null
    ): AniListWriteOutcome? {
        val episodeNumber = episode?.takeIf { it > 0 } ?: return null
        if (!connected(context)) return null
        if (sessionProfileId != null && !sessionStillActive(context, sessionProfileId)) return null

        val mediaId = mediaIdFor(showTitle) ?: return null
        val key = "$mediaId:$episodeNumber"
        if (!written.add(key)) return null

        return try {
            AniListLibrary.pushProgress(
                context = context,
                mediaId = mediaId,
                progress = episodeNumber,
                totalEpisodes = totalEpisodes
            ).also { outcome -> note(mediaId, "progress $episodeNumber", outcome) }
        } catch (error: Exception) {
            // A failed write must never fail the player's completion path.
            written.remove(key)
            Log.w(TAG, "AniList progress write failed: ${error.javaClass.simpleName}")
            null
        }
    }

    /** A title-level rating, sent as the format-independent `scoreRaw`. */
    suspend fun rate(
        context: Context,
        showTitle: String?,
        score: Int
    ): AniListWriteOutcome? {
        if (!connected(context)) return null
        val mediaId = mediaIdFor(showTitle) ?: return null
        return try {
            AniListLibrary.pushRating(context, mediaId, score)
                .also { outcome -> note(mediaId, "rating $score", outcome) }
        } catch (error: Exception) {
            Log.w(TAG, "AniList rating write failed: ${error.javaClass.simpleName}")
            null
        }
    }

    /** A title status change, by KBStream's own spelling. */
    suspend fun setStatus(
        context: Context,
        showTitle: String?,
        appStatus: String
    ): AniListWriteOutcome? {
        if (!connected(context)) return null
        val mediaId = mediaIdFor(showTitle) ?: return null
        return try {
            AniListLibrary.pushStatus(context, mediaId, appStatus)
                .also { outcome -> note(mediaId, "status $appStatus", outcome) }
        } catch (error: Exception) {
            Log.w(TAG, "AniList status write failed: ${error.javaClass.simpleName}")
            null
        }
    }

    /** True when this profile holds an AniList session that may write. */
    fun connected(context: Context): Boolean =
        AniListAuth.connectionState(context) == AniListConnectionState.Connected

    /**
     * The show's AniList id from the ID hub (phase 1), which resolves once per
     * title per process. A hub failure is "no id" and nothing else.
     */
    private suspend fun mediaIdFor(showTitle: String?): Int? {
        val title = showTitle?.trim().orEmpty()
        if (title.isEmpty()) return null
        return runCatching { AnimeIdHub.resolve(title).anilistId }
            .getOrNull()
            ?.takeIf { it > 0 }
    }

    private fun sessionStillActive(context: Context, sessionProfileId: String): Boolean =
        com.kennyb1201.kbstream.data.history.PlaybackHistoryWriter
            .sessionStillActive(context, sessionProfileId)

    private fun note(mediaId: Int, what: String, outcome: AniListWriteOutcome) {
        when (outcome) {
            is AniListWriteOutcome.Success -> Log.d(TAG, "AniList $what ok for $mediaId")
            is AniListWriteOutcome.NeedsReconnect ->
                Log.w(TAG, "AniList $what stopped: reconnect needed")
            is AniListWriteOutcome.EntryGone ->
                Log.i(TAG, "AniList $what: entry gone, will re-resolve")
            is AniListWriteOutcome.RateLimited ->
                Log.i(TAG, "AniList $what parked until ${outcome.parkedUntilMs}")
            is AniListWriteOutcome.Failed ->
                Log.w(TAG, "AniList $what failed: ${outcome.reason}")
        }
    }
}
