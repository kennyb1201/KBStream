package com.kennyb1201.kbstream.data.trakt

import android.util.Log
import com.kennyb1201.kbstream.data.runCatchingCancellable

/*
 * Outbound rating writes for [TraktRepository]: POST /sync/ratings and
 * POST /sync/ratings/remove, the tracker half of a taste signal.
 *
 * Split into its own file for the same reason Simkl's history writes are: the
 * repository file is long, and this is still repository behavior, reached
 * through a same-named member. Every entry point reports an OUTCOME instead of
 * throwing, so a tracker outage can never reach the thumb press that caused it
 * - the viewer's "Liked: <title>" feedback already fired from the LOCAL write
 * and says nothing about the tracker on purpose.
 *
 * The call shape is the existing write shape, in this order and for these
 * reasons: the token first (`validAccessToken` refreshes a due token under the
 * repository's refresh lock, so a ratings call never runs its own exchange),
 * then the rate guard (a budget denial is "no answer", not a failure), then the
 * id resolution (a title Trakt cannot be addressed with is refused before any
 * request is made), then the call, then `noteRetryAfter` on every answer so a
 * 429 parks every Trakt path, ratings included.
 *
 * ---------------------------------------------------------------------------
 * LANDING GATE - ENABLED WITHOUT A LIVE PROBE, ON THE ACCOUNT OWNER'S CALL
 * ---------------------------------------------------------------------------
 * The path and body below come from corroborating client implementations, not
 * from an official doc page. The probe that would confirm them (`KBStream -
 * Live-probe procedure: enabling the tracker rating legs`) still has not run -
 * it needs live credentials and a title to overwrite - so the shape below is
 * unconfirmed by a real account.
 *
 * [TRAKT_RATINGS_ENABLED] is nevertheless TRUE, by the owner's explicit decision
 * (2026-10-29): ship the leg so a real release can be exercised against the real
 * API, and pull it back out if it misbehaves. Rollback is this one constant back
 * to false - `onSignalWritten` stops enqueueing for this leg and `drain` returns
 * before sending. Ratings already sent stay sent; taking one back needs an
 * explicit un-thumb while the leg is on.
 *
 * What "misbehaves" looks like, since it is silent to the viewer by design: a
 * `taste ratings:` line in COPY DIAGNOSTICS reading `Trakt: on` with a growing
 * `queued=` count and a repeated last-failure, and `TRAKT_RATINGS` log lines
 * carrying 404 (path wrong) or a steady `NO_ANSWER` for a title both ids resolve.
 * `PAUSED` is a 429/budget, `AUTH` is the grant, neither a shape verdict.
 */
internal const val TRAKT_RATINGS_ENABLED = true

/** What one Trakt ratings call came back as. */
internal enum class TraktRatingOutcome {

    /** The tracker took the write - added, updated or deleted. */
    LANDED,

    /**
     * The tracker's resolver positively said it does not know the title.
     * Dropped, never retried: retrying an id the server cannot match is a
     * storm, not a retry.
     */
    NOT_FOUND,

    /** The title carries no id Trakt can be addressed with. Dropped. */
    NO_IDS,

    /**
     * The grant is gone (401/403): the entry is dropped and the leg stays
     * closed until the profile reconnects - a retired grant is not something a
     * retry can fix, and an automatic re-auth is not this spec's business.
     */
    AUTH,

    /**
     * Nothing was sent this time and nothing failed: no client id, no token, a
     * spent budget, or a 429 still parking the leg. The entry stays queued and
     * does NOT count as an attempt - a server-requested pause is not a failure.
     */
    PAUSED,

    /** No usable answer (transport failure, or any other non-2xx). An attempt. */
    NO_ANSWER
}

/**
 * One title's rating, written to or removed from Trakt.
 *
 * [parentId] is the app's own id spelling (an `tt...` or `tmdb:<n>` form) and
 * [tmdbId] its resolved twin, both handed to `idsFor` unchanged - the id grammar
 * stays in one place. [mediaType] is the store's normalized type, which decides
 * the bucket. [rating] is already on the 1-10 scale (the caller maps thumbs and
 * clamps numbers); on a removal it is presence only.
 */
internal suspend fun TraktRepository.submitRatingImpl(
    parentId: String,
    tmdbId: Int?,
    mediaType: String,
    rating: Int,
    removal: Boolean,
    ratedAtIso: String?
): TraktRatingOutcome {
    if (!isConfigured()) return TraktRatingOutcome.PAUSED
    val token = validAccessToken() ?: return TraktRatingOutcome.PAUSED

    val ids = idsFor(parentId, tmdbId)
    if (ids.imdb == null && ids.tmdb == null) return TraktRatingOutcome.NO_IDS

    if (!guard.tryAcquire()) return TraktRatingOutcome.PAUSED

    val entry = TraktRatingEntry(rating = rating, ratedAt = ratedAtIso, ids = ids)
    val isMovie = mediaType.trim().lowercase() == "movie"
    val body = TraktRatingsRequest(
        movies = if (isMovie) listOf(entry) else null,
        shows = if (isMovie) null else listOf(entry)
    )

    val response = runCatchingCancellable {
        if (removal) {
            api.removeRatings(authorization = "Bearer $token", body = body)
        } else {
            api.addRatings(authorization = "Bearer $token", body = body)
        }
    }.getOrNull() ?: return TraktRatingOutcome.NO_ANSWER

    noteRetryAfter(response)

    return when {
        response.code() == 429 -> TraktRatingOutcome.PAUSED
        response.code() == 401 || response.code() == 403 -> TraktRatingOutcome.AUTH
        !response.isSuccessful -> {
            Log.e(
                TAG,
                "rating ${verb(removal)} failed code=${response.code()} body=${errorText(response)}"
            )
            TraktRatingOutcome.NO_ANSWER
        }
        else -> {
            val parsed = response.body()
            when {
                // A 2xx with an empty body is taken at its word - the same rule
                // SimklAddToListResponse.landed uses for its 201s.
                parsed == null -> TraktRatingOutcome.LANDED
                parsed.anyNotFound && !parsed.anyLanded -> TraktRatingOutcome.NOT_FOUND
                else -> TraktRatingOutcome.LANDED
            }
        }
    }
}

/** The one word the log lines use for a write versus a take-back. */
private fun verb(removal: Boolean): String = if (removal) "remove" else "submit"

private const val TAG = "TRAKT_RATINGS"

/** Trakt's error body, which Retrofit does not parse for a non-2xx. */
private fun errorText(response: retrofit2.Response<*>): String = try {
    response.errorBody()?.string()?.take(200) ?: "none"
} catch (e: Exception) {
    "unreadable: ${e.message}"
}
