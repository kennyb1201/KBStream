package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tournament card's press, and the sheet it raises.
 *
 * An unmatched tournament rendered as a bare `Surface` with no `onClick` - and a
 * Surface with no click handler is not focusable at all, so the D-pad could see
 * the card and never land on it or press it. The game-card click fix covered
 * `GameCard` only, and there was no tournament detail path at all, so an
 * unmatched tournament had nowhere to show its info or the per-stage
 * diagnostics the game cards got.
 *
 * What is pinned here: both branches of the card are the same focusable
 * container, the unmatched press opens the tournament sheet, the matched card
 * still plays its ordered feed list, a card during matching says it is looking
 * rather than reading as permanently unplayable, and the sheet states the
 * channel/matching reason without offering an action that cannot work. There is
 * no TV in CI, so - like the hub's other contracts - the wiring is read out of
 * the source.
 */
class SportsTournamentCardDetailContractTest {

    private val hub: String by lazy { source(HUB).replace(Regex("\\s+"), " ") }

    private val card: String by lazy { slice("private fun TournamentCard(", "private fun Leaderboard(") }

    private val elseBranch: String by lazy { card.substringAfter("} else {") }

    private val sheet: String by lazy {
        slice("private fun TournamentDetailSheet(", "private fun GameDetailSheet(")
    }

    // ── the card is a card, matched or not ────────────────────────────────

    @Test
    fun `an unmatched tournament card is focusable and opens the sheet`() {
        assertTrue(
            "the unmatched branch must be the same clickable container as the playable one - " +
                "a Surface with no onClick is not focusable, which is why the D-pad could never land on it",
            elseBranch.contains("KBCard(")
        )
        assertFalse(
            "the bare surface is what made the card dead",
            elseBranch.contains("Surface(")
        )
        assertTrue(
            "and the press has to have an answer: the detail sheet",
            elseBranch.contains("onClick = { onOpenDetail(event) }")
        )
    }

    @Test
    fun `a matched tournament card still plays the ordered list`() {
        assertTrue("the card is still split on a matched channel", card.contains("if (playable) {"))
        assertTrue("which is the head of the feed list", card.contains("val channel = channels.firstOrNull()"))
        assertTrue(
            "and the whole ordered list, head first, is what it plays - exactly as before",
            card.contains("onClick = { onPlayChannels(channels) }")
        )
        assertTrue(
            "the screen threads both callbacks into the card builder",
            hub.contains("onPlayChannels = onPlayChannels, onOpenDetail = onOpenTournamentDetail,")
        )
    }

    // ── the loading treatment ─────────────────────────────────────────────

    @Test
    fun `a card during matching says it is looking, not that it is unplayable`() {
        assertTrue("the card takes the flag", card.contains("matchingDone: Boolean"))
        assertTrue(
            "and draws the same channel line the game cards do, with the same flag",
            card.contains(
                "ChannelLine( channel = channel, lineupMissing = lineupMissing, " +
                    "matchingDone = matchingDone )"
            )
        )
        assertFalse(
            "the press is never gated on matching - a card during the 40-60s pass still opens its sheet",
            card.contains("if (matchingDone)")
        )
        assertTrue(
            "and the line's own loading wording is the shared one",
            hub.contains("!matchingDone -> \"Finding channel…\"")
        )
    }

    // ── the sheet ─────────────────────────────────────────────────────────

    @Test
    fun `the sheet explains why there is no feed and offers no action that cannot work`() {
        assertTrue(
            "the reason is the shared channel line, told both facts",
            sheet.contains(
                "ChannelLine( channel = channel, lineupMissing = lineupMissing, " +
                    "matchingDone = matchingDone )"
            )
        )
        assertTrue(
            "and the disabled row carries the reason from the shared rules, not the word WATCH",
            sheet.contains("SportsDetailRules.watchLabel(false, lineupMissing, matchingDone)")
        )
        assertTrue("which is a plain, non-focusable surface", sheet.contains("Surface("))
        assertTrue(sheet.contains("enabled = false"))
        assertTrue(
            "an actionable Watch only exists behind a matched channel, which is what the guard is for",
            sheet.contains("if (channel != null) {") &&
                sheet.indexOf("if (channel != null) {") < sheet.indexOf(".focusRequester(watchButton)")
        )
        assertTrue(
            "and that Watch plays through the hub's one existing launch",
            hub.contains("onPlay = { feeds -> detailTournament = null onPlayChannels(feeds) }")
        )
    }

    @Test
    fun `the sheet renders the loaded tournament and fetches nothing`() {
        assertTrue("the name, from the event the card was holding", sheet.contains("text = event.name.ifBlank { event.league }"))
        assertTrue(
            "the league, named from the catalog the hub already knows rather than the raw path",
            sheet.contains("(SportsLeagues.byPath(event.league)?.label ?: event.league)")
        )
        assertTrue("the status line is the shared one", sheet.contains("SportsDetailRules.statusLine(event)"))
        assertTrue(
            "the venue/broadcast/context rows come from the pure rules",
            sheet.contains("SportsDetailRules.detailRows(event).forEach")
        )
        assertTrue(
            "and the leaders section is drawn only when the feed had some",
            sheet.contains("if (event.leaders.isNotEmpty()) {") &&
                sheet.contains("Leaderboard(leaders = event.leaders)")
        )
        assertFalse("the sheet takes no ViewModel, so it cannot fetch", sheet.contains("viewModel"))
        assertFalse("and has no loading state of its own", sheet.contains("loading = true"))
    }

    @Test
    fun `the sheet offers the other feeds and reports a manual pick`() {
        assertTrue(
            "the sheet takes the pick callback, defaulted so the call site is the only wiring",
            sheet.contains("onManualPick: (IptvChannel) -> Unit = {}")
        )
        assertTrue(
            "the other feeds the playlist holds are the sheet's own rows",
            sheet.contains("val backups = channels.drop(1)") &&
                sheet.contains("text = \"BACKUP CHANNELS\"")
        )
        assertTrue(
            "and a tap on one reports the viewer's choice, then plays it first",
            sheet.contains("onManualPick(backup)") &&
                sheet.contains("onPlay(listOf(backup) + channels.filter { it.id != backup.id })")
        )
        assertTrue(
            "the screen records that choice against the tournament's own key",
            hub.contains(
                "onManualPick = { chosen -> viewModel.rememberTournamentChannel(event, chosen.id) }"
            )
        )
    }

    // ── raising and dismissing it ─────────────────────────────────────────

    @Test
    fun `the press raises the sheet and dismissing it returns focus to the card`() {
        assertTrue(
            "the screen holds the tournament whose sheet is up",
            hub.contains("var detailTournament by remember { mutableStateOf<TournamentEvent?>(null) }")
        )
        assertTrue("and raises it from the card's press", hub.contains("detailTournament?.let { event ->"))
        assertTrue(
            "Back dismisses the sheet before it can leave the hub",
            hub.contains("BackHandler { detailTournament = null }")
        )
        assertTrue(
            "and so does onDismissRequest",
            hub.contains("onClose = { detailTournament = null }")
        )
        assertTrue(
            "the sheet is its own dialog window, which is what returns focus to the tree behind it - " +
                "the card that raised it",
            sheet.contains("usePlatformDefaultWidth = false") &&
                sheet.contains("androidx.compose.ui.window.Dialog(")
        )
    }

    @Test
    fun `every league body hands the tournament press its own sheet`() {
        assertTrue(
            "the row builder takes the callback",
            hub.contains("onOpenTournamentDetail: (TournamentEvent) -> Unit,")
        )
        assertTrue(
            "and passes it to the card",
            hub.contains("onOpenDetail = onOpenTournamentDetail,")
        )
        assertEquals(
            "all three LeagueBody call sites - search results, favourites and the plain section - raise it",
            3,
            Regex("onOpenTournamentDetail = \\{ detailTournament = it \\}").findAll(hub).count()
        )
    }

    private fun slice(startMarker: String, endMarker: String): String {
        val start = hub.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = hub.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return hub.substring(start, end)
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
    }
}
