package com.kennyb1201.kbstream.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON cache is the app's only unbounded disk store, and it reached
 * gigabytes on real devices: one cached TMDB detail response measured 65-280 KB
 * against the live API, every enriched card writes one, and the only eviction
 * was by age - which never fires for a title the user keeps re-fetching.
 *
 * So these cases are about the rule that replaces that: a hard byte budget with
 * newest-first retention. Sizes are modelled on the measured payloads (150 KB
 * detail rows, 60 KB season rows) rather than round numbers, because the whole
 * point of the budget is how many real rows it holds.
 */
class TmdbJsonCacheBudgetTest {

    private val kb = 1024L

    private fun entry(key: String, kbSize: Long, updatedAt: Long) =
        JsonCacheEntry(key = key, bytes = kbSize * kb, updatedAt = updatedAt)

    @Test
    fun `a table inside its budget evicts nothing`() {
        val rows = listOf(
            entry("detail_en:series:tt1", 150, 300),
            entry("detail_en:movie:tt2", 280, 200),
            entry("season:tt3:1", 60, 100)
        )

        assertTrue(
            jsonCacheEvictions(rows, maxBytes = 64 * kb * kb, maxEntries = 4_000).isEmpty()
        )
    }

    @Test
    fun `past the byte budget the oldest rows go first`() {
        // Three 150 KB rows against a 300 KB budget: exactly one has to go, and
        // it is the one nothing has asked for longest.
        val rows = listOf(
            entry("newest", 150, 300),
            entry("middle", 150, 200),
            entry("oldest", 150, 100)
        )

        assertEquals(
            listOf("oldest"),
            jsonCacheEvictions(rows, maxBytes = 300 * kb, maxEntries = 4_000)
        )
    }

    @Test
    fun `the budget is a hard ceiling, not a per-row check`() {
        // Five 150 KB rows, a 400 KB budget: two fit (300 KB), a third would
        // cross it, so everything from there back is evicted.
        val rows = (1..5).map { i -> entry("row$i", 150, i.toLong()) }

        val evicted = jsonCacheEvictions(rows, maxBytes = 400 * kb, maxEntries = 4_000)

        assertEquals(listOf("row3", "row2", "row1"), evicted)
    }

    @Test
    fun `the row ceiling bounds a flood of small rows`() {
        // Season and genre rows are ~60 KB, so bytes alone would let thousands
        // through - which is what makes the size index the trim reads
        // expensive. The row cap is what stops that.
        val rows = (1..50).map { i -> entry("season$i", 1, i.toLong()) }

        val evicted = jsonCacheEvictions(rows, maxBytes = 64 * kb * kb, maxEntries = 10)

        assertEquals(40, evicted.size)
        assertFalse(evicted.contains("season50"))
        assertFalse(evicted.contains("season41"))
        assertTrue(evicted.contains("season40"))
    }

    @Test
    fun `a row larger than the whole budget is evicted rather than kept`() {
        // Unreachable with a 64 MB budget and 280 KB rows, but the alternative
        // (keeping it because it is the newest) would make the budget
        // unenforceable for any caller that lowered it.
        val rows = listOf(entry("huge", 5_000, 10), entry("small", 1, 5))

        assertEquals(
            listOf("huge"),
            jsonCacheEvictions(rows, maxBytes = 64 * kb, maxEntries = 10)
        )
    }

    @Test
    fun `the pinned snapshot survives a trim that it makes necessary`() {
        // Rebuilding the MDBList snapshot means re-downloading the user's
        // entire paginated history, so it is never dropped for space. It is
        // also the oldest row in the table by write time, which is exactly the
        // row age-first retention would have thrown away.
        val rows = listOf(
            entry("mdblist:snapshot:key", 600, 1),
            entry("newest", 150, 300),
            entry("middle", 150, 200),
            entry("oldest", 150, 100)
        )

        val evicted = jsonCacheEvictions(rows, maxBytes = 1_000 * kb, maxEntries = 4_000)

        // 1,000 KB budget less the 600 KB the snapshot reserves leaves 400 KB:
        // the two newest 150 KB rows fit, the third does not.
        assertEquals(listOf("oldest"), evicted)
        assertFalse(evicted.contains("mdblist:snapshot:key"))
    }

    @Test
    fun `a pinned row that fills the budget evicts everything else`() {
        // The pin means "never evict this", not "ignore it when measuring".
        val rows = listOf(
            entry("mdblist:snapshot:key", 900, 1),
            entry("detail", 150, 300)
        )

        assertEquals(
            listOf("detail"),
            jsonCacheEvictions(rows, maxBytes = 1_000 * kb, maxEntries = 4_000)
        )
    }

    @Test
    fun `a non-positive budget or row cap evicts everything unpinned`() {
        val rows = listOf(entry("a", 1, 2), entry("b", 1, 1))

        assertEquals(listOf("a", "b"), jsonCacheEvictions(rows, maxBytes = 0, maxEntries = 10))
        assertEquals(listOf("a", "b"), jsonCacheEvictions(rows, maxBytes = 64 * kb, maxEntries = 0))
    }

    @Test
    fun `rows written in the same millisecond evict a deterministic count`() {
        // `updatedAt` is the last FETCH, and a batch of enrichment writes
        // shares a timestamp, so ties are normal rather than exotic. Which of
        // the tied rows goes is then decided by the order the database handed
        // them over (the sort is stable); how MANY go is decided by the budget,
        // and that is the part that has to hold.
        val rows = (1..4).map { i -> entry("row$i", 100, 7) }

        val evicted = jsonCacheEvictions(rows, maxBytes = 200 * kb, maxEntries = 10)

        assertEquals(2, evicted.size)
        assertEquals(listOf("row3", "row4"), evicted)
    }

    @Test
    fun `the real budget holds hundreds of measured detail rows`() {
        // The budget is only meaningful if it holds more than a browsing
        // session re-asks for. 64 MB against the measured 65-280 KB rows.
        val rows = (1..600).map { i -> entry("detail$i", 150, i.toLong()) }

        val evicted = jsonCacheEvictions(
            rows,
            maxBytes = TMDB_JSON_CACHE_MAX_BYTES,
            maxEntries = TMDB_JSON_CACHE_MAX_ROWS
        )

        val kept = rows.size - evicted.size
        assertTrue("kept=$kept", kept in 400..500)
        // And it is the OLD end that went, not the new.
        assertFalse(evicted.contains("detail600"))
        assertTrue(evicted.contains("detail1"))
    }

    private companion object {
        /** Mirrors [TmdbJsonCacheMaintenance]'s budget, in bytes. */
        val TMDB_JSON_CACHE_MAX_BYTES = TmdbJsonCacheMaintenance.MAX_BYTES

        val TMDB_JSON_CACHE_MAX_ROWS = TmdbJsonCacheMaintenance.MAX_ROWS
    }
}
