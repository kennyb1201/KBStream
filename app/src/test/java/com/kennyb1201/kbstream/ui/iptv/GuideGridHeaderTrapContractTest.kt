package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header focus trap (spec F1-F3, T1-T4), pinned from the sources.
 *
 * The field report: UP to the icon buttons worked, DOWN back into the guide
 * sometimes did not - the press was claimed by the header and focus stayed on
 * the same button. Two forks could cause it (the button never reached the
 * shared handler, or the return effect gave up), so this spec closes both:
 *
 *  - every one of the six buttons carries its own DOWN handler, with the
 *    screen's Row handler kept as the backstop (F1, T1),
 *  - the return asks the remembered home DIRECTLY first, falls back to the
 *    pending retry, and then hands the press to the platform's geometric
 *    search rather than ending on the header (F2, T2-T3),
 *  - UP, LEFT/RIGHT and the list view are untouched (F3, T4).
 *
 * Source-reading style, like the guide's other contract tests: this module has
 * no Compose test harness, so the shape is asserted and the field settles it.
 */
class GuideGridHeaderTrapContractTest {

    private val grid = source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt")
    private val screen = source("com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt")

    private val header = screen.slice("private fun GuideHeader(", "private fun GuideHeaderClock(")

    /** The screen's GuideHeader call: its modifier carries the shared handler. */
    private val headerCall = screen.slice("GuideHeader(", "Spacer(modifier = Modifier.height(14.dp))")

    /** The return effect: DOWN out of the header, all three of its paths. */
    private val returnEffect = grid.slice(
        "LaunchedEffect(returnFocusSignal, sidebarVisible)",
        // The entry grab's effect follows the return's, and is a different
        // effect with its own exits - the slice stops where that one starts.
        "// ENTRY-FOCUS F1b/F1c: the grab itself"
    )

    // ── T1: every button drives the return itself ──────────────────────────

    @Test
    fun `each of the six header buttons carries the DOWN return`() {
        assertTrue(
            "F1a: one handler, built once, GRID-gated",
            header.contains(
                "val downReturn: Modifier = if (guideView == GuideViewMode.GRID && onDown != null)"
            ) && header.contains("if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown)")
        )
        assertTrue(
            "the toggle - the leftmost button and the one the field report named",
            header.contains("}.then(downReturn)")
        )
        listOf(
            "onCycleWindow" to "zoom",
            "onTogglePreview" to "preview",
            "onSportsClick" to "sports",
            "onSearchClick" to "search",
            "onSetupClick" to "setup"
        ).forEach { (callback, name) ->
            assertTrue(
                "F1a: the $name button carries the same DOWN handler",
                header.contains("KBCard(onClick = $callback, modifier = downReturn)")
            )
        }
        assertTrue(
            "it bumps the screen's one return signal",
            header.contains("onDown()")
        )
    }

    @Test
    fun `the shared row handler stays as the backstop`() {
        assertTrue(
            "F1b: the Row's own handler is still there, and still grid only",
            headerCall.contains("Modifier.onPreviewKeyEvent { event ->") &&
                headerCall.contains("guideView == GuideViewMode.GRID &&") &&
                headerCall.contains("gridReturnFocusSignal += 1")
        )
        assertTrue(
            "and the screen passes the same signal into the buttons",
            screen.contains("onDownReturn = if (guideView == GuideViewMode.GRID) {") &&
                screen.contains("{ gridReturnFocusSignal += 1 }")
        )
        assertTrue(
            "T1: a double bump is harmless - the signal is a counter, the effect " +
                "re-runs and lands the same target",
            grid.contains("if (returnFocusSignal <= 0) return@LaunchedEffect")
        )
    }

    // ── T2: the return lands on the remembered home, directly ──────────────

    @Test
    fun `the return asks the remembered home directly before anything else`() {
        assertTrue(
            "F2a: sidebar while it is showing, else the cell/channel focus left",
            returnEffect.contains("sidebarVisible -> \"sidebar\"") &&
                returnEffect.contains("cell != null -> \"cell\"") &&
                returnEffect.contains("else -> \"channel\"")
        )
        assertTrue(
            "the requester is the target's own: sidebar row, cell, channel",
            returnEffect.contains("label?.let { sidebarRequesters[it] }") &&
                returnEffect.contains("cell?.let { cellRequesters[it.id] }") &&
                returnEffect.contains("channelRequesters[it.channelKey]")
        )
        assertTrue(
            "F2a: direct request FIRST, before the pending retry can be reached",
            returnEffect.contains(
                "val wanted = requesterFor(home)\n" +
                    "        if (wanted != null && runCatching { wanted.requestFocus() }.getOrDefault(false)) {"
            )
        )
        assertTrue(
            "lands the home in the same state the in-grid moves do",
            returnEffect.contains("focusHome = when (home)") &&
                returnEffect.contains("\"sidebar\" -> GridFocusHome.SIDEBAR") &&
                returnEffect.contains("\"cell\" -> GridFocusHome.CELL") &&
                returnEffect.contains("else -> GridFocusHome.CHANNEL")
        )
        assertTrue(
            "F2c: one line per return, naming the home and the path",
            returnEffect.contains("grid handoff header->grid signal=\$returnFocusSignal \$target") &&
                returnEffect.contains("direct=true landed=\$home")
        )
    }

    @Test
    fun `the pending path stays as the fallback for a row that must scroll`() {
        assertTrue(
            "F2a: the fallback is the pending shape - scroll, then retry",
            returnEffect.contains("while (attempts < HEADER_RETURN_ATTEMPTS)") &&
                returnEffect.contains("rowListState.animateScrollToItem(rowIndex)") &&
                returnEffect.contains("rowListState.scrollToItem(rowIndex)")
        )
        assertTrue(
            "it is bounded, like the grid's other handoffs",
            grid.contains("private const val HEADER_RETURN_ATTEMPTS = 8")
        )
        assertTrue(
            "and it says which path ran",
            returnEffect.contains("direct=false fallback=true landed=\$home attempts=\$attempts")
        )
        assertTrue(
            "a landed return clears the pending state so the other effects do not " +
                "also fire",
            returnEffect.contains("pendingSidebarFocus = false") &&
                returnEffect.contains("pendingFocus = null")
        )
    }

    // ── T3: no return ever stays on the header ─────────────────────────────

    @Test
    fun `with no remembered requester the press falls through to geometry`() {
        assertTrue(
            "F2b: the platform's own search, from the grid's scope",
            returnEffect.contains("focusManager.moveFocus(FocusDirection.Down)")
        )
        assertTrue(
            "the grid reads that manager from composition",
            grid.contains("val focusManager = LocalFocusManager.current")
        )
        assertTrue(
            "T3: the log records the geometric fall-through, landed or not",
            returnEffect.contains("direct=false fallback=false geometric=\$moved attempts=\$attempts")
        )
        assertTrue(
            "and no other exit exists: the effect returns early only for the " +
                "signal guard and for the two paths that LANDED, so a failed " +
                "return always reaches geometry",
            returnEffect.countOccurrences("return@LaunchedEffect") == 3
        )
    }

    // ── T4: everything else is unchanged ───────────────────────────────────

    @Test
    fun `UP still lands on the toggle only and the list view keeps its own DOWN`() {
        assertTrue(
            "T4/F3b: the grid's UP asks the one requester the screen hands it",
            grid.contains("headerFocusRequester.requestFocus()")
        )
        assertTrue(
            "and that is still the view toggle, not the row's last button",
            screen.contains("headerFocusRequester = viewToggleFocusRequester")
        )
        assertFalse(
            "F1c: no new requester for the other buttons - DOWN is the only " +
                "thing they gained",
            header.contains("FocusRequester()")
        )
        assertTrue(
            "T4/F3a: the header's DOWN is grid only, so the list still hands DOWN " +
                "to the chips",
            headerCall.contains("guideView == GuideViewMode.GRID &&") &&
                screen.contains("onDownReturn = if (guideView == GuideViewMode.GRID) {")
        )
        assertFalse(
            "and RIGHT across the buttons is untouched - no key handling was added",
            header.contains("Key.DirectionRight")
        )
        assertFalse(
            "nor LEFT",
            header.contains("Key.DirectionLeft")
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var at = indexOf(needle)
        while (at >= 0) {
            count++
            at = indexOf(needle, at + needle.length)
        }
        return count
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
