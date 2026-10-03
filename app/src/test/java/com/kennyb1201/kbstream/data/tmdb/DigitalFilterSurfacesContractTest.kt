package com.kennyb1201.kbstream.data.tmdb

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings digital-release switch reaches every surface that lists a
 * catalog or a TMDB row, not just Home's rails.
 *
 * Reported: an upcoming family movie ("Forgotten Island") showed on Home's kids
 * rails, and the same class of title could still appear in a KB folder or in
 * add-on search, because only some call sites ran the filter. The pieces are
 * silent when dropped - a loader that stops calling the filter still compiles
 * and still renders, it just shows a title with no home release - so this reads
 * the sources the way BrowseCatalogPublishContractTest does.
 */
class DigitalFilterSurfacesContractTest {

    private companion object {
        const val TMDB_REPOSITORY = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
        const val KB_CONTENT_LOADER = "com/kennyb1201/kbstream/data/kb/KBContentLoader.kt"
        const val HOME_VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val SEARCH_VIEW_MODEL = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"

        const val SHARED_FILTER = "filterByHomeAvailabilityById("
        const val SWITCH = "isDigitalFilterEnabled()"
    }

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

    @Test
    fun `the raw TMDB discover loader used by the kids rails and KB folders filters`() {
        val repository = source(TMDB_REPOSITORY)
        assertTrue(
            "discoverKB is the raw loader behind the kids Home rails and every " +
                "KB folder's TMDB discover source; it must apply the filter itself, " +
                "or those surfaces show un-released titles again",
            repository.contains("fun discoverKB(") &&
                repository.contains("filterByHomeAvailability")
        )
    }

    @Test
    fun `one shared implementation serves the non-Home surfaces`() {
        val repository = source(TMDB_REPOSITORY)
        assertTrue(
            "TmdbRepository must expose the id-keyed filter the other surfaces share",
            repository.contains("fun <T> filterByHomeAvailabilityById(")
        )
        assertTrue(
            "the shared filter must gate on the Settings switch itself, since its " +
                "callers are spread across the app",
            repository.contains(SWITCH)
        )
    }

    @Test
    fun `Home, the KB folders and add-on search all call the shared filter`() {
        assertTrue(
            "HomeViewModel must filter its rails through the shared helper",
            source(HOME_VIEW_MODEL).contains(SHARED_FILTER)
        )
        assertTrue(
            "a KB folder rail carries add-on and TMDB rows; the loader must filter them",
            source(KB_CONTENT_LOADER).contains(SHARED_FILTER)
        )
        assertTrue(
            "add-on search hits must be filtered like the TMDB search hits already are",
            source(SEARCH_VIEW_MODEL).contains(SHARED_FILTER)
        )
    }
}
