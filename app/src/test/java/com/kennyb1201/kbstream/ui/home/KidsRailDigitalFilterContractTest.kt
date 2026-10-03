package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-wide digital-release filter reaches the kids rails too.
 *
 * Reported: an upcoming family movie ("Forgotten Island", a 2026 DreamWorks
 * title with a theatrical run and no home release yet) sat in "Top Kids
 * Movies" while the filter the viewer had switched on hid the same title from
 * every add-on rail. The kids rails were the one path that opted out — they
 * built their page straight from a TMDB discover query and passed it only
 * through the kids-ceiling filter, with no `filterUpcoming` and no
 * [HomeViewModel.applyDigitalAvailabilityFilter], and pinned `hideUpcoming =
 * false` in the rail info besides.
 *
 * None of that fails to compile and none of it is reachable from a behavioural
 * test that cannot build a TV UI, which is why this reads the source the way
 * HomeBrowseShortcutRemovalContractTest and BrowseCatalogPublishContractTest
 * do.
 */
class KidsRailDigitalFilterContractTest {

    private companion object {
        const val HOME_VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"

        const val KIDS_DECL = "private suspend fun loadPinnedKidsRails("
        const val TOP_TODAY_DECL = "private suspend fun loadPinnedTopTodayRails("
        const val KIDS_CALL = "loadPinnedKidsRails("
        const val DIGITAL_FILTER = "applyDigitalAvailabilityFilter("
        const val UPCOMING_FILTER = "filterUpcoming("
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

    private fun bodyBetween(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("$from not found in the source", start >= 0)
        val end = src.indexOf(to, start + from.length)
        assertTrue("$to not found after $from, so the body is not bounded", end > start)
        return src.substring(start, end)
    }

    @Test
    fun `the kids rail applies the same digital and upcoming filters as every other rail`() {
        val src = source(HOME_VIEW_MODEL)
        val kids = bodyBetween(src, KIDS_DECL, TOP_TODAY_DECL)

        assertTrue(
            "loadPinnedKidsRails must take the app-wide hide-upcoming switch, " +
                "or it has no way to know the filter is on",
            kids.contains("hideUpcoming: Boolean")
        )
        assertTrue(
            "the kids rail must run the digital-release filter - without it an " +
                "upcoming family movie shows here while every add-on rail hides it",
            kids.contains(DIGITAL_FILTER)
        )
        assertTrue(
            "the kids rail must also drop future-dated titles, as the add-on " +
                "rails do",
            kids.contains(UPCOMING_FILTER)
        )
    }

    @Test
    fun `the kids rail is handed the switch rather than a hardcoded opt-out`() {
        val src = source(HOME_VIEW_MODEL)

        assertFalse(
            "the kids rail must not pin hideUpcoming = false: that was the " +
                "opt-out that let the reported title through",
            src.contains("hideUpcoming = false")
        )

        // The call site comes before the declaration in the file, so the first
        // occurrence is the call and its arguments.
        val at = src.indexOf(KIDS_CALL)
        assertTrue("the kids rail call site is missing", at >= 0)
        val call = src.substring(at, minOf(src.length, at + 200))
        assertTrue(
            "the call must pass the app-wide switch through, not drop it",
            call.contains("hideUpcoming")
        )
    }
}
