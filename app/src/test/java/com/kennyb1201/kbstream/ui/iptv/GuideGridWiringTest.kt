package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timeline grid's WIRING (spec T4, T5, T6, T8), pinned from the sources.
 *
 * The rules are behaviour and live in GuideGridRulesTest; what is left is the
 * part a pure function cannot answer: that the screen feeds the grid the same
 * channels and groups the list uses, that nothing in the grid can invent a
 * program, that the zap lineup still publishes the browsed group, and that the
 * group ORDER has exactly one editor (the Manage hidden dialog) and two
 * displays (the sidebar and the chips).
 *
 * Same source-reading style as the guide's other contract tests, for the same
 * reason: this module has no Compose test harness.
 */
class GuideGridWiringTest {

    private val grid = source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt")
    private val screen = source("com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt")
    private val rules = source("com/kennyb1201/kbstream/ui/iptv/GuideGridRules.kt")
    private val repository = source("com/kennyb1201/kbstream/data/iptv/IptvRepository.kt")
    private val viewModel = source("com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt")
    private val mobile = source("com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt")
    private val mobileGrid = source("com/kennyb1201/kbstream/ui/mobile/GuideGridMobileView.kt")
    private val mobileGroups = source("com/kennyb1201/kbstream/ui/mobile/MobileGuideGroups.kt")

    // ── T4: nothing is fabricated ──────────────────────────────────────────

    @Test
    fun `grid programs can only come from the guide's own rows`() {
        // Exactly one construction site, and it maps a DAO row: the grid has no
        // path that invents a program to fill a span.
        assertEquals(
            "a program is built from a stored row and nowhere else",
            2,
            Regex("GridProgram\\s*\\(").findAll(rules).count()
        )
        assertTrue(
            "and that one site copies the row's real start and end",
            rules.contains("startUtcMillis = it.startUtcMillis") &&
                rules.contains("endUtcMillis = it.endUtcMillis")
        )
        // Gap cells are the absence of a program, never a program with a filler
        // title (the strip says "No program data" only for those).
        assertTrue(rules.contains("val isGap: Boolean get() = title == null"))
    }

    @Test
    fun `the time axis stops at the last imported program`() {
        assertTrue(
            "the grid takes the guide's hard right edge",
            grid.contains("scheduleEndMs: Long?") &&
                grid.contains("maxEndUtcMillis = scheduleEndMs")
        )
        assertTrue(
            "which is maxProgramEndForSource, through the ViewModel",
            viewModel.contains("repository.newestGuideProgramEndMs()") &&
                repository.contains("suspend fun newestGuideProgramEndMs()")
        )
        assertTrue(
            "and a page past it is a no-op rather than empty invented time",
            rules.contains("if (direction > 0 && maxEndUtcMillis != null && nextStart >= maxEndUtcMillis) return this")
        )
    }

    @Test
    fun `a channel still loading is never labelled as having no data`() {
        assertTrue(
            "the channel cell distinguishes pending from empty",
            grid.contains("guidePending -> \"Loading guide…\"") &&
                grid.contains("row.item.channel.id !in resolvedGuideIds")
        )
        assertTrue(
            "and the grid is handed the same resolved set the list reads",
            screen.contains("resolvedGuideIds = resolvedGuideIds")
        )
    }

    @Test
    fun `the window read is per visible page and keeps the list's batching`() {
        assertTrue(
            "rows on screen plus a page of lookahead",
            grid.contains("GuideGrid.LOOKAHEAD_ROWS") &&
                grid.contains("onRequestWindow(ids, window.startUtcMillis, window.endUtcMillis)")
        )
        assertTrue(
            "the repository batches exactly as the list does",
            repository.contains("programKeys.chunked(PROGRAM_QUERY_BATCH_SIZE)") &&
                repository.contains("perChannelLimit = perChannelLimit")
        )
        assertTrue(
            "and the ViewModel remembers (channel, window) pairs instead of re-reading",
            viewModel.contains("gridWindowKey(channel.id, windowStart, windowEnd) !in gridWindowReads")
        )
    }

    // ── T5: the zap lineup is the same visible set ─────────────────────────

    @Test
    fun `the grid draws the same channels the lineup publishes`() {
        assertTrue(
            "the grid is fed the browsed group's rows, not a second list",
            screen.contains("items = groupedChannels")
        )
        assertTrue(
            "the lineup effect still publishes every group from groupChannels",
            screen.contains("channels = groupChannels(label).map(::zapChannelOf)") &&
                screen.contains("selected = selectedGroup")
        )
        // The lineup publish must not be gated on the view, or CH+/CH- would
        // change group membership when the viewer switched views.
        val publish = screen.slice(
            "LiveChannelZapRegistry.setGroups(",
            "// A reminder notification's tap asked for a specific channel"
        )
        assertFalse(
            "the published lineup does not depend on the guide's view",
            publish.contains("guideView")
        )
    }

    // ── T6: the sidebar ────────────────────────────────────────────────────

    @Test
    fun `the sidebar lists the same groups the chips do, with visible counts`() {
        assertTrue(
            "one group list is handed to the sidebar",
            screen.contains("groups = groups") &&
                screen.contains("groupCount = { label -> groupChannels(label).size }")
        )
        assertTrue(
            "selecting a row re-filters through the same state the list uses",
            screen.contains("onSelectGroup = { selectedGroup = it }")
        )
        // F2k-c: the sidebar takes space from the grid, it does not shrink it.
        assertTrue(
            "the sidebar is 220dp and the window/cell geometry is unchanged",
            rules.contains("const val SIDEBAR_WIDTH_DP = 220") &&
                rules.contains("const val PX_PER_MIN = 4") &&
                rules.contains("const val WINDOW_HOURS = 6")
        )
        // F2k-d: a group change keeps the window, so the axis does not jump.
        // Round 3 (F12e) made the window's WIDTH the zoom level; the key is
        // still neither the group nor anything else that moves under focus.
        assertTrue(
            "the time window is not keyed by the group",
            grid.contains(
                "var window by remember { mutableStateOf(GridWindow.opening(nowMs, windowHours)) }"
            )
        )
    }

    @Test
    fun `the focus chain has no dead direction`() {
        // program -> channel -> sidebar -> channel, and Up from the top to the
        // header, all answered explicitly from the loaded lists.
        assertTrue(
            "a row's first cell hands Left to the channel column",
            grid.contains("val target = horizontalTarget(row.cells, cell.id, -1)") &&
                // Round-2 F11 / F1: the move is DELIVERED through the direct-first
                // handoff (a null cell is the channel column). The destination is
                // the same one `focusCell` / `focusChannel` posted before, and
                // GuideGridCatchUpContractTest pins the delivery itself.
                grid.contains("handoffFocus(rowIndex, target, \"cell-left\")") &&
                grid.contains("handoffFocus(rowIndex, null, \"cell-left-to-channel\")")
        )
        assertTrue(
            "the channel column hands Left to the sidebar and Right back to the grid",
            grid.contains("sidebarRequesters[groups.getOrNull(index)]") &&
                grid.contains("val first = row.cells.firstOrNull { !it.isGap }")
        )
        assertTrue(
            "Up out of the first row and the first sidebar row reaches the header",
            grid.contains("if (rowIndex == 0) {") &&
                grid.contains("if (index == 0) {") &&
                grid.contains("headerFocusRequester.requestFocus()")
        )
        assertTrue(
            "vertical movement is computed from the loaded lists, not geometry",
            grid.contains("verticalTarget(rows.map { it.cells }, rowIndex, cell, direction)")
        )
        // F3e: requesters are pruned, never accumulated.
        assertTrue(
            "requesters are pruned to the live set",
            grid.contains("cellRequesters.keys.retainAll(liveCells)")
        )
    }

    // ── T8: reorder lives in the Manage hidden dialog, nowhere else ────────

    @Test
    fun `the dialog lists playlist groups in saved order with move handles`() {
        assertTrue(
            "the TV dialog orders its rows through the shared rule",
            screen.contains("reorderableGroupLabels(applyGroupOrder(allGroups, groupOrder))")
        )
        assertTrue(
            "each row has ▲ / ▼ and both persist at once",
            screen.contains("onMoveUp = { onMoveGroup(group, -1) }") &&
                screen.contains("onMoveDown = { onMoveGroup(group, 1) }") &&
                screen.contains("saveGroupOrder(moveGroupOrder(ordered, index, direction))")
        )
        assertTrue(
            "the phone dialog is the same editor",
            mobile.contains("onMoveGroup = { group, direction -> moveGroup(group, direction) }") &&
                mobile.contains("onMoveGroup(group, -1)") &&
                mobile.contains("onMoveGroup(group, 1)")
        )
        assertTrue(
            "a move rewrites the overlay in its stored form",
            screen.contains("putString(\"group_order\", encodeGroupOrder(next))") &&
                mobile.contains("putString(\"group_order\", encodeGroupOrder(next))")
        )
    }

    @Test
    fun `the sidebar only displays the order`() {
        assertFalse(
            "no reorder control in the sidebar",
            grid.contains("onMoveGroup") || grid.contains("moveGroupOrder")
        )
        assertTrue(
            "the saved order reaches both views through one derivation",
            screen.contains("applyGroupOrder(") &&
                mobileGroups.contains("= applyGroupOrder(buildList {") &&
                mobile.contains("guideGroupLabels(unhiddenChannels, favorites, recentKeys, groupOrder)")
        )
        assertTrue(
            "All, Recent and Favorites are pinned",
            rules.contains("internal val GRID_PINNED_GROUPS = setOf(GRID_GROUP_ALL, GRID_GROUP_RECENT, GRID_GROUP_FAVORITES)") &&
                rules.contains("val pinned = pinnedNames.filter { it in labels }")
        )
    }

    // ── The phone grid is the same view, touch-first ───────────────────────

    @Test
    fun `the phone grid shares the geometry but none of the focus machinery`() {
        assertTrue(
            "same window rule, same cell-width rule, same detail strip",
            mobileGrid.contains("GridWindow.opening(nowMs, windowHours)") &&
                mobileGrid.contains(
                    "cellWidthDp(cell.startUtcMillis, cell.endUtcMillis, pxPerMin = pxPerMin)"
                ) &&
                mobileGrid.contains("MobileGridDetailStrip(")
        )
        assertFalse(
            "and no D-pad machinery in the tv-free file",
            mobileGrid.contains("androidx.compose.ui.focus") ||
                mobileGrid.contains("onPreviewKeyEvent") ||
                mobileGrid.contains("KeyEventType")
        )
        assertTrue(
            "the same toggle and the same stored preference on both forms",
            screen.contains("putString(\"guide_view\", next.prefValue)") &&
                mobile.contains("putString(\"guide_view\", next.prefValue)") &&
                screen.contains("GuideViewMode.fromPref(") &&
                mobile.contains("GuideViewMode.fromPref(")
        )
    }

    @Test
    fun `the list stays the default`() {
        assertTrue(
            "an unknown or missing preference reads as the list",
            rules.contains("entries.firstOrNull { it.prefValue == value } ?: LIST")
        )
    }

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
