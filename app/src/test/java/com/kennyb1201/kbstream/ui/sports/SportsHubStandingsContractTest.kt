package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertEquals
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
    private val mobile: String by lazy { flat(MOBILE) }
    private val orderRule: String by lazy { flat(ORDER_RULE) }

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
            hub.contains("groups.forEachIndexed { index, group ->")
        )
        assertTrue(
            "each group has its own heading, keyed on its POSITION and name so two same-named " +
                "divisions in one column cannot collide into a duplicate key",
            hub.contains("val groupKey = \"std-\$index-\" + group.name") &&
                hub.contains("item(key = \"\$groupKey-head\")")
        )
        assertTrue(
            "and one column header per group, not one for the whole screen",
            hub.contains(
                "item(key = \"\$groupKey-cols\") { StandingsColumnHeader(points = points, " +
                    "ties = ties, columns = columns) }"
            )
        )
        assertTrue(
            "the labels are the spec's, in order",
            hub.contains("listOf(\"W\", \"L\", \"T\", \"PCT\", \"GB\", \"STRK\")")
        )
        assertTrue(
            "and a POINTS table labels the columns the LEAGUE declares - soccer is read on points, " +
                "and the feed publishes neither a win percentage nor games behind for it",
            hub.contains("points -> columns.map { it.label }") &&
                hub.contains("StandingsColumnHeader(points = points, ties = ties, columns = columns)") &&
                hub.contains("points = league.pointsTable")
        )
        assertTrue(
            "and the row draws the same league-declared columns, in the same order, so a heading " +
                "can never sit over the wrong figure",
            hub.contains("columns.forEach { column -> StandingsStat(entry.pointsCell(column)) }") &&
                hub.contains("columns = league.pointsColumns")
        )
        assertTrue(
            "the non-points row still draws wins, losses, the optional T and the three feed strings",
            hub.contains("StandingsStat(entry.ties.toString())") &&
                hub.contains("StandingsStat(standingsText(entry.winPercent))")
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
    fun `a row is a D-pad stop that follows the team it names`() {
        val row = slice(hub, "private fun StandingsRow(", "private fun StandingsStat(")
        assertTrue(
            "it is a card so the D-pad can reach it",
            row.contains("KBCard(")
        )
        assertTrue(
            "the empty action left a full focus stop (border + glow) that " +
                "answered OK with nothing; OK now follows/unfollows the team",
            row.contains("onClick = onToggleFollow,")
        )
        assertTrue(
            "and the row's follow state is read from the same favourite set the " +
                "scoreboard uses, keyed by the same league-scoped rule",
            hub.contains("val followed = team.favoriteKey in favoriteKeys") &&
                hub.contains("val team = standingsTeam(leaguePath, entry)")
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
            "a row's team id is read from the feed, so a follow from the table " +
                "keys under the same favourite the scoreboard does",
            repository.contains("teamId = team.optString(\"id\", \"\").takeIf { it.isNotBlank() }")
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
        // The parse FAILS (null) rather than answering an empty table: an empty
        // table is a SUCCESS, and a successful empty response used to overwrite
        // the cached standing for six hours. Null takes the stale-cache path.
        val parse = slice(repository, "internal fun parseStandingsOrNull(", "internal fun parseStandings(")
        assertTrue(
            "a payload that is not standings yields null, so the cache is kept",
            parse.contains("runCatching { JSONObject(json) }.getOrNull() ?: return null")
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
                "val columns = SportsStandingsLayout.columns( groups = table, widthDp = LocalConfiguration.current.screenWidthDp )"
            )
        )
        assertTrue(
            "two columns are drawn only when the rule asks for them, sharing the game grid's gutter",
            hub.contains("if (columns.size == 2) {") &&
                hub.contains("horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER_DP.dp)")
        )
        assertTrue(
            "each column stacks its own conference's divisions",
            hub.contains("columns.forEachIndexed { index, columnGroups ->") &&
                hub.contains("StandingsColumn( groups = columnGroups, listState = columnState,")
        )
        assertTrue(
            "each column keeps its OWN scroll state, and reports it so the " +
                "D-pad's scroll fallback can ride the focused one instead of a " +
                "list nothing composes with in this mode",
            hub.contains("val columnState = rememberLazyGridState()") &&
                hub.contains("onColumnFocusChanged = onColumnFocusChanged,") &&
                hub.contains(
                    "val scrollTarget = standingsColumnState.value ?: listState"
                )
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

    @Test
    fun `a league that cannot tie drops the T column`() {
        // The five that always play to a winner are marked so in the catalog:
        // ESPN ships a `ties` stat on their tables and fills it with a hard 0,
        // so a column read from the data alone was a column of zeroes. Hockey
        // joins them: a level game is decided in overtime, never drawn.
        assertEquals(
            "the NBA, MLB, the WNBA, college basketball and the NHL are the five that never tie",
            5,
            Regex("hasTies = false").findAll(sportModels).count()
        )
        assertTrue(
            "the NFL keeps its T column, because a tie is a real result there",
            sportModels.contains(
                "SportsLeague(\"football/nfl\", \"NFL\", SportsKind.HEAD_TO_HEAD, hasStandings = true)"
            )
        )
        assertTrue(
            "the header drops T for a league that cannot tie",
            hub.contains("ties -> listOf(\"W\", \"L\", \"T\", \"PCT\", \"GB\", \"STRK\")") &&
                hub.contains("else -> listOf(\"W\", \"L\", \"PCT\", \"GB\", \"STRK\")")
        )
        assertTrue(
            "and the row leaves the count out, so no column of zeroes is drawn",
            hub.contains("if (ties) StandingsStat(entry.ties.toString())")
        )
        assertTrue(
            "both bodies take the flag from the selected league",
            hub.contains("ties = league.hasTies") && mobile.contains("ties = league.hasTies")
        )
    }

    @Test
    fun `a long table can be narrowed to one conference`() {
        assertTrue(
            "the picker is offered only where the shared rule asks for it",
            hub.contains("if (SportsStandingsLayout.needsConferenceFilter(groups)) {")
        )
        assertTrue(
            "and the table drawn under it is the filtered one",
            hub.contains("SportsStandingsLayout.filter(groups, conference)")
        )
        assertTrue(
            "ALL is the whole table, exactly as before",
            hub.contains("LeagueViewChip(\"ALL\", selected == null)")
        )
        assertTrue(
            "the phone draws the same picker, from the same rule",
            mobile.contains("StandingsConferenceFilterMobile(") &&
                mobile.contains("SportsStandingsLayout.label(name)")
        )
    }

    @Test
    fun `the table is read best record first, whatever order ESPN sent`() {
        // The report: the NBA's standings were upside down, worst record at the
        // top. Its document really does arrive that way (the 0-3 teams led the
        // Eastern Conference), so the held table is ranked as it arrives.
        assertTrue(
            "the held table is ranked by the shared rule, on the league's own figure",
            model.contains(
                "val table = SportsStandingsOrder.sortedGroups( groups = groups, " +
                    "pointsTable = SportsLeagues.byPath(path)?.pointsTable == true )"
            )
        )
        assertTrue(
            "and the ranking is a stable sort, so equal records keep ESPN's own tiebreakers",
            orderRule.contains("entries.sortedByDescending { entry -> rank(entry, pointsTable) }")
        )
    }

    @Test
    fun `a points table is the league's own fact, not a stat the feed reuses`() {
        assertEquals(
            "the four soccer leagues and the NHL are ranked on points",
            5,
            Regex("pointsTable = true").findAll(sportModels).count()
        )
        assertFalse(
            "the shape is never inferred from ESPN's points stat - the NBA's is a point differential",
            hub.contains("points.isNotBlank()") || mobile.contains("points.isNotBlank()")
        )
        assertTrue(
            "both bodies take the flag from the selected league",
            hub.contains("points = league.pointsTable") &&
                mobile.contains("points = league.pointsTable")
        )
    }

    @Test
    fun `a hockey table reads OTL where a soccer table reads draws`() {
        // ESPN's hockey table carries no `ties` stat at all - it sends
        // `otLosses` - so the third figure is an OVERTIME loss, and calling it
        // "D" both mislabelled it and printed a hard 0 under the label.
        assertTrue(
            "the OTL column has a value of its own, parsed from ESPN's otLosses stat",
            repository.contains("overtimeLosses = espnStatText(stats, \"otLosses\").toIntOrNull() ?: 0")
        )
        assertTrue(
            "the heading is OTL, and it reads that column - never the draws count",
            sportModels.contains("OVERTIME_LOSSES(\"OTL\")") &&
                sportModels.contains("PointsColumn.OVERTIME_LOSSES -> overtimeLosses.toString()")
        )
        assertTrue(
            "the NHL declares W-L-OTL-PTS, the order hockey records are read in",
            sportModels.contains(
                "pointsColumns = listOf( PointsColumn.WINS, PointsColumn.LOSSES, " +
                    "PointsColumn.OVERTIME_LOSSES, PointsColumn.POINTS )"
            )
        )
        assertFalse(
            "and no points table is labelled with a bare draws list any more",
            hub.contains("points -> listOf(\"W\", \"D\", \"L\", \"PTS\")")
        )
    }

    @Test
    fun `MLS reads W-L-D, the one soccer table that does`() {
        // Every other soccer table here is W-D-L (the league default); MLS
        // publishes its record W-L-D, so its columns are declared, not shared.
        assertTrue(
            "MLS declares W-L-D-PTS",
            sportModels.contains(
                "pointsColumns = listOf( PointsColumn.WINS, PointsColumn.LOSSES, " +
                    "PointsColumn.DRAWS, PointsColumn.POINTS )"
            )
        )
        assertTrue(
            "and the other soccer tables keep the league default, W-D-L-PTS",
            sportModels.contains(
                "val DEFAULT_POINTS_COLUMNS: List<PointsColumn> = listOf(PointsColumn.WINS, " +
                    "PointsColumn.DRAWS, PointsColumn.LOSSES, PointsColumn.POINTS)"
            )
        )
        assertTrue(
            "and the phone's dashed record reads the same declared columns",
            mobile.contains("columns.joinToString(\"-\") { entry.pointsCell(it) }") &&
                mobile.contains("columns = league.pointsColumns")
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
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/SportsHubScreenMobile.kt"
        const val ORDER_RULE = "com/kennyb1201/kbstream/ui/sports/SportsStandingsOrder.kt"
    }
}
