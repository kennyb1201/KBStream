package com.kennyb1201.kbstream.data.memory

/**
 * How much of the decoded-bitmap cache a memory-pressure trim gives back (spec
 * 2026-11-15, scrolling round two F4; ordering revised by dig four, 2026-11-16,
 * §3c).
 *
 * The old response was `clear()`: the whole memory cache, every bitmap, at
 * RUNNING_CRITICAL. On the field TV that fired once mid-session and the rail on
 * screen re-decoded as the viewer scrolled past cards that had been painted a
 * second earlier - the trim made the jank it was supposed to relieve.
 *
 * Round two replaced it with a graded trim: half the cache, biggest bitmaps
 * (the hero-size backdrops) first. Dig four showed that order works against the
 * thing it was meant to protect - the hero-size group contains exactly the
 * lookahead-warmed backdrops the hero resolve had just paid for, so a trim
 * shortly after a warm deletes the three decodes F3 bought (`heroWarms` in the
 * diagnostics report now makes that visible).
 *
 * The trim is now LRU-ONLY: Coil's memory cache is an `LruCache` (pinned Coil
 * 3.6.3, `RealStrongMemoryCache` wrapping `coil3.util.LruCache`), and
 * `trimToSize` evicts the eldest - least recently used - entry first. That is
 * the order that matches a forward rail walk: what gets dropped is what the
 * viewer scrolled away from, while the just-warmed and just-drawn entries
 * survive. An evicted entry is also demoted into Coil's weak cache rather than
 * hard-removed, so a trim evictee can still hit without a disk decode if it has
 * not been collected.
 *
 * Only the fraction is left here, and it is pure so the arithmetic is testable
 * without a Context, an image loader or a bitmap.
 */
object ImageCacheTrim {

    /**
     * The share of what is resident that a trim gives back. Already a knob the
     * caller may override; the graded caller passes nothing.
     */
    const val TARGET_FRACTION = 0.5

    /**
     * The share a MID-SCROLL trim gives back (round four F3c, 2026-11-16).
     *
     * Smaller than [TARGET_FRACTION] on purpose: this is the only trim that runs
     * with the rail on screen, and on the boxes this is for it is delivered only
     * by API 33 and older (`RUNNING_CRITICAL`; API 34+ never sends it - see
     * MainApplication's level split). Every byte given back here is a card the
     * viewer is looking at that has to decode again, so the scroll path takes
     * the smaller bite and leaves the deeper reclaim to the backgrounding
     * clear, which runs with nothing on screen.
     */
    const val SCROLL_FRACTION = 0.35

    /**
     * The size to trim TO, given what is resident - [residentBytes] less
     * [targetFraction] of it.
     *
     * Zero when nothing is resident (nothing to trim), and clamped so a caller
     * handing this a fraction computed from a device cannot ask for a negative
     * or an over-100% target.
     */
    fun targetBytes(residentBytes: Long, targetFraction: Double = TARGET_FRACTION): Long {
        if (residentBytes <= 0L) return 0L
        val share = targetFraction.coerceIn(0.0, 1.0)
        return (residentBytes * (1.0 - share)).toLong().coerceAtLeast(0L)
    }

    /**
     * What one `onTrimMemory` delivery should give back (2026-11-26, art-decode
     * audit).
     *
     * The walk paste showed the remaining cost of a mid-walk trim: a graded trim
     * still walks Coil's LRU order, and the rail the viewer is looking at is
     * not the eldest entry - but the cards just ahead of the walk have not been
     * touched yet, so they are, and `cardDiskDecodes` rises right after the trim
     * tag. Protecting "the visible set" properly would need a pin API Coil does
     * not expose, and re-warming after the trim pays the decode the trim was
     * trying to reclaim.
     *
     * So the mid-walk trim is DEFERRED rather than approximated: while a metered
     * gesture is open (or inside its short tail), a critical delivery gives back
     * nothing. The cache is already capped at its own max size, the eviction a
     * trim would perform is exactly what the next insert's LRU bound does for
     * free, and the backgrounding clear - which runs with nothing on screen and
     * is the deep reclaim that matters - is unaffected.
     *
     * Pure so the response table is testable without a Context: the caller maps
     * the platform's level constants onto [backgrounded]/[critical].
     */
    internal fun releaseFor(
        backgrounded: Boolean,
        critical: Boolean,
        inWalkWindow: Boolean,
    ): ImageCacheRelease = when {
        backgrounded -> ImageCacheRelease.CLEAR
        !critical -> ImageCacheRelease.NONE
        inWalkWindow -> ImageCacheRelease.NONE
        else -> ImageCacheRelease.TRIM
    }
}

/** What a memory-pressure delivery should give back. */
internal enum class ImageCacheRelease {
    /** Nothing: the delivery is a hint, or the viewer is mid-walk. */
    NONE,

    /** The graded LRU trim at the resting size ([ImageCacheTrim.SCROLL_FRACTION]). */
    TRIM,

    /** The whole decoded-bitmap cache - the process has left the screen. */
    CLEAR,
}
