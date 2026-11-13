package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gap rows at the top of the Guide grid are reachable
 * (spec 2026-11-12, build 262).
 *
 * The cells are empty because those channels carry no program data for the
 * window - an honest blank, not a rendering bug. What WAS a bug is the
 * navigation: `verticalTarget` walked past a row that had no programs in the
 * window, so the empty rows were invisible to UP/DOWN, and an UP that found
 * nothing above it carried focus to the header. `onChannelKey`'s RIGHT returned
 * without moving at all on the same rows, so the empty row could not be entered
 * from the channel column either.
 *
 * The rules themselves are pinned as behaviour in [GuideGridRulesTest]
 * (`verticalTarget` is pure and runs on the JVM). This file pins the two things
 * it cannot: that the grid's key handlers no longer eject or no-op on those
 * paths, and the cause of the blank cells.
 *
 * Local harness: kotlinc -nowarn -cp kotlin-stdlib.jar:junit.jar:hamcrest.jar
 * -d out this file, then JUnitCore with -Duser.dir at the repo root.
 */
class GuideGapRowFocusContractTest {

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        if (from < 0) return ""
        val to = text.indexOf(end, from + start.length)
        if (to < 0) return ""
        return text.substring(from, to)
    }

    // ── F1: the rule stops at a gap-only row ──────────────────────────────

    @Test
    fun `the vertical search stops at the first row with any cell`() {
        val rules = source(RULES)
        val body = between(
            rules,
            "internal fun verticalTarget(",
            "private fun verticalPick("
        )

        assertFalse(
            "a row must not be skipped for having no programs - the skip is the bug",
            body.contains("val programs = rows[row].filterNot { it.isGap }")
        )
        assertTrue(
            "the search takes the row's cells and asks whether ANY exist",
            body.contains("val cells = rows[row]") && body.contains("cells.isEmpty()")
        )
        assertTrue(
            "and a gap-only row is picked by the same rule the programs use",
            body.contains("verticalPick(if (programs.isEmpty()) cells else programs, fromCell)")
        )
    }

    // ── F2: the header is reached from row 0, and only from row 0 ─────────

    @Test
    fun `up reaches the header only from the first row`() {
        val grid = source(GRID)
        val cellKey = between(grid, "fun onCellKey(", "fun onChannelKey(")

        assertTrue(
            "the ejection is gated on being on the FIRST row, not on having " +
                "found no target - the two are the same only once gap rows land",
            cellKey.contains("else if (direction < 0 && rowIndex == 0) {")
        )
        assertFalse(
            "the old unguarded arm is gone",
            cellKey.contains("} else if (direction < 0) {")
        )
        assertTrue(
            "and the header is still what row 0's UP asks for (S4)",
            cellKey.contains("headerFocusRequester.requestFocus()")
        )
    }

    // ── F3: RIGHT from an empty channel enters its gap cell ───────────────

    @Test
    fun `right from a gap-only channel name enters the grid`() {
        val grid = source(GRID)
        val channelKey = between(grid, "fun onChannelKey(", "fun onSidebarKey(")
        val right = between(channelKey, "Key.DirectionRight -> {", "Key.DirectionUp -> {")

        assertFalse(
            "the dead-press shape is gone: a program lookup that returns when the " +
                "row has none",
            right.contains("val first = row.cells.firstOrNull { !it.isGap } ?: return true")
        )
        assertTrue(
            "a row with no programs falls through to its GAP cells instead...",
            right.contains("?: row.cells.firstOrNull { nowMs >= it.startUtcMillis")
        )
        assertTrue(
            "...choosing the one under now when there is one",
            right.contains("nowMs >= it.startUtcMillis && nowMs < it.endUtcMillis")
        )
        assertTrue(
            "...else the row's first cell - a channel with no EPG has one gap " +
                "across the whole window",
            right.contains("?: row.cells.firstOrNull()")
        )
        assertTrue(
            "and it is delivered through the same handoff every other move uses",
            right.contains("handoffFocus(rowIndex, first, \"channel-right\")")
        )
    }

    // ── F4: why the cells are empty, and what stays honest ────────────────

    @Test
    fun `an empty cell means no program data, and nothing invents one`() {
        val rules = source(RULES)
        val rowCells = between(rules, "internal fun rowCells(", "/**\n * Width of a cell in dp")

        assertTrue(
            "the whole window becomes ONE gap cell when there are no programs",
            rowCells.contains("if (cursor < window.endUtcMillis)") &&
                rowCells.contains("gap = true")
        )
        assertTrue(
            "and a gap is a cell with no title - never a placeholder title",
            rules.contains("val isGap: Boolean get() = title == null")
        )

        // The cause the grid sees: no matched guide data for the channel, which
        // the repository reports as an item with no EPG rather than as an error.
        val repository = source(REPOSITORY)
        assertTrue(
            "the lineup answers a channel with no matched guide as an unmatched " +
                "item (epg = null), which is what renders as the blank cell",
            repository.contains("private fun unmatchedItem(channel: IptvChannel)") &&
                repository.contains("playlist.channels.map(::unmatchedItem)")
        )
        assertTrue(
            "and a source whose guide imported nothing contributes nothing " +
                "instead of failing the whole lineup - the blank row, not an error",
            repository.contains("if (snapshot.guideChannels.isEmpty())")
        )
        assertTrue(
            "a negative match is NOT persisted, so an unmatched channel is " +
                "re-matched on the next lineup query rather than stuck empty forever",
            repository.contains("if (resolvedMatch.epgUrl != null) {")
        )
    }

    private companion object {
        const val RULES = "com/kennyb1201/kbstream/ui/iptv/GuideGridRules.kt"
        const val GRID = "com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt"
        const val REPOSITORY = "com/kennyb1201/kbstream/data/iptv/IptvRepository.kt"
    }
}
