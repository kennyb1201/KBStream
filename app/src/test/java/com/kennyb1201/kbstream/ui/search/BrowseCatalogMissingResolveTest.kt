package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A name added to the browse catalog used to stay invisible for a whole week.
 *
 * The disk cache counts as "fresh" when the slice it renders holds >= 90% of the
 * active name list - a threshold that exists so a resolve interrupted by TMDB
 * throttling gets repaired - and eight new keyword names on a hundred is 92.6%,
 * i.e. fresh. `SearchViewModel.loadBrowseCatalogCache` now resolves the names the
 * cache cannot answer for on load, in the background, and merges them into the
 * strip and into the cache.
 *
 * [missingBrowseNames] carries "which names" and [mergeBrowseEntries] carries
 * "where they go"; both are pure, so the interesting cases are asserted here
 * directly. The wiring is pinned as text, the way BrowseCatalogPublishContractTest
 * pins the publish paths: a load path that quietly stops scheduling the targeted
 * resolve compiles, renders, and fails exactly the way the bug did.
 */
class BrowseCatalogMissingResolveTest {

    /** Stand-in ids: every helper here keys on the name, which is the chip. */
    private fun chips(vararg names: String): List<BrowseEntry> =
        names.mapIndexed { index, name -> BrowseEntry(index + 1, name) }

    // ── which names get resolved ─────────────────────────────────────

    @Test
    fun `only the names the cache has no entry for are resolved`() {
        assertEquals(
            listOf("kaiju", "spaghetti western"),
            missingBrowseNames(
                listOf("zombie", "kaiju", "vampire", "spaghetti western"),
                chips("zombie", "vampire")
            )
        )
    }

    @Test
    fun `an edit below the freshness threshold is still caught`() {
        // 100 names, 92 resolved: 92% - "complete" - so nothing but this check
        // would ever notice the eight missing chips.
        val all = (1..100).map { "tag$it" }
        val cached = chips(*all.take(92).toTypedArray())
        assertEquals(all.drop(92), missingBrowseNames(all, cached))
        assertTrue(
            "the fixture has to sit above the 90% threshold to be the reported case",
            cached.size * 10 >= all.size * 9
        )
    }

    @Test
    fun `a fully cached list asks for nothing`() {
        assertTrue(missingBrowseNames(listOf("a", "b"), chips("b", "a")).isEmpty())
    }

    @Test
    fun `an empty cache asks for the whole list, in list order`() {
        assertEquals(
            listOf("a", "b", "c"),
            missingBrowseNames(listOf("a", "b", "c"), emptyList())
        )
    }

    // ── where the resolved names go ──────────────────────────────────

    @Test
    fun `a newly resolved name lands in catalog position, not at the end`() {
        assertEquals(
            listOf("A", "B", "C"),
            mergeBrowseEntries(
                cached = chips("A", "C"),
                resolved = chips("B"),
                nameOrder = listOf("A", "B", "C")
            ).map { it.name }
        )
    }

    @Test
    fun `an id already on screen wins over a fresh lookup`() {
        // The cached id is the one the chip already opens; a second lookup of
        // the same name must not swap it (TMDB's ranking drifts).
        val merged = mergeBrowseEntries(
            cached = listOf(BrowseEntry(11, "A")),
            resolved = listOf(BrowseEntry(99, "A")),
            nameOrder = listOf("A")
        )
        assertEquals(1, merged.size)
        assertEquals(11, merged.single().id)
    }

    @Test
    fun `a name the order no longer knows keeps a place at the end`() {
        // A name a catalog edit dropped is still in the cache; the publish
        // filter removes it either way, but it must not be handed a position in
        // front of a name the active list does ask for.
        assertEquals(
            listOf("A", "B", "old1", "old2"),
            mergeBrowseEntries(
                cached = chips("old1", "A", "old2"),
                resolved = chips("B"),
                nameOrder = listOf("A", "B")
            ).map { it.name }
        )
    }

    @Test
    fun `the merge loses nothing it was not asked to change`() {
        val merged = mergeBrowseEntries(
            cached = chips("A", "B", "C"),
            resolved = emptyList(),
            nameOrder = listOf("A", "B", "C")
        )
        assertEquals(listOf("A", "B", "C"), merged.map { it.name })
        assertEquals(listOf(1, 2, 3), merged.map { it.id })
    }

    // ── the order the cache itself is stored in ──────────────────────

    @Test
    fun `the cache's union order appends the kids-only names`() {
        // The resolver builds its union in this order and the cache slice keeps
        // it, so the append has to write in the same order or the new chip
        // shows up at the bottom of the strip for as long as the cache lives.
        val keywords = browseUnionOrderFor("keywords")
        assertEquals((BROWSE_KEYWORD_NAMES + KIDS_KEYWORD_NAMES).distinct(), keywords)
        val adult = BROWSE_KEYWORD_NAMES.toSet()
        val kidsOnly = keywords.filterNot { it in adult }
        assertTrue(
            "the kids keyword list has been documented as no longer a subset " +
                "since the kids-only franchises landed; if that changed this " +
                "union order is no longer interesting",
            kidsOnly.isNotEmpty()
        )
        assertTrue(
            "a kids-only name has to be appended after the standard list, not " +
                "inserted into it: the cache and the resolver must agree",
            keywords.indexOfFirst { it !in adult } > keywords.indexOfLast { it in adult }
        )

        assertEquals(
            (BROWSE_COLLECTION_NAMES + KIDS_COLLECTION_NAMES + KIDS_COLLECTION_NAMES_EXTRA)
                .distinct(),
            browseUnionOrderFor("collections")
        )
    }

    @Test
    fun `a category with no name list has no union order`() {
        assertTrue(browseUnionOrderFor("genres").isEmpty())
        assertTrue(browseUnionOrderFor("services").isEmpty())
    }

    @Test
    fun `re-reading the cache puts the strip in the catalog's current order`() {
        // A cache written before the seasonal chips moved to the head of both
        // keyword lists still holds them at the tail, and the cache-load path
        // renders that order as-is - so without this the re-order would only
        // show up after the 7-day TTL. The holiday chips, the ids and the
        // entries a catalog edit dropped are all pinned here.
        val seasonal = listOf(
            "halloween",
            "christmas",
            "thanksgiving",
            "easter",
            "valentine's day",
            "new year's eve",
            "st. patrick's day",
            "fourth of july",
            "hanukkah",
            "day of the dead",
            "horror"
        )
        val stale = chips("zombie", "vampire", "dropped tag", *seasonal.toTypedArray())
        val ordered = browseEntriesInCatalogOrder("keywords", stale)

        assertEquals(
            "the cached strip must lead with the catalog's own head",
            seasonal,
            ordered.take(seasonal.size).map { it.name }
        )
        assertEquals(
            "a cached id is what the chip already opens; re-ordering must not " +
                "swap or drop one",
            stale.associate { it.name to it.id },
            ordered.associate { it.name to it.id }
        )
        assertEquals(
            "nothing may be lost (or added) on the way",
            stale.size,
            ordered.size
        )
        assertEquals(
            "a name the lists no longer carry goes last, not into the head",
            "dropped tag",
            ordered.last().name
        )
    }

    @Test
    fun `a category with no name list is handed back untouched`() {
        val entries = chips("b", "a")
        assertEquals(entries, browseEntriesInCatalogOrder("services", entries))
        assertEquals(entries, browseEntriesInCatalogOrder("genres", entries))
    }

    @Test
    fun `no name appears twice in a union order`() {
        listOf("keywords", "collections").forEach { key ->
            val order = browseUnionOrderFor(key)
            assertEquals("$key union holds a duplicate name", order.size, order.toSet().size)
        }
    }

    // ── the wiring (source contract) ─────────────────────────────────

    @Test
    fun `the cache load schedules a resolve for the names it could not answer for`() {
        val load = loadBlock()
        assertTrue(
            "the active-modes' missing names are what the targeted resolve is for",
            load.contains("missingBrowseNames(activeKeywordNames(), keywordsForMode)") &&
                load.contains("missingBrowseNames(activeCollectionNames(), collectionsForMode)")
        )
        assertTrue(
            "computing the names without scheduling the resolve is the bug",
            load.contains("viewModelScope.launch {") &&
                load.contains("resolveMissingCatalogEntries(missingKeywords, missingCollections)")
        )
    }

    @Test
    fun `the cache load re-orders the cached strip into the catalog's order`() {
        val load = loadBlock()
        assertTrue(
            "the cache is read in the order it was written in, so a catalog " +
                "re-order has to be applied on the way out or it waits for the TTL",
            load.contains("browseEntriesInCatalogOrder(\"keywords\", keywords)") &&
                load.contains("browseEntriesInCatalogOrder(\"collections\", collections)")
        )
        assertTrue(
            "both lists are sliced for the active mode after the re-order",
            load.contains(".forMode(isKidsMode)")
        )
    }

    @Test
    fun `the targeted resolve reuses the full pass's lookups, semaphore and retry`() {
        val targeted = targetedBlock()
        assertTrue(targeted.contains("resolveMissingNames("))
        assertTrue(targeted.contains("resolveKeywordName(it)"))
        assertTrue(targeted.contains("resolveCollectionName(it)"))

        val names = missingNamesBlock()
        assertTrue(
            "the burst has to be capped here too, or TMDB throttles it",
            names.contains("catalogResolveSemaphore.withPermit")
        )
        assertTrue(
            "and a throttled name still gets its one retry",
            names.contains("resolveWithRetry(name) { lookup(name) }")
        )
    }

    @Test
    fun `the targeted resolve publishes through the mode slice`() {
        val targeted = targetedBlock()
        assertTrue(targeted.contains("ResolvedBrowseCatalog("))
        assertTrue(
            "a targeted publish must slice to the active mode like every other",
            targeted.contains(".forMode(isKidsMode)")
        )
        assertTrue(targeted.contains("publishBrowseCategories("))
    }

    @Test
    fun `the targeted resolve appends to the cache instead of overwriting it`() {
        val targeted = targetedBlock()
        assertTrue(
            "the new entries have to survive the session",
            targeted.contains("appendBrowseCatalogCache(keywords, collections)")
        )
        assertFalse(
            "saveBrowseCatalogCache writes the whole union, so a targeted " +
                "resolve using it would drop the other mode's entries",
            targeted.contains("saveBrowseCatalogCache(")
        )

        val append = block(
            "private fun appendBrowseCatalogCache(", "private fun saveBrowseCatalogCache("
        )
        assertTrue(append.contains("readBrowseCatalogCache(prefs)"))
        assertTrue(append.contains("browseUnionOrderFor(\"keywords\")"))
        assertTrue(append.contains("browseUnionOrderFor(\"collections\")"))
        assertTrue(append.contains("saveBrowseCatalogCache("))
    }

    @Test
    fun `the TTL and the completeness threshold are untouched`() {
        val load = loadBlock()
        assertTrue(
            "the throttling-gap threshold is the half that stays",
            load.contains("keywordsForMode.size * 10 >= activeKeywordNames().size * 9") &&
                load.contains("collectionsForMode.size * 10 >= activeCollectionNames().size * 9")
        )
        assertTrue(
            viewModel.contains("const val BROWSE_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L")
        )
    }

    @Test
    fun `the cold-start pass still resolves the union in one pass and rewrites the cache`() {
        val full = block("private suspend fun resolveCatalogEntries() {", "private suspend fun resolveWithRetry(")
        assertTrue(full.contains("browseUnionOrderFor(\"keywords\")"))
        assertTrue(full.contains("browseUnionOrderFor(\"collections\")"))
        assertTrue(full.contains("saveBrowseCatalogCache(allKeywords, allCollections)"))
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
    }

    private val viewModel: String by lazy { source(VIEW_MODEL) }

    private fun loadBlock(): String =
        block("private fun loadBrowseCatalogCache() {", "private fun readBrowseCatalogCache(")

    private fun targetedBlock(): String =
        block("private suspend fun resolveMissingCatalogEntries(", "private suspend fun resolveMissingNames(")

    private fun missingNamesBlock(): String =
        block("private suspend fun resolveMissingNames(", "// Browse-catalog disk cache:")

    /**
     * Resolves `…/src/main/java` from the test's working directory, which is the
     * module dir under Gradle (`app/`) but the repo root under some runners.
     * Walking up covers both; a miss is loud rather than a silent skip, because
     * a green run that read nothing is worse than no test.
     */
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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The text from [startMarker] up to the first [endMarker] after it. */
    private fun block(startMarker: String, endMarker: String): String {
        val start = viewModel.indexOf(startMarker)
        assertTrue("marker missing: $startMarker", start >= 0)
        val end = viewModel.indexOf(endMarker, start + startMarker.length)
        assertTrue("end marker missing after $startMarker: $endMarker", end >= 0)
        return viewModel.substring(start, end)
    }
}
