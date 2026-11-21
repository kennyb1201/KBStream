package com.kennyb1201.kbstream.ui.home

/**
 * The numbers and arithmetic of "Hold to glide" (see `rememberHoldToGlide` for the
 * behavior itself).
 *
 * Kept free of Compose types on purpose: the velocity ramp, the per-frame cap and
 * the landing choice are the three things that can be wrong without a TV in front
 * of you, so they are pinned by a plain JUnit test
 * (HoldToGlideContractTest) instead of only by a field paste.
 */

/** Which way a glide runs: along one rail row, or down the rails column. */
internal enum class GlideAxis {
    /** Left / Right inside one rail row. */
    Horizontal,

    /** Up / Down across the rails column. */
    Vertical
}

/**
 * The glide's full speed, in dp per second.
 *
 * The upstream numbers, kept: horizontal 1600; vertical 2000 rather than the
 * original 3200, because a row is taller than a card and 2000 dp/s is the rate at
 * which the rows stay legible as they pass.
 */
internal const val GlideHorizontalDpPerSec = 1600f
internal const val GlideVerticalDpPerSec = 2000f

/** How long the speed ramps up from a standstill to full, in ms. */
internal const val GlideRampMs = 130L

/** Ceiling on one frame's dt, in seconds: a paused frame is not paid back. */
internal const val GlideFrameCapSeconds = 0.048f

/**
 * How long a direction has to stay DOWN before it becomes a glide.
 *
 * Long enough that an ordinary step (a tap, and the deliberate card-by-card walk
 * a viewer still does) is over well before it, so a step never glides; short
 * enough that a hold feels like it took over immediately. The FIRST press has
 * already moved focus one step by the time this fires, which is exactly the
 * upstream split: press once, step once; keep holding, glide.
 */
internal const val GlideHoldStartMs = 320L

/** No repeat for this long ends a glide whose KeyUp was lost. */
internal const val GlideLostReleaseMs = 160L

/**
 * Absolute ceiling on one glide, so a lost KeyUp can never scroll forever.
 *
 * Only reachable on the hardware the app already documents (KBCard's long
 * press): a remote that never sends repeat KeyDown and then loses the KeyUp as
 * well. Eight seconds is far past any deliberate hold and still finite.
 */
internal const val GlideMaxMs = 8_000L

/** The axis' full speed in dp/s. */
internal fun glideFullSpeed(axis: GlideAxis): Float = when (axis) {
    GlideAxis.Horizontal -> GlideHorizontalDpPerSec
    GlideAxis.Vertical -> GlideVerticalDpPerSec
}

/**
 * The glide's speed [heldMs] into the hold: a linear ramp over [GlideRampMs] to
 * [glideFullSpeed], then constant. A hold that never ramps lurches on its first
 * frame; the ramp is what makes it read as a glide rather than a jump.
 */
internal fun glideDpPerSecond(axis: GlideAxis, heldMs: Long): Float {
    val full = glideFullSpeed(axis)
    if (heldMs <= 0L) return 0f
    if (heldMs >= GlideRampMs) return full
    return full * (heldMs.toFloat() / GlideRampMs.toFloat())
}

/**
 * The distance one frame travels: speed at [heldMs] over [dtSeconds], with the dt
 * capped at [GlideFrameCapSeconds] first, so a frame that arrived late (a GC, a
 * decode) does not teleport the list.
 */
internal fun glideDpForFrame(axis: GlideAxis, heldMs: Long, dtSeconds: Float): Float {
    if (dtSeconds <= 0f) return 0f
    val dt = dtSeconds.coerceAtMost(GlideFrameCapSeconds)
    return glideDpPerSecond(axis, heldMs) * dt
}

/**
 * The item a glide lands on: the first FULLY visible one, falling back to the
 * first item that is at least partly visible, so a release always has somewhere
 * to land even when no whole item fits in the viewport.
 *
 * Offsets are item starts in the list's own coordinate space, sizes are item
 * sizes, and the viewport runs [viewportStart]..[viewportEnd]. Landing on the
 * first fully visible item is what makes the release snap cleanly to the start
 * padding instead of leaving a half tile at the edge.
 */
internal fun glideLandingIndex(
    offsets: List<Int>,
    sizes: List<Int>,
    viewportStart: Int,
    viewportEnd: Int
): Int? {
    if (offsets.isEmpty() || offsets.size != sizes.size) return null
    for (index in offsets.indices) {
        val start = offsets[index]
        val end = start + sizes[index]
        if (start >= viewportStart && end <= viewportEnd) return index
    }
    for (index in offsets.indices) {
        val start = offsets[index]
        val end = start + sizes[index]
        if (end > viewportStart && start < viewportEnd) return index
    }
    return null
}
