package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The game detail sheet: the tap that raises it, what is on it, and the one
 * action it has.
 *
 * A sheet is chrome over the hub, so there is no TV in CI to look at it - the
 * wiring is read out of the source, as the hub's other contracts are. What is
 * pinned: a tap opens the sheet rather than playing, the sheet renders the
 * already-loaded game (no fetch, no spinner), its Watch button is focused by
 * default and hands the matched channel to the one existing launch, and with no
 * matched channel the button is disabled and says so.
 */
class SportsGameDetailSheetContractTest {

    private val hub: String by lazy { source(HUB).replace(Regex("\\s+"), " ") }

    @Test
    fun `a tap opens the sheet and the long press still follows a team`() {
        assertTrue(
            "the card opens the sheet rather than playing",
            hub.contains("onClick = { onOpenDetail(game) }")
        )
        assertTrue(
            "and the long press still raises the follow dialog",
            hub.contains("onClick = { onOpenDetail(game) }") &&
                hub.contains("onLongClick = onEditFavorites")
        )
        assertTrue(
            "the screen raises the sheet from a tapped game",
            hub.contains("onOpenDetail = { detailGame = it }") &&
                hub.contains("detailGame?.let { game ->")
        )
    }

    @Test
    fun `the card answers the press whether or not it matched`() {
        val card = slice("private fun GameCard(", "private fun TeamColumn(")
        assertFalse(
            "the press is never gated on a matched channel",
            card.contains("if (playable) {")
        )
        assertTrue(
            "every card opens the sheet, so an unmatched one has somewhere to go",
            card.contains("onClick = { onOpenDetail(game) }")
        )
        assertTrue(
            "and the long press follows a team from any card",
            card.contains("onLongClick = onEditFavorites")
        )
    }

    @Test
    fun `the sheet is a bottom sheet, not a centred dialog`() {
        val sheet = slice("private fun GameDetailSheet(", "private fun DetailRow(")
        assertTrue(
            "it takes the whole window so the sheet can sit on the bottom edge",
            sheet.contains("usePlatformDefaultWidth = false")
        )
        assertTrue(
            "and the plate is aligned to the bottom centre",
            sheet.contains("contentAlignment = Alignment.BottomCenter")
        )
    }

    @Test
    fun `the sheet renders the loaded game and fetches nothing`() {
        val sheet = slice("private fun GameDetailSheet(", "private fun DetailRow(")
        assertTrue(
            "the status line is the shared one",
            sheet.contains("SportsDetailRules.statusLine(game)")
        )
        assertTrue(
            "the score line too",
            sheet.contains("SportsDetailRules.scoreLine(game)")
        )
        assertTrue(
            "the detail rows come from the pure rules",
            sheet.contains("SportsDetailRules.detailRows(game).forEach")
        )
        assertTrue(
            "and the leaders section is drawn only when the feed had some",
            sheet.contains("val leaderLines = SportsDetailRules.leaderLines(game)") &&
                sheet.contains("if (leaderLines.isNotEmpty()) {")
        )
        assertFalse(
            "the sheet takes no ViewModel, so it cannot fetch: there is no spinner in it",
            sheet.contains("viewModel")
        )
        assertFalse(
            "and no loading state either",
            sheet.contains("loading = true")
        )
    }

    @Test
    fun `the watch button plays through the one existing path, and is focused on open`() {
        val sheet = slice("private fun GameDetailSheet(", "private fun DetailRow(")
        assertTrue(
            "enabled, the button plays the ordered feed list - head first, backups behind it",
            sheet.contains("onClick = { onPlay(channels) }")
        )
        assertTrue(
            "the screen hands that to the same launch a card tap used to take",
            hub.contains("onPlay = { feeds -> detailGame = null onPlayChannels(feeds) }")
        )
        assertTrue(
            "and it is focused by default, so one press watches",
            sheet.contains(".focusRequester(watchButton)") &&
                sheet.contains("watchButton.requestFocus()")
        )
    }

    @Test
    fun `with no matched channel the button is disabled and says why`() {
        val sheet = slice("private fun GameDetailSheet(", "private fun DetailRow(")
        assertTrue(
            "the disabled label is the rules' own, told which of the two facts this is",
            sheet.contains("SportsDetailRules.watchLabel(false, lineupMissing)")
        )
        assertTrue(
            "the enabled label too",
            sheet.contains("SportsDetailRules.watchLabel(true)")
        )
        assertTrue(
            "and the disabled branch is a plain, non-focusable surface",
            sheet.contains("Surface(") && sheet.contains("enabled = false")
        )
    }

    @Test
    fun `back dismisses the sheet and returns focus to the card`() {
        assertTrue(
            "Back closes the sheet before it can leave the hub",
            hub.contains("BackHandler { detailGame = null }")
        )
        assertTrue(
            "and onDismissRequest does the same",
            hub.contains("onClose = { detailGame = null }")
        )
    }

    @Test
    fun `a game the playlist holds on several feeds offers the others`() {
        val sheet = slice("private fun GameDetailSheet(", "private fun DetailRow(")
        assertTrue(
            "the sheet takes the whole ordered list",
            sheet.contains("channels: List<IptvChannel>")
        )
        assertTrue(
            "the Watch button is the head of it, so a backup cannot displace the feed a card plays",
            sheet.contains("val channel = channels.firstOrNull()")
        )
        assertTrue(
            "and the rest are the sheet's own rows",
            sheet.contains("val backups = channels.drop(1)")
        )
        assertTrue(
            "under a heading drawn only when there is something under it",
            sheet.contains("if (backups.isNotEmpty()) {") &&
                sheet.contains("text = \"BACKUP CHANNELS\"")
        )
        assertTrue(
            "each row switches to that feed and keeps the others behind it",
            sheet.contains("onPlay(listOf(backup) + channels.filter { it.id != backup.id })")
        )
        assertTrue(
            "and is named by the feed it plays",
            sheet.contains("BackupChannelLabel(channel = backup)") &&
                sheet.contains("text = channel.displayName.ifBlank { channel.name }.uppercase()")
        )
        assertTrue(
            "the screen resolves them from the hub's own match map, not a second one",
            hub.contains("channels = matches[game.id].orEmpty()")
        )
    }

    @Test
    fun `a hub with no lineup says so instead of accusing every game`() {
        assertTrue(
            "the card's line has both sentences",
            hub.contains("if (lineupMissing) \"Lineup not loaded\" else \"Not in your playlist\"")
        )
        assertTrue(
            "driven by the hub's own status rather than by the game",
            hub.contains("val lineupMissing = lineupStatus == LineupStatus.MISSING")
        )
        assertTrue(
            "said once, at the top, instead of on every card",
            hub.contains("if (lineupMissing) {") &&
                hub.contains("LineupMissingNotice(onRetry = viewModel::retryLineup)")
        )
        assertTrue(
            "and the notice is a pressable card with the retry on it",
            hub.contains("onClick = onRetry") && hub.contains("text = \"LOAD LINEUP\"")
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
