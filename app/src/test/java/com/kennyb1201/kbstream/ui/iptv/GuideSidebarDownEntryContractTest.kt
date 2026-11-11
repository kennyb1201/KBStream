package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DOWN out of the sidebar's last group row enters the grid (guide sidebar-DOWN).
 *
 * The 0.9.221 field report, from a list of `All` + `Recent` only: entry focus
 * lands on the sidebar (`grid entry-focus landed=sidebar group=Recent
 * attempts=0`), DOWN moves to the next group while one exists, and then nothing -
 * focus stuck, no way down. Every grid handoff was `direct=true` in the same
 * paste, so the grid was fine; the press never reached it.
 *
 * The cause is in §1: `onSidebarKey`'s DOWN asked for `groups[index + 1]`, got
 * null on the last row, focused nothing, and returned `true` anyway - a consumed
 * press pointing at a group that does not exist. RIGHT was the only way in, and
 * it is the path the paste proves.
 *
 * Pinned here: the last-row arm makes the SAME `enterGrid` call RIGHT makes, on
 * the row the viewer was on (F1); an empty guide is not consumed, following the
 * m14 rule the RIGHT arm already carries (F1); the arm writes its own trace line
 * before the handoff (F2); and DOWN-with-a-next-group, RIGHT, UP, and the entry
 * grab are untouched (§3).
 *
 * Source-reading style, like the guide's other contract tests: this module has no
 * Compose test harness, so the shape is asserted and the settler settles it.
 */
class GuideSidebarDownEntryContractTest {

    private val grid by lazy { source(GRID_FILE) }

    /** The sidebar key handler - sliced from the function, not the first DOWN in the file. */
    private val sidebar: String by lazy {
        grid.slice("fun onSidebarKey(index: Int, key: Key): Boolean = when (key) {", "val focusedCell")
    }

    /** The DOWN arm, and only it: UP ends where this begins. */
    private val down: String by lazy {
        sidebar.slice("Key.DirectionDown -> {", "Key.DirectionRight -> {")
    }

    /** The RIGHT arm, the entry path the 0.9.221 paste proves - the one to copy. */
    private val right: String by lazy {
        sidebar.slice("Key.DirectionRight -> {", "Key.DirectionLeft -> true")
    }

    // ── F1: the last row enters the grid, the way RIGHT does ──────────────

    @Test
    fun `the last group row enters the grid on the row the viewer was on`() {
        assertTrue(
            "F1: the last-row arm coerces the focused row into the loaded rows, the same " +
                "expression RIGHT uses - not row 0, which would drop the viewer at the top " +
                "of the guide they were half-way down",
            down.contains("val rowIndex = focusedRow.coerceIn(0, rows.lastIndex)") &&
                right.contains("enterGrid(focusedRow.coerceIn(0, rows.lastIndex))")
        )
        assertTrue(
            "F1: and it delivers through enterGrid - the ONE entry path, which asks the " +
                "row's channel cell, sets focusedRow/focusHome, hides the sidebar and falls " +
                "back to a pending focus when the row is not composed yet. A second entry " +
                "path here would be a second set of those rules to keep in step",
            down.contains("enterGrid(rowIndex)")
        )
        assertTrue(
            "F1: the press moved focus, so it is consumed - as RIGHT's is",
            down.substringAfter("enterGrid(rowIndex)").replace(Regex("\\s+"), " ").trim() == "true } }"
        )
        assertFalse(
            "F1: the hide, the focusHome update and the pending fallback stay enterGrid's " +
                "job - the arm must not re-do them, or the two paths can disagree",
            down.contains("onSidebarVisibleChange") ||
                down.contains("pendingFocus =") ||
                down.contains("focusHome =")
        )
        assertTrue(
            "no second handoff writer either: enterGrid logs `grid handoff " +
                "sidebar->channel`, and one call means one of those per press",
            down.count("enterGrid(") == 1
        )
    }

    @Test
    fun `an empty guide does not consume the press`() {
        val emptyArm = down
            .substringAfter("rows.isEmpty()")
            .substringBefore("} else {")
        assertTrue(
            "F1: the m14 rule the RIGHT arm already follows - with an empty guide there is " +
                "no grid to enter, and swallowing the press anyway leaves a dead Down on the " +
                "only focusable surface on screen. `false` lets it fall through (Back included)",
            emptyArm.contains("false") && !emptyArm.contains("enterGrid")
        )
        assertTrue(
            "F1: and it is checked BEFORE the entry, so an empty guide cannot reach enterGrid",
            down.indexOf("rows.isEmpty()") < down.indexOf("enterGrid(rowIndex)")
        )
        assertTrue(
            "the RIGHT arm has the same guard, and it is the precedent this copies",
            right.contains("if (rows.isEmpty()) {") && right.contains("false")
        )
    }

    // ── F2: the DOWN move is readable in a paste ──────────────────────────

    @Test
    fun `the last-row move writes its own trace line before the handoff`() {
        assertTrue(
            "F2: one line naming the press and the row - the handoff line that follows says " +
                "a sidebar->channel move happened, never that DOWN asked for it. Without this " +
                "the next report on this surface shows nothing again, which is why the field " +
                "paste had no evidence at all",
            down.contains("\"grid sidebar-down last-row enterGrid row=\$rowIndex\"")
        )
        assertTrue(
            "F2: both sinks, the file's pattern for every handoff - Log.i for logcat and " +
                "GuideTrace for COPY DIAGNOSTICS",
            down.contains("Log.i(\"GUIDE\", guideLine)") &&
                down.contains("GuideTrace.note(guideLine)")
        )
        assertTrue(
            "F2: written before enterGrid, so a paste reads press-then-handoff in the order " +
                "they happened",
            down.indexOf("GuideTrace.note(guideLine)") in 0 until down.indexOf("enterGrid(rowIndex)")
        )
        assertTrue(
            "F2: one line, both sinks - the handoff's own two lines in enterGrid are " +
                "unchanged, and this spec adds nothing anywhere else in the view",
            down.count("GuideTrace.note(") == 1 && down.count("Log.i(") == 1
        )
    }

    // ── §3: what must not change ──────────────────────────────────────────

    @Test
    fun `DOWN between groups still only moves one row`() {
        assertTrue(
            "the next group is still resolved from the pressed index, and asked directly",
            down.contains("val next = groups.getOrNull(index + 1)") &&
                down.contains("sidebarRequesters[next]?.let { runCatching { it.requestFocus() } }")
        )
        assertTrue(
            "consumed, because focus moved",
            down.contains("if (next != null) {") && down.contains("true")
        )
        assertFalse(
            "and no retry loop: the adjacent-row requester case has no field report, and a " +
                "down press moves one row at a time - the next row is adjacent, not scrolled to",
            down.contains("while (") || down.contains("attempts")
        )
    }

    @Test
    fun `RIGHT, UP and the entry grab are untouched`() {
        assertTrue(
            "RIGHT still enters the grid and hides the sidebar - a regression in the path the " +
                "0.9.221 paste proves would be worse than the bug",
            right.contains("enterGrid(focusedRow.coerceIn(0, rows.lastIndex))") &&
                right.contains("m14")
        )
        assertTrue(
            "UP still reaches the header from the first group row, and the previous group " +
                "below it - a real target, not a trap",
            sidebar.contains("Key.DirectionUp -> {") &&
                sidebar.contains("if (index == 0) {") &&
                sidebar.contains("headerFocusRequester.requestFocus()") &&
                sidebar.contains("val previous = groups.getOrNull(index - 1)")
        )
        assertTrue(
            "the entry grab is untouched: the paste shows it landing first try " +
                "(landed=sidebar group=Recent attempts=0), and this fix is about leaving, " +
                "not arriving",
            grid.contains("LaunchedEffect(entryFocusArmed, rows)")
        )
        assertTrue(
            "as is the in-grid DOWN it now feeds: row below, or a no-op at the bottom - no " +
                "new routing was needed once focus gets there",
            grid.contains("if (rowIndex < rows.lastIndex) handoffFocus(rowIndex + 1, null, \"channel-down\")")
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
        assertTrue("missing start marker: $startMarker", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("missing end marker: $endMarker", end > start)
        return substring(start, end)
    }

    private fun String.count(needle: String): Int {
        var total = 0
        var index = indexOf(needle)
        while (index >= 0) {
            total++
            index = indexOf(needle, index + needle.length)
        }
        return total
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
        const val GRID_FILE = "com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt"
    }
}
