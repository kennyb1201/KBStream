package com.kennyb1201.kbstream.data.tmdb

/**
 * A Home-availability verdict as it is PERSISTED, not just remembered.
 *
 * The pass is one enriched-detail read per movie and the answers are pure
 * functions of (that payload, today's date), yet the only memory was a
 * `ConcurrentHashMap` that dies with the process: every Home rebuild - a resume,
 * an add-on refresh, a relaunch - re-read and re-parsed the same payloads to
 * derive the same answers. On the 0.9.422 pastes that was
 * `catalogFilter=11603ms` / `pinnedVerdicts=10944ms` against
 * `detailSrc=mem:390 disk:962 net:461`: 1,353 of those reads re-derived a verdict
 * the device had already reached.
 *
 * So an answer is written as its own tiny row beside the payloads it came from,
 * and the next build reads it instead of the payload.
 *
 * TWO CLOCKS, ONE ROW. A verdict is a statement about whether a title is
 * available at home today.
 *
 *  - "not available" is a statement about TODAY: the digital window may have
 *    opened overnight, so yesterday's "not yet" must never answer for today.
 *    It is honoured only on the day it was written.
 *  - "available" is derived from a release date in the past. That date cannot
 *    move back into the future, so the answer stays true tomorrow - and every
 *    current-year title that is already out used to be re-derived on the first
 *    launch of every day for no new information. It is honoured for
 *    [AVAILABLE_MAX_AGE_DAYS], the payload's own 30-day TTL: a corrected TMDB
 *    date still re-derives within that window, and the answer can never outlive
 *    the payload that would correct it.
 *
 * The row is `"<epochDay>:<0|1>"` - a dozen bytes, so a Home screen's worth of
 * them is nothing beside the `detail_en:` payloads they replace, and knowing
 * they exist is what keeps them out of anyone else's key space (see
 * [KEY_PREFIX] and [isVerdictKey]).
 */
internal object HomeAvailabilityVerdict {

    /**
     * Prefix for the verdict rows in the shared TMDB JSON cache.
     *
     * The cache holds `detail_en:` and `rail_en:` payloads keyed by title
     * ([TmdbRepository]'s own keys); a verdict is neither, so it gets its own
     * namespace rather than sharing a key with the payload that produced it.
     *
     * That namespace is PINNED against the cache's byte budget (see
     * `PINNED_JSON_CACHE_PREFIXES`), and it has to be: the trim keeps the newest
     * rows, a verdict is written once and never refreshed, and a payload is
     * 27-81 KB against this row's dozen bytes - so an unpinned verdict row is the
     * first thing a full cache spends, and the whole pass then re-derives on the
     * next start. Pinning does not extend either clock above: the 30-day age
     * prune still ends the row's life.
     */
    const val KEY_PREFIX = "homeavail:"

    /**
     * How long an "available" answer is honoured after the day it was derived.
     *
     * The payload behind it keeps a 30-day TTL (TmdbRepository's
     * detailCacheDiskTtlMs); an answer must not outlive the data that could
     * correct it. "Not available" never uses this - it is day-scoped.
     */
    const val AVAILABLE_MAX_AGE_DAYS = 30L

    /** The cache key for one title's verdict. */
    fun key(id: String): String = KEY_PREFIX + id

    /** Whether [key] is a verdict row rather than a payload. */
    fun isVerdictKey(key: String): Boolean = key.startsWith(KEY_PREFIX)

    /** The row to persist: the day the answer was derived, and the answer. */
    fun encode(epochDay: Long, available: Boolean): String =
        "$epochDay:${if (available) 1 else 0}"

    /**
     * The verdict [row] holds for [epochDay].
     *
     * "Not available" answers only for the day they were written: an unreleased
     * title must never be shown on a stale answer, because the digital window
     * may have opened overnight. "Available" answers for
     * [AVAILABLE_MAX_AGE_DAYS], because a release date already in the past
     * cannot move back into the future.
     *
     * Null for every other case - an expired row, a future row, a malformed
     * row, a missing one - because `null` is what makes the caller do the thing
     * it always did: read the payload and decide for itself.
     */
    fun decode(row: String?, epochDay: Long): Boolean? {
        val text = row?.trim().orEmpty()
        val separator = text.indexOf(':')
        if (separator <= 0 || separator != text.lastIndexOf(':')) return null
        val day = text.substring(0, separator).toLongOrNull() ?: return null
        if (day > epochDay) return null
        val ageDays = epochDay - day
        return when (text.substring(separator + 1)) {
            "1" -> if (ageDays <= AVAILABLE_MAX_AGE_DAYS) true else null
            "0" -> if (ageDays == 0L) false else null
            else -> null
        }
    }
}
