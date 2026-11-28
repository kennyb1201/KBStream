package com.kennyb1201.kbstream.data.tmdb

import com.kennyb1201.kbstream.data.cache.JsonCacheEntry
import com.kennyb1201.kbstream.data.cache.PINNED_JSON_CACHE_PREFIXES
import com.kennyb1201.kbstream.data.cache.jsonCacheEvictions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background availability pass: 13s on a cold TV start
 * (`home.catalogFilter=13062ms`, 452 TMDB calls) for a screen that had been
 * drawn in 0.4s.
 *
 * Two things are pinned here, in the order the read established them:
 *
 *  - **The answers were not surviving.** A verdict row is tens of bytes and is
 *    written once, while a rail payload is 27-81 KB and is re-written by every
 *    cold start. The trim's retention is oldest-first, so on a table at its
 *    budget every `homeavail:` row was evicted ahead of the payloads it makes
 *    unnecessary — the pass then re-derived the whole catalog on the next
 *    start, wrote those payloads back over the budget, and evicted its own
 *    answers again. That is why [PINNED_JSON_CACHE_PREFIXES] has to carry the
 *    verdict namespace, and why the pass must keep reporting its split.
 *  - **The split is what the next paste needs.** `catalogFilter` alone cannot
 *    tell a count problem from a queue problem from a slow read, and 452 calls
 *    cannot either (a detail-cache hit under the permit is not a call). The
 *    counters in [AvailabilityPassStats] are recorded per leg and as a running
 *    total, and the wiring is pinned so a refactor cannot silently drop them.
 */
class CatalogFilterPassContractTest {

    private val kb = 1024L

    private fun entry(key: String, kbSize: Long, updatedAt: Long) =
        JsonCacheEntry(key = key, bytes = kbSize * kb, updatedAt = updatedAt)

    // ── the leak: a full cache eats its cheapest, most valuable rows ─────

    @Test
    fun `a full cache never evicts a verdict row for a payload`() {
        // The shape that made this permanent: a table at its budget, its oldest
        // rows being the one-word answers the cold start wrote first, and its
        // newest rows the 27-81 KB payloads that same start wrote after them.
        // 1 KB each, which is generous: the real row is a few dozen bytes. Sized
        // so the case fails for the RIGHT reason before the pin - an old row the
        // budget can reach is evicted, however small it is.
        val verdicts = (1..40).map { i ->
            entry("homeavail:tmdb:$i", kbSize = 1, updatedAt = 100L + i)
        }
        val payloads = (1..8).map { i ->
            entry("detail_en:movie:tt$i", kbSize = 60, updatedAt = 900L + i)
        }

        // Six payloads' worth of budget. The pinned rows' 40 KB is reserved up
        // front, so five payloads fit and the three oldest cannot.
        val evicted = jsonCacheEvictions(
            rows = verdicts + payloads,
            maxBytes = 6 * 60 * kb,
            maxEntries = 4_000
        )

        assertFalse(
            "a verdict row must never be spent for 40 bytes of payload: $evicted",
            evicted.any { key -> key.startsWith(HomeAvailabilityVerdict.KEY_PREFIX) }
        )
        // The payloads still obey the budget - the pin is a pin, not an amnesty.
        assertEquals(
            listOf("detail_en:movie:tt1", "detail_en:movie:tt2", "detail_en:movie:tt3"),
            evicted.sorted()
        )
    }

    @Test
    fun `the pinned namespace is the verdict namespace, not a copy of it`() {
        // A literal here would drift the day the key prefix changes, and the
        // failure would look like "the pass got slow again", not like a rename.
        assertTrue(
            "PINNED_JSON_CACHE_PREFIXES must carry ${HomeAvailabilityVerdict.KEY_PREFIX}",
            PINNED_JSON_CACHE_PREFIXES.contains(HomeAvailabilityVerdict.KEY_PREFIX)
        )
    }

    @Test
    fun `a verdict row is still aged out by the table's own TTL`() {
        // Pinning is about the byte budget only: "available" is honoured for 30
        // days by the verdict's read rule, and the table's 30-day age prune must
        // stay the thing that ends its life - otherwise a pin would make an
        // answer immortal, which the day-scoped "not available" rule forbids.
        val trimmed = source("com/kennyb1201/kbstream/data/cache/TmdbJsonCacheMaintenance.kt")
        val trim = trimmed.substringAfter("suspend fun trim(")
        val agePruneAt = trim.indexOf("deleteOlderThan")
        val budgetAt = trim.indexOf("jsonCacheEvictions(")

        assertTrue("trim() must age rows out", agePruneAt >= 0)
        assertTrue("trim() must keep its byte budget", budgetAt >= 0)
        assertTrue(
            "the age prune runs before the budget, and independently of the pins",
            agePruneAt < budgetAt
        )
        assertTrue(
            "the age prune must not consult the pinned prefixes",
            !trim.substring(0, budgetAt).contains("PINNED_JSON_CACHE_PREFIXES")
        )
    }

    // ── the instrument: the pass reports what it actually did ───────────

    @Test
    fun `the pass reports its count, its store hits and its permit time`() {
        val repo = source("com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt")
        val pass = repo.substringAfter("suspend fun <T> filterByHomeAvailabilityById(")
            .substringBefore("private suspend fun canonicalVerdictKey")

        assertTrue(
            "the pass must report its split (AvailabilityPassStats)",
            pass.contains("onStats: ((AvailabilityPassStats) -> Unit)? = null")
        )
        // The three decisions the totals have to separate: the cheap gates, the
        // stored answer, and the reads (of which only some are requests).
        assertTrue(pass.contains("movies.incrementAndGet()"))
        assertTrue(pass.contains("yearSkipped.incrementAndGet()"))
        assertTrue(pass.contains("storeAnswered.incrementAndGet()"))
        assertTrue(pass.contains("fetched.incrementAndGet()"))
        assertTrue(
            "a read answered from the detail cache is not a request to TMDB",
            pass.contains("{ source ->")
        )
        assertTrue(pass.contains("netFetches.incrementAndGet()"))
        assertTrue(
            "the summed permit time is what the queue floor is computed from",
            pass.contains("fetchTotalMs.add(fetchMs)")
        )
    }

    @Test
    fun `home records the split per leg and across the whole cold start`() {
        val vm = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")

        assertTrue(
            "filterCatalogRowBehind must hand the leg's stats to the recorder",
            vm.contains("recordVerdictPassStats(label, stats, buildEpoch)")
        )
        // Both views: this rail's pass, and the sum the cold start actually
        // paid. `home.catalogFilter` is one rail, and six rows run per build.
        assertTrue(vm.contains("listOf(\"\$label.all\" to totals, label to stats)"))
        assertTrue(vm.contains("PerfTrace.recordCount(\"\$prefix.fetched\""))
        assertTrue(vm.contains("PerfTrace.recordCount(\"\$prefix.net\""))
        assertTrue(vm.contains("PerfTrace.record(\"\$prefix.fetchAvg\""))
        assertTrue(
            "counts are recorded as counts, not forwarded as slow durations",
            !vm.contains("PerfTrace.record(\"\$prefix.fetched\"")
        )
    }

    @Test
    fun `the stats arithmetic the paste reads is the pass's own`() {
        val pass = AvailabilityPassStats(
            movies = 120,
            yearSkipped = 80,
            storeAnswered = 25,
            fetched = 15,
            netFetches = 9,
            fetchTotalMs = 6_000,
            fetchMaxMs = 1_400
        )

        assertEquals(400L, pass.fetchAvgMs)
        // 6s of reads through six permits cannot finish in under 1s, whatever
        // the cache hit rate says: this is the number that decides whether the
        // next cut is concurrency or count.
        assertEquals(1_000L, pass.queueFloorMs(6))
        assertEquals(6_000L, pass.queueFloorMs(1))

        val summed = pass + pass
        assertEquals(240, summed.movies)
        assertEquals(18, summed.netFetches)
        assertEquals(12_000L, summed.fetchTotalMs)
        // The slowest read is a max, not a sum.
        assertEquals(1_400L, summed.fetchMaxMs)
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        error("source tree not found from ${System.getProperty("user.dir")}")
    }
}
