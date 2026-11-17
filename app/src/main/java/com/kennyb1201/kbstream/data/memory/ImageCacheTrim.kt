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
}
