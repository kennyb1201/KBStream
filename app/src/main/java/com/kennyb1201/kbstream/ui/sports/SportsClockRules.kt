package com.kennyb1201.kbstream.ui.sports

/**
 * The Sports Hub clock's cadence, as a pure rule.
 *
 * The header shows a wall clock, and a wall clock only has one correct cadence:
 * the minute boundary. Sleeping a fixed sixty seconds from whenever the header
 * happened to compose would tick at `:17` past every minute - visibly wrong
 * beside a phone - and polling per second would spend a hundred times the work
 * to look the same.
 */

/** One minute, in milliseconds. */
internal const val SPORTS_CLOCK_MINUTE_MS = 60_000L

/**
 * How long [nowMs] must wait to reach the next minute boundary.
 *
 * Always in `1..60_000`: exactly on a boundary the answer is a full minute, not
 * zero, because the caller has just formatted that instant and a zero delay
 * would format it again. A millisecond before the boundary waits one
 * millisecond.
 */
internal fun millisUntilNextMinute(nowMs: Long): Long {
    val intoMinute = nowMs % SPORTS_CLOCK_MINUTE_MS
    return if (intoMinute <= 0L) {
        SPORTS_CLOCK_MINUTE_MS
    } else {
        SPORTS_CLOCK_MINUTE_MS - intoMinute
    }
}
