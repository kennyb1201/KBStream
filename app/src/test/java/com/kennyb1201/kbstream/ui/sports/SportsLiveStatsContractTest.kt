package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live-stats wiring: the situation line on a card, the sheet's stats
 * section, and - the part the spec is strictest about - WHEN a summary is
 * fetched at all.
 *
 * There is no TV in CI, so the wiring is read out of the source, as the hub's
 * other contracts are. What is pinned here is the shape of the feature rather
 * than its words (those are [SportsDetailRulesTest] and
 * `EspnGameSummaryTest`): the card's line appears only when the feed carried a
 * situation, the stats section sits between the score header and the channels,
 * the sheet still fetches nothing itself, and the summary is fetched lazily on
 * sheet-open for a game whose stats exist - in play or finished, never upcoming
 * - refreshed by the hub's existing 30s tick rather than by a timer of its own.
 * The heading is the rule's own ([SportsDetailRules.statsHeading]), because the
 * same block now heads a finished game's box score FINAL STATS.
 */
class SportsLiveStatsContractTest {

    private val hub: String by lazy { flat(HUB) }
    private val model: String by lazy { flat(VIEW_MODEL) }

    /** The game card, from its declaration to the team column after it. */
    private val card: String by lazy { slice(hub, "private fun GameCard(", "private fun TeamColumn(") }

    /** The detail sheet, from its declaration to the first row helper after it. */
    private val sheet: String by lazy { slice(hub, "private fun GameDetailSheet(", "private fun DetailRow(") }

    /** The card's situation line, from its declaration to the score text after it. */
    private val situation: String by lazy { slice(hub, "private fun SituationLine(", "private fun ScoreText(") }

    /** Opening and closing the sheet: the only two places a summary is asked for. */
    private val openDetail: String by lazy { slice(model, "fun openDetail(game: SportsGame) {", "fun closeDetail()") }

    private val refresh: String by lazy {
        slice(model, "private fun refreshDetailSummary() {", "override fun onCleared()")
    }

    private val tick: String by lazy {
        slice(model, "private suspend fun refreshLeagues(", "override fun onCleared()")
    }

    @Test
    fun `the card draws the situation line only when the feed carried one`() {
        // The line moved into its own composable when the live card's rows
        // became reserved slots (see SportsLiveCardStabilityContractTest), so
        // the assertions split with it: the card fixes the ORDER and decides
        // whether the row is reserved, and the line itself decides its words.
        assertTrue(
            "the card draws the score row and the situation line in that order",
            card.indexOf("ScoreRow(game = game, reserveSlot = liveSlots)") >= 0 &&
                card.indexOf("ScoreRow(game = game, reserveSlot = liveSlots)") <
                card.indexOf("SituationLine(game = game, reserveSlot = liveSlots)")
        )
        assertTrue(
            "the line is the parsed situation, drawn when it exists",
            situation.contains("val situation = game.situation?.takeIf { it.isNotBlank() }")
        )
        assertTrue(
            "and it says what the parser built, not a re-spelling of it",
            situation.contains("text = situation ?: RESERVED_ROW_TEXT,")
        )
        assertTrue(
            "in the card's secondary text style, under the score",
            situation.contains("style = MaterialTheme.typography.bodySmall,") &&
                situation.contains("color = KBTextLo,")
        )
        assertTrue(
            "and the only thing ever drawn in place of the feed's own line is the reserved " +
                "blank - never a stand-in a viewer could read, and never an empty string that " +
                "would lay out no line at all",
            situation.contains("RESERVED_ROW_TEXT") &&
                !situation.contains("?: \"\"") &&
                !situation.contains("N/A")
        )
    }

    @Test
    fun `the stats section sits between the score header and the channels`() {
        val scoreLine = sheet.indexOf("SportsDetailRules.scoreLine(game)")
        val stats = sheet.indexOf("StatsSection(game = game, summary = stats)")
        val detailRows = sheet.indexOf("SportsDetailRules.detailRows(game).forEach")
        val backups = sheet.indexOf("text = \"BACKUP CHANNELS\"")

        assertTrue("the score header is in the sheet", scoreLine >= 0)
        assertTrue("the stats section is in the sheet", stats >= 0)
        assertTrue("it comes after the score header", scoreLine < stats)
        assertTrue("and before the venue rows, the watch button and the backups", stats < detailRows)
        assertTrue(backups < 0 || detailRows < backups)
    }

    @Test
    fun `the stats block is pinned above the body's scroll, not inside it`() {
        // Reported: "I'm not seeing the live stats anywhere in sports." The
        // section existed, but it lived INSIDE the sheet's scrolling body - and
        // the sheet focuses WATCH on open, which scrolls that body and carried
        // the whole block off the top before the viewer ever saw it. Pinned
        // under the score, no forced scroll can hide it.
        val stats = sheet.indexOf("StatsSection(game = game, summary = stats)")
        val scroll = sheet.indexOf(".verticalScroll(rememberScrollState())")
        assertTrue(
            "the stats block and the body's scroll are both in the sheet",
            stats >= 0 && scroll >= 0
        )
        assertTrue(
            "the stats must sit ABOVE the scroll, in the pinned header",
            stats < scroll
        )
    }

    @Test
    fun `the sheet draws the stats section only when there is something in it`() {
        assertTrue(
            "one gate for the whole section: an empty summary is the sheet as it was",
            sheet.contains("summary?.takeIf { SportsDetailRules.hasStats(it) }?.let { stats ->")
        )
        assertTrue(
            "the three parts are drawn in reading order: probability, stats, last play",
            sheet.indexOf("WinProbabilityBar(game = game, homePercent = chance)") <
                sheet.indexOf("StatCompareRow(away = awayValue, label = label, home = homeValue)") &&
                sheet.indexOf("StatCompareRow(away = awayValue, label = label, home = homeValue)") <
                sheet.indexOf("text = \"Last: \$play\"")
        )
        assertTrue(
            "the probability line names the home side beside the number",
            sheet.contains("text = \"\${game.home.abbreviation.ifBlank { game.home.displayName }.uppercase()} \" +")
        )
        assertTrue(
            "and the rows read away | label | home, the score header's own order",
            sheet.contains("private fun StatCompareRow(away: String, label: String, home: String)")
        )
    }

    @Test
    fun `the stats heading says which game it belongs to`() {
        // A finished game the hub is still showing is opened exactly to read its
        // box score, so the section must not head those numbers "LIVE STATS".
        // The heading is the rule's, not a literal in the composable.
        assertTrue(
            "the section's heading comes from the pure rule",
            sheet.contains("text = SportsDetailRules.statsHeading(game),")
        )
        assertFalse(
            "and neither literal is spelled in the composable, where a finished" +
                " game's box score would be stuck reading LIVE STATS",
            sheet.contains("text = \"LIVE STATS\"") || sheet.contains("text = \"FINAL STATS\"")
        )
    }

    @Test
    fun `the sheet still fetches nothing itself`() {
        assertTrue(
            "it takes the summary the hub read, as a plain parameter",
            sheet.contains("summary: EspnGameSummary? = null,")
        )
        assertFalse(
            "no repository and no second door onto ESPN",
            sheet.contains("EspnSportsRepository") || sheet.contains("gameSummary(")
        )
        assertFalse(
            "no loading or error state: the section is simply absent when there is nothing",
            sheet.contains("CircularProgressIndicator")
        )
    }

    @Test
    fun `opening a game with stats is what asks for a summary, and never an upcoming one`() {
        assertTrue(
            "the policy lives in the repository's rules, not at this call site",
            openDetail.contains("if (!EspnSummaryRules.shouldFetch(game.state)) return")
        )
        assertTrue(
            "the request is fired lazily, from the open, and no earlier",
            openDetail.contains("detailJob = viewModelScope.launch { loadDetailSummary() }")
        )
        assertFalse(
            "a card tap is not a fetch: nothing outside openDetail asks for a summary",
            model.split("espn.gameSummary(").size - 1 > 1
        )
        assertTrue(
            "the screen raises the sheet through the hub rather than fetching",
            hub.contains("LaunchedEffect(detailGame) {") &&
                hub.contains("if (open == null) viewModel.closeDetail() else viewModel.openDetail(open)")
        )
        assertTrue(
            "and closing it - or leaving the hub - clears the summary",
            hub.contains("DisposableEffect(Unit) { onDispose { viewModel.closeDetail() } }")
        )
    }

    @Test
    fun `the summary rides the hub's existing live tick instead of a timer`() {
        assertTrue(
            "the tick refreshes it as part of the pass it already runs",
            tick.contains("refreshDetailSummary()")
        )
        assertTrue(
            "and does nothing at all while no sheet is open",
            refresh.contains("if (detailRequest == null) return")
        )
        assertEquals(
            "still exactly one polling loop in the ViewModel - no new timer",
            1,
            Regex("while \\(isActive\\)").findAll(model).count()
        )
        assertTrue(
            "the beat itself is unchanged, so nothing here can shorten it",
            model.contains("SportsLivePollRules.POLL_INTERVAL_MS")
        )
    }

    private fun flat(relative: String): String =
        source(relative).replace(Regex("\\s+"), " ").trim()

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return source.substring(start, end)
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
    }
}
