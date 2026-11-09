package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LEFT out of a channel lands on the selected group's sidebar row (GUIDE-LEFT).
 *
 * The 0.9.206 field paste named both failing steps:
 *
 * ```
 * guide: grid handoff channel->sidebar row=2 group=All requester=true direct=false fallback=true  (x5)
 * guide: grid handoff channel->sidebar group=All landed=false attempts=8
 * ```
 *
 * `requester=true` proves only that the map HAS a key. The entry was stale - the
 * row was disposed while the sidebar was hidden, or it sat outside the sidebar's
 * persisted scroll window - so neither the synchronous ask nor eight retries
 * against the same detached node could land. The row has to be composed first.
 *
 * Pinned here: the effect scrolls the selected group into place before it asks
 * (F1), the key handler's direct ask is only attempted for a row that is already
 * on screen (F2), and the landed line says whether that scroll ran (F4). The
 * selected-group rule, the eight-frame budget and the sidebar's show/hide
 * behaviour are pinned as unchanged.
 */
class GuideSidebarLeftLandingContractTest {

    private val grid by lazy { source(GRID_FILE) }

    /**
     * The LEFT arm of the CHANNEL key handler. Sliced from the function, not from
     * the first `Key.DirectionLeft` in the file: the cell arm has one too.
     */
    private val channelLeft: String by lazy {
        grid.slice(
            "fun onChannelKey(rowIndex: Int, key: Key): Boolean = when (key) {",
            "Key.DirectionRight -> {"
        )
    }

    /** The pending-sidebar retry effect. */
    private val sidebarReturn: String by lazy {
        grid.slice("LaunchedEffect(pendingSidebarFocus", "// F1e:")
    }

    // ── F1: the retry composes the row before it asks ─────────────────────

    @Test
    fun `the retry scrolls the selected group into place before asking again`() {
        assertTrue(
            "F1: the selected group's own index, the entry grab's step - the row " +
                "filed before the sidebar was hidden, or left outside its LazyColumn " +
                "window, has a map entry and no attached node",
            sidebarReturn.contains("val groupIndex = groups.indexOf(selectedGroup).coerceAtLeast(0)") &&
                sidebarReturn.contains("sidebarState.scrollToItem(groupIndex)")
        )
        val loop = sidebarReturn.slice("while (attempts < 8) {", "pendingSidebarFocus = false")
        assertTrue(
            "the scroll is attempt 0's answer to a failed ask - the failed ask is what " +
                "says the row is not composed, so it runs only when the handoff needs it",
            loop.contains("if (attempts == 0) {") &&
                loop.indexOf("requester.requestFocus()") in 0 until
                loop.indexOf("sidebarState.scrollToItem(groupIndex)")
        )
        assertTrue(
            "and it runs before the frame wait, so the ask after the recomposition has " +
                "the fresh requester the row just filed",
            loop.indexOf("sidebarState.scrollToItem(groupIndex)") in 0 until
                loop.indexOf("withFrameNanos { }")
        )
        assertTrue(
            "it also reports itself, so the paste can tell a plain retry from a rescued " +
                "one (F4)",
            sidebarReturn.contains("scrolled = true")
        )
    }

    @Test
    fun `the retry re-reads the map on every attempt`() {
        val loop = sidebarReturn.slice("while (attempts < 8) {", "pendingSidebarFocus = false")
        assertTrue(
            "each attempt re-reads the requester, so a fresh one filed by the row the " +
                "scroll just composed is what the next ask sees - retrying the captured " +
                "instance is the bug",
            loop.contains("val requester = label?.let { sidebarRequesters[it] }")
        )
        assertTrue(
            "and the landing is the request's own answer, not a call that did not throw",
            loop.contains("runCatching { requester.requestFocus() }.getOrDefault(false)")
        )
    }

    // ── F2: the direct ask only for a row that is already composed ────────

    @Test
    fun `the synchronous ask is skipped unless the row is on screen`() {
        assertTrue(
            "F2: the guard is the sidebar actually showing - the parameter still holds " +
                "this frame's value after onSidebarVisibleChange(true) - plus the row in " +
                "its own visible window",
            channelLeft.contains("val rowOnScreen = sidebarVisible &&") &&
                channelLeft.contains(
                    "sidebarState.layoutInfo.visibleItemsInfo.any { it.index == index }"
                )
        )
        assertTrue(
            "and it gates the direct ask, so a detached entry is never asked - the " +
                "field's direct=false was that ask, wasted before the effect could scroll",
            channelLeft.contains("val direct = rowOnScreen && requester != null &&")
        )
        assertTrue(
            "an off-screen row goes to the effect, which owns the first ask after the " +
                "frame and the scroll",
            channelLeft.contains("if (direct) {") &&
                channelLeft.contains("pendingSidebarFocus = true")
        )
        assertTrue(
            "the sidebar is still shown first, so the effect has something to scroll",
            channelLeft.indexOf("onSidebarVisibleChange(true)") in 0 until
                channelLeft.indexOf("sidebarState.layoutInfo")
        )
    }

    // ── F4: the landed line says whether the scroll ran ───────────────────

    @Test
    fun `the landed line carries the scroll it took`() {
        assertTrue(
            "F4: group, landed, attempts and scrolled - the existing fields unchanged, " +
                "the scroll appended",
            sidebarReturn.contains("group=\$label landed=\$landed") &&
                sidebarReturn.contains("attempts=\$attempts scrolled=\$scrolled")
        )
        assertTrue(
            "and it is still the same single line, logged and noted",
            sidebarReturn.contains("Log.i(") && sidebarReturn.contains("GuideTrace.note(guideLine)")
        )
        assertTrue(
            "the key handler's own line keeps its shape - the field paste is read by " +
                "eye and by grep",
            channelLeft.contains("\"grid handoff channel->sidebar row=\$rowIndex group=\$selectedGroup \"") &&
                channelLeft.contains("requester=\${requester != null} direct=\$direct fallback=\${!direct}")
        )
    }

    // ── §3: what must not change ──────────────────────────────────────────

    @Test
    fun `LEFT still targets the selected group and never the first visible row`() {
        assertTrue(
            "the target is the selected group's index",
            channelLeft.contains("val index = groups.indexOf(selectedGroup).coerceAtLeast(0)")
        )
        assertFalse(
            "not whatever row the sidebar happens to be showing - that is the header " +
                "route's behaviour, not this path's",
            channelLeft.contains("visibleItemsInfo.first") ||
                channelLeft.contains("visibleItemsInfo[0]")
        )
        assertTrue(
            "and the effect asks that same group",
            sidebarReturn.contains("val label = groups.getOrNull(groupIndex)")
        )
    }

    @Test
    fun `the retry budget and the give-up line are unchanged`() {
        assertTrue(
            "eight frames: the budget was never the problem, and a still-failing " +
                "handoff must stay readable rather than silent",
            sidebarReturn.contains("while (attempts < 8)") &&
                sidebarReturn.contains("landed=\$landed")
        )
        assertTrue(
            "no new focusable or key handler was introduced by the fix - the arm still " +
                "ends by consuming the press",
            channelLeft.trimEnd().endsWith("true\n        }") &&
                channelLeft.count("Key.Direction") == 1
        )
        assertTrue(
            "and the sidebar's show/hide contract is untouched: shown on LEFT",
            channelLeft.contains("onSidebarVisibleChange(true)")
        )
    }

    @Test
    fun `the entry grab and the grid direction are not part of this change`() {
        assertTrue(
            "the entry grab still scrolls its own way - the precedent this fix copies",
            grid.contains("if (attempts == 0 && home == \"sidebar\" && requester == null) {") &&
                grid.contains("sidebarState.scrollToItem(groupIndex)")
        )
        assertTrue(
            "and RIGHT out of the sidebar still hides it and lands in the grid",
            grid.slice("fun enterGrid(rowIndex: Int) {", "fun activateCell(")
                .contains("onSidebarVisibleChange(false)")
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
