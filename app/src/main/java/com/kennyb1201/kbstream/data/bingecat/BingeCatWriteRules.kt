package com.kennyb1201.kbstream.data.bingecat

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The tracker's rules with no network and no Android in them.
 *
 * Four decisions decide whether a write can happen and what it says; each one is
 * wrong silently if it drifts, so they live here and are pinned by tests rather
 * than by a TV:
 *
 *  - **The stored grant must carry `history:write`.** A step-1 token carries
 *    only the three read scopes, and a write attempted on it would earn a 403
 *    that reads like a failure; the absence is a state ("reconnect"), not an
 *    error (spec F3's gate 2).
 *  - **At least one locally resolvable identifier**, and both when both are
 *    known: BingeCat's `MediaIds` refuses a body with neither, and an app id
 *    that is neither `tt…` nor `tmdb:…` cannot be translated into one.
 *  - **`watched_at` is an RFC 3339 instant**, second precision: the contract
 *    declares `format: date-time`, and BingeCat orders history by it.
 *  - **A 429 parks the leg for its `Retry-After`** (a minute by default), so
 *    the next event is held back instead of earning the same bare 429 - Simkl's
 *    rule, applied to a leg that has no spacing window of its own.
 */
object BingeCatWriteRules {

    /** The scope a grant needs before any write is attempted. */
    const val WRITE_SCOPE = BingeCatConfig.WRITE_SCOPE

    private val IMDB = Regex("^tt[0-9]+$")

    private const val TMDB_PREFIX = "tmdb:"

    /**
     * Whether [scope] - the grant's own recorded scope string - allows writes.
     *
     * Parsed leniently on separators (the contract is space-separated, but a
     * server that echoes commas must not read as a missing scope) and strictly
     * on the value: only an exact `history:write` counts, never a substring.
     */
    fun hasWriteScope(scope: String?): Boolean =
        scope.orEmpty()
            .split(' ', ',', '\t', '\n')
            .map { it.trim() }
            .any { it == WRITE_SCOPE }

    /**
     * The ids to send for one title, or null when this app cannot name it.
     *
     * [parentId] is the app's own spelling (`tt1234567` or `tmdb:12345`) and
     * [tmdbId] the session's resolved TMDB id when it has one. Both are sent
     * when both are known, because the contract resolves on whichever it can -
     * sending one would let a title with a mismatched single id land on the
     * wrong medium.
     */
    fun writeIds(parentId: String, tmdbId: Int? = null): BingeCatIds? {
        val trimmed = parentId.trim()
        val imdb = trimmed.takeIf { IMDB.matches(it) }
        val tmdb = tmdbId?.takeIf { it > 0 }
            ?: trimmed.takeIf { it.startsWith(TMDB_PREFIX) }
                ?.removePrefix(TMDB_PREFIX)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
        if (imdb == null && tmdb == null) return null
        return BingeCatIds(imdb = imdb, tmdb = tmdb)
    }

    /** The completion instant, as the contract's `date-time`. */
    fun watchedAt(nowMs: Long = System.currentTimeMillis()): String =
        Instant.ofEpochMilli(nowMs).truncatedTo(ChronoUnit.SECONDS).toString()
}

/**
 * The write leg's 429 park.
 *
 * Process-wide and not a field on the repository: the repository is constructed
 * per screen, and a park that reset with each construction would not exist.
 * Deliberately its own guard rather than Simkl's: a 429 on BingeCat's bucket
 * says nothing about Simkl's, and parking one leg must not silence the other.
 */
internal class BingeCatWriteGuard(
    private val clock: () -> Long = System::currentTimeMillis
) {

    private var blockedUntilMs = 0L

    /** Honor a 429's `Retry-After` (seconds), or [DEFAULT_RETRY_AFTER_SECONDS]. */
    @Synchronized
    fun noteRetryAfter(seconds: Long?) {
        val waitMs = (seconds ?: DEFAULT_RETRY_AFTER_SECONDS).coerceAtLeast(1L) * 1_000L
        blockedUntilMs = maxOf(blockedUntilMs, clock() + waitMs)
    }

    /** Milliseconds the leg is parked for; 0 when a write may go now. */
    @Synchronized
    fun parkedForMs(): Long = (blockedUntilMs - clock()).coerceAtLeast(0L)

    companion object {
        /** What a 429 without a usable `Retry-After` means (the Trakt/Simkl default). */
        const val DEFAULT_RETRY_AFTER_SECONDS = 60L
    }
}

/** The write leg's single guard instance (see [BingeCatWriteGuard]). */
internal val bingeCatWriteGuard = BingeCatWriteGuard()

/** Milliseconds the BingeCat write leg is parked by a 429; 0 = may send now. */
internal fun bingeCatWriteParkMs(): Long = bingeCatWriteGuard.parkedForMs()

/**
 * Applies the 429 rule to one write response: park for `Retry-After` (a minute
 * when the header is absent) and answer true, so the caller returns its own
 * failure but the NEXT write is held back.
 */
internal fun noteBingeCatWriteRateLimit(code: Int, retryAfterHeader: String?): Boolean {
    if (code != 429) return false
    val seconds = retryAfterHeader?.trim()?.toLongOrNull()
    bingeCatWriteGuard.noteRetryAfter(seconds)
    return true
}
