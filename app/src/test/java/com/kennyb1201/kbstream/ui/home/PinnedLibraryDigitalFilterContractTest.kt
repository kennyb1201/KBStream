package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The digital-release switch reaches the Library's two surfaces.
 *
 * The field report (Kenny, 2026-11-20): The Hunger Games: Sunrise on the
 * Reaping - theatrical that day, no digital date - drew on Home inside a pinned
 * Library list with the filter switched on, and on no other surface. Catalog
 * rails get the availability pass at publish and the pinned "Top … Today" rails
 * get it by deferral, but [rememberLibraryHomeRailsState] - the single loader
 * behind both Home screens - applied the kids ceiling and nothing else. The
 * Library screen had the same gap one level down.
 *
 * These assertions keep both entries on the SHARED pass, and keep the ceiling
 * from short-circuiting it.
 */
class PinnedLibraryDigitalFilterContractTest {

    private companion object {
        const val LOADER =
            "com/kennyb1201/kbstream/ui/home/LibraryHomeRailLoader.kt"
        const val LIBRARY_VM =
            "com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt"
        const val RELEASE_FILTER = "filterByHomeAvailabilityById("
        const val KIDS_METAS = "kidsFilterMetas("
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

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    /** The function body, from its opening brace to the next 4-space member. */
    private fun body(signature: String, from: String): String {
        val start = from.indexOf(signature)
        assertTrue("source missing: $signature", start >= 0)
        val brace = from.indexOf('{', start)
        val rest = from.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `the pinned-rail loader asks the shared verdict pass after the ceiling`() {
        val loader = source(LOADER)

        val kids = loader.indexOf("tmdb.$KIDS_METAS")
        val release = loader.indexOf("tmdb.$RELEASE_FILTER")

        assertTrue("the kids ceiling is unchanged", kids >= 0)
        assertTrue(
            "a pinned Library list must take the digital-release pass too, or a " +
                "theatrical-only movie draws on Home while every catalog rail " +
                "hides it",
            release > kids
        )
        assertTrue(
            "the raw-id entry point, because these rows carry an IMDb id or " +
                "tmdb:<n> - not a second verdict path",
            loader.contains("id = { meta -> meta.id }") &&
                loader.contains("type = { meta -> meta.type }")
        )
        assertTrue(
            "the row's own year must reach the age gate, or the pass pays a " +
                "detail fetch per card",
            loader.contains("releasedYear = { meta -> meta.yearOrNull }")
        )
        assertTrue(
            "a list that filters to empty must still drop its rail rather than " +
                "draw an empty heading",
            loader.contains("if (items.isNotEmpty()) rails += LibraryHomeRailUi(pin, items)")
        )
        assertTrue(
            "one loader for both Home screens - no per-screen copy",
            occurrences(loader, RELEASE_FILTER) == 1
        )
    }

    @Test
    fun `the Library screen folds the release pass into its one row filter`() {
        val viewModel = source(LIBRARY_VM)
        val filter = body("private suspend fun kidsFiltered(", viewModel)

        assertTrue(
            "the release pass must sit inside the entry every row set goes " +
                "through, not at one call site",
            filter.contains("releaseFiltered(")
        )
        assertTrue(
            "and the kids ceiling must no longer short-circuit it: with no " +
                "ceiling set, kidsFiltered returned the rows untouched and the " +
                "digital filter never ran",
            !filter.contains("if (tmdbRepository.kidsMaxAge() == null || items.isEmpty())")
        )
        assertTrue(
            "one row-filter entry, one verdict path",
            occurrences(viewModel, RELEASE_FILTER) == 1
        )

        val release = body("private suspend fun releaseFiltered(", viewModel)

        assertTrue(
            "keyed off the row's own id and media type, with its year for the " +
                "age gate",
            release.contains("id = { row -> row.navigationId.orEmpty() }") &&
                release.contains("releasedYear = { row -> row.year }")
        )
        assertTrue(
            "view-only: a filtered row is not removed from any list",
            release.contains(".filter") &&
                !release.contains("remove") &&
                !release.contains("store.")
        )
        assertTrue(
            "the surviving rows keep the viewer's sort order",
            release.contains("kept.toHashSet()") &&
                release.contains("row in keptSet")
        )
    }
}
