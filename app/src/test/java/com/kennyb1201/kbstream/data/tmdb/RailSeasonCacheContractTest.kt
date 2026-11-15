package com.kennyb1201.kbstream.data.tmdb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rail and season caches: what evicts, in which order, and what a miss
 * re-fetches (spec 2026-11-15).
 *
 * The field paste shows all three TMDB caches at their caps
 * (`detail=150/150 rail=150/150 season=200/200`), and "full" on its own decides
 * nothing: a full cache that is never re-read costs nothing, while one that
 * churns is a re-fetch factory. So what is pinned here is the evidence the next
 * paste will carry (per-cache hit/disk/network counts and the eviction counts),
 * the ORDER those entries are dropped in, and the fact that the caps did not
 * move - both of them, because `MAX_DETAIL_ENTRIES` sizes detail AND rail.
 *
 * Read from the source: [TmdbRepository] needs Android, a Room database and the
 * network, so the cashier's rules are pinned where they are written.
 */
class RailSeasonCacheContractTest {

    private val repository by lazy { read("data/tmdb/TmdbRepository.kt") }
    private val eviction by lazy { read("data/memory/CacheEviction.kt") }

    /** The season-episode lookup, where its memory hit is decided. */
    private val seasonLookup: String by lazy {
        repository.substringAfter("pruneMemoryCaches()\n        val cached = seasonEpisodesCache[key]")
            .substringBefore("val seasonDetail = api.getSeasonDetail")
    }

    // F1: the rate

    @Test
    fun `each in-scope cache reports where its answers came from`() {
        assertTrue(
            "a rail card's answer says whether it was a memory read or a fetch - " +
                "the rate is what decides the cap, and the size cannot",
            repository.contains("railMemoryHits") &&
                repository.contains("railDiskHits")
        )
        assertTrue(
            "the season lists are counted the same way, network included",
            repository.contains("seasonMemoryHits") &&
                repository.contains("seasonDiskHits") &&
                repository.contains("seasonFetches")
        )
        assertTrue(
            "and both counts reach the paste beside the sizes, which is where a " +
                "diagnostics reader already looks",
            repository.contains("railSrc=mem:\${railMemoryHits.get()}") &&
                repository.contains("disk:\${railDiskHits.get()}") &&
                repository.contains("seasonSrc=mem:\${seasonMemoryHits.get()}") &&
                repository.contains("net:\${seasonFetches.get()}") &&
                repository.contains("evicted=rail:\${railEvictions.get()}") &&
                repository.contains("season:\${seasonEvictions.get()}")
        )
    }

    @Test
    fun `the eviction rate is counted, not just the size`() {
        assertTrue(
            "evictOldest reports how many it dropped, so a cache at its cap can " +
                "say whether it churned",
            eviction.contains("): Int {") && eviction.contains("return over")
        )
        assertTrue(
            "the two caches this spec owns add that up",
            repository.contains("railEvictions.addAndGet(") &&
                repository.contains("seasonEvictions.addAndGet(")
        )
    }

    // F1: the ordering

    @Test
    fun `a season hit re-stamps its entry, so eviction is by use and not by age`() {
        assertTrue(
            "evictOldest drops the LOWEST stamp, so a cache that never re-stamps " +
                "on a read evicts the show the viewer opens every evening while a " +
                "show fetched once last week stays",
            seasonLookup.contains("seasonEpisodesCache[key] = now to cached.second")
        )
        assertTrue(
            "the rail projection and the full detail entry already do this (the " +
                "detail spec's F3, landed in 2664934c)",
            repository.contains("railDetailCache[key] = now to railCached.second") &&
                repository.contains("detailCache[key] = now to cached.second")
        )
        assertTrue(
            "and the re-stamp stays out of the cached-failure branch: a list that " +
                "is not an answer is skipped and re-fetched, not kept young",
            !seasonLookup.contains("seasonEpisodesCache[key] = now to cached.second\n                }\n                return") ||
                seasonLookup.indexOf("seasonEpisodesAreAnAnswer(named)") <
                seasonLookup.indexOf("seasonEpisodesCache[key] = now to cached.second")
        )
    }

    // F2: the caps, untouched, in one place

    @Test
    fun `neither cap moved, and the shared constant still sizes two caches`() {
        assertTrue(
            "no blind raise: the caps exist for the 192MB heap and the spec " +
                "requires a measured memory cost first, which no paste here carries",
            repository.contains("private val MAX_DETAIL_ENTRIES = 150") &&
                repository.contains("private val MAX_SEASON_ENTRIES = 200")
        )
        assertTrue(
            "MAX_DETAIL_ENTRIES sizes detail AND rail - a change to it moves both, " +
                "which is why the decision lives in one spec",
            repository.contains("evictOldest(detailCache, { it.first }, MAX_DETAIL_ENTRIES)") &&
                repository.contains("evictOldest(railDetailCache, { it.first }, MAX_DETAIL_ENTRIES)")
        )
        assertTrue(
            "and there is still exactly one eviction function, shared",
            eviction.contains("internal fun <V> evictOldest(") &&
                !read("data/omdb/OmdbRepository.kt").contains("private fun evictOldest")
        )
    }

    // F3: what a miss costs

    @Test
    fun `a season miss is one fetch, and a cached failure is never an answer`() {
        assertEquals(
            "the season path fetches once per miss: one getSeasonDetail, and it is " +
                "counted",
            repository.split("seasonFetches.incrementAndGet()").size,
            2
        )
        assertTrue(
            "an empty or failed list is not stored as an answer, so a transient " +
                "failure cannot be cached into the cap",
            repository.contains("if (seasonEpisodesAreAnAnswer(episodes))") &&
                repository.contains("seasonEpisodesAreAnAnswer(named)")
        )
        assertFalse(
            "the rail projection is filled by the SAME enriched fetch as detail, so " +
                "this spec does not add a second request for rail art",
            repository.contains("railFetches")
        )
    }

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }
}
