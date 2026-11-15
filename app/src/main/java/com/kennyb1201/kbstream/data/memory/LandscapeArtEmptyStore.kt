package com.kennyb1201.kbstream.data.memory

/**
 * The titles this device has already learned have NO landscape art, kept across
 * app sessions (spec 2026-11-15, Home art F5).
 *
 * The per-build memo remembers an empty answer - the repository deliberately
 * does not cache a miss, so without the memo a title TMDB has nothing for was
 * asked again for every rail it appeared in. But the memo dies with the build,
 * so the SAME title paid the same ~500 ms lookup again every session, forever,
 * and the field paste shows exactly that (`home.artEmpty n=2 total=989ms`).
 *
 * So the empty answers outlive the build - with a TTL, because art is not a
 * permanent fact: TMDB gains backdrops, and a title with none today may have
 * one next week. Past [TTL_MS] the answer is forgotten and asked once more;
 * it is never kept forever, and it is never a value that can outlive a fix to
 * the title's id.
 *
 * Only EMPTY answers are stored. Art itself is a URL pair the rail already
 * holds per build, and persisting it would be a second, staler copy of
 * something the memo and the shared detail cache already answer.
 *
 * Free of Android imports on purpose, like [evictOldest]: the rules worth
 * testing (the window, the cap, a corrupt blob) are the ones that need no
 * device.
 */
internal object LandscapeArtEmptyStore {

    /**
     * How long an empty answer is trusted.
     *
     * Two weeks: long enough that a viewer who browses the same catalogs does
     * not re-pay the lookup session after session, short enough that new art
     * reaches the card on a reasonable schedule. Chosen, not measured - and
     * cheap to be wrong about either way, since an expired entry only costs one
     * lookup and a remembered one only delays a backdrop by a fortnight.
     */
    const val TTL_MS: Long = 14L * 24L * 60L * 60L * 1000L

    /**
     * Titles kept. A big catalog pages a lot of them, and the blob is read and
     * written as one string, so it is bounded well below the memo's own cap.
     * The oldest entries are dropped first: they are the ones closest to the
     * TTL anyway.
     */
    const val MAX_ENTRIES: Int = 500

    /** Whether [key]'s empty answer is still inside its window at [now]. */
    fun isRemembered(
        entries: Map<String, Long>,
        key: String,
        now: Long,
        ttlMs: Long = TTL_MS
    ): Boolean {
        val at = entries[key] ?: return false
        val age = now - at
        // A stamp from the future is a clock that moved, not a fresh answer:
        // treat it as unknown rather than trusting it for longer than the TTL.
        return age in 0 until ttlMs
    }

    /**
     * Records [key] at [now] and keeps the map inside [max] entries.
     *
     * Mutates [entries] (the ViewModel holds a concurrent map it reads on many
     * coroutines); [prune] is the copy-based form used for the disk blob.
     */
    fun remember(
        entries: MutableMap<String, Long>,
        key: String,
        now: Long,
        max: Int = MAX_ENTRIES
    ) {
        entries[key] = now
        if (entries.size <= max) return
        entries.entries
            .sortedBy { it.value }
            .take(entries.size - max)
            .forEach { entries.remove(it.key) }
    }

    /** Drops expired entries and then the oldest ones above [max]. */
    fun prune(
        entries: Map<String, Long>,
        now: Long,
        ttlMs: Long = TTL_MS,
        max: Int = MAX_ENTRIES
    ): Map<String, Long> {
        val live = entries.filterValues { at -> (now - at) in 0 until ttlMs }
        if (live.size <= max) return live
        return live.entries
            .sortedBy { it.value }
            .drop(live.size - max)
            .associate { it.key to it.value }
    }

    /**
     * Reads the stored blob. One `timestamp<TAB>key` per line, malformed lines
     * skipped: a corrupt or half-written blob must cost a re-lookup, never a
     * crash and never a title remembered as art-less by accident.
     */
    fun parse(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Long>()
        raw.lineSequence().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0 || tab == line.lastIndex) return@forEach
            val at = line.substring(0, tab).trim().toLongOrNull() ?: return@forEach
            val key = line.substring(tab + 1)
            if (key.isNotBlank()) out[key] = at
        }
        return out
    }

    /** Writes [entries] in the shape [parse] reads. */
    fun serialize(entries: Map<String, Long>): String =
        entries.entries.joinToString("\n") { (key, at) -> "$at\t$key" }
}
