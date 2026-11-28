package com.kennyb1201.kbstream.data.tmdb

/**
 * What one [TmdbRepository.filterByHomeAvailabilityById] pass did, so the next
 * cold-start paste can name where its milliseconds went instead of costing
 * another build.
 *
 * The pass records its total only (`home.catalogFilter=13062ms` on the 6415 TV
 * start), and that figure beside a call count cannot separate the three costs
 * that want three different fixes:
 *
 *  - **[movies] / [yearSkipped]** — how much of the row the cheap gates
 *    answered. A pass asking about twice the movies it must is a gate bug, not
 *    a queue bug, and no amount of concurrency would hide it.
 *  - **[storeAnswered]** — titles the session memo or the persisted verdict row
 *    ([HomeAvailabilityVerdict]) already answered. These cost no request and no
 *    permit, so their share is the only measure of whether the answers are
 *    surviving between launches (see `PINNED_JSON_CACHE_PREFIXES`).
 *  - **[fetched] / [netFetches]** — payload reads, and how many reached the
 *    network. The difference is a detail-cache hit inside the permit: still a
 *    queue slot, no request. One number for "452 calls" cannot tell them apart.
 *  - **[fetchTotalMs] / [fetchMaxMs]** — the permit-held leg, queue wait
 *    included. [fetchAvgMs] against the permits is the pass's floor: 200 reads
 *    at 450ms through six permits cannot finish in under 15s, whatever the
 *    cache says, which is precisely the "queue or latency" question.
 *
 * Deliberately counters, not spans: they are added on the pass's own coroutines
 * and read once, so the instrumentation cannot become the cost it measures.
 */
data class AvailabilityPassStats(
    /** Movie items the pass examined (series return before this). */
    val movies: Int = 0,
    /** Movies [ReleaseAgeGate] answered from the row's own year. */
    val yearSkipped: Int = 0,
    /** Movies answered by the memo or a persisted verdict row. */
    val storeAnswered: Int = 0,
    /** Movies whose payload was read — cache or network. */
    val fetched: Int = 0,
    /** Of [fetched], the ones that reached the network (or shared a request). */
    val netFetches: Int = 0,
    /** Summed permit-held time of the [fetched] reads, queue wait included. */
    val fetchTotalMs: Long = 0L,
    /** The slowest single read, for comparison with the paste's p95. */
    val fetchMaxMs: Long = 0L
) {

    val fetchAvgMs: Long get() = if (fetched == 0) 0L else fetchTotalMs / fetched

    /**
     * The pass's wall-time floor at [permits] concurrent reads, if the work
     * packed perfectly: the summed read time divided by the permits.
     *
     * Not a prediction — it is the number the ONE shared queue cannot go below,
     * so a 13s pass with a 4s floor is queueing and one with a 12s floor is
     * read latency. Raising permits only helps the first.
     */
    fun queueFloorMs(permits: Int): Long =
        if (permits <= 0) fetchTotalMs else fetchTotalMs / permits

    operator fun plus(other: AvailabilityPassStats) = AvailabilityPassStats(
        movies = movies + other.movies,
        yearSkipped = yearSkipped + other.yearSkipped,
        storeAnswered = storeAnswered + other.storeAnswered,
        fetched = fetched + other.fetched,
        netFetches = netFetches + other.netFetches,
        fetchTotalMs = fetchTotalMs + other.fetchTotalMs,
        fetchMaxMs = maxOf(fetchMaxMs, other.fetchMaxMs)
    )

    companion object {
        val Zero = AvailabilityPassStats()
    }
}
