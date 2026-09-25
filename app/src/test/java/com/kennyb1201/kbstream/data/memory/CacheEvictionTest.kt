package com.kennyb1201.kbstream.data.memory

import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sizing rule every bounded in-memory cache in the data layer shares.
 *
 * It is worth pinning because both ways it can fail are silent. Evicting by
 * insertion order instead of by stamp drops the entry the user most recently
 * opened and keeps the stalest ones -- and a [ConcurrentHashMap]'s iteration
 * order makes that look plausible while it is happening. Off-by-one on
 * `size - max` either leaves the cache unbounded (exactly the leak this exists
 * to stop, so nothing would fail) or discards an entry on every call, which
 * only shows up as repeated network work on a device.
 */
class CacheEvictionTest {

    @Test
    fun `a cache at or under its cap is left untouched`() {
        val cache = ConcurrentHashMap<String, Long>()
        cache["a"] = 1L
        cache["b"] = 2L
        cache["c"] = 3L

        // Exactly at the cap must be a no-op: `size - max` is 0, not 1.
        evictOldest(cache, { it }, 3)
        assertEquals(3, cache.size)

        evictOldest(cache, { it }, 10)
        assertEquals(3, cache.size)
    }

    @Test
    fun `an empty cache is a no-op`() {
        val cache = ConcurrentHashMap<String, Long>()

        evictOldest(cache, { it }, 5)

        assertTrue(cache.isEmpty())
    }

    @Test
    fun `only the excess above the cap is dropped`() {
        val cache = ConcurrentHashMap<String, Long>()
        cache["a"] = 1L
        cache["b"] = 2L
        cache["c"] = 3L
        cache["d"] = 4L
        cache["e"] = 5L

        evictOldest(cache, { it }, 2)

        assertEquals(setOf("d", "e"), cache.keys)
    }

    @Test
    fun `eviction is by stamp, not by insertion order`() {
        // Inserted newest-first, so stamp order and insertion order disagree:
        // evicting by insertion would keep the stalest entries.
        val cache = ConcurrentHashMap<String, Long>()
        cache["newest"] = 100L
        cache["middle"] = 50L
        cache["oldest"] = 1L

        evictOldest(cache, { it }, 1)

        assertEquals(setOf("newest"), cache.keys)
    }

    @Test
    fun `the stamp need not be the value itself`() {
        // The TMDB caches keep their timestamp in Pair.first while the trailer
        // source cache keeps it in the value, which is why the stamp exists.
        val cache = ConcurrentHashMap<String, Pair<Long, String>>()
        cache["a"] = 3L to "third"
        cache["b"] = 1L to "first"
        cache["c"] = 2L to "second"

        evictOldest(cache, { it.first }, 2)

        assertEquals(setOf("a", "c"), cache.keys)
    }
}
