package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The game sheet's three-part split, and the field fault it fixes.
 *
 * Reported 2026-10-10: on the game detail sheet the WATCH button and the backup
 * channel rows were neither visible nor reachable. They were the last rows of
 * the sheet's scrolling body, under the pinned header AND a pinned stats block;
 * with a full stats section (win probability, team comparison, last play) the
 * pinned part consumed the sheet and the `weight(1f)` body was left with no
 * visible room, so its rows were laid out only in the sense that they existed.
 *
 * The fix, and what is pinned here: the sheet is a pinned header, a scrolling
 * middle (stats at the top, then the venue rows, the leaders and the backups),
 * and a pinned footer holding the one action. Two things follow, and both are
 * asserted rather than assumed. WATCH is not a descendant of the scrolling body
 * at all, so it is always laid out. And because focusing WATCH on open was what
 * used to scroll the body, a WATCH outside that body cannot scroll it - which is
 * also why the stats, now the top of the scroll, stay where they are drawn.
 *
 * There is no TV and no Compose UI-test harness in CI, so the sheet's tree is
 * read out of the source, as the hub's other contracts are: the STRUCTURE is
 * what is pinned here (which composable is a child of which), never a rendered
 * pixel.
 */
class SportsDetailSheetWatchFooterContractTest {

    private val hub: String by lazy { source(HUB).replace(Regex("\\s+"), " ") }

    /** The whole game sheet, including the helpers it calls. */
    private val sheet: String by lazy { slice("private fun GameDetailSheet(", "private fun DetailRow(") }

    /** The sheet function alone, ending where the shared scroll body begins. */
    private val sheetCall: String by lazy {
        slice("private fun GameDetailSheet(", "private fun ColumnScope.SheetScrollBody(")
    }

    /** The scrolling middle, ending where the stats block it heads begins. */
    private val body: String by lazy {
        slice("private fun ColumnScope.DetailSheetBody(", "private fun StatsSection(")
    }

    @Test
    fun `WATCH is the pinned footer, not a row inside the scrolling body`() {
        assertTrue(
            "the body is called from the sheet - and the action is drawn after it, so the split reads " +
                "in one place",
            sheetCall.contains("DetailSheetBody(") &&
                sheetCall.indexOf("DetailSheetBody(") < sheetCall.indexOf(".focusRequester(watchButton)")
        )
        assertFalse(
            "the action must not be a descendant of the scrolling middle, or a full stats block can " +
                "push it into a region the body has no room for",
            body.contains("watchButton")
        )
        assertFalse(
            "the scroll lives in the helper, so nothing in the sheet function - the footer included - " +
                "is inside it",
            sheetCall.contains("verticalScroll")
        )
        val footer = sheetCall.substring(sheetCall.indexOf("if (channel != null) {"))
        assertFalse(
            "and the footer wraps its content: no weight, which would let it claim scrollable space",
            footer.contains("weight(")
        )
        assertTrue(
            "the stats move with the body, as its first child",
            body.contains("StatsSection(game = game, summary = stats)") &&
                body.indexOf("StatsSection(game = game, summary = stats)") <
                body.indexOf("SportsDetailRules.detailRows(game).forEach")
        )
    }

    @Test
    fun `the footer still plays the whole ordered list and still focuses itself on open`() {
        assertTrue(
            "enabled, the footer plays `channels` head-first, exactly as the body row did",
            sheetCall.contains("onClick = { onPlay(channels) }") &&
                sheetCall.contains("SportsDetailRules.watchLabel(true)")
        )
        assertTrue(
            "and it is focused on open, so one press watches",
            sheetCall.contains(".focusRequester(watchButton)") &&
                sheetCall.contains("LaunchedEffect(Unit) { runCatching { watchButton.requestFocus() } }")
        )
        assertEquals(
            "exactly one focus request in the whole sheet: the action's, and it is outside the scroll",
            1,
            Regex("requestFocus\\(\\)").findAll(sheet).count()
        )
        assertFalse(
            "the shared scroll body asks for no focus of its own, so opening the sheet moves nothing",
            slice("private fun ColumnScope.SheetScrollBody(", "private fun Modifier.focusDownTo(")
                .contains("requestFocus")
        )
    }

    @Test
    fun `a full stats section and backups leave the footer laid out below them`() {
        assertTrue(
            "the stats are gated on having something to draw",
            body.contains("summary?.takeIf { SportsDetailRules.hasStats(it) }?.let { stats ->")
        )
        assertTrue(
            "the backup feeds stay in the scrolling middle, under that stats block",
            body.contains("if (backups.isNotEmpty()) {") &&
                body.indexOf("StatsSection(game = game, summary = stats)") <
                body.indexOf("if (backups.isNotEmpty()) {") &&
                body.contains("BackupChannelLabel(channel = backup)")
        )
        assertTrue(
            "and the footer sits after both of them in the sheet's own order, so nothing the body draws " +
                "can come between it and the plate's bottom edge",
            sheetCall.contains(".padding(top = 10.dp)") &&
                sheetCall.indexOf("DetailSheetBody(") < sheetCall.indexOf(".padding(top = 10.dp)")
        )
    }

    @Test
    fun `the D-pad walks the body top to bottom, then hands off to the action`() {
        assertTrue(
            "the pills chain downward in the order they are drawn, ending with the platform's own search",
            body.contains("backups.forEachIndexed { index, backup ->") &&
                body.contains(
                    ".focusRequester(backupButtons[index]) " +
                        ".focusDownTo(backupButtons.getOrNull(index + 1))"
                )
        )
        assertTrue(
            "and the action below them names the LAST pill as the stop above it - the pill nearest it",
            sheetCall.contains(".focusUpTo(backupButtons.lastOrNull())")
        )
        assertTrue(
            "the one requester per pill is still made from the same list the body draws",
            sheetCall.contains(
                "val backupButtons = remember(backups.size) { List(backups.size) { FocusRequester() } }"
            )
        )
    }

    @Test
    fun `with no matched channel the footer is still honest and never a D-pad stop`() {
        assertTrue(
            "the enabled branch exists only behind a matched channel",
            sheetCall.contains("if (channel != null) {")
        )
        assertTrue(
            "and the disabled one keeps the rules' own honest label",
            sheetCall.contains("SportsDetailRules.watchLabel(false, lineupMissing, matchingDone)")
        )
        assertTrue(
            "which is a plain Surface, so it is not focusable and cannot be a D-pad stop",
            sheetCall.contains("Surface(") && sheetCall.contains("enabled = false")
        )
        val enabled = sheetCall.substring(
            sheetCall.indexOf("if (channel != null) {"),
            sheetCall.indexOf("} else {")
        )
        val disabled = sheetCall.substring(sheetCall.indexOf("} else {"))
        assertTrue(
            "only the matched branch carries the requester",
            enabled.contains(".focusRequester(watchButton)") &&
                !disabled.contains("focusRequester")
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
