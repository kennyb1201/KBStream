package com.kennyb1201.kbstream.ui.player

/**
 * The rule that keeps a far seek from resuming on too little runway.
 *
 * Reported (2026-11-13, ExoPlayer, build 0.9.267): scrubbing through most of a
 * file and releasing played "really bad, real stuttery", while nudging the same
 * file a little at a time was fine. A small seek lands inside or near the
 * already-buffered window (the player holds up to 60s ahead), so it resumes with
 * that headroom still in hand. A far seek discards the buffer and starts a cold
 * re-fetch - new range request, TCP ramp, server latency - and resumes as soon
 * as [AutoSkipRules]-unrelated `bufferForPlaybackAfterRebufferMs` (5s) is filled.
 * On a burst-gap source those 5s drain before the fill rate recovers, so the
 * player stalls again, refills 5s, stalls again: the stall cluster the buffer
 * comment already records.
 *
 * The fix is deliberately not a raised global threshold - the 5s after-rebuffer
 * value was chosen to keep an ordinary rebuffer short, and raising it would make
 * every mid-playback dip wait longer (the 11.4s stall the comment measures at
 * 6s). The wait is specific to the case that needs it: a seek that jumped
 * outside the buffered window resumes only once 10s has been buffered.
 *
 * Pure, so the decision can be tested without a player.
 */
internal object LargeSeekResumeRules {

    /**
     * How far a seek has to jump before it counts as "large".
     *
     * Half the 60s max buffer: a jump bigger than that is certainly outside the
     * buffered window. A 10s step, a small scrub nudge and a resume-point seek
     * all stay under it and keep the current path.
     */
    const val LARGE_SEEK_THRESHOLD_MS = 30_000L

    /**
     * The runway a large seek wants before playback resumes.
     *
     * Twice the 5s after-rebuffer threshold: enough to ride out a burst-gap
     * source's next gap instead of stalling a second later, without waiting for
     * the full 15s minBuffer (which a slow source may take a while to reach
     * while the viewer watches a spinner).
     */
    const val RESUME_BUFFER_MS = 10_000L

    /**
     * The longest the gate may hold before it lets playback go anyway.
     *
     * Not a stall watchdog - that job is the existing no-progress one, which is
     * inert while `playWhenReady` is false (a paused player is not a stalled
     * one). This is only the escape that keeps a dead source from leaving the
     * viewer paused forever: once it expires the ordinary rebuffer path takes
     * over, exactly as it does for any other seek.
     */
    const val HOLD_MAX_MS = 20_000L

    /** How often the hold re-reads the buffered duration while it waits. */
    const val HOLD_POLL_MS = 100L

    /**
     * Whether a seek from [fromMs] to [toMs] jumped outside the buffered window.
     *
     * Distance only, in either direction, and only when both positions are
     * known: without them there is nothing to compare, so the seek takes the
     * ordinary path.
     */
    fun isLargeSeek(fromMs: Long?, toMs: Long?): Boolean {
        if (fromMs == null || toMs == null) return false
        val distance = toMs - fromMs
        return (if (distance < 0) -distance else distance) > LARGE_SEEK_THRESHOLD_MS
    }

    /** Whether enough has been buffered for a large seek's resume. */
    fun hasRunway(bufferedMs: Long): Boolean = bufferedMs >= RESUME_BUFFER_MS

    /**
     * Whether the hold should end now: the buffer is ready, the hold has run out
     * of patience, or the player has failed (in which case resuming is the
     * ordinary path's business).
     */
    fun shouldResume(heldMs: Long, bufferedMs: Long, playerFailed: Boolean): Boolean =
        playerFailed || hasRunway(bufferedMs) || heldMs >= HOLD_MAX_MS
}
