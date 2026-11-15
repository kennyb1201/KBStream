package com.kennyb1201.kbstream.data.reporting

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search split (spec 2026-11-15, search speedup F1).
 *
 * Search had no measurement at all, so a slow search could only be blamed on a
 * guess - and the four candidates want four different fixes: the debounce, the
 * slowest TMDB wave, the add-on probes, or a merge that waits for everything.
 * So what is pinned is what the line reports (a leg per wave, submit → titles,
 * submit → add-on rails, the debounce as a fact, and whether the cache answered)
 * and the two flow facts the spec asks the report to state.
 */
class SearchRunTraceTest {

    @Test
    fun `each leg is measured and the run's facts carry them`() {
        PerfTrace.reset()
        SearchRunTrace.markSubmitted(cached = false, debounce = 300L, atMs = 1_000L)

        runBlocking {
            // A fake clock, so the leg's arithmetic is pinned rather than the
            // device's speed.
            var tick = 0
            SearchRunTrace.timedLeg("movies", clock = { if (tick++ == 0) 100L else 350L }) { Unit }
        }

        SearchRunTrace.onTitlesPublished(count = 40, atMs = 2_200L)
        SearchRunTrace.onAddonsPublished(groups = 3, atMs = 3_000L)

        val facts = SearchRunTrace.facts()!!
        assertEquals("the debounce is reported as a fact, not inferred", 300L, facts.debounceMs)
        assertEquals("submit to the built-in rails", 1_200L, facts.titlesMs)
        assertEquals("submit to the add-on rails", 2_000L, facts.addonMs)
        assertEquals(40, facts.titlesCount)
        assertEquals(3, facts.addonGroups)
        assertEquals(false, facts.cached)
        assertTrue(
            "and every TMDB wave is on the ring under its own label",
            PerfTrace.latestByPrefix("search.leg.movies").isNotEmpty()
        )
        assertEquals(1_200L, PerfTrace.latestByPrefix("search.titles").first().second)
        assertEquals(2_000L, PerfTrace.latestByPrefix("search.addon").first().second)
    }

    @Test
    fun `a leg is closed once per run, so a later publish cannot rewrite it`() {
        SearchRunTrace.markSubmitted(cached = false, debounce = 300L, atMs = 1_000L)
        SearchRunTrace.onTitlesPublished(count = 10, atMs = 2_000L)
        // The add-on enrichment republishes the same rails minutes later.
        SearchRunTrace.onTitlesPublished(count = 10, atMs = 60_000L)
        SearchRunTrace.onAddonsPublished(groups = 1, atMs = 4_000L)
        SearchRunTrace.onAddonsPublished(groups = 1, atMs = 90_000L)

        assertEquals(1_000L, SearchRunTrace.facts()!!.titlesMs)
        assertEquals(3_000L, SearchRunTrace.facts()!!.addonMs)
    }

    @Test
    fun `a cached repeat says so rather than reporting a network speed`() {
        SearchRunTrace.markSubmitted(cached = true, debounce = 300L, atMs = 1_000L)
        SearchRunTrace.onTitlesPublished(count = 12, atMs = 1_005L)

        val facts = SearchRunTrace.facts()!!
        assertTrue("no wave was fired", facts.cached)
        assertTrue("and the leg is the restore, not a fetch", facts.titlesMs < 50L)
        assertEquals(
            "an add-on leg that never published stays unknown, not zero",
            -1L,
            facts.addonMs
        )
    }

    @Test
    fun `nothing is reported before the first search`() {
        // A session that never searched must not grow a search line in the
        // paste - which is what makes an absent line mean "not searched"
        // rather than "measured zero".
        SearchRunTrace.reset()
        assertNull(SearchRunTrace.facts())
    }

    // ── The flow facts the spec asks the report to state ──────────────────

    @Test
    fun `the debounce still gates the waves, so typing fires nothing per keystroke`() {
        val viewModel = read("ui/search/SearchViewModel.kt")
        val job = viewModel.substringAfter("searchJob = viewModelScope.launch {")
        assertTrue(
            "the delay sits BEFORE the loading flag and the waves",
            job.indexOf("delay(SEARCH_DEBOUNCE_MS)") in 0 until job.indexOf("_isLoading.value = true")
        )
        assertTrue(
            "and it is still a real debounce, not a per-keystroke fire",
            viewModel.contains("const val SEARCH_DEBOUNCE_MS = 300L")
        )
    }

    @Test
    fun `the add-on wave publishes on its own path, in parallel with the TMDB waves`() {
        val viewModel = read("ui/search/SearchViewModel.kt")
        assertTrue(
            "the add-on search is launched before the TMDB awaits, so neither " +
                "wave holds the other",
            viewModel.indexOf("launchAddonSearch(") < viewModel.indexOf("val moviePage = tmdbMoviesDeferred.await()")
        )
        assertTrue(
            "and it publishes its own groups the moment they are ready",
            viewModel.contains("_addonResultGroups.value = publishedGroups")
        )
        assertTrue(
            "each of the six TMDB waves is timed on its own",
            listOf("movies", "tv", "person", "studio", "collection", "keyword").all {
                viewModel.contains("timedLeg(\"$it\")")
            }
        )
    }

    @Test
    fun `the split reaches the paste as one line`() {
        val diagnostics = read("data/reporting/Diagnostics.kt")
        assertTrue(
            "appended next to the startup and playback lines",
            diagnostics.contains("searchLine()?.let { report.appendLine(it) }")
        )
        assertTrue(
            "with both legs, the debounce, the per-wave split and the cache flag",
            diagnostics.contains("append(\"search: titles=\")") &&
                diagnostics.contains("append(\" addon=\")") &&
                diagnostics.contains("append(\" debounce=\")") &&
                diagnostics.contains("append(\" legs=\")") &&
                diagnostics.contains("append(\" cached=\")")
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
