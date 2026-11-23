package com.kennyb1201.kbstream.data.simkl

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kennyb1201.kbstream.data.settings.prefs.Prefs

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
 * LANDING GATE - ENABLED WITHOUT A LIVE PROBE, ON THE ACCOUNT OWNER'S CALL
 * ---------------------------------------------------------------------------
 * No official Simkl documentation page for `/sync/ratings` was located; the path
 * and body come from corroborating client implementations. The procedure for
 * proving them (`KBStream - Live-probe procedure: enabling the tracker rating
 * legs`) still has not been executed - it needs live credentials and a title to
 * overwrite - so the shape below is unconfirmed by a real account.
 *
 * [SIMKL_RATINGS_ENABLED] is nevertheless TRUE, by the owner's explicit decision
 * (2026-10-29): ship the leg so a real release can be exercised against the real
 * API, and pull it back out if it misbehaves. That is the whole rollback story -
 * flip this one constant back to false. Nothing else changes: `onSignalWritten`
 * stops enqueueing for this leg and `drain` returns before sending, so no
 * further call leaves the device. Ratings already sent stay sent; taking one
 * back needs an explicit un-thumb while the leg is on.
 *
 * What "misbehaves" looks like, since it is silent to the viewer by design: a
 * `taste ratings:` line in COPY DIAGNOSTICS reading `Simkl: on` with a growing
 * `queued=` count and a repeated last-failure, and `SIMKL_RATINGS` log lines
 * carrying 404 (path wrong) or a steady `NO_ANSWER` for a title both ids
 * resolve. `PAUSED` is a 429 and `AUTH` is the grant, neither a shape verdict.
 */
internal const val SIMKL_RATINGS_ENABLED = true

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
 * The quota class of the ratings park in force, or null when the leg is not
 * parked (spec 2026-11-19, Simkl quota F2).
 *
 * Read by the drain's park line: `user_limit_exceeded` means this account's
 * Simkl traffic is the lever, `app_limit_exceeded` means the V1 app's daily quota
 * is spent for every KBStream user and the only honest response is to wait.
 */
internal fun simklRatingParkLimitLabel(): String? = simklRatingGuard.parkLimitLabel()

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
 * The quota class of the playback park in force, or null when the leg is not
 * parked (spec 2026-11-20, F3).
 *
 * The ratings leg has had this word since the quota spec; the history leg did
 * not, so a paste could show a 397-minute park with no way to tell this
 * account's own quota from the app's. Same classifier, same two words.
 */
internal fun simklPlaybackParkLimitLabel(): String? =
    simklPlaybackGuard.parkLimitLabel()

/**
 * Gives both legs a park that survives process death (spec 2026-11-19, F1).
 *
 * Called once at app start. Two separate keys, because the two guards are
 * separate on purpose and their parks must never merge; a SharedPreferences read
 * is cheap, and the only write is one per 429.
 *
 * Nothing here touches the spacing floor: that stays in memory (see
 * [SimklRatingGuard]).
 */
internal fun installSimklParkStores(context: Context) {
    val prefs = Prefs.file(context.applicationContext, SIMKL_PARK_PREFS)
    simklRatingGuard.attachParkStore(prefsParkStore(prefs, KEY_PARK_RATINGS))
    simklPlaybackGuard.attachParkStore(prefsParkStore(prefs, KEY_PARK_PLAYBACK))
}

/** One precedence key as a [SimklParkStore]. */
private fun prefsParkStore(prefs: SharedPreferences, key: String): SimklParkStore =
    object : SimklParkStore {
        override fun readParkUntilMs(): Long = prefs.getLong(key, 0L)

        // apply(), not commit(): a 429 lands on a loader thread and nothing waits
        // on this value - the next read is the next process's.
        override fun writeParkUntilMs(untilMs: Long) {
            prefs.edit().putLong(key, untilMs).apply()
        }
    }

private const val SIMKL_PARK_PREFS = "kbstream_simkl_park"
private const val KEY_PARK_RATINGS = "ratings_until"
private const val KEY_PARK_PLAYBACK = "playback_until"

/**
 * Applies the playback leg's 429 rule to a playback/history response: park for
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
                // F2: name the quota that was spent. The classifier already
                // exists and the interceptor already reads the body this way -
                // the park line was the only place that threw the class away, so
                // a paste could not tell this account's daily quota from the
                // app's (spec 2026-11-19, Simkl quota F2).
                val bodyError = rateLimitError(response)
                val limit = SimklAuthV2Rules.rateLimit(response.code(), bodyError)
                simklRatingGuard.noteRetryAfter(
                    seconds = response.headers()["Retry-After"]?.toLongOrNull(),
                    limitLabel = parkLabel(limit, bodyError)
                )
                Log.w(
                    TAG,
                    "rating ${verb(removal)} parked: 429 class=${limit.name} " +
                        "retry-after=" + (response.headers()["Retry-After"] ?: "none")
                )
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

/** How many bytes of a 429 body are read to classify it (the interceptor's cap). */
private const val RATE_LIMIT_PEEK_BYTES = 1_024L

/**
 * The `error` field of a 429 body, read WITHOUT consuming the response.
 *
 * The same peek the rate-limit interceptor makes, and for the same reason: the
 * body still has to be readable by whatever handles the response next.
 */
private fun rateLimitError(response: retrofit2.Response<*>): String? = runCatching {
    // `peekBody` is okhttp's, not Retrofit's: the wrapper answers body() and
    // errorBody() (both consuming on read), and raw() is the response underneath
    // the interceptor saw.
    org.json.JSONObject(response.raw().peekBody(RATE_LIMIT_PEEK_BYTES).string())
        .optString("error")
        .takeIf { it.isNotBlank() }
}.getOrNull()

/**
 * The word the park line records for [limit].
 *
 * The server's own spellings for the two daily quotas, so the paste and Simkl's
 * docs use the same words.
 *
 * [SimklAuthV2Rules.RateLimit.BURST] is two different findings that used to read
 * as one word: the server saying `rate_limit`, and a body the classifier could
 * not read at all. The 15:52 paste parked on `Retry-After: 29460` (491 min) and
 * printed `burst`, which left no way to tell whether Simkl calls a daily reset a
 * burst or whether the body was simply unreadable - and that decides whether the
 * daily-quota path can ever fire. They are separate words now: `rate_limit` when
 * the server said it, `burst/no-body` when it did not (or could not be read).
 */
private fun parkLabel(limit: SimklAuthV2Rules.RateLimit, bodyError: String?): String =
    when (limit) {
        SimklAuthV2Rules.RateLimit.USER_DAILY -> "user_limit_exceeded"
        SimklAuthV2Rules.RateLimit.APP_DAILY -> "app_limit_exceeded"
        SimklAuthV2Rules.RateLimit.BURST ->
            bodyError?.trim()?.lowercase()?.takeIf { it == "rate_limit" } ?: "burst/no-body"
        SimklAuthV2Rules.RateLimit.NONE -> "none"
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
