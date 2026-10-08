package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The STANDINGS view, pinned where it can be seen.
 *
 * There is no TV in CI and this screen wants a ViewModel, a live tick and ESPN
 * data, so - like the hub's other contracts - the wiring is read out of the
 * source. What is pinned: the toggle exists only for a league with a table, the
 * table is grouped with a heading and a column header once per group, the row
 * carries the spec's columns, and the standings are fetched on a path of their
 * own - never on the scoreboard's, and never through game-card logic.
 */
class SportsHubStandingsContractTest {

    private fun flat(relative: String): String =
        source(relative).replace(Regex("\\s+"), " ")

    private val hub: String by lazy { flat(HUB) }
    private val model: String by lazy { flat(VIEW_MODEL) }
    private val repository: String by lazy { flat(REPOSITORY) }
    private val sportModels: String by lazy { flat("com/kennyb1201/kbstream/data/sports/SportsModels.kt") }

    @Test
    fun `the standings toggle is drawn only where a table exists`() {
        assertTrue(
            "the toggle needs the selected league to have a table, and the favourites tab is never offered one",
            hub.contains(
                "val standingsAvailable = !favoritesTab && selectedLeague?.hasStandings == true"
            )
        )
        assertTrue(
            "and the toggle is what is drawn from it",
            hub.contains("LeagueViewToggle(selected = leagueView, onSelect = viewModel::setView)")
        )
        assertTrue(
            "the toggle offers both bodies",
            hub.contains("LeagueViewChip(\"GAMES\"") && hub.contains("LeagueViewChip(\"STANDINGS\"")
        )
    }

    @Test
    fun `a league with no table is hidden, not shown empty`() {
        // The catalog marks the eleven team leagues as having a table and the
        // four individual/tournament ones as not - so tennis, MMA, golf and F1
        // never draw the toggle.
        assertTrue(
            "a team league is marked as having standings",
            sportModels.contains("SportsLeague(\"football/nfl\", \"NFL\", SportsKind.HEAD_TO_HEAD, hasStandings = true)")
        )
        assertTrue(
            "and a league ESPN publishes no table for is not",
            sportModels.contains("SportsLeague(\"tennis/atp\", \"Tennis\", SportsKind.HEAD_TO_HEAD)")
        )
        assertTrue(
            "selecting a league with no table falls back to the games body",
            model.contains(
                "if (SportsLeagues.byPath(path)?.hasStandings != true) _view.value = LeagueView.GAMES"
            )
        )
        assertTrue(
            "and the setter refuses a standings view for a league that has none",
            model.contains("if (view == LeagueView.STANDINGS && !selectedLeagueHasStandings()) return")
        )
    }

    @Test
    fun `the table is grouped with a heading and the column labels once per group`() {
        assertTrue(
            "groups come from the feed and are drawn in order",
            hub.contains("groups.forEach { group ->")
        )
        assertTrue(
            "each group has its own heading, keyed on its name so two divisions cannot collide",
            hub.contains("item(key = \"std-head-\" + group.name)")
        )
        assertTrue(
            "and one column header per group, not one for the whole screen",
            hub.contains("item(key = \"std-cols-\" + group.name) { StandingsColumnHeader() }")
        )
        assertTrue(
            "the labels are the spec's, in order",
            hub.contains("listOf(\"W\", \"L\", \"T\", \"PCT\", \"GB\", \"STRK\").forEach")
        )
    }

    @Test
    fun `a row carries the mark, the code, the name and the six stats`() {
        val row = slice(hub, "private fun StandingsRow(", "private fun StandingsStat(")
        assertTrue("the crest plate", row.contains("StandingMark(entry = entry)"))
        assertTrue("the code, on its own width so the columns align", row.contains("entry.abbreviation"))
        assertTrue("and the full name, ellipsised into the slack", row.contains("entry.displayName"))
        assertTrue("wins", row.contains("StandingsStat(entry.wins.toString())"))
        assertTrue("losses", row.contains("StandingsStat(entry.losses.toString())"))
        assertTrue("ties", row.contains("StandingsStat(entry.ties.toString())"))
        assertTrue("win pct", row.contains("StandingsStat(standingsText(entry.winPercent))"))
        assertTrue("games behind", row.contains("StandingsStat(standingsText(entry.gamesBehind))"))
        assertTrue("streak", row.contains("StandingsStat(standingsText(entry.streak))"))
    }

    @Test
    fun `a row is a D-pad stop with no tap action of its own`() {
        val row = slice(hub, "private fun StandingsRow(", "private fun StandingsStat(")
        assertTrue(
            "it is a card so the D-pad can reach it",
            row.contains("KBCard(")
        )
        assertTrue(
            "with an empty action - the spec's \"no tap action on a row\"",
            row.contains("onClick = {},")
        )
    }

    @Test
    fun `standings are fetched on their own path, never through game logic`() {
        assertTrue(
            "the ViewModel asks the repository for the league's table",
            model.contains("val groups = withContext(Dispatchers.IO) { espn.standings(path) }")
        )
        assertTrue(
            "and holds a failure state of its own",
            model.contains("_standingsFailed.value = if (groups.isEmpty() && espn.lastStandingsFetchFailed(path))")
        )
        // The standings fetch must not touch the scoreboard match machinery.
        val ensure = slice(model, "private fun ensureStandings()", "fun setTeamFavorite(")
        assertFalse(
            "no game-card matching runs from the standings fetch",
            ensure.contains("resolveMatches(")
        )
    }

    @Test
    fun `the repository reads ESPN's standings host and holds the table for six hours`() {
        assertTrue(
            "the standings host is the web.api one, not the scoreboard's",
            repository.contains("\"https://site.web.api.espn.com/apis/v2/sports/\"")
        )
        assertTrue(
            "the endpoint carries the spec's query",
            repository.contains("/standings?region=us&lang=en&type=0")
        )
        assertTrue(
            "and the TTL is six hours",
            repository.contains("private const val STANDINGS_TTL_MS = 6L * 60L * 60_000L")
        )
        assertTrue(
            "with a six-hour cache check rather than the scoreboard's minute-scale one",
            repository.contains("now - cached.atMs < STANDINGS_TTL_MS")
        )
    }

    @Test
    fun `a standings failure shows the cached table or the empty state, never a crash`() {
        val parse = slice(repository, "internal fun parseStandings(", "private fun espnStandingEntries(")
        assertTrue(
            "a payload that is not standings yields no groups",
            parse.contains("runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()")
        )
        assertTrue(
            "and an empty group is skipped rather than drawn as a heading",
            parse.contains("if (entries.isEmpty()) continue")
        )
    }

    @Test
    fun `two conferences are drawn side by side, collapsing to one on a narrow screen`() {
        assertTrue(
            "the split is the pure rule's, from the groups and the display width",
            hub.contains(
                "val columns = SportsStandingsLayout.columns( groups = groups, widthDp = LocalConfiguration.current.screenWidthDp )"
            )
        )
        assertTrue(
            "two columns are drawn only when the rule asks for them, sharing the game grid's gutter",
            hub.contains("if (columns.size == 2) {") &&
                hub.contains("horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER_DP.dp)")
        )
        assertTrue(
            "each column stacks its own conference's divisions",
            hub.contains("columns.forEach { columnGroups -> StandingsColumn( groups = columnGroups,")
        )
        assertTrue(
            "the one-column body keeps the screen's scroll state the D-pad fallback rides on",
            hub.contains("groups = columns.first(), listState = listState,")
        )
        assertTrue(
            "and one column body is split out so both shapes draw the same rows",
            hub.contains("private fun StandingsColumn(")
        )
    }

    @Test
    fun `a Left press at the standings edge rises to the tab row instead of sticking`() {
        assertTrue(
            "the Left press is answered",
            hub.contains("Key.DirectionLeft -> FocusDirection.Left")
        )
        assertTrue(
            "and only the standings body redirects it upward, so the games body is unchanged",
            hub.contains(
                "leagueView == LeagueView.STANDINGS && focusManager.moveFocus(FocusDirection.Up)"
            )
        )
        assertTrue(
            "up and down still focus-then-scroll, exactly as before",
            hub.contains("focusManager.moveFocus(direction) || scrollByPage(direction)")
        )
    }

    private fun slice(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = text.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return text.substring(start, end)
    }

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
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
        const val HUB = "com/kennyb1201/kbstream/ui/sports/SportsHubScreen.kt"
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/sports/SportsHubViewModel.kt"
        const val REPOSITORY = "com/kennyb1201/kbstream/data/sports/EspnSportsRepository.kt"
    }
}
