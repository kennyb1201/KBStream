package com.kennyb1201.kbstream.data.iptv

/**
 * Whether a player close is worth a guide refresh (spec 2026-11-16, guide refresh
 * on playback end).
 *
 * The background refresh never runs while something is playing - by design, since
 * a guide rebuild under a starting player caused 750 ms GC pauses and a 6.9 s
 * rebuffer. So a television that plays most of the time it is on starves the 6h
 * tick: the field paste showed twelve deferred attempts and a guide a day old,
 * and the only path that still healed it was opening the Guide.
 *
 * This is the third path. Once a title ends and the player closes, the guide is
 * checked against the SAME staleness rule the entry policy uses, and a stale one
 * gets a one-time refresh then - instead of waiting for a tick that will likely
 * defer again.
 *
 * The decision is pure so each of its ways to be wrong is a test:
 *
 *  - a FRESH guide must not enqueue anything (an import is a multi-minute
 *    download plus a few hundred thousand inserts; S2's "no wasted import");
 *  - a rapid open/close cycle must not enqueue repeatedly - [COOLDOWN_MS] is
 *    measured against the attempt stamp both other paths already write, so the
 *    catch-up cannot become a hammer;
 *  - a clock that was put back (an attempt stamped in the future) must read as
 *    "just tried", not as "overdue since forever".
 */
internal object EpgCatchUpRules {

    /**
     * How soon after a pass STARTED another catch-up is allowed.
     *
     * The app's existing "do not hammer a provider" interval
     * ([EPG_ENTRY_RETRY_COOLDOWN_MS]), reused rather than invented: a guide that
     * did not get better is worth one more try no sooner than this, and the entry
     * path already spaces its own empty-guide retries off the same stamp.
     */
    const val COOLDOWN_MS = EPG_ENTRY_RETRY_COOLDOWN_MS

    /**
     * Whether the catch-up should enqueue now.
     *
     * [urgency] is [GuideRefreshPolicy.urgency]'s answer - the same function, the
     * same marker, the same window end the Guide screen and the diagnostics read,
     * so the three cannot disagree about what "stale" means.
     */
    fun shouldRun(
        urgency: GuideRefreshPolicy.Urgency,
        nowMs: Long,
        lastAttemptMs: Long
    ): Boolean {
        if (!GuideRefreshPolicy.needsImport(urgency)) return false
        if (lastAttemptMs <= 0L) return true
        if (lastAttemptMs > nowMs) return false
        return nowMs - lastAttemptMs >= COOLDOWN_MS
    }
}
