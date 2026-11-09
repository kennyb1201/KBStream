package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wave that could not be MADE is not a wave that found nothing.
 *
 * `failedTmdbWaves` used to count only thrown exceptions, and the person /
 * studio / collection searches swallowed everything into `emptyList()` - a
 * missing TMDB key most of all. A keyless build therefore reported zero failed
 * waves, the all-fail error card never appeared, and the screen said "No matches
 * found." for a search that never ran: precisely the failure C4 was built to
 * stop, reached through the other door.
 *
 * The repository now exposes an `OrNull` variant per wave, mirroring the title
 * pair that already had one, and the view model's wave wrapper counts a null as
 * failed while keeping an empty list a real answer. Both halves are pinned here;
 * the repository itself needs a live API, so the split is pinned as source and
 * the counting rule as the one condition it lives in.
 */
class SearchWaveFailureContractTest {

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

    /** From [start] up to the first [end] after it, or the file's end. */
    private fun slice(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("start marker missing: $start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        return text.substring(from, if (to > from) to else text.length)
    }

    private fun repository() = source(REPOSITORY)

    private fun viewModel() = source(VIEW_MODEL)

    /** The list-wave wrapper inside `search()`. */
    private fun waveWrapper() = slice(
        viewModel(),
        "suspend fun <T> tmdbWave(",
        "\n                }"
    )

    @Test
    fun `every list wave answers null when the request could not be made`() {
        val repo = repository()
        // The four list waves that used to fold a failure into "no matches".
        listOf(
            "searchPersonOrNull",
            "searchCompanyOrNull",
            "searchCollectionOrNull",
            "searchKeywordsOrNull"
        ).forEach { name ->
            val body = slice(repo, "suspend fun $name(", "\n    }")
            assertTrue(
                "$name must be nullable: an unmakeable request has to be " +
                    "distinguishable from an empty answer",
                body.contains("suspend fun $name(query: String): List<") &&
                    body.contains(">? {")
            )
            assertTrue(
                "$name must answer null with no TMDB key rather than an empty list",
                body.contains("if (apiKey.isBlank()) return null")
            )
            assertTrue(
                "$name must answer null when the call itself fails",
                body.contains(".getOrNull()")
            )
            assertFalse(
                "$name must not swallow a failure into emptyList()",
                body.contains("getOrDefault(emptyList())")
            )
        }
        // The two title waves the rail asks for already had the split.
        listOf("searchMoviesPage", "searchTvPage").forEach { name ->
            assertTrue(
                "$name must keep returning null for an unmakeable page",
                repo.contains("suspend fun $name(query: String, page: Int = 1): TmdbTitlePage? {")
            )
        }
    }

    @Test
    fun `the non-null shapes other callers use still exist`() {
        // CatalogBuilder and friends want a list; they must keep getting one.
        val repo = repository()
        listOf("searchPerson", "searchCompany", "searchCollection", "searchKeywords").forEach { name ->
            assertTrue(
                "$name must stay for callers that do not care about the split",
                repo.contains("suspend fun $name(query: String): List<") &&
                    repo.contains("${name}OrNull(query).orEmpty()")
            )
        }
    }

    @Test
    fun `every wave in search asks for the failure-distinguishing variant`() {
        val vm = viewModel()
        listOf(
            "searchPersonOrNull(normalized)",
            "searchCompanyOrNull(normalized)",
            "searchCollectionOrNull(normalized)",
            "searchKeywordsOrNull(normalized)",
            "searchMoviesPage(normalized, page = 1)",
            "searchTvPage(normalized, page = 1)"
        ).forEach { call ->
            assertTrue("the wave must use $call", vm.contains("tmdbRepository.$call"))
        }
        listOf(
            "searchPerson(normalized)",
            "searchCompany(normalized)",
            "searchCollection(normalized)",
            "searchKeywords(normalized)"
        ).forEach { call ->
            assertFalse(
                "no wave may fold a failure into emptiness: tmdbRepository.$call",
                vm.contains("tmdbRepository.$call")
            )
        }
    }

    @Test
    fun `a null counts as a failed wave and an empty answer does not`() {
        val wrapper = waveWrapper()
        assertTrue(
            "a null answer is a wave that could not be made",
            wrapper.contains("outcome.isFailure || results == null")
        )
        assertTrue(
            "and it has to reach the counter the all-fail card reads",
            wrapper.contains("failedTmdbWaves.incrementAndGet()")
        )
        assertFalse(
            "an EMPTY list is a real answer - the zero-hits path must not be " +
                "counted as a failure, or a query that found nothing would show " +
                "the error card",
            wrapper.contains("isEmpty()")
        )
        assertTrue(
            "and the wave still hands the caller a list",
            wrapper.contains("results.orEmpty()")
        )
    }

    @Test
    fun `the error card needs every wave to fail`() {
        val vm = viewModel()
        assertTrue(
            "six waves, and the count must say six",
            vm.contains("const val TMDB_WAVE_COUNT = 6")
        )
        assertTrue(
            "the card is drawn only when all of them failed",
            vm.contains("if (failedTmdbWaves.get() == TMDB_WAVE_COUNT)")
        )
        // A keyless build fails every wave, so the card is what it gets - but
        // only if all six waves are the null-aware ones.
        val waves = slice(
            vm,
            "val tmdbMoviesDeferred = async {",
            "// The de-dupe index the add-on rails consult"
        )
        assertEquals(
            "six waves, no more and no fewer",
            6,
            waves.split("Deferred = async {").size - 1
        )
    }

    private companion object {
        const val REPOSITORY = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
    }
}
