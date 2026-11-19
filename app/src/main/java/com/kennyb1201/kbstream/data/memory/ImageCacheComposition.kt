package com.kennyb1201.kbstream.data.memory

/**
 * What the decoded-bitmap cache is holding, not just how full it is
 * (diagnostics, 2026-11-19).
 *
 * `image cache: 47MB of 48MB` proves the cap is doing its job, and nothing
 * more: the same figure describes a cache full of the rail the viewer is
 * looking at and one full of hero-size backdrops the walk has already left. On
 * the build-386 walk that distinction was the open question - 113 hero warms at
 * ~3.5MB each through a 48MB cache - and it had to be inferred from insert
 * counts, because no line enumerated the cache.
 *
 * This is that enumeration's arithmetic, kept pure so it is testable without a
 * Context, an image loader or a bitmap (the same split [ImageCacheTrim] uses).
 * The caller reads Coil's keys and passes each resident image's own width and
 * own byte size - the cache's accounting, not a guess from pixel dimensions -
 * and the buckets split on the SAME 960 px rule the decode line counts
 * `heroDecodes` with, so the two lines cannot disagree about what a hero-size
 * image is.
 *
 * "other" is everything narrower than that: rail tiles, posters, clearlogos and
 * the artwork-tint samples. On Home the tiles are what it is mostly made of.
 */
internal object ImageCacheComposition {

    /**
     * The width at which a resident image counts as HERO-size.
     *
     * The hero's own decode is 1280 wide, a rail tile is ~420 and a poster ~248,
     * so 960 separates them with room for neither to be miscounted. Same number
     * as [com.kennyb1201.kbstream.data.reporting.ImageLoadTrace]'s decode rule,
     * on purpose: one definition of "hero-size" across the whole report.
     */
    const val HERO_MIN_WIDTH_PX = 960

    /** One resident entry, as Coil reports it: its own width, its own bytes. */
    class Sample(
        val widthPx: Int,
        val sizeBytes: Long
    )

    /**
     * The split for one read of the cache.
     *
     * [totalBytes] and [maxBytes] are the cache's own figures (`size` and
     * `maxSize`); [heroBytes] and [otherBytes] are the sum over the entries the
     * caller enumerated. They are the same thing except for Coil's weak cache
     * (see [liveBytes]), which is why the two are kept apart instead of one
     * being derived from the other.
     */
    class Reading(
        val totalBytes: Long,
        val maxBytes: Long,
        val heroBytes: Long,
        val heroEntries: Int,
        val otherBytes: Long,
        val otherEntries: Int
    ) {
        /**
         * The bytes the enumeration actually saw.
         *
         * Coil's `keys` is the strong cache PLUS every evicted value the GC has
         * not collected yet, while `size` counts the strong cache alone - so
         * this can exceed [totalBytes] by those still-alive evicted entries. It
         * equals [totalBytes] in the ordinary case, and the line says so only
         * when it does not.
         */
        val liveBytes: Long get() = heroBytes + otherBytes

        val entries: Int get() = heroEntries + otherEntries
    }

    /** Buckets [samples] by width. A zero-byte entry is not an entry. */
    fun read(
        totalBytes: Long,
        maxBytes: Long,
        samples: List<Sample>
    ): Reading {
        var heroBytes = 0L
        var heroEntries = 0
        var otherBytes = 0L
        var otherEntries = 0
        samples.forEach { sample ->
            if (sample.sizeBytes <= 0L) return@forEach
            if (sample.widthPx >= HERO_MIN_WIDTH_PX) {
                heroBytes += sample.sizeBytes
                heroEntries += 1
            } else {
                otherBytes += sample.sizeBytes
                otherEntries += 1
            }
        }
        return Reading(
            totalBytes = totalBytes,
            maxBytes = maxBytes,
            heroBytes = heroBytes,
            heroEntries = heroEntries,
            otherBytes = otherBytes,
            otherEntries = otherEntries
        )
    }

    /**
     * The paste line: the cap as it was, then the split.
     *
     * `hero=42MB/12` is bytes over entries, the same "how much, over how many"
     * the cap is stated as. A cache with nothing in it keeps the old line
     * exactly, so a cold report stays as short as it was.
     */
    fun line(reading: Reading): String = buildString {
        append("image cache: ")
        append(reading.totalBytes / MIB)
        append("MB of ")
        append(reading.maxBytes / MIB)
        append("MB")
        if (reading.entries == 0) return@buildString
        append(" hero=")
        append(reading.heroBytes / MIB)
        append("MB/")
        append(reading.heroEntries)
        append(" other=")
        append(reading.otherBytes / MIB)
        append("MB/")
        append(reading.otherEntries)
        if (reading.liveBytes != reading.totalBytes) {
            append(" live=")
            append(reading.liveBytes / MIB)
            append("MB")
        }
    }

    private const val MIB = 1_048_576L
}
