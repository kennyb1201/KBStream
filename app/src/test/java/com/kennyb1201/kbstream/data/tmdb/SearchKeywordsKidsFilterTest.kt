package com.kennyb1201.kbstream.data.tmdb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kids Mode on the search screen's Keywords rail, and the same leak on Detail
 * (spec 2026-11-18, F1/F2).
 *
 * The report: "adult keywords showed up in the kids profile search results" -
 * and, once the first fix (drop the rail) was proposed, "but i want there to be
 * kids keywords in search". The rail is the one published with no kids check at
 * all, on the rule that "a NAME is not content" (which is true for a studio and
 * for a person's name). It is not true for a keyword: a keyword IS a content
 * descriptor, and its own spelling can be adult, so the name itself leaked even
 * though the Tag page behind a tap filters the titles underneath it.
 *
 * So the rail is KEPT and vetted the way collections are: a keyword is shown to a
 * kids profile when at least one of its TITLES passes the ceiling. The stated
 * limit is the collections rule's - an adult-named keyword attached to a clean
 * title still shows, because the name itself is not vetted (a blocklist would be
 * unmaintainable and evadable).
 *
 * Detail cannot use that rule (the viewed title is one of each keyword's titles
 * and it passed), so its row is dropped while a ceiling is active.
 *
 * The vetting is a network fan-out on an Android singleton, so the wiring is
 * pinned as source, like this folder's neighbour [SearchCollectionsKidsFilterTest]
 * does for the collection rule it mirrors.
 */
class SearchKeywordsKidsFilterTest {

    // ── F1: the rule, and that it mirrors collections ─────────────────────

    @Test
    fun `the rail is vetted by its titles, the way a collection is by its parts`() {
        val repo = source(REPO)
        val filter = block(repo, "suspend fun kidsFilterKeywords(")
        assertTrue(
            "the filter is the repository's own, beside the collection one",
            filter.isNotEmpty()
        )
        assertTrue(
            "keep the keyword when ANY of its titles passes the ceiling - the " +
                "collection rule, one rail over",
            filter.contains("sample.any { (tmdbId, mediaType) -> kidsAllowed(tmdbId, mediaType) }")
        )
        assertTrue(
            "on the keyword's popular end, not all 40 titles: this runs behind the " +
                "6-permit gate the rails share, and sampling can only DROP a keyword",
            filter.contains("val sample = titles.take(keywordTitleSample)") &&
                repo.contains("private val keywordTitleSample = 12")
        )
        assertTrue(
            "and where the titles cannot be fetched, the known-unknown rule: kept " +
                "only on the loosest ceiling",
            filter.contains("ceiling == KidsMode.CEIL_PG13")
        )
        assertTrue(
            "it no-ops for an adult profile, so no keywords are vetted there",
            repo.contains("if (keywords.isEmpty() || kidsMaxAge() == null) return keywords")
        )
        val collections = block(repo, "suspend fun kidsFilterCollections(")
        listOf("async {", "}.awaitAll().filterNotNull()").forEach { shape ->
            assertTrue(
                "the two filters fan out the same way ($shape)",
                filter.contains(shape) && collections.contains(shape)
            )
        }
        assertTrue(
            "and both go through the shared availability gate",
            filter.contains("availabilitySemaphore.withPermit {") &&
                collections.contains("availabilitySemaphore.withPermit {")
        )
    }

    @Test
    fun `a keyword's titles come from both media types`() {
        val repo = source(REPO)
        val titles = block(repo, "private suspend fun keywordTitles(")
        assertTrue(
            "movies and series are both asked - a keyword's titles span both",
            titles.contains("api.getKeywordMovies(keywordId, apiKey)") &&
                titles.contains("api.getKeywordTv(keywordId, apiKey)")
        )
        assertTrue(
            "in parallel, so one keyword costs one round trip",
            titles.contains("val movies = async {") && titles.contains("val series = async {")
        )
        assertTrue(
            "under the spellings the whole ceiling family agrees on",
            titles.contains("result.id to \"movie\"") && titles.contains("result.id to \"series\"")
        )
        assertTrue(
            "a dead endpoint is an empty side, and the caller's known-unknown rule " +
                "then decides - never a silent pass",
            titles.contains("runCatchingCancellable { api.getKeywordMovies(") &&
                titles.contains(".getOrDefault(emptyList())")
        )
    }

    @Test
    fun `the two endpoints exist and accept include_adult`() {
        val api = source(API)
        listOf("keyword/{keyword_id}/movies" to "getKeywordMovies", "keyword/{keyword_id}/tv" to "getKeywordTv")
            .forEach { (path, method) ->
                val declaration = api.substringAfter("@GET(\"$path\")").substringBefore("@GET(")
                assertTrue(
                    "$method must be the keyword's own list endpoint",
                    declaration.contains("suspend fun $method(")
                )
                assertTrue(
                    "$method takes include_adult - THESE endpoints accept it",
                    declaration.contains("@Query(\"include_adult\") includeAdult: Boolean = false")
                )
                assertTrue(
                    "keyed on the keyword's id, as a path parameter",
                    declaration.contains("@Path(\"keyword_id\") keywordId: Int")
                )
            }
    }

    // ── F3 / S6: search/keyword cannot be the fix ─────────────────────────

    @Test
    fun `searchKeyword carries no include_adult`() {
        val api = source(API)
        val keywordSearch = api.substringAfter("@GET(\"search/keyword\")")
            .substringBefore("// A KEYWORD'S OWN TITLES")
        assertTrue(
            "the search is still there",
            keywordSearch.contains("suspend fun searchKeyword(")
        )
        assertFalse(
            "TMDB's /search/keyword takes query + page only: include_adult there " +
                "would be a silent no-op and mislead the next reader (F3)",
            keywordSearch.contains("include_adult")
        )
        assertFalse(
            "and the repository must not pass one either",
            block(source(REPO), "suspend fun searchKeywordsOrNull(").contains("include_adult") ||
                block(source(REPO), "suspend fun searchKeywords(").contains("include_adult")
        )
    }

    // ── F1: the publish site, and the cap after the filter ────────────────

    @Test
    fun `a kids profile keeps the rail, filtered before the cap`() {
        val vm = source(SEARCH)
        val publish = vm.substringAfter("_keywordResults.value =")
            .substringBefore("// Remember the answer now that every rail is published")
        assertTrue(
            "the adult path is untouched: the wave's own list, distinct, capped (S3)",
            publish.contains("keywordResults.distinctBy { it.id }.take(MAX_KEYWORD_RESULTS)")
        )
        assertTrue(
            "and a kids profile is vetted instead of dropped (the revised F1)",
            publish.contains("tmdbRepository.kidsFilterKeywords(keywordResults)")
        )
        val vetted = publish.indexOf("kidsFilterKeywords(")
        val cap = publish.indexOf(".take(MAX_KEYWORD_RESULTS)", vetted.coerceAtLeast(0))
        assertTrue(
            "the cap runs AFTER the filter, so a strict profile still sees eight (S2)",
            vetted in 0 until cap
        )
        assertTrue(
            "both branches go through the shared count",
            Regex("take\\(MAX_KEYWORD_RESULTS\\)").findAll(publish).count() == 2
        )
    }

    @Test
    fun `the Tag page is still the backstop`() {
        // F4: a keyword reached another way still has its titles ceiling-checked
        // by the page that lists them - that is why keeping the rail is safe.
        val railPages = source(RAIL_PAGES)
        assertTrue(
            "the keyword page still funnels through the ceiling-checked rail tail",
            railPages.contains("finishDeepRailPage(")
        )
        val repo = source(REPO)
        assertTrue(
            "and the page-level filter it calls is untouched",
            repo.contains("internal suspend fun kidsFilterPage(page: TagRailPage): TagRailPage") &&
                repo.contains("kidsFilterItems(page.items)")
        )
    }

    // ── F2: Detail's keywords row is dropped on a kids profile ───────────

    @Test
    fun `Detail drops its keywords row while a kids ceiling is set`() {
        val vm = source(DETAIL_VM)
        assertTrue(
            "the ceiling reaches the screen reactively, so a profile switch while " +
                "the page is open cannot leave the row up",
            vm.contains("val kidsCeiling: StateFlow<Int?> = ProfileManager.activeProfile") &&
                vm.contains(".map { profile -> profile?.kidsMaxAge }")
        )
        val screen = source(DETAIL)
        assertTrue(
            "the screen collects it",
            screen.contains("val kidsCeiling by viewModel.kidsCeiling.collectAsStateWithLifecycle()")
        )
        assertTrue(
            "and the row's own list is empty while it is set - the presence flag and " +
                "the render both read this one value, so the row and its chain entry " +
                "cannot disagree",
            screen.contains(
                "val keywords = remember(tmdbDetail, kidsCeiling) {\n" +
                    "                if (kidsCeiling != null) emptyList() else tmdbDetail?.keywords?.list().orEmpty()\n" +
                    "            }"
            )
        )
        assertTrue(
            "the presence flag is still derived from that list",
            screen.contains("hasKeywordsRow = keywords.isNotEmpty(),")
        )
    }

    @Test
    fun `the phone detail drops its keyword chips too`() {
        // The same title page, the same ViewModel, one form factor over: the
        // chips print the same names, so the same rule has to hold there or a
        // kids profile would only leak on a phone.
        val mobile = source(DETAIL_MOBILE)
        assertTrue(
            "the phone collects the ceiling from the shared view model",
            mobile.contains("val kidsCeiling by viewModel.kidsCeiling.collectAsStateWithLifecycle()")
        )
        assertTrue(
            "and empties the keyword chips, keeping the genres (a fixed vocabulary)",
            mobile.contains("val keywords = remember(tmdbDetail, kidsCeiling) {") &&
                mobile.contains(
                    "if (kidsCeiling != null) emptyList() else tmdbDetail?.keywords?.list().orEmpty()"
                )
        )
        assertTrue(
            "its tag row is still gated on the two lists",
            mobile.contains("if (tmdbGenres.isNotEmpty() || keywords.isNotEmpty()) {")
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** One function's source, by matching its declaration and balancing braces. */
    private fun block(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        require(at >= 0) { "missing: $declaration" }
        val bodyStart = source.indexOf("{", at)
        require(bodyStart > at) { "no body: $declaration" }
        var depth = 0
        for (i in bodyStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        throw AssertionError("unbalanced: $declaration")
    }

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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

    private companion object {
        const val REPO = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
        const val API = "com/kennyb1201/kbstream/data/tmdb/TmdbApiService.kt"
        const val SEARCH = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
        const val RAIL_PAGES = "com/kennyb1201/kbstream/data/tmdb/TmdbRailPages.kt"
        const val DETAIL_VM = "com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt"
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val DETAIL_MOBILE = "com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt"
    }
}
