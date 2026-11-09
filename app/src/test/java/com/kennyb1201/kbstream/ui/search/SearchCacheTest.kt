package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-memory search cache.
 *
 * Retyping a query re-fired every wave plus the add-on probes even when the same
 * answer had been on screen seconds earlier. The cache is deliberately small and
 * deliberately forgetful: five queries, five minutes, memory only.
 *
 * The rules are pure, so they are exercised directly against an injected clock.
 * What a JVM cannot show - that the view model serves a hit instead of starting
 * the waves, and that a profile switch clears it - is pinned from source, the way
 * the rest of this screen's wiring is.
 */
class SearchCacheTest {

    private var now = 1_000_000L

    private fun cache(
        maxEntries: Int = 5,
        ttlMillis: Long = 5 * 60 * 1000L
    ) = SearchCache<List<String>>(maxEntries, ttlMillis) { now }

    @Test
    fun `a repeat inside the TTL is a hit`() {
        val cache = cache()
        cache.put("alien", listOf("tt1", "tt2"))

        assertEquals(listOf("tt1", "tt2"), cache.get("alien"))
    }

    @Test
    fun `an unasked query is a miss`() {
        val cache = cache()
        cache.put("alien", listOf("tt1"))

        assertNull("nothing was cached for this query", cache.get("heist"))
    }

    @Test
    fun `the answer expires five minutes after it was written`() {
        val cache = cache()
        cache.put("alien", listOf("tt1"))

        now += 5 * 60 * 1000L - 1
        assertEquals(
            "one millisecond before the TTL it is still the same question",
            listOf("tt1"),
            cache.get("alien")
        )

        now += 1
        assertNull("at the TTL the answer is stale and must be re-fetched", cache.get("alien"))
        assertEquals("and the expired entry is dropped, not kept around", 0, cache.size)
    }

    @Test
    fun `the last five queries survive and the sixth evicts the oldest`() {
        val cache = cache()
        listOf("one", "two", "three", "four", "five", "six").forEach { query ->
            cache.put(query, listOf(query))
            now += 1
        }

        assertEquals("the cap is five, whatever was asked", 5, cache.size)
        assertNull("the oldest written query made way", cache.get("one"))
        assertEquals(listOf("six"), cache.get("six"))
        assertEquals(
            "and the four in between are still answerable",
            listOf("two"),
            cache.get("two")
        )
    }

    @Test
    fun `re-writing a query refreshes it instead of duplicating it`() {
        val cache = cache()
        cache.put("alien", listOf("tt1"))
        now += 1_000
        cache.put("alien", listOf("tt2"))

        assertEquals("one entry, not two", 1, cache.size)
        assertEquals("and the newer answer wins", listOf("tt2"), cache.get("alien"))
    }

    @Test
    fun `the key ignores case and surrounding space`() {
        val cache = cache()
        cache.put(" Alien ", listOf("tt1"))

        assertEquals(
            "the same query typed with a shift held is the same query",
            listOf("tt1"),
            cache.get("ALIEN")
        )
    }

    @Test
    fun `clearing forgets every answer`() {
        val cache = cache()
        cache.put("alien", listOf("tt1"))
        cache.put("heist", listOf("tt2"))

        cache.clear()

        assertEquals(0, cache.size)
        assertNull(cache.get("alien"))
        assertNull(cache.get("heist"))
    }

    // ── the view model's use of it, pinned from source ───────────────────

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

    private fun slice(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("start marker missing: $start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        return text.substring(from, if (to > from) to else text.length)
    }

    private fun viewModel() = source(VIEW_MODEL)

    /** The hit path in `search()`: from the lookup to the early return it ends on. */
    private fun hitPath() = slice(
        viewModel(),
        "val cached = searchCache.get(normalized)",
        "\n            return"
    )

    @Test
    fun `a hit publishes the cached answer instead of firing the waves`() {
        val hit = hitPath()
        assertTrue(
            "the cached answer is put back on screen",
            hit.contains("restoreCachedSearch(normalized, cached)")
        )
        assertFalse(
            "and no TMDB wave may be started on that path",
            hit.contains("tmdbWave(") ||
                hit.contains("tmdbMoviesDeferred = async")
        )
        assertTrue(
            "the restore runs the query under its own name",
            viewModel().contains("private suspend fun restoreCachedSearch(")
        )
        assertTrue(
            "the add-on rails still arrive, de-duped against the cached titles",
            hit.contains("CompletableDeferred(cached.tmdbDedup)")
        )
    }

    @Test
    fun `an answer is cached once every rail is published`() {
        val vm = viewModel()
        assertTrue("the write exists", vm.contains("searchCache.put("))
        // After the rails, before the badge resolution: what was cached is what
        // the screen showed.
        val keyword = vm.indexOf("_keywordResults.value =")
        val write = vm.indexOf("searchCache.put(")
        val resolve = vm.indexOf("resolveTmdbTitles(_results.value)")
        assertTrue("the write must come after the rails are published", write > keyword)
        assertTrue("and before the badge pass rewrites nothing", resolve > write)
        assertTrue(
            "the Titles master list is what is stored, so the rail still pages",
            vm.contains("titles = titlesPaging.results")
        )
    }

    @Test
    fun `a profile switch clears the cache and a restart cannot restore it`() {
        val vm = viewModel()
        val reset = slice(vm, "fun resetSearchState() {", "\n    }")
        assertTrue(
            "one profile's answers must never be served to another",
            reset.contains("searchCache.clear()")
        )
        // Memory only: no prefs file, no serialization, nothing on disk that a
        // restart could read back.
        val cacheSource = source(CACHE)
        assertFalse("the cache must not persist", cacheSource.contains("prefs"))
        assertFalse("nor serialize itself", cacheSource.contains("Json"))
        assertFalse("nor open a file", cacheSource.contains("File("))
        assertTrue(
            "it holds five answers for five minutes, named as constants",
            vm.contains("const val MAX_CACHED_SEARCHES = 5") &&
                vm.contains("const val SEARCH_CACHE_TTL_MS = 5 * 60 * 1000L")
        )
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
        const val CACHE = "com/kennyb1201/kbstream/ui/search/SearchCache.kt"
    }
}
