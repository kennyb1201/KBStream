package com.kennyb1201.kbstream.data.memory

import java.util.concurrent.ConcurrentHashMap

/**
 * Drops the oldest entries of [cache] until it is back at or below [max].
 *
 * The sizing rule for every bounded in-memory cache in the data layer lives
 * here rather than once per cache: entries are ordered by [stamp] and only the
 * excess above [max] is removed, oldest first. A cache already at or under its
 * cap costs one integer comparison, which is what makes it cheap enough to run
 * on every lookup instead of at the dozen write sites.
 *
 * [stamp] is a lambda rather than a fixed `Pair<Long, V>` shape because the
 * callers store their timestamp differently -- `TmdbRepository`'s four maps put
 * it in `Pair.first`, while the trailer source cache keeps it in the value.
 *
 * Safe to call while other threads read the map: iterating a
 * [ConcurrentHashMap] walks a snapshot, so entries may be removed during the
 * walk. Callers only invoke this from a path that already serializes their
 * writers, so there is no competing pruner.
 *
 * Returns how many entries it dropped, so the cache that evicts can report the
 * RATE beside its size (spec 2026-11-15, rail/season cache F1): a cache sitting
 * at its cap all session reads the same whether it drops one entry or a
 * thousand, and only the second number says whether the cap is costing
 * re-fetches.
 */
internal fun <V> evictOldest(
    cache: ConcurrentHashMap<String, V>,
    stamp: (V) -> Long,
    max: Int
): Int {
    val over = cache.size - max
    if (over <= 0) return 0
    cache.entries
        .sortedBy { stamp(it.value) }
        .take(over)
        .forEach { cache.remove(it.key) }
    return over
}
