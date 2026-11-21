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
 * DAY, NOT A TTL. A verdict is a statement about a date - "can this be watched
 * at home TODAY" - so yesterday's "not yet" is not an answer about today: the
 * digital window may have opened overnight. The encoded day makes that explicit
 * and [decode] refuses every other day. The payloads behind it keep their own
 * 30-day TTL; the answers deliberately do not.
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
     */
    const val KEY_PREFIX = "homeavail:"

    /** The cache key for one title's verdict. */
    fun key(id: String): String = KEY_PREFIX + id

    /** Whether [key] is a verdict row rather than a payload. */
    fun isVerdictKey(key: String): Boolean = key.startsWith(KEY_PREFIX)

    /** The row to persist: the day the answer was derived, and the answer. */
    fun encode(epochDay: Long, available: Boolean): String =
        "$epochDay:${if (available) 1 else 0}"

    /**
     * The verdict [row] holds, when it was decided on [epochDay].
     *
     * Null for every other case - a row from an earlier day, a malformed row, a
     * missing one - because `null` is what makes the caller do the thing it
     * always did: read the payload and decide for itself. There is deliberately
     * no "stale is close enough" branch: an unreleased title shown in a row is
     * exactly the thing this filter exists to prevent.
     */
    fun decode(row: String?, epochDay: Long): Boolean? {
        val text = row?.trim().orEmpty()
        val separator = text.indexOf(':')
        if (separator <= 0 || separator != text.lastIndexOf(':')) return null
        val day = text.substring(0, separator).toLongOrNull() ?: return null
        if (day != epochDay) return null
        return when (text.substring(separator + 1)) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }
}
