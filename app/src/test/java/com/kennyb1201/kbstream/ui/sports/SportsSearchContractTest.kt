package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hub's search field, pinned as wiring.
 *
 * The spec's invariants are all about what search must NOT do - never fetch,
 * never re-fetch when it is cleared, never carry the query into the data layer -
 * and there is no TV and no ESPN in CI to prove those by running the screen. So,
 * like the hub's other contracts, they are read out of the source: the filter
 * has no repository to call, the screen filters the sections it already holds,
 * and the field is the app's own text field rather than a second keyboard.
 */
class SportsSearchContractTest {

    private val hub: String by lazy { flat(HUB) }
    private val rules: String by lazy { flat(RULES) }
    private val model: String by lazy { flat(VIEW_MODEL) }
    private val matcher: String by lazy { flat(MATCHER) }

    @Test
    fun `search can only filter, because it has nothing to fetch with`() {
        listOf("espn", "iptv", "Repository", "suspend", "withContext", "viewModel", "Dispatchers")
            .forEach { forbidden ->
                assertFalse(
                    "SportsSearchRules must not be able to reach $forbidden - it filters loaded data",
                    rules.contains(forbidden)
                )
            }
        assertTrue(
            "it reads the sections it is handed instead",
            rules.contains("fun results( sections: List<LeagueSection>, query: String,")
        )
    }

    @Test
    fun `the screen filters the sections it already has, and the favorites list too`() {
        val branch = slice(hub, SEARCH_BRANCH, STANDINGS_MARKER)
        assertTrue(
            "the favorites tab searches the followed list",
            branch.contains(
                "val searchSections = if (favoritesTab) listOf(favoritesSection) else sections"
            )
        )
        assertTrue(
            "every other tab searches ALL the loaded leagues, which is the spec's one behavior",
            branch.contains("SportsSearchRules.results(searchSections, searchQuery, searchLeague)")
        )
        assertTrue(
            "and it renders through the hub's ordinary body, so a card behaves exactly as it does elsewhere",
            branch.contains("section = results,")
        )
        listOf(
            "viewModel.refresh()",
            "viewModel::refresh",
            "retryLineup",
            "setLeagueEnabled",
            "ensureStandings",
            "espn"
        ).forEach { fetch ->
            assertFalse("searching must never trigger $fetch", branch.contains(fetch))
        }
    }

    @Test
    fun `clearing the query restores the view it had, because there is nothing to restore`() {
        assertTrue(
            "the query is screen state",
            hub.contains("var searchQuery by remember { mutableStateOf(\"\") }")
        )
        assertTrue(
            "and the search body only exists while a query does",
            hub.contains(SEARCH_BRANCH)
        )
        listOf("searchQuery", "searchFocused", "searchSections")
            .forEach { state ->
                assertFalse(
                    "$state must not reach the ViewModel, or clearing the query could mean a re-fetch",
                    model.contains(state)
                )
            }
        assertTrue(
            "a blank query is the hub's ordinary view, not an empty result set",
            rules.contains("if (!isActive(query)) return null")
        )
    }

    @Test
    fun `the field is the app's own keyboard path, opened on select`() {
        val field = slice(hub, "private fun SportsSearchField(", "private fun SearchResultsLine(")
        assertTrue("it is the shared text field", field.contains("KBTextField("))
        assertTrue(
            "focusing it does not throw a keyboard over the scores - OK starts editing",
            field.contains("openKeyboardOnFocus = false,")
        )
        assertTrue(
            "and it stops asking for the IME once the results below it take focus",
            field.contains("closeKeyboardOnBlur = true,")
        )
        assertTrue(
            "it reports its session and obeys a Back the screen decides was the keyboard's",
            field.contains("onEditingChanged = onEditingChanged,") &&
                field.contains("endEditingSignal = endEditingSignal,")
        )
        assertFalse("no second keyboard is built here", field.contains("BasicTextField("))
    }

    @Test
    fun `back gives up the field before it gives up the query`() {
        val handler = slice(
            hub,
            "BackHandler(enabled = searchEditing || searchFocused",
            "Box("
        )
        val editing = handler.indexOf("searchEditing -> searchEndEditingSignal++")
        val focus = handler.indexOf("searchFocused -> focusManager.clearFocus()")
        val query = handler.indexOf("else -> searchQuery = \"\"")
        assertTrue(
            "the editing session is offered first, then focus, then the query",
            editing >= 0 && editing < focus && focus < query
        )
    }

    @Test
    fun `no results is a sentence, not a blank screen`() {
        assertTrue(
            "the empty state names itself",
            hub.contains("No games found")
        )
        assertTrue(
            "and it says what it looked for, so the query can be corrected rather than retyped blind",
            hub.contains("Nothing matching") && hub.contains("is on the loaded schedule.")
        )
    }

    @Test
    fun `the name rules are the matcher's, not a second copy`() {
        assertTrue(
            "search asks the matcher",
            rules.contains("SportsChannelMatcher.matchesQuery(it, query)")
        )
        assertTrue(
            "and the matcher answers from the same variant list the EPG tier reads",
            matcher.contains("addAll(strongVariants(team))")
        )
        assertTrue(
            "search is a separate entry point, not a fourth tier",
            matcher.contains("fun matchesQuery(game: SportsGame, query: String): Boolean") &&
                matcher.contains("fun matchesQuery(event: TournamentEvent, query: String): Boolean")
        )
    }

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return source.substring(start, end)
    }

    private fun flat(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
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
        const val HUB = "com/kennyb1201/kbstream/ui/sports/SportsHubScreen.kt"
        const val RULES = "com/kennyb1201/kbstream/ui/sports/SportsSearchRules.kt"
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/sports/SportsHubViewModel.kt"
        const val MATCHER = "com/kennyb1201/kbstream/data/sports/SportsChannelMatcher.kt"

        /** The hub body's search branch. */
        const val SEARCH_BRANCH = "if (SportsSearchRules.isActive(searchQuery)) {"

        /** The first thing after that branch in the hub body. */
        const val STANDINGS_MARKER = "// The standings toggle exists only where a table does"
    }
}
