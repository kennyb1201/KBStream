package com.kennyb1201.kbstream.data.mdblist

import com.kennyb1201.kbstream.data.format.DateFormats

/**
 * The free key's daily allowance (docs.mdblist.com -> API limits; paid tiers
 * start at 10k). The interceptor and this line are the only places the number
 * appears.
 */
internal const val MDBLIST_DAILY_LIMIT = 1000

/** Refetchable reads stop here, keeping the rest of the day for writes. */
internal const val MDBLIST_READ_BUDGET = 850

/** Writes stop here - short of the API's own limit, so a 429 is never what
 * stops us. */
internal const val MDBLIST_TOTAL_CEILING = 980

/**
 * The day's spend, in one line for COPY DIAGNOSTICS.
 *
 * The per-device spend was invisible: it lived in `SharedPreferences` and the
 * only reader was the interceptor's own guard, so "we ran out of key again"
 * could only be answered by arithmetic on somebody's memory of the day. This
 * line is what the interceptor enforces, printed.
 *
 * [count] is a request COUNT and nothing else. No key material, no key tag: the
 * diagnostics header already names the active profile, so the count is
 * implicitly that profile's, and a report is pasted into issue threads.
 *
 * [limitedUntilMs] is the interceptor's 429 park (0 when not parked); a park in
 * the future is what "the day is gone" actually looks like, so it is named with
 * the time it lifts rather than left to be guessed from a full count.
 */
internal fun mdbListBudgetLine(
    count: Int,
    limitedUntilMs: Long,
    nowMs: Long
): String = buildString {
    append("mdblist: budget=")
    append(count.coerceAtLeast(0))
    append("/")
    append(MDBLIST_DAILY_LIMIT)
    append(" (reads stop ")
    append(MDBLIST_READ_BUDGET)
    append(", writes stop ")
    append(MDBLIST_TOTAL_CEILING)
    append(")")
    if (limitedUntilMs > nowMs) {
        append(", limited until ")
        append(DateFormats.time(limitedUntilMs, DateFormats.MONTH_DAY_CLOCK_12H))
    }
}
