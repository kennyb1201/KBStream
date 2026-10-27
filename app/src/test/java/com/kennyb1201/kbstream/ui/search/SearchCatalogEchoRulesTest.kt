package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.data.addon.MetaPreview
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps a query-ignoring rank catalog out of the search rails,
 * plus the two properties of the guard that keep its verdict honest.
 *
 * The rule is pure, so it is exercised directly - including the two shapes that
 * defeated the old exact ordered-list comparison (a ranking that rotated
 * between the two calls, and a search endpoint that answers with a smaller page
 * than the browse endpoint). The rest of the guard needs a live add-on, a
 * catalog cache and a ViewModel, so its remaining legs are pinned from source
 * the way the search deep dive pins its wiring: the comparison page is fetched
 * live, and a request that never answered is not cached as a verdict.
 */
class SearchCatalogEchoRulesTest {

    private fun movie(id: String) = MetaPreview(id = id, type = "movie", name = "Title $id")

    /** A ranking catalog's own page: twenty entries, in its published order. */
    private val chart = (1..20).map { movie("tt$it") }

    @Test
    fun `a rank catalog answering with its own chart is an echo`() {
        assertTrue(
            "the 2026-10-27 report: a Top Ten list returned whatever the term is " +
                "the catalog's browse page, not a search answer",
            catalogEchoesItsBrowseList(chart, chart)
        )
    }

    @Test
    fun `a rotated ranking is still an echo`() {
        val swapped = chart.toMutableList().also {
            it[0] = chart[1]
            it[1] = chart[0]
        }

        assertTrue(
            "one repositioned entry between the two calls used to defeat the " +
                "exact ordered comparison, which is not evidence about the query",
            catalogEchoesItsBrowseList(chart, swapped)
        )
        assertTrue(
            "and a fully rotated ranking is the same list",
            catalogEchoesItsBrowseList(chart, chart.reversed())
        )
    }

    @Test
    fun `a shorter answer that echoes the head of the chart is an echo`() {
        assertTrue(
            "an add-on whose search endpoint pages smaller than its browse " +
                "endpoint still echoes the head of the same ranking",
            catalogEchoesItsBrowseList(chart, chart.take(10))
        )
    }

    @Test
    fun `a filtered answer is kept`() {
        assertFalse(
            "a catalog that honoured the query answers with its matches, not " +
                "with the head of its chart",
            catalogEchoesItsBrowseList(chart, listOf(chart[5], chart[12]))
        )
        assertFalse(
            "titles from further down the chart are not its head, however many " +
                "of them there are",
            catalogEchoesItsBrowseList(chart, chart.drop(10))
        )
    }

    @Test
    fun `the head of the chart with one different title is kept`() {
        assertFalse(
            "a real answer that happens to overlap the head is not the head",
            catalogEchoesItsBrowseList(chart, chart.take(9) + movie("tt-other"))
        )
    }

    @Test
    fun `a single hit is never an echo`() {
        assertFalse(
            "searching a chart's own number one is a real search result, and " +
                "the old comparison would have dropped it",
            catalogEchoesItsBrowseList(chart, listOf(chart[0]))
        )
    }

    @Test
    fun `an empty answer or an empty chart is not an echo`() {
        assertFalse(
            "a catalog that answered nothing answered the query",
            catalogEchoesItsBrowseList(chart, emptyList())
        )
        assertFalse(
            "and there is no list to have been echoed",
            catalogEchoesItsBrowseList(emptyList(), chart.take(10))
        )
    }

    @Test
    fun `an answer longer than the chart is not an echo`() {
        assertFalse(
            "a search page longer than the browse page is not a page of it",
            catalogEchoesItsBrowseList(chart.take(3), chart.take(5))
        )
    }

    // ── the guard's other two legs, pinned from source ────────────────

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private val viewModelSource: String by lazy {
        File(sourceRoot, VIEW_MODEL).readText().replace(Regex("\\s+"), " ")
    }

    /** Just the guard's body, so a `contains` below cannot be satisfied elsewhere. */
    private fun guard(): String {
        val start = viewModelSource.indexOf("private suspend fun catalogIgnoredQuery(")
        assertTrue("the ignored-query guard is missing", start >= 0)
        val end = viewModelSource.indexOf("private fun ", start + 1)
        assertTrue("the guard's end is missing", end > start)
        return viewModelSource.substring(start, end)
    }

    @Test
    fun `the comparison page is fetched live`() {
        assertTrue(
            "the search page the guard compares against is a live call, so a " +
                "cached snapshot of a ranking that has since rotated would read " +
                "as a page that honoured the query",
            guard().contains("forceRefresh = true")
        )
    }

    @Test
    fun `a dropped request is not cached as a verdict`() {
        val body = guard()
        val refusal = body.indexOf("if (unfiltered == null) return false")
        val cached = body.indexOf("ignoredQueryCache[key] = ignored")
        assertTrue("the probe's failure path is missing", refusal >= 0)
        assertTrue("the verdict is not cached at all", cached >= 0)
        assertTrue(
            "no answer is no verdict: one dropped request must not pin a rail " +
                "on for the rest of the session",
            refusal < cached
        )
    }

    @Test
    fun `the rank pre-filter and the search-style exemption still hold`() {
        assertTrue(
            "the cheaper pre-filter still spares a normal catalog the probe's " +
                "extra HTTP call",
            guard().contains("if (!catalog.isRankStyleCatalog()) return false")
        )
        assertTrue(
            "and a search-style catalog is still never dropped at the call site",
            viewModelSource.contains(
                "if (!searchStyle && catalogIgnoredQuery(baseUrl, catalog, hits)) continue"
            )
        )
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
    }
}
