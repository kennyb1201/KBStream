package com.kennyb1201.kbstream.data.cache

/**
 * One cache row as the budget sees it: what it costs, and how recently it was
 * written.
 *
 * [updatedAt] is the last FETCH, not the last read, so it behaves like an HTTP
 * validator rather than a true LRU stamp: a row that keeps being re-fetched
 * stays young, and a row nothing has asked for since the day it was written
 * ages out first. That is the right ordering for this cache — re-fetching is
 * what makes a row worth keeping, because that is the traffic the cache exists
 * to absorb.
 */
internal data class JsonCacheEntry(
    val key: String,
    val bytes: Long,
    val updatedAt: Long
)

/**
 * Keys that must never be dropped for space, by key prefix.
 *
 * The MDBList watched-state snapshot is one row that costs a full paginated
 * download of the user's ENTIRE history to rebuild, and it is written once per
 * six hours — the most expensive thing in this table by a wide margin. A byte
 * trim that evicted it would silently turn every following read into hundreds
 * of requests, which is a far worse outcome than the space it would save.
 * Pinned rows still count against the budget, so a snapshot large enough to
 * fill it on its own would evict everything else rather than be evicted.
 */
internal val PINNED_JSON_CACHE_PREFIXES = listOf("mdblist:snapshot:")

/**
 * Which rows [rows] must give up to fit [maxBytes] and [maxEntries].
 *
 * Newest-first: the freshest rows are the ones the app is most likely to ask
 * for again, so they are the last to go. Nothing about this is Android- or
 * TMDB-specific, which is what lets the rule be tested as a pure function
 * rather than through a database.
 *
 * Two caps rather than one because they fail differently. The byte budget is
 * the one that matters (detail rows run 65-280 KB each, so it is what actually
 * bounds the file), while the row ceiling stops a flood of small season/genre
 * rows from making [TmdbJsonCacheDao.sizeIndex] expensive instead.
 *
 * A row that cannot fit the budget on its own is evicted like any other: the
 * caps here are hard, and a cache that cannot hold one entry is a
 * misconfiguration worth seeing rather than papering over. With a 64 MB budget
 * against 280 KB rows that case is unreachable in practice.
 */
internal fun jsonCacheEvictions(
    rows: List<JsonCacheEntry>,
    maxBytes: Long,
    maxEntries: Int,
    pinnedPrefixes: List<String> = PINNED_JSON_CACHE_PREFIXES
): List<String> {
    fun pinned(key: String) = pinnedPrefixes.any { key.startsWith(it) }

    // Pinned rows are kept unconditionally, and their bytes are RESERVED
    // up front rather than added as they come up in retention order.
    // Allocating the budget to the unpinned rows first and only then admitting
    // the pins would let a newest-first pass spend the whole budget and still
    // overrun it by the pinned row, while reporting nothing to evict — a trim
    // that silently does nothing is the bug this whole file exists to avoid.
    var bytes = rows.asSequence().filter { pinned(it.key) }.sumOf { it.bytes }
    var slots = maxEntries - rows.count { pinned(it.key) }
    val budget = maxBytes.coerceAtLeast(0L)

    val evict = ArrayList<String>()
    for (row in rows.sortedByDescending { it.updatedAt }) {
        if (pinned(row.key)) continue
        if (slots > 0 && bytes + row.bytes <= budget) {
            bytes += row.bytes
            slots--
        } else {
            evict += row.key
        }
    }

    return evict
}
