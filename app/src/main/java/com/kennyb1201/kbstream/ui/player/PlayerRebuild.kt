package com.kennyb1201.kbstream.ui.player

/**
 * How much of a rebuild's decoder grace period is still owed, given when the
 * previous player was released.
 *
 * A source switch hands the outgoing video decoder back and then asks for a new
 * one on the same output Surface. On the Realtek/TCL stack the outgoing
 * component keeps its buffers bound to that Surface for seconds after
 * ExoPlayer lets it go, and a request inside that window comes back
 * OMX_ErrorInsufficientResources (0x80001000) — the failure the viewer sees as
 * "this TV has run out of video decoder resources" (see
 * [SOURCE_SWITCH_SETTLE_MS]).
 *
 * The grace period used to be counted from the moment a rebuild was *asked
 * for*, and only when a player existed to release. So a second switch landing
 * inside the window rebuilt straight away — one second after the release — and
 * failed exactly like the switch the window was added for: the viewer picking
 * again, or the automatic next-source ladder firing while they do. Counting
 * from the release instead means every rebuild waits out what is left of it.
 *
 * @param settleMs the grace period a rebuild asks for; 0 or less is "no
 *   waiting" and returns 0.
 * @param releasedAtMs when the previous player was released, or 0 when none has
 *   been in this activity — the first build has nothing to wait for.
 * @param nowMs the current wall clock, so this stays pure and testable.
 * @return milliseconds still to wait, never negative.
 */
internal fun rebuildSettleRemainingMs(settleMs: Long, releasedAtMs: Long, nowMs: Long): Long {
    if (settleMs <= 0L || releasedAtMs <= 0L) return 0L
    val elapsed = nowMs - releasedAtMs
    // A clock that has gone backwards cannot say how much of the window is
    // left. Waiting the whole grace period is the safe reading: asking for a
    // decoder too early is the failure this exists to prevent.
    if (elapsed < 0L) return settleMs
    return (settleMs - elapsed).coerceAtLeast(0L)
}
