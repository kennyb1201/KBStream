package com.kennyb1201.kbstream.data.memory

/**
 * Which decoded bitmaps a memory-pressure trim gives back (spec 2026-11-15,
 * scrolling round two F4).
 *
 * The old response was `clear()`: the whole memory cache, every bitmap, at
 * RUNNING_CRITICAL. On the field TV that fired once mid-session and the rail on
 * screen re-decoded as the viewer scrolled past cards that had been painted a
 * second earlier - the trim made the jank it was supposed to relieve.
 *
 * The graded trim gives back half, biggest first, and the size order is the
 * useful one here: a 1280x720 hero backdrop is ~3.7MB and a rail tile is a
 * fraction of that, so the hero-size entries are both the most to reclaim and
 * the cheapest to re-decode from Coil's disk cache (the hero re-fetches the art
 * it is already showing only when focus moves).
 *
 * Correction (dig four, 2026-11-16): Coil's `MemoryCache` IS a
 * least-recently-used cache - `RealStrongMemoryCache` wraps `coil3.util.LruCache`
 * (pinned Coil 3.6.3), whose `trimToSize` evicts eldest-first, and an evicted
 * entry is demoted into the weak cache rather than removed outright. So
 * `trimToSize` on its own already trims by age. This helper's explicit pass is
 * still how the graded order is chosen here, but it is not the only ordering
 * available: see `MemoryPressure.trimImageMemoryCache`, where `trimToSize` runs
 * last as a backstop. The known cost of this order (dig four §3d) is that the
 * hero-first group contains exactly the lookahead-warmed backdrops the hero
 * resolve just paid for.
 *
 * Pure on purpose: the ordering is the whole behavior, and it is testable
 * without a Context, an image loader or a bitmap.
 */
object ImageCacheTrim {

    /** Where "hero size" starts, matching the report's own classification. */
    const val HERO_MIN_WIDTH = 960

    /** How much of what is resident a graded trim frees, as a fraction. */
    const val TARGET_FRACTION = 0.5

    /** One cached bitmap, as much as the trim needs to know about it. */
    data class Entry(val width: Int, val height: Int, val sizeBytes: Long)

    /**
     * The indices into [entries] to evict, in the order to evict them -
     * hero-size entries first, each group biggest-first, stopping as soon as
     * [targetFraction] of [residentBytes] has been accounted for.
     *
     * A bitmap whose size the loader would not report (`sizeBytes <= 0`) still
     * counts as a candidate at the end: it is still a bitmap, and the caller's
     * `trimToSize` backstop covers the case where the bytes were not knowable.
     *
     * Nothing is evicted when the request is for nothing - an empty cache, a zero
     * target, or a negative one - so a caller can hand this a fraction it computed
     * from a device without special-casing.
     */
    fun evictIndices(
        entries: List<Entry>,
        residentBytes: Long,
        targetFraction: Double = TARGET_FRACTION
    ): List<Int> {
        if (entries.isEmpty() || targetFraction <= 0.0) return emptyList()
        val resident =
            if (residentBytes > 0) residentBytes else entries.sumOf { it.sizeBytes.coerceAtLeast(0) }
        if (resident <= 0) return emptyList()
        val target = (resident * targetFraction).toLong().coerceAtLeast(1)

        val hero = mutableListOf<Int>()
        val rest = mutableListOf<Int>()
        entries.forEachIndexed { index, entry ->
            if (entry.width >= HERO_MIN_WIDTH) hero += index else rest += index
        }
        fun bySizeDescending(indices: List<Int>): List<Int> =
            indices.sortedWith(
                compareByDescending<Int> { entries[it].sizeBytes }
                    .thenBy { it }
            )

        val out = mutableListOf<Int>()
        var freed = 0L
        (bySizeDescending(hero) + bySizeDescending(rest)).forEach { index ->
            if (freed >= target) return@forEach
            out += index
            freed += entries[index].sizeBytes.coerceAtLeast(0)
        }
        return out
    }
}
