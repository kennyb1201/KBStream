package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid's FIELD FIXES (spec T1-T8), pinned from the sources.
 *
 * The grid shipped (70d381c9, 6cc2618d, 76c95a59) and the first field run found
 * what a pure function cannot: focus could not enter the grid at all, the
 * sidebar had no reason to leave, the header carried a redundant count and a
 * word button, and the bottom row sat cut off. These tests hold the five fixes
 * that answer those findings, including the one property that matters most:
 * every handoff is visible in the log when it fails.
 *
 * Source-reading style, like the guide's other contract tests: this module has
 * no Compose test harness, so the shape is asserted and the field settles it.
 */
class GuideGridFieldFixesContractTest {

    private val grid = source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt")
    private val screen = source("com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt")
    private val header = screen
        .substringAfter("private fun GuideHeader(")
        .substringBefore("private fun GuideHeaderClock(")

    private val enterGrid = grid.slice("fun enterGrid(rowIndex: Int) {", "fun activateCell(")

    private val sidebarRight = grid.slice(
        "Key.DirectionRight -> {\n            // Into the grid",
        "Key.DirectionLeft -> true"
    )

    private val channelLeft = grid.slice(
        "Key.DirectionLeft -> {\n            // F1b",
        "Key.DirectionRight -> {\n            val row = rows.getOrNull(rowIndex)"
    )

    private val cellLeft = grid.slice(
        "Key.DirectionLeft -> {\n                val target = horizontalTarget",
        "Key.DirectionRight -> {\n                val target = horizontalTarget(row.cells, cell.id, 1)"
    )

    private val sidebarReturn = grid.slice("LaunchedEffect(pendingSidebarFocus", "// F1e:")

    private val headerReturn = grid.slice("LaunchedEffect(returnFocusSignal,", "fun focusCell(")

    // ── T1: RIGHT from a group enters the grid and hides the sidebar ───────

    @Test
    fun `right from a group hides the sidebar and lands on the row's channel cell`() {
        assertTrue(
            "RIGHT must go through the handoff, not straight to a posted target",
            sidebarRight.contains("enterGrid(focusedRow.coerceIn(0, rows.lastIndex))")
        )
        assertTrue(
            "the hide is part of the handoff, so the grid reclaims the 220dp",
            enterGrid.contains("onSidebarVisibleChange(false)")
        )
        assertTrue(
            "and the channel column's requester is asked DIRECTLY first: the row is on " +
                "screen, so an async-only handoff is the silent stay that was reported",
            enterGrid.contains("channelRequesters[row.channelKey]") &&
                enterGrid.contains("requester.requestFocus()")
        )
        assertTrue(
            "the async path survives for rows that still have to be scrolled into place",
            enterGrid.contains("if (!direct) focusChannel(rowIndex)")
        )
        assertTrue(
            "the panels weigh the grid, so no width is reserved for a hidden sidebar",
            grid.contains("Column(modifier = Modifier.weight(1f).fillMaxHeight())")
        )
    }

    // ── T2: LEFT from a channel brings the sidebar back ────────────────────

    @Test
    fun `left from a channel shows the sidebar again and puts focus on the group`() {
        assertTrue(
            "the sidebar comes back",
            channelLeft.contains("onSidebarVisibleChange(true)")
        )
        assertTrue(
            "focus goes to the current group's row, never to a program cell",
            channelLeft.contains("sidebarRequesters[groups.getOrNull(index)]") &&
                !channelLeft.contains("focusCell")
        )
        // The sidebar is composed only once the state flips, so the direct
        // request can miss; the pending path waits for the row with the grid's
        // own retry shape (8 frames) and logs the exhaustion.
        assertTrue(
            "a pending target covers the frames before the row exists",
            channelLeft.contains("pendingSidebarFocus = true")
        )
        assertTrue(
            "the retry asks the current group's requester",
            sidebarReturn.contains("val label = groups.getOrNull(groups.indexOf(selectedGroup)")
        )
        assertTrue(
            "and gives up after eight frames with a line",
            sidebarReturn.contains("while (attempts < 8)") &&
                sidebarReturn.contains("landed=\$landed attempts=\$attempts")
        )
    }

    // ── T3: cells go to the column, the column goes to the sidebar ─────────

    @Test
    fun `the two LEFT edges stay distinct`() {
        assertTrue(
            "LEFT from a program cell is the channel column (F3f, unchanged)",
            cellLeft.contains(
                "if (target != null) focusCell(rowIndex, target) else focusChannel(rowIndex)"
            )
        )
        assertFalse(
            "and never the sidebar, even past the first program",
            cellLeft.contains("sidebarRequesters") || cellLeft.contains("onSidebarVisibleChange")
        )
        assertFalse(
            "LEFT from the channel column is the sidebar, never a cell",
            channelLeft.contains("focusCell")
        )
        assertTrue(
            "LEFT from the sidebar itself stays a no-op",
            grid.contains("Key.DirectionLeft -> true")
        )
    }

    // ── T4: no header count on grid, counts stay on the rows ───────────────

    @Test
    fun `the header count is gone on grid and only on grid`() {
        assertTrue(
            "the screen gates the meta line on the view",
            screen.contains("showMeta = guideView != GuideViewMode.GRID")
        )
        assertTrue(
            "and the header renders nothing when it is off",
            header.contains("if (showMeta) {")
        )
        assertTrue(
            "the list keeps the line, word for word",
            header.contains("else -> \"\$channelCount in \$selectedGroup\"")
        )
        assertTrue(
            "the sidebar rows still carry their own counts",
            grid.contains("text = groupCount(label).toString()")
        )
    }

    // ── T5: the toggle is an icon, and it says which view you are in ───────

    @Test
    fun `the toggle shows the current view as an accent icon with a spoken name`() {
        assertTrue(
            "a grid glyph while the grid is up",
            header.contains("Icons.Filled.GridView")
        )
        assertTrue(
            "a list glyph while the list is up",
            header.contains("Icons.AutoMirrored.Filled.List")
        )
        assertTrue(
            "the glyph follows the view",
            header.contains("if (guideView == GuideViewMode.GRID) {\n                                Icons.Filled.GridView")
        )
        assertTrue(
            "an icon-only button must carry the name the word provided",
            header.contains("\"Grid view\"") && header.contains("\"List view\"")
        )
        assertTrue(
            "and keeps the active state's accent",
            header.contains("tint = KBAccent")
        )
        assertFalse(
            "the word button is gone",
            header.contains("\"GRID\"")
        )
        assertTrue(
            "same card, same 12/9 padding, same 24dp glyph as the icons beside it",
            header.contains("Modifier.padding(horizontal = 12.dp, vertical = 9.dp)") &&
                header.contains("Modifier.size(24.dp)")
        )
    }

    // ── T6: rows scroll, and nothing sits on the bottom edge ───────────────

    @Test
    fun `the last row is reachable and the bottom keeps its safe area`() {
        assertTrue(
            "focus scrolls the rows into place - the cut row is fixed by focus working",
            grid.contains("rowListState.animateScrollToItem(target.rowIndex)")
        )
        assertTrue(
            "and Down past the last visible row is a computed target, not a dead end",
            grid.contains("verticalTarget(rows.map { it.cells }, rowIndex, cell, direction)")
        )
        assertTrue(
            "F5b: 12dp under the detail strip, inside the grid's own bounds",
            grid.contains(".fillMaxSize().padding(bottom = 12.dp)")
        )
        assertTrue(
            "the LazyColumn's 6dp is content padding, not the safe area - both stay",
            grid.contains("contentPadding = PaddingValues(bottom = 6.dp)")
        )
    }

    // ── T7: the header round trip remembers where focus came from ──────────

    @Test
    fun `down from the header returns to the last focus home, hidden sidebar included`() {
        assertTrue(
            "the screen forwards the header's Down into the grid",
            screen.contains("event.key == Key.DirectionDown") &&
                screen.contains("gridReturnFocusSignal += 1")
        )
        assertTrue(
            "grid only: on the list the header sits above the chips",
            screen.contains("guideView == GuideViewMode.GRID &&\n                                event.type == KeyEventType.KeyDown")
        )
        assertTrue(
            "a sidebar that is still showing gets the sidebar",
            headerReturn.contains("if (sidebarVisible) {") &&
                headerReturn.contains("pendingSidebarFocus = true")
        )
        assertTrue(
            "otherwise the channel or the cell it came from - and the sidebar stays away",
            headerReturn.contains("focusHome == GridFocusHome.CELL") &&
                headerReturn.contains("GridPendingFocus(rowIndex, cell?.id, channelFocus = cell == null)") &&
                !headerReturn.contains("onSidebarVisibleChange")
        )
        assertTrue(
            "the grid records which home focus is in",
            grid.contains("private enum class GridFocusHome { SIDEBAR, CHANNEL, CELL }") &&
                grid.contains("focusHome = GridFocusHome.CHANNEL") &&
                grid.contains("focusHome = GridFocusHome.CELL")
        )
    }

    // ── T8: both handoffs are logged, with the amount they failed by ───────

    @Test
    fun `each handoff logs its target, requester presence and result`() {
        assertTrue(
            "sidebar -> channel names the row, the channel and both paths' result",
            enterGrid.contains("\"grid handoff sidebar->channel row=\$rowIndex channel=\${row.channelKey} \"") &&
                enterGrid.contains("requester=\${requester != null} direct=\$direct fallback=\${!direct}")
        )
        assertTrue(
            "channel -> sidebar names the row, the group and both paths' result",
            channelLeft.contains("\"grid handoff channel->sidebar row=\$rowIndex group=\$selectedGroup \"") &&
                channelLeft.contains("requester=\${requester != null} direct=\$direct fallback=\${!direct}")
        )
        assertTrue(
            "and the async sidebar retry reports frames spent, so an exhaustion is visible",
            sidebarReturn.contains("\"grid handoff channel->sidebar group=\$label landed=\$landed attempts=\$attempts\"")
        )
        assertTrue(
            "the panel is the guide's own GUIDE tag",
            grid.contains("Log.i(\n            \"GUIDE\",")
        )
    }

    // ── F1d: the slide honours reduced motion ─────────────────────────────

    @Test
    fun `the sidebar slide is a hard cut under reduced motion`() {
        assertTrue(
            "the grid's existing rule",
            grid.contains("val reducedMotion = rememberReducedMotion()")
        )
        assertTrue(
            "no motion means no transition, in both directions",
            grid.contains("EnterTransition.None") && grid.contains("ExitTransition.None")
        )
        assertTrue(
            "and the slide itself is horizontally off the edge, not a fade in place",
            grid.contains("slideInHorizontally(initialOffsetX = { -it })") &&
                grid.contains("slideOutHorizontally(targetOffsetX = { -it })")
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
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
}
