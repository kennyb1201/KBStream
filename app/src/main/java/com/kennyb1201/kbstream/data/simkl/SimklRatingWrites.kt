package com.kennyb1201.kbstream.data.simkl

import android.util.Log

/*
 * Outbound rating writes for [SimklRepository]: POST /sync/ratings and
 * POST /sync/ratings/remove, the tracker half of a taste signal.
 *
 * These live outside SimklRepository.kt only to keep that file readable - they
 * are still SimklRepository's behavior, called through same-named members on the
 * repository. Every entry point logs and answers an OUTCOME instead of throwing,
 * so a tracker outage can never reach the thumb press that caused it.
 *
 * The contract mirrors SimklHistoryWrites': bail out when Simkl is unconfigured
 * or unauthenticated, resolve the ids with the repository's own
 * `parsePlaybackIds` (the id grammar lives in one place), send through the
 * repository's authenticated api, and parse the answer rather than merely
 * checking its status - Simkl's sync endpoints answer 2xx even for an item it
 * could not match.
 *
 * ---------------------------------------------------------------------------
 * LANDING GATE (spec §6.4) - THE LIVE PROBE HAS NOT BEEN RUN
 * ---------------------------------------------------------------------------
 * No official Simkl documentation page for `/sync/ratings` was located; the path
 * and body come from corroborating client implementations. Before this leg may
 * write to a real account, one probe must confirm it: a ratings read, then one
 * add and one remove on a real title, checking the response carries the
 * `added`/`not_found` buckets [SimklRatingsResponse] parses. Until then
 * [SIMKL_RATINGS_ENABLED] stays FALSE and nothing is sent. The probe did NOT run
 * in the change that added this file (no live credentials were available), which
 * is why the leg lands closed rather than assuming its own shape is right.
 */
internal const val SIMKL_RATINGS_ENABLED = false

/** What one Simkl ratings call came back as. */
internal enum class SimklRatingOutcome {

    /** The tracker took the write. */
    LANDED,

    /** The tracker positively said it does not know the title. Dropped. */
    NOT_FOUND,

    /** The title carries no id Simkl can be addressed with. Dropped. */
    NO_IDS,

    /** The grant is gone: the entry is dropped and the leg waits for a reconnect. */
    AUTH,

    /** Nothing sent, nothing failed: unconfigured, no token, spacing, or a 429. */
    PAUSED,

    /** No usable answer (transport failure, or any other non-2xx). An attempt. */
    NO_ANSWER
}

/**
 * Simkl's politeness and 429 guard, local to the ratings leg.
 *
 * Trakt has a budget object because its authenticated limit was known; no such
 * documented limit was verified for Simkl, so this is deliberately NOT a budget
 * claim - it is a floor that keeps a burst of thumbs from becoming a burst of
 * requests (one call per [minSpacingMs] at most), plus the same
 * `Retry-After` rule Trakt gets, defaulting to a minute when the header is
 * absent. It lives here rather than in [SimklRepository] so that no existing
 * Simkl path (history, watchlist, scrobbles) changes its timing.
 *
 * No Android types and a replaceable clock, so its arithmetic is pinned by a
 * plain JVM test - the same shape as TraktRateGuard.
 */
internal class SimklRatingGuard(
    private val minSpacingMs: Long = 2_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private var lastCallAtMs = 0L

    @Volatile
    private var blockedUntilMs = 0L

    /** True when a call may be made now. A refusal spends nothing. */
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        if (now < blockedUntilMs) return false
        if (lastCallAtMs != 0L && now - lastCallAtMs < minSpacingMs) return false
        lastCallAtMs = now
        return true
    }

    /** Honor a 429's `Retry-After` (seconds), or [DEFAULT_RETRY_AFTER_SECONDS]. */
    @Synchronized
    fun noteRetryAfter(seconds: Long?) {
        val waitMs = (seconds ?: DEFAULT_RETRY_AFTER_SECONDS).coerceAtLeast(1L) * 1_000L
        blockedUntilMs = maxOf(blockedUntilMs, clock() + waitMs)
    }

    /** True while the leg is parked (a 429) or inside its spacing window. */
    @Synchronized
    fun isBlocked(): Boolean = blockedForMs() > 0L

    /**
     * Just the 429 park, without the spacing window.
     *
     * The playback/history legs use this one: a scrobble or a completion is an
     * event, not a burst, so suppressing it inside the ratings leg's two-second
     * spacing would drop events the viewer actually produced - while a 429's
     * park is exactly the instruction to stop sending.
     */
    @Synchronized
    fun parkedForMs(): Long = (blockedUntilMs - clock()).coerceAtLeast(0L)

    /**
     * How long the leg is held back for: the longer of a 429's park and the
     * spacing window, in milliseconds, 0 when a call may go now.
     *
     * The caller uses this to tell a pause worth waiting out (a second or two of
     * spacing) from one that is not (a 429's Retry-After), which is the whole
     * difference between a prompt retry and a coroutine idling for an hour.
     */
    @Synchronized
    fun blockedForMs(): Long {
        val now = clock()
        val parked = (blockedUntilMs - now).coerceAtLeast(0L)
        val spacing = if (lastCallAtMs == 0L) 0L
        else (lastCallAtMs + minSpacingMs - now).coerceAtLeast(0L)
        return maxOf(parked, spacing)
    }

    companion object {
        /** What a 429 without a usable `Retry-After` means (the Trakt default). */
        const val DEFAULT_RETRY_AFTER_SECONDS = 60L
    }
}

/**
 * The leg's single guard instance.
 *
 * Process-wide and deliberately NOT a field on [SimklRepository]: the
 * repository is constructed per screen, and a spacing floor that reset with each
 * construction would not be a floor at all.
 */
private val simklRatingGuard = SimklRatingGuard()

/** How long the Simkl ratings leg is held back for; 0 when a call may go now. */
internal fun simklRatingPauseMs(): Long = simklRatingGuard.blockedForMs()

/**
 * The PLAYBACK/history leg's own guard.
 *
 * Process-wide for the same reason as the ratings one, and separate from it on
 * purpose: a 429 on /scrobble or /sync/history says nothing about the ratings
 * limit, and parking the thumbs for a playback 429 (or the reverse) would hold
 * back a leg that is not being limited.
 */
private val simklPlaybackGuard = SimklRatingGuard()

/** Milliseconds the playback/history leg is parked by a 429; 0 = may send now. */
internal fun simklPlaybackParkMs(): Long = simklPlaybackGuard.parkedForMs()

/**
 * Applies the ratings leg's 429 rule to a playback/history response: park for
 * `Retry-After` (a minute when the header is absent), log the park, and answer
 * true so the caller returns its ordinary `false` - but with the park recorded
 * and the next write suppressed.
 *
 * Before this, a 429 on a scrobble or a completion history write was logged as
 * a generic failure and the very next event re-sent immediately, producing the
 * bare repeated 429s the ratings leg was taught to avoid (spec C14).
 */
internal fun noteSimklPlaybackRateLimit(code: Int, retryAfterHeader: String?): Boolean {
    if (code != 429) return false
    val seconds = retryAfterHeader?.trim()?.toLongOrNull()
    simklPlaybackGuard.noteRetryAfter(seconds)
    Log.w(
        TAG,
        "playback write parked: 429 retry-after=" +
            (seconds?.toString() ?: SimklRatingGuard.DEFAULT_RETRY_AFTER_SECONDS.toString()) +
            "s"
    )
    return true
}

/**
 * One title's rating, written to or removed from Simkl.
 *
 * [parentId] / [tmdbId] are the app's own id spellings, resolved here only
 * through the repository's `parsePlaybackIds`. [rating] is already on the 1-10
 * scale; on a removal it is presence only (Simkl matches the entry on its ids).
 */
internal suspend fun SimklRepository.submitRatingImpl(
    parentId: String,
    tmdbId: Int?,
    mediaType: String,
    rating: Int,
    removal: Boolean,
    ratedAtIso: String?
): SimklRatingOutcome {

    if (!isConfigured() || !hasToken()) return SimklRatingOutcome.PAUSED

    val ids = parsePlaybackIds(parentId, tmdbId)
    if (ids == null) {
        Log.i(TAG, "rating ${verb(removal)} skipped: unparseable id=$parentId")
        return SimklRatingOutcome.NO_IDS
    }

    if (!simklRatingGuard.tryAcquire()) return SimklRatingOutcome.PAUSED

    val entry = SimklRatingEntry(ids = ids, rating = rating, ratedAt = ratedAtIso)
    val isMovie = mediaType.trim().lowercase() == "movie"
    val body = SimklRatingsRequest(
        movies = if (isMovie) listOf(entry) else emptyList(),
        shows = if (isMovie) emptyList() else listOf(entry)
    )

    return try {
        val response = if (removal) {
            api.removeRatings(authorization = trackedAuthHeader(), body = body)
        } else {
            api.addRatings(authorization = trackedAuthHeader(), body = body)
        }

        when {
            response.code() == 429 -> {
                simklRatingGuard.noteRetryAfter(response.headers()["Retry-After"]?.toLongOrNull())
                SimklRatingOutcome.PAUSED
            }
            response.code() == 401 || response.code() == 403 -> SimklRatingOutcome.AUTH
            !response.isSuccessful -> {
                Log.e(
                    TAG,
                    "rating ${verb(removal)} failed code=${response.code()} body=" +
                        errorText { response.errorBody()?.string() }
                )
                SimklRatingOutcome.NO_ANSWER
            }
            else -> {
                val parsed = response.body()
                when {
                    // A 2xx with an unreadable body is taken at its word, the
                    // same rule SimklAddToListResponse.landed applies.
                    parsed == null -> SimklRatingOutcome.LANDED
                    parsed.landed -> SimklRatingOutcome.LANDED
                    else -> SimklRatingOutcome.NOT_FOUND
                }
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Cancellation is not a failure: rethrow so the scope that was canceled
        // stops here instead of recording an attempt (see SimklHistoryWrites).
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "rating ${verb(removal)} error: ${e.message}", e)
        SimklRatingOutcome.NO_ANSWER
    }
}

/** The one word the log lines use for a write versus a take-back. */
private fun verb(removal: Boolean): String = if (removal) "remove" else "submit"

private const val TAG = "SIMKL_RATINGS"

/** A short, safe rendering of an error body for the log line. */
private fun errorText(read: () -> String?): String = try {
    read()?.take(200) ?: "none"
} catch (e: Exception) {
    "unreadable: ${e.message}"
}
