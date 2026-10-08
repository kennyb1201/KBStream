package com.kennyb1201.kbstream.ui.player

/**
 * The ramp a held LEFT/RIGHT press seeks at once the quick-press step has been
 * left behind.
 *
 * A single press is one ten-second jump and stays one: the quick press is the
 * fine-grained tool, and the engines keep it as it is. Holding is the coarse
 * one - the viewer wants to cover ground, and on this box the box itself is the
 * limit (a 4K release flushes its decoder on every seek, so the video can only
 * advance as fast as it can actually seek). The step therefore grows with the
 * hold rather than with the number of remote repeats: after [HOLD_START_MS]
 * the scrub begins at [START_STEP_MS] per tick - already more than the
 * quick-press step, so a hold never starts out slower than tapping - and
 * reaches [MAX_STEP_MS] within [RAMP_MS], which takes a two-hour film from end
 * to end in about a quarter of a minute of holding.
 *
 * Shared by both engines so the two feel the same: ExoPlayer's overlay-less
 * scrub and libmpv's hold-to-scrub both read [stepFor], and [TICK_MS] is the
 * interval both advance on. Pure arithmetic, no Android, so the ramp can be
 * exercised without a TV.
 */
internal object ScrubAcceleration {

    /**
     * How long LEFT/RIGHT must be held before the fixed quick-press step turns
     * into a scrub. Below this the press is a tap and only steps once.
     */
    const val HOLD_START_MS = 250L

    /** How often a held scrub advances while it is running. */
    const val TICK_MS = 60L

    /**
     * The step the first held tick advances by, the moment the scrub starts.
     *
     * Above the engines' ten-second quick-press step on purpose: the moment a
     * press becomes a hold it must already be covering more ground than tapping
     * would, or "hold to go faster" would start by going slower.
     */
    const val START_STEP_MS = 12_000L

    /** The step a held scrub tops out at, however long the press is held. */
    const val MAX_STEP_MS = 30_000L

    /** How long the ramp takes to climb from [START_STEP_MS] to [MAX_STEP_MS]. */
    const val RAMP_MS = 900L

    /**
     * The distance one held tick moves, [elapsedMs] into the hold.
     *
     * Linear and monotone: it reads [START_STEP_MS] at the beginning of the
     * hold, climbs to [MAX_STEP_MS] by [RAMP_MS] and stays there afterwards.
     */
    fun stepFor(elapsedMs: Long): Long {
        if (elapsedMs <= 0L) return START_STEP_MS
        if (elapsedMs >= RAMP_MS) return MAX_STEP_MS
        return START_STEP_MS + (MAX_STEP_MS - START_STEP_MS) * elapsedMs / RAMP_MS
    }
}
