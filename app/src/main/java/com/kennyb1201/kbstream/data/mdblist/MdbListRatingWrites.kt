package com.kennyb1201.kbstream.data.mdblist

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.library.TasteRatingMapping
import com.kennyb1201.kbstream.data.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/*
 * Outbound rating writes for [MdbListClient]: POST /sync/ratings and
 * POST /sync/ratings/remove, the MDBList third of a taste signal.
 *
 * Split out of MdbListClient.kt for the same reason the Simkl and Trakt legs live
 * outside their repositories: that file is already a very long object, and this is
 * still its behavior, reached through a same-named member. Every entry point
 * reports an OUTCOME instead of throwing (see MdbListRatingWire.kt for the
 * taxonomy), so an MDBList outage can never reach the thumb press that caused it -
 * the viewer's "Liked: <title>" feedback fired from the LOCAL write and says
 * nothing about this leg on purpose.
 *
 * What is REUSED rather than reinvented, in the order the call makes it:
 *
 *  - the key ([MdbListClient.apiKey]), read per profile, blank meaning paused;
 *  - the id grammar (`idsNode`), so `imdb` and `tmdb` are the only id forms that
 *    can go out and a title with neither is refused before any request;
 *  - the mapping ([TasteRatingMapping]): LIKE -> 8, DISLIKE -> 2, a cleared
 *    signal a removal, numbers clamped 1-10. There is no MDBList-specific table
 *    and no half-star conversion - the integer scale is the verified one;
 *  - the client's budgeted OkHttp instance, tagged `WRITE_TAG`, so a rating is
 *    charged to the same per-key daily budget as every other MDBList write and a
 *    429 parks the key for every MDBList path, scrobbles included. There is NO
 *    guard object here: the budget interceptor already IS this leg's guard, which
 *    is why it has no pause of its own to wait out (see
 *    `TasteRatingSubmission.remainingPauseMs`).
 *
 * ---------------------------------------------------------------------------
 * LANDING GATE - ENABLED WITHOUT A LIVE PROBE, ON THE ACCOUNT OWNER'S CALL
 * ---------------------------------------------------------------------------
 * The path and body are MDBList's own, checked against the OpenAPI schema its docs
 * app loads (`https://api.mdblist.com/schema/`, read 2026-10-29): both endpoints
 * take `{movies|shows: [{ids: {imdb, tmdb}, rating, rated_at}]}`, ratings 1-10 in
 * 0.5 steps, `rated_at` optional ISO 8601, auth `?apikey=`. That settles the body
 * the original investigation could only infer, and the payload here follows it
 * exactly - whole numbers, because the shared mapping produces them. If this file
 * and that schema ever disagree, the schema wins.
 *
 * What is still unproven is the ACCOUNT round trip, not the shape: the live probe
 * (a write with real credentials, then a read-back) has not run, so no real account
 * has confirmed that a rating lands and a remove takes it back.
 *
 * [MDBLIST_RATINGS_ENABLED] is nevertheless TRUE, by the owner's explicit decision
 * (2026-10-28), the same call that shipped the other two legs. Rollback is this
 * one constant back to false: `onSignalWritten` stops enqueueing for this leg and
 * `drain` returns before sending. Ratings already sent stay sent; taking one back
 * needs an explicit un-thumb while the leg is on.
 *
 * What "misbehaves" looks like, since it is silent to the viewer by design: a
 * `taste ratings:` line in COPY DIAGNOSTICS reading `MDBList: on` with a growing
 * `queued=` count and a repeated last-failure, and `MDBLIST_RATINGS` log lines
 * carrying 404 (path wrong) or a steady `NO_ANSWER` for a title both ids resolve.
 * A 2xx that the account never shows is the same verdict one step later: the body
 * shape was wrong. `PAUSED` is a 429/budget and `AUTH` is the key, neither a shape
 * verdict.
 */
internal const val MDBLIST_RATINGS_ENABLED = true

/** The two paths this leg uses, on MDBList's Trakt-mirrored sync surface. */
private const val ADD_PATH = "/sync/ratings"
private const val REMOVE_PATH = "/sync/ratings/remove"

/** The media type every other MDBList write posts with. */
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

private const val TAG = "MDBLIST_RATINGS"

/**
 * The tag a closed leg is recorded against, or null when no key is pasted.
 *
 * The key's existing non-reversible tag ([MdbListClient.keyTag]), never the raw
 * secret in a map, a log line or the diagnostics report. Null means "nothing can
 * be sent either way", which `isLegClosed` already treats as "not closed"; a
 * CHANGED key is a different tag and re-opens the leg by itself, which is this
 * leg's whole reconnect story (there is no MDBList OAuth flow to hook, and a
 * removed key is a pause rather than a refusal).
 */
internal fun mdbListKeyGrant(context: Context): String? {
    val key = MdbListClient.apiKey(context)
    return if (key.isBlank()) null else MdbListClient.keyTag(key)
}

/**
 * One title's rating, written to or removed from MDBList.
 *
 * [parentId] is the app's own id spelling (`tt...` or `tmdb:<n>`) and [tmdbId] its
 * resolved twin, both handed to the client's own `idsNode` - the id grammar stays
 * in one place. [rating] is already on the 1-10 scale and is clamped again here
 * (nothing derived from stored state may leave the scale); on a removal it is not
 * sent at all - the remove endpoint's documented shape is the ids without a
 * rating. [context] is the app context the per-profile key is read with.
 */
internal suspend fun MdbListClient.submitRatingImpl(
    context: Context,
    parentId: String,
    tmdbId: Int?,
    mediaType: String,
    rating: Int,
    removal: Boolean,
    ratedAtIso: String?
): MdbListRatingOutcome {
    val key = apiKey(context)
    if (key.isBlank()) return MdbListRatingOutcome.PAUSED

    val ids = idsNode(mdbListImdbId(parentId), tmdbId)
    if (ids == null) {
        Log.i(TAG, "rating ${verb(removal)} skipped: no id on ${parentId.ifBlank { "a title" }}")
        return MdbListRatingOutcome.NO_IDS
    }

    // A removal sends the ids only (the documented remove shape); a write sends
    // the clamped number. Either way the clamp is the second net on the scale.
    val payload = mdbListRatingsPayload(
        ids = ids,
        isMovie = TasteRatingMapping.isMovie(mediaType),
        rating = if (removal) null else TasteRatingMapping.clamp(rating),
        ratedAtIso = ratedAtIso
    )

    val url = "$BASE${if (removal) REMOVE_PATH else ADD_PATH}?apikey=$key"

    // A rating write is a state change the viewer asked for, so it carries
    // WRITE_TAG: the budget interceptor never skips it against the reads' lower
    // ceiling, and its 429 park covers this path along with every other call.
    val answer = runCatchingCancellable {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .tag(WRITE_TAG)
                .build()
            client.newCall(request).execute().use { response ->
                response.code to response.body?.string().orEmpty()
            }
        }
    }.getOrNull() ?: return MdbListRatingOutcome.NO_ANSWER

    val outcome = mdbListRatingOutcomeOf(answer.first, answer.second)
    if (outcome == MdbListRatingOutcome.NO_ANSWER) {
        Log.e(
            TAG,
            "rating ${verb(removal)} failed code=${answer.first} body=${answer.second.take(200)}"
        )
    }
    return outcome
}

/** The one word the log lines use for a write versus a take-back. */
private fun verb(removal: Boolean): String = if (removal) "remove" else "submit"
