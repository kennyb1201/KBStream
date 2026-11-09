package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Titles rail's type filter and sort, pinned at the sites a JVM run cannot
 * reach directly: the ViewModel's view state and resets, and the two screens'
 * chip rows.
 *
 * The RULES are tested for real in [TitlesViewTest]. What this checks is that
 * production is wired to them - the setters re-publish instead of re-fetching,
 * a new query resets the view, the chip rows exist on both surfaces with the
 * same labels, and the empty-type message is the filter's, not the screen's.
 */
class TitleFilterSortContractTest {

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

    private val viewModel = source("com/kennyb1201/kbstream/ui/search/SearchViewModel.kt")
    private val view = source("com/kennyb1201/kbstream/ui/search/TitlesView.kt")
    private val tvScreen = source("com/kennyb1201/kbstream/ui/search/SearchScreen.kt")
    private val hero = source("com/kennyb1201/kbstream/ui/search/SearchScreenPart2.kt")
    private val phoneScreen = source("com/kennyb1201/kbstream/ui/mobile/SearchScreenMobile.kt")

    @Test
    fun `the filter and sort are views of what is loaded, not new queries`() {
        assertTrue(
            "the published rail is the paged master put through the view",
            viewModel.contains("private fun visibleTitles(): List<SearchTitleResult> = sortTitles(") &&
                viewModel.contains("filterTitlesByType(titlesPaging.results, _titleTypeFilter.value)")
        )
        listOf(
            "fun setTitleTypeFilter(" to "fun setTitleSort(",
            "fun setTitleSort(" to "fun loadMoreTitles("
        ).forEach { (start, end) ->
            val body = viewModel.substringAfter(start).substringBefore(end)
            assertTrue(
                "$start must re-publish the rail",
                body.contains("publishTitles()")
            )
            assertFalse(
                "$start must NOT ask TMDB for anything: changing the view of a " +
                    "result set is not a new search",
                body.contains("tmdbRepository") ||
                    body.contains("searchMoviesPage") ||
                    body.contains("searchTvPage") ||
                    body.contains("titlesPaging = TitlesPagingState()")
            )
        }
    }

    @Test
    fun `the view settings are held per query and reset by a new one`() {
        val newQuery = viewModel.substringAfter("fun search(query: String) {")
            .substringBefore("searchJob = viewModelScope.launch")
        assertTrue(
            "a new query starts unfiltered",
            newQuery.contains("_titleTypeFilter.value = TitleTypeFilter.ALL")
        )
        assertTrue(
            "and in the rail's own order",
            newQuery.contains("_titleSort.value = TitleSort.BEST_MATCH")
        )
        assertTrue(
            "the profile switch drops them too - they describe one profile's query",
            viewModel.substringAfter("fun resetSearchState() {")
                .substringBefore("fun ")
                .contains("_titleTypeFilter.value = TitleTypeFilter.ALL")
        )
        assertTrue(
            "and they are state, not a preference: nothing persists them",
            !viewModel.contains("KEY_TITLE_FILTER") && !viewModel.contains("KEY_TITLE_SORT")
        )
    }

    @Test
    fun `both rails carry the same chip row, above the rail`() {
        listOf("TV" to tvScreen, "phone" to phoneScreen).forEach { (which, src) ->
            listOf("ALL", "MOVIES", "SERIES", "BEST MATCH", "NEWEST", "A-Z").forEach { label ->
                assertTrue(
                    "$which chip row is missing $label",
                    src.contains("label = \"$label\",")
                )
            }
            assertTrue(
                "$which filter chips call the view model's filter",
                src.contains("viewModel.setTitleTypeFilter(TitleTypeFilter.")
            )
            assertTrue(
                "$which sort chips call the view model's sort",
                src.contains("viewModel.setTitleSort(TitleSort.")
            )
            assertTrue(
                "$which chip row is drawn before the rail's own row",
                src.indexOf("label = \"A-Z\",") < src.indexOf("MobileSearchRail(title = \"Titles\"") ||
                    src.indexOf("label = \"A-Z\",") < src.indexOf("LazyRow(\n                            state = titlesRailState")
            )
        }
        assertTrue(
            "the phone uses the app's touch chip, not a TV control",
            phoneScreen.contains("TouchChoiceChip(") &&
                phoneScreen.contains("selected = titleTypeFilter == TitleTypeFilter.")
        )
        assertTrue(
            "the TV chips take focus themselves (SearchChip is focusable) and " +
                "the selected one is accented",
            tvScreen.contains("accent = titleTypeFilter == TitleTypeFilter.") &&
                tvScreen.contains("accent = titleSort == TitleSort.")
        )
    }

    @Test
    fun `selecting a chip on TV hands the D-pad back to the rail`() {
        assertTrue(
            "a pulse is raised by every chip",
            tvScreen.contains("var railFocusPulse by remember { mutableStateOf(0) }") &&
                tvScreen.split("returnToRail()").size > 6
        )
        assertTrue(
            "and the effect focuses the rail's first tile once it has composed",
            tvScreen.contains("LaunchedEffect(railFocusPulse)") &&
                tvScreen.contains("val first = results.firstOrNull()") &&
                tvScreen.contains("requester.requestFocus()")
        )
        assertTrue(
            "retried across frames, the same landing rule the rail returns use",
            tvScreen.contains("repeat(BROWSE_RETURN_FOCUS_STEPS)") &&
                tvScreen.contains("awaitFrame()")
        )
    }

    @Test
    fun `the hero and the empty state speak in the filter's terms`() {
        assertTrue(
            "the TV hero labels its titles chip from the filter",
            hero.contains("add(titleCountLabel(catalogCount, titleTypeFilter, titlesTotal))")
        )
        assertTrue(
            "and so does the phone's",
            phoneScreen.contains("titleCountLabel(")
        )
        listOf("TV" to tvScreen, "phone" to phoneScreen).forEach { (which, src) ->
            assertTrue(
                "$which shows the filter's own empty message",
                src.contains("titlesEmptyLabel(titleTypeFilter)")
            )
            assertTrue(
                "$which keeps the section - and with it the chips - mounted " +
                    "whenever titles were LOADED, so an empty filter can always be " +
                    "left again instead of stranding the viewer on it",
                src.contains("if (!isLoading && (results.isNotEmpty() || titlesAvailable > 0)) {") &&
                    src.contains("\"titles_type_empty\"")
            )
        }
        assertTrue(
            "the generic count uses the unfiltered title count",
            tvScreen.contains("val totalCount = titlesAvailable + actorResults.size") &&
                phoneScreen.contains("val totalCount = titlesAvailable + actorResults.size")
        )
    }

    @Test
    fun `the view layer is the three filters and three sorts the spec names`() {
        listOf("ALL,", "MOVIES,", "SERIES").forEach {
            assertTrue("TitleTypeFilter is missing $it", view.contains(it))
        }
        listOf("BEST_MATCH,", "NEWEST,", "AZ").forEach {
            assertTrue("TitleSort is missing $it", view.contains(it))
        }
        assertTrue(
            "Best match reuses the existing comparator rather than a copy",
            view.contains("items.sortedWith(searchTitleOrder(lowerQuery, taste))")
        )
        assertTrue(
            "taste reaches Best match only",
            view.contains("internal fun sortTitles(") &&
                view.contains("taste: TasteSignals.Snapshot = TasteSignals.Snapshot()") &&
                view.contains("TitleSort.NEWEST -> items.sortedWith(newestTitleOrder(lowerQuery))") &&
                view.contains("TitleSort.AZ -> items.sortedWith(alphabeticalTitleOrder(lowerQuery))")
        )
    }
}
