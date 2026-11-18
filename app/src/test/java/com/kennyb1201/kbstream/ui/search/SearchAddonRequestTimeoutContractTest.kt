package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search add-on request cap (spec 2026-11-17, add-on timeout F1-F5).
 *
 * One add-on request held a search at 31s while the same wave measured 3s on
 * another build. Nothing was uncapped, so the wave's total WAS the slowest
 * single request: no timeout anywhere in the search path. These pin the cap,
 * the per-request (not per-wave) failure, both probe paths, and the paste line
 * that has to name the slow add-on next time.
 */
class SearchAddonRequestTimeoutContractTest {

    private val viewModel: String by lazy { source("ui/search/SearchViewModel.kt") }
    private val diagnostics: String by lazy { source("data/reporting/Diagnostics.kt") }
    private val trace: String by lazy { source("data/reporting/SearchRunTrace.kt") }

    // ── F1: one 8s cap per request ───────────────────────────────────────

    @Test
    fun `the cap is eight seconds and it is per request, not per wave`() {
        assertTrue(
            "the spec's constant, in the ViewModel that owns the wave",
            viewModel.contains("const val SEARCH_ADDON_REQUEST_TIMEOUT_MS = 8_000L")
        )
        assertEquals(
            "exactly three requests are capped: /search, searchCatalog and the " +
                "ignored-query guard - no cap is left lying around the TMDB legs",
            3,
            Regex("withTimeoutOrNull\\(SEARCH_ADDON_REQUEST_TIMEOUT_MS\\)")
                .findAll(viewModel).count()
        )
    }

    // ── F3: both probe paths ─────────────────────────────────────────────

    @Test
    fun `both the search endpoint and the catalog probes run under the cap`() {
        val group = groupBody()
        val firstCap = group.indexOf("withTimeoutOrNull(SEARCH_ADDON_REQUEST_TIMEOUT_MS)")
        val searchCall = group.indexOf("repository.search(")
        assertTrue("the /search endpoint probe is capped", firstCap >= 0)
        assertTrue(
            "and the cap encloses it, rather than sitting somewhere beside it",
            searchCall > firstCap
        )
        val secondCap =
            group.indexOf("withTimeoutOrNull(SEARCH_ADDON_REQUEST_TIMEOUT_MS)", firstCap + 1)
        val catalogCall = group.indexOf("repository.searchCatalog(")
        assertTrue("the catalog probe is capped too (F3: no uncapped path)", secondCap > firstCap)
        assertTrue("and that cap encloses the catalog call", catalogCall > secondCap)
    }

    @Test
    fun `the ignored-query guard is capped, so it cannot hold the wave past the cap`() {
        val guard = viewModel.substringAfter("private suspend fun catalogIgnoredQuery(")
            .substringBefore("private fun tmdbResultName(")
        val cap = guard.indexOf("withTimeoutOrNull(SEARCH_ADDON_REQUEST_TIMEOUT_MS)")
        assertTrue(
            "the guard's extra browse fetch sits inside the add-on wave's rail " +
                "loop, so an uncapped one would still hold the wave at 31s",
            cap >= 0 && guard.indexOf("repository.getCatalog(") > cap
        )
    }

    // ── F2: a timeout fails that request only ────────────────────────────

    @Test
    fun `a cut request answers empty instead of cancelling the wave`() {
        val group = groupBody()
        assertTrue(
            "the /search probe falls through to an empty answer (and its catalogs) " +
                "rather than throwing",
            group.contains("?: run {") &&
                group.contains("onTimeout()") &&
                group.contains("emptyList()")
        )
        assertTrue(
            "the catalog probe counts only a CUT request as a timeout, not any " +
                "thrown one",
            group.contains("if (answer.isSuccess && answer.getOrNull() == null) onTimeout()")
        )
        assertFalse(
            "no TimeoutCancellationException is caught or rethrown: a timeout is " +
                "not a cancellation here",
            viewModel.contains("TimeoutCancellationException")
        )
        assertTrue(
            "and a genuinely cancelled wave is still rethrown, never treated as a " +
                "slow add-on",
            viewModel.contains("catch (e: kotlinx.coroutines.CancellationException) {") &&
                viewModel.contains("throw e")
        )
        assertTrue(
            "the wave still joins every add-on instead of cancelling them",
            viewModel.contains("}.joinAll()")
        )
    }

    @Test
    fun `the addon's other catalogs still probe and the rails already published stay`() {
        val group = groupBody()
        assertTrue(
            "every searchable catalog still gets its own async probe",
            group.contains("val probes = searchableCatalogs.map { catalog ->") &&
                group.contains("async {") &&
                group.contains("val (catalog, hits) = probe.await()")
        )
        assertTrue(
            "and each finished rail is still published the moment it is ready",
            viewModel.contains("publishAddonSnapshot(index, group, slots, generation)")
        )
    }

    // ── F4: the paste names the slowest add-on ───────────────────────────

    @Test
    fun `each add-on's own time and cut count are recorded`() {
        assertTrue(
            "the wave times each add-on and counts its cut requests",
            viewModel.contains("SearchRunTrace.onAddonProbed(") &&
                viewModel.contains("name = addon.displayName,") &&
                viewModel.contains("timedOut = timedOut.get()")
        )
        assertTrue(
            "recorded even when the add-on throws, so a failed one is not invisible",
            viewModel.contains("} finally {")
        )
        assertTrue(
            "a superseded wave does not describe the paste's search",
            viewModel.contains("if (generation == addonSearchGeneration) {")
        )
        assertTrue(
            "the trace keeps the per-add-on facts",
            trace.contains("private var addonTimings: List<SearchAddonTiming>") &&
                trace.contains("fun onAddonProbed(") &&
                trace.contains("addonTimings = addonTimings.filterNot { it.name == name } +")
        )
    }

    @Test
    fun `the breakdown reaches the search line`() {
        assertTrue(
            "appended to the one search line the paste carries",
            diagnostics.contains("searchAddonBreakdown(facts.addonTimings)?.let { append(\" \").append(it) }")
        )
        assertTrue(
            "and it lists the add-ons plus the wave's cut total",
            source("data/reporting/SearchAddonTimings.kt")
                .contains("append(\" timeouts=\")")
        )
    }

    // ── F5: scope - the titles leg, kids ceiling and dedupe are untouched ─

    @Test
    fun `nothing else in the search path was rewired`() {
        assertTrue(
            "the titles leg still has its own legs and no add-on cap",
            viewModel.contains("timedLeg(\"movies\")") &&
                viewModel.contains("timedLeg(\"keyword\")")
        )
        assertTrue(
            "the kids ceiling and the consolidated de-dupe are still the publish's " +
                "own steps",
            viewModel.contains("withKidsCeiling(snapshot)") &&
                viewModel.contains("withKidsCeiling(visibleGroups)") &&
                viewModel.contains("coalesceAddonGroups(")
        )
        assertTrue(
            "and the digital-release filter still runs on each rail before it is " +
                "published",
            viewModel.contains("val visible = withHomeAvailability(listOf(group)).firstOrNull() ?: return")
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** The add-on wave's per-add-on body, where both probe paths live. */
    private fun groupBody(): String = viewModel
        .substringAfter("private suspend fun collectAddonSearchGroup(")
        .substringBefore("private suspend fun catalogIgnoredQuery(")

    private fun source(relative: String): String {
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
