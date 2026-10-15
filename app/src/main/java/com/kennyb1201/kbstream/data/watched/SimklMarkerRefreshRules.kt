package com.kennyb1201.kbstream.data.watched

import kotlinx.coroutines.CancellationException

/**
 * How one SIMKL marker set is refreshed: the fetch's answer, or the set this
 * device already had when the fetch failed.
 *
 * The reported failure — transient Simkl checkmarks disappearing from every
 * rail for a quarter of an hour — had one cause and one amplifier:
 *
 *  - the CAUSE: each of the six fetches ended in `catch { emptySet() }`, and
 *    that blank was then written straight into the live marker sets. A single
 *    timeout, a 5xx or a moment offline therefore told the app "this account
 *    has watched nothing", and every Simkl-only tick on screen went out with
 *    it — while the local/override state stayed correct, so the badges looked
 *    erased rather than stale;
 *  - the AMPLIFIER: the same block stamped `simklSetsFetchedAt = now`, so the
 *    empty sets were treated as freshly confirmed for the whole TTL. Nothing
 *    retried until it expired, and because the app's own `remoteSetsCold`
 *    check saw both sets empty, every preload in between re-ran the full
 *    all-shows fetch — a request storm that kept failing.
 *
 * So: a failed fetch keeps what it could not replace (the badge is stale, never
 * falsified), reports the failure so the caller can leave the TTL stamp alone,
 * and lets the next preload try again. A genuinely empty account is unaffected:
 * that is a SUCCESSFUL fetch returning nothing, and it is adopted as such.
 *
 * Cancellation is rethrown rather than captured, like
 * [com.kennyb1201.kbstream.data.runCatchingCancellable]: a preload canceled by
 * a profile switch must not be reported as "Simkl is down".
 */
internal object SimklMarkerRefreshRules {

    /**
     * One set's outcome: the keys to use, and the exception when the fetch
     * failed (null on success — including a successful empty answer).
     */
    internal data class Attempt(
        val keys: Set<String>,
        val failure: Exception? = null
    ) {
        val failed: Boolean get() = failure != null
    }

    /**
     * Runs [fetch], falling back to [previous] when it throws.
     *
     * [previous] is the set as it stands right now, not a cached copy: the
     * point is that the caller's state is left exactly as it was.
     */
    suspend fun keepPreviousOnFailure(
        previous: Set<String>,
        fetch: suspend () -> Set<String>
    ): Attempt =
        try {
            Attempt(keys = fetch())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Attempt(keys = previous, failure = error)
        }
}
