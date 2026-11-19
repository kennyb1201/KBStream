package com.kennyb1201.kbstream.data.simkl

/**
 * How long the READ legs hold off after Simkl refuses or fails (m23; the daily
 * quota, 2026-11-19).
 *
 * The write legs honored the full `Retry-After` from the start; the reads
 * clamped every header into five seconds..ten minutes. That bound was written
 * for burst failures - "long enough that a retry is a new question, short
 * enough that a transient blip does not freeze the rail" - and it is the wrong
 * bound for a daily quota, which answers with a ~16-hour header. Clamping it to
 * ten minutes is what turned ONE 429 into a polling loop through a spent quota:
 * 97 of the last 100 requests in the 2026-11-19 debug log were that loop's
 * reads, all 429.
 *
 * The 429's class decides which bound applies ([SimklAuthV2Rules.rateLimit]
 * reads it off the body's `error` field, the same read the rate-limit
 * interceptor makes):
 *  - `USER_DAILY` / `APP_DAILY` - the full header, unclamped above. A missing or
 *    unparseable header still parks for [DEFAULT_MS], and never below [MIN_MS].
 *  - `BURST` - the old clamp, but only for a header that FITS inside it. A
 *    header longer than a burst can last wins over the class
 *    ([headerOutlastsBurst]): the class is a word in a body, the header is the
 *    server's own instruction, and the 15:52 paste showed a 491-minute
 *    `Retry-After` arriving under a class that reads `burst`. Clamping that to
 *    ten minutes is exactly the polling loop this exists to stop.
 *  - anything that is not a 429 - [FAILURE_BACKOFF_MS], unchanged.
 *
 * Pure, and separate from the repository, so the arithmetic that decides how
 * long a rate-limited account stays quiet is pinned by a plain JVM test rather
 * than by reading source text.
 */
internal object SimklReadPark {

    /** No header, or one that does not parse: a minute. */
    const val DEFAULT_MS = 60_000L

    /** The floor under every 429, whatever the header says. */
    const val MIN_MS = 5_000L

    /** The ceiling on a BURST header, and on nothing else. */
    const val MAX_BURST_MS = 10L * 60L * 1000L

    /** A non-429 failure: half a minute, unchanged. */
    const val FAILURE_BACKOFF_MS = 30_000L

    /** `Retry-After` (seconds) as milliseconds, or null when it is not a number. */
    fun retryAfterMs(header: String?): Long? =
        header
            ?.trim()
            ?.toLongOrNull()
            ?.times(1_000L)

    /**
     * Whether a 429's own header asks for longer than a burst can last.
     *
     * The class in the body says what Simkl CALLS the limit; the header says how
     * long to wait. When they disagree this way - the 2026-11-19 15:52 paste
     * parked on `Retry-After: 29460` with the class reading `burst`, which is
     * also what an unreadable body renders as - the wait is the one that has to
     * be honoured, because a burst that asks for eight hours is not a burst.
     */
    fun headerOutlastsBurst(header: String?): Boolean =
        retryAfterMs(header)
            ?.let { it > MAX_BURST_MS }
            ?: false

    /**
     * The park for one failed read.
     *
     * [quota] is the 429's class as classified from its body; [SimklAuthV2Rules.RateLimit.NONE]
     * on a 429 (a caller that could not read the body at all) parks as a burst,
     * which is the classifier's own fallback.
     */
    fun durationMs(
        statusCode: Int?,
        retryAfter: String?,
        quota: SimklAuthV2Rules.RateLimit
    ): Long {
        // A plain failure backs off; it says nothing about a quota.
        if (statusCode != 429) return FAILURE_BACKOFF_MS

        val header = retryAfterMs(retryAfter)
        val limit =
            if (quota == SimklAuthV2Rules.RateLimit.NONE) {
                SimklAuthV2Rules.RateLimit.BURST
            } else {
                quota
            }

        // A daily class OR a header that outlasts a burst takes the header
        // whole. The second half is what the field reports needed: a 429 the
        // body calls `rate_limit` - or one whose body could not be read at all -
        // still carries the reset in its header, and clamping THAT is the loop.
        return if (
            SimklAuthV2Rules.isDailyQuota(limit) ||
            headerOutlastsBurst(retryAfter)
        ) {
            // A spent quota answers again after its reset and not before, so the
            // header is the whole point. The floor still applies to a
            // missing/silly value.
            header?.coerceAtLeast(MIN_MS) ?: DEFAULT_MS
        } else {
            header?.coerceIn(MIN_MS, MAX_BURST_MS) ?: DEFAULT_MS
        }
    }
}
