package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detail load's legs, and the cache evidence that decides what to fix
 * (spec 2026-11-15, F1/F3).
 *
 * The number in the field report was `detail.load total=2513ms` beside
 * `tmdb: detail=150/150`, and neither can say WHERE the time went: that figure
 * times the whole load, refine tail included, and a full cache says nothing
 * about whether a title was answered from memory, re-read from the 30-day disk
 * row, or fetched over the network. Three different causes, three different
 * fixes, one ambiguous paste - so the legs and the cache sources are recorded,
 * and this pins them from the source because a JVM test cannot press a remote.
 *
 * Kept out of [DetailTraceDiagnosticsContractTest] on purpose: that one is the
 * focus trace, and these are timings.
 */
class DetailLoadLegsContractTest {

    private val viewModel by lazy {
        read("ui/detail/DetailViewModel.kt")
    }
    private val repository by lazy {
        read("data/tmdb/TmdbRepository.kt")
    }
    private val diagnostics by lazy {
        read("data/reporting/Diagnostics.kt")
    }

    // ── F1: first paint, and the legs around it ───────────────────────────

    @Test
    fun `first paint is timed once, and the tail is what is left after it`() {
        assertTrue(
            "F1: the page's own figure exists beside detail.load",
            viewModel.contains("PerfTrace.record(\n                    \"detail.firstPaint\",")
        )
        assertTrue(
            "and it is recorded ONCE per load: the early paint and the finally " +
                "both reach for it, and a second line per open would read as two " +
                "paints",
            viewModel.contains("var firstPaintAt = -1L") &&
                viewModel.contains("fun markFirstPaint() {\n                if (firstPaintAt >= 0) return")
        )
        assertTrue(
            "the early paint - the one that brings the spinner down before the " +
                "refine tail - marks it",
            viewModel.contains("_isLoading.value = false\n                        markFirstPaint()")
        )
        assertTrue(
            "and the finally marks it too, because a load that never reached " +
                "that arm (no TMDB record, a kids block, a failure) still stops " +
                "being a spinner there",
            viewModel.contains("            } finally {\n                _isLoading.value = false") &&
                viewModel.contains("                markFirstPaint()\n                // Hand the state to the freshness gate")
        )
        assertTrue(
            "F1: the tail is the load minus the paint - the 'feels slow after it " +
                "paints' number, which is the half the field report could not see",
            viewModel.contains("\"detail.tail\",\n                        loadTotalMs - firstPaintAt,") &&
                viewModel.contains("if (firstPaintAt >= 0) {")
        )
    }

    @Test
    fun `every leg F1 names a fix for is timed where it runs`() {
        assertTrue(
            "the TMDB fetch, the leg that can be seconds, timed around its own " +
                "call so a memory hit reads as a millisecond",
            viewModel.contains("val fetchStartedAt = SystemClock.elapsedRealtime()") &&
                viewModel.contains("\"detail.tmdb\",\n                        tmdbFetchMs,")
        )
        assertTrue(
            "and the hit/miss is recorded with it: a failed fetch is not a slow one",
            viewModel.contains("ok = fetched.getOrNull() != null")
        )
        assertTrue(
            "the addons list (the probe leg's first half)",
            viewModel.contains("\"detail.addons\",") &&
                viewModel.contains("val addons = addonManager.getEnabledAddons()")
        )
        assertTrue(
            "and the Simkl round-trip, which the load awaits after the paint",
            viewModel.contains("\"detail.simkl\",") &&
                viewModel.contains("val simklStartedAt = SystemClock.elapsedRealtime()")
        )
        val earlyPaint =
            viewModel.indexOf("_isLoading.value = false\n                        markFirstPaint()")
        val tailLeg = viewModel.indexOf("\"detail.simkl\"")
        assertTrue(
            "and the paint marker is recorded before the tail's own legs, so a " +
                "tail timer can never stand in for the paint: nothing in the tail " +
                "may move in front of it",
            earlyPaint in 0 until tailLeg
        )
        assertTrue(
            "and the one leg the paint DOES wait on is still awaited where it " +
                "always was - the timers do not reorder the load",
            viewModel.contains("tmdbDetailResult = tmdbDeferred.await()")
        )
    }

    @Test
    fun `the paste carries the legs in one line, whatever the summary ranks`() {
        assertTrue(
            "F1: the diagnostics print the detail legs explicitly. The perf " +
                "summary ranks by total and keeps six, so on a busy session a " +
                "home label can push every detail leg out of the paste",
            diagnostics.contains("PerfTrace.latestByPrefix(\"detail.\")") &&
                diagnostics.contains("\"detailload: \" + legs.joinToString(\" \")")
        )
        assertTrue(
            "the line names the leg without the label's own prefix, so it reads " +
                "as firstPaint/tmdb/tail",
            diagnostics.contains("label.removePrefix(\"detail.\")")
        )
    }

    // ── F3: eviction by USE, and where the answers come from ──────────────

    @Test
    fun `a cache hit re-stamps the entry, so eviction drops what was not opened`() {
        val hit = repository
            .substringAfter("val cached = detailCache[key]")
            .substringBefore("return cached.second")
        assertTrue(
            "F3: the hit writes the entry back with the current stamp. " +
                "evictOldest drops the LOWEST stamp, so with insert-only stamps " +
                "it dropped the titles the session opened early and came back " +
                "to - the ones a viewer reopens - while a title fetched once " +
                "during a browse and never looked at again stayed",
            hit.contains("detailCache[key] = now to cached.second")
        )
        assertTrue(
            "the same for the slim rails' copy, which has its own cap",
            repository.contains("railDetailCache[key] = now to railCached.second")
        )
        assertTrue(
            "and the cap itself is untouched: this is a better choice of which " +
                "entries to keep, not a bigger cache on a 192MB heap",
            repository.contains("private val MAX_DETAIL_ENTRIES = 150")
        )
    }

    @Test
    fun `the cache says whether an open was answered from memory, disk or the network`() {
        assertTrue(
            "F1's second question: the cache is full - so how often is a Detail " +
                "open actually re-fetching? Counted per answer, not guessed",
            repository.contains("detailMemoryHits") &&
                repository.contains("detailDiskHits") &&
                repository.contains("detailFetches")
        )
        assertTrue(
            "and printed beside the sizes, where the paste already looks",
            repository.contains("\" detailSrc=mem:") &&
                repository.contains(" disk:\${detailDiskHits.get()}")
        )
        assertTrue(
            "a disk row is NOT a network fetch: an evicted memory entry is " +
                "rebuilt from the 30-day JSON row, so `disk` and `net` have to " +
                "be told apart before any cap is raised",
            repository.contains("detailDiskHits.incrementAndGet()") &&
                repository.contains("detailFetches.incrementAndGet()")
        )
    }

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }
}
