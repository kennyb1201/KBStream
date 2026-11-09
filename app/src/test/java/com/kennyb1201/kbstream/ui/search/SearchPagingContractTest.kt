package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Titles rail's paging, pinned at the sites this tree cannot unit-test
 * directly: the Retrofit endpoint, the response model, the repository seam, the
 * ViewModel's page-1/next-page wiring and the two rails' end-of-row trigger.
 *
 * The paging RULES themselves are pure and are tested for real in
 * [TitlesPagingTest]; what is checked here is that the production code is wired
 * to them - the endpoints take a page, the totals reach the model, page 1 goes
 * through the same absorb as every later page, and both screens ask for the next
 * page only when the row reaches its end.
 */
class SearchPagingContractTest {

    private fun source(path: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + "src/main/java/" + path)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found walking up from " + System.getProperty("user.dir"))
    }

    private val api = source("com/kennyb1201/kbstream/data/tmdb/TmdbApiService.kt")
    private val models = source("com/kennyb1201/kbstream/data/tmdb/TmdbModels.kt")
    private val repository = source("com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt")
    private val viewModel = source("com/kennyb1201/kbstream/ui/search/SearchViewModel.kt")
    private val paging = source("com/kennyb1201/kbstream/ui/search/TitlesPaging.kt")
    private val view = source("com/kennyb1201/kbstream/ui/search/TitlesView.kt")
    private val tvScreen = source("com/kennyb1201/kbstream/ui/search/SearchScreen.kt")
    private val hero = source("com/kennyb1201/kbstream/ui/search/SearchScreenPart2.kt")
    private val phoneScreen = source("com/kennyb1201/kbstream/ui/mobile/SearchScreenMobile.kt")

    @Test
    fun `both title searches take a page`() {
        // Scoped to the two signatures: the service has plenty of other paged
        // endpoints, and their page parameters say nothing about this rail.
        val movie = api.substringAfter("suspend fun searchMovie(")
            .substringBefore("): TmdbSearchTitleResponse")
        val tv = api.substringAfter("suspend fun searchTv(")
            .substringBefore("): TmdbSearchTitleResponse")

        listOf("search/movie" to movie, "search/tv" to tv).forEach { (name, signature) ->
            assertEquals(
                "$name needs exactly one page parameter; a defaulted one keeps " +
                    "every existing caller on page 1",
                1,
                Regex("""@Query\("page"\) page: Int = 1""").findAll(signature).count()
            )
        }
        // The keyword search already had one and is the shape both copies.
        assertTrue(
            api.substringAfter("suspend fun searchKeyword(")
                .substringBefore("): TmdbSearchKeywordResponse")
                .contains("""@Query("page") page: Int = 1""")
        )
    }

    @Test
    fun `the title response models the totals, like the keyword response does`() {
        // Up to the class's closing paren at column 0: the field annotations
        // carry parens of their own.
        val response = models.substringAfter("data class TmdbSearchTitleResponse(")
            .substringBefore("\n)")
        assertTrue(
            "total_results is what the hero's 'of N' reads",
            response.contains("""@Json(name = "total_results")""") &&
                response.contains("totalResults")
        )
        assertTrue(
            "total_pages is what says whether another page exists",
            response.contains("""@Json(name = "total_pages")""") &&
                response.contains("totalPages")
        )
    }

    @Test
    fun `the repository pages, and the list-shaped seams are page 1 of it`() {
        assertTrue(
            repository.contains("suspend fun searchMoviesPage(query: String, page: Int = 1): TmdbTitlePage?") &&
                repository.contains("suspend fun searchTvPage(query: String, page: Int = 1): TmdbTitlePage?")
        )
        assertTrue(
            "a page keeps FAILURE distinct from an empty answer, or a page-2 " +
                "outage would read as the end of the list",
            repository.substringAfter("suspend fun searchMoviesPage(")
                .substringBefore("suspend fun searchTvPage(")
                .contains("?: return null")
        )
        assertTrue(
            "the existing list-shaped calls stay, as page 1, so every other " +
                "caller is untouched",
            repository.contains("searchMoviesPage(query, page = 1)?.items") &&
                repository.contains("searchTvPage(query, page = 1)?.items")
        )
        assertTrue(
            "the digital-release filter runs per page inside the page seam, " +
                "exactly as it ran on the only page before",
            repository.substringAfter("suspend fun searchMoviesPage(")
                .substringBefore("suspend fun searchTvPage(")
                .contains("filterByHomeAvailability(response.results)")
        )
    }

    @Test
    fun `page 1 and every later page go through the same paging state`() {
        assertTrue(
            "page 1 is the first absorb, not a separate path: that is what " +
                "makes dedupe and the counters hold for the first page too",
            viewModel.contains("titlesPaging = TitlesPagingState().absorb(") &&
                viewModel.contains("number = 1,")
        )
        assertTrue(
            "the rail's cards are published FROM the paging state",
            viewModel.contains("_results.value = visibleTitles()")
        )
        assertTrue(
            "a load-more exists and is a no-op until the rail can take one",
            viewModel.contains("fun loadMoreTitles()") &&
                viewModel.contains("if (!titlesPaging.hasMore) return") &&
                viewModel.contains("if (_titlesLoadingMore.value) return")
        )
        assertTrue(
            "a page that failed does not advance the pager, and the rail keeps " +
                "what it has",
            viewModel.contains("if (moviesPage == null && showsPage == null) {") &&
                viewModel.contains("titlesPaging = titlesPaging.failed(TITLES_PAGE_UNAVAILABLE)")
        )
    }

    @Test
    fun `a new query resets paging and cancels the page in flight`() {
        val newQueryReset = viewModel.substringAfter("fun search(query: String) {")
            .substringBefore("searchJob = viewModelScope.launch")
        assertTrue(
            "the in-flight page is cancelled, and the token invalidated, so it " +
                "cannot append itself to the next query",
            newQueryReset.contains("titlesGeneration++") &&
                newQueryReset.contains("titlesPageJob?.cancel()") &&
                newQueryReset.contains("titlesPaging = TitlesPagingState()") &&
                newQueryReset.contains("titlesQuery = null")
        )
        assertTrue(
            "and the page load itself re-checks the token before publishing",
            viewModel.contains("if (generation != titlesGeneration) return@launch")
        )
        assertTrue(
            "a profile switch drops the pages too - they belong to the profile " +
                "that searched for them",
            viewModel.substringAfter("fun resetSearchState() {")
                .substringBefore("fun ")
                .contains("titlesPaging = TitlesPagingState()")
        )
    }

    @Test
    fun `the cap is gone, and the hero says how much of the total is showing`() {
        assertFalse(
            "the invisible 24-title cap is what the field report is about",
            viewModel.contains("MAX_TITLE_RESULTS")
        )
        assertTrue(
            "the rail's count compares what is loaded with the service's total - " +
                "the wording lives in titleCountLabel (tested in TitlesViewTest), so " +
                "the two heroes cannot drift apart",
            hero.contains("add(titleCountLabel(catalogCount, titleTypeFilter, titlesTotal))") &&
                view.contains("total > count -> \"Showing \$count of \$total titles\"")
        )
        assertTrue(
            "and the hero is handed that total by the screen",
            tvScreen.contains("titlesTotal = titlesTotal,")
        )
        assertTrue(
            "the phone's hero line makes the same statement, through the same helper",
            phoneScreen.contains("titleCountLabel(")
        )
    }

    @Test
    fun `reaching the end of either Titles rail asks for the next page`() {
        listOf("TV" to tvScreen, "phone" to phoneScreen).forEach { (which, src) ->
            assertTrue(
                "$which rail uses the app's ONE infinite-scroll sentinel, so it " +
                    "prefetches as far from the end as every other paged surface",
                src.contains("InfiniteScrollEffect(") &&
                    src.contains("onLoadMore = viewModel::loadMoreTitles")
            )
            assertTrue(
                "$which rail only asks when there IS a next page and the last " +
                    "one did not fail (the tail chip is the retry)",
                src.contains("hasMore = titlesHaveMore && titlesPageError == null")
            )
            assertTrue(
                "$which rail draws a tail item, so a failure has a reachable retry",
                src.contains("if (titlesLoadingMore || titlesPageError != null) {") &&
                    src.contains("""item(key = "titles_tail")""")
            )
        }
        assertTrue(
            "the tail is an item, not an overlay, so the D-pad can reach its retry",
            hero.contains("internal fun SearchRailTail(")
        )
    }

    @Test
    fun `paging stops at the service ceiling`() {
        assertTrue(
            "TMDB answers 400 past page 500, so the rail must stop there",
            paging.contains("internal const val TMDB_SEARCH_PAGE_LIMIT = 500") &&
                paging.contains("minOf(totalPages, TMDB_SEARCH_PAGE_LIMIT)")
        )
    }
}
