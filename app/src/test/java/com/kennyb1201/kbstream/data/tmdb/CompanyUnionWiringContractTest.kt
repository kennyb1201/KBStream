package com.kennyb1201.kbstream.data.tmdb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A studio chip's rails discover by its PRIMARY TMDB company id, so the union
 * in [COMPANY_ID_UNIONS] only helps if every rail path actually asks for it.
 *
 * The two drops that do not fail anything else:
 *
 *  - the request is one id again (the parameter is an Int, or a rail forgets
 *    the resolved value and passes the chip id), which is the original bug —
 *    DC's page shows the 2019 animated films and nothing since;
 *  - the cache key stays on the primary id, so a unioned page and a single-id
 *    page are filed under the same key and the first fetch wins;
 *  - the genre chip re-narrows to the primary id, which hides the widened
 *    catalog as soon as one genre is picked.
 *
 * Header, logo and detail must keep the primary id, so the union has exactly
 * one call site per file and none in the identity path.
 */
class CompanyUnionWiringContractTest {

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

    private companion object {
        const val API = "com/kennyb1201/kbstream/data/tmdb/TmdbApiService.kt"
        const val RAILS = "com/kennyb1201/kbstream/data/tmdb/TmdbRailPages.kt"
        const val REPOSITORY = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
    }

    @Test
    fun `both company discover queries carry the pipe-joined union`() {
        val api = source(API)
        assertEquals(
            "discoverMovieByCompany and discoverTvByCompany both bind with_companies; " +
                "either one still taking a single Int re-narrows that media type",
            2,
            occurrences(api, "@Query(\"with_companies\") companyIds: String")
        )
        assertTrue(
            "a single-id with_companies binding must be gone",
            !api.contains("@Query(\"with_companies\") companyId: Int")
        )
    }

    @Test
    fun `a company rail resolves its union once and keys the cache by it`() {
        val rails = source(RAILS)
        assertEquals(
            "companyPage is the one place the chip's id is widened; resolving " +
                "per item would let the request and the cache key disagree",
            1,
            occurrences(rails, "resolveCompanyUnion(")
        )
        assertTrue(
            "the resolved union must be reused for the cache key",
            rails.contains("val companyIds = resolveCompanyUnion(companyId)")
        )
        assertTrue(
            "the cache key must use the union, or a unioned page collides with " +
                "a single-id page for the same rail",
            rails.contains("cacheKey = \"company|\$companyIds|\$title|\$lang\"") &&
                !rails.contains("cacheKey = \"company|\$companyId|\$title|\$lang\"")
        )
        assertTrue(
            "the union must be resolved before the per-page fetcher is handed it",
            rails.indexOf("val companyIds = resolveCompanyUnion(companyId)") in
                1..<rails.indexOf("companyPageItems(repo, companyIds, title, p, lang)")
        )
    }

    @Test
    fun `every one of the six company rails passes the joined value`() {
        val rails = source(RAILS)
        assertTrue(
            "companyPageItems must accept the joined string",
            rails.contains("companyIds: String,")
        )
        assertEquals(
            "MOVIES/SERIES x RECENT/POPULAR/TOP RATED all widen together",
            6,
            occurrences(rails, "companyIds = companyIds,")
        )
        assertTrue(
            "no rail may fall back to the bare company id",
            !rails.contains("companyId = companyId,")
        )
    }

    @Test
    fun `a genre chip on the DC page stays widened`() {
        val repository = source(REPOSITORY)
        assertTrue(
            "the cross-genre path must resolve the union too, or the genre chip " +
                "re-narrows to 9993 and the missing films come back",
            repository.contains("base.kind == \"company\" -> resolveCompanyUnion(base.id)")
        )
        assertTrue(
            "the old single-id genre path must be gone",
            !repository.contains("base.kind == \"company\" -> base.id.toString()")
        )
    }

    @Test
    fun `header, logo and detail keep the primary id`() {
        val repository = source(REPOSITORY)
        assertEquals(
            "the union belongs to the rails only; the brand's identity (logo, " +
                "detail, header) must keep the chip's primary id",
            1,
            occurrences(repository, "resolveCompanyUnion(")
        )
    }
}
