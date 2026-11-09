package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A landing that failed while the page was still loading waits instead of dying
 * at the edge (DETAIL-DOWN-WAIT).
 *
 * The 0.9.206 paste, DetailTrace live:
 *
 * ```
 * detail move GENRE DOWN -> EPISODES landed=false via=edge   (x2, then x4)
 * ```
 *
 * The chain found the row, so the press skipped the page's old wait - which only
 * gated on "the chain has NO row that way" - and went straight to a landing that
 * cannot succeed yet: the episode section's chips are not composed, so the
 * reveal has nothing to reveal and the geometric search finds nothing below. Six
 * dead DOWNs in two walks, each landing the moment the episodes arrived.
 *
 * Pinned here: a failed landing waits on the same bounded budget, re-reads the
 * settled presence, re-asks the SAME target exactly once, and only then falls to
 * the unchanged toggle/edge tail (F1); the move line carries the loading state
 * the press arrived in and whether the wait ran (F2); and a settled page never
 * waits, so its edges are exactly what they were (F3).
 *
 * Read from the source: these are suspend moves in a Compose page. The
 * behaviour stands on the device (§4).
 */
class DetailRowWaitOnFailContractTest {

    private val detail: String by lazy { normalized(stripComments(read(DETAIL_FILE))) }

    /** The chain move, from its signature to the handler that drives it. */
    private val move: String by lazy {
        detail.substringAfter("suspend fun moveFocusVertical(")
            .substringBefore("fun detailRowVerticalFocus(")
    }

    // ── F1: wait, re-ask the same target once, then the old tail ──────────

    @Test
    fun `a failed landing waits when the rows are still arriving`() {
        assertTrue(
            "F1: the wait is gated on the page still loading, so a settled page " +
                "never waits",
            move.contains("if (rowsStillArriving()) {")
        )
        assertTrue(
            "and it is the page's own bounded budget - no new constants, no second " +
                "retry",
            move.contains("while (rowsStillArriving() && waited < RETURN_FOCUS_STEPS) {") &&
                move.contains("delay(RETURN_FOCUS_DELAY_MS)") &&
                !move.contains("FOCUS_RETRY_MS")
        )
        assertTrue(
            "the wait re-reads the presence that LANDED, the same way the old " +
                "no-row wait does",
            move.contains("val settled = latestRowPresence.value ?: presence")
        )
        assertTrue(
            "and the re-ask is for the SAME target, with the settled presence - the " +
                "press never starts asking for rows below it",
            move.contains("focusRow(target, fromColumn, from, direction, settled)")
        )
        val wait = move.slice(
            "if (rowsStillArriving()) {",
            "val fellThrough = fallThroughIfMidPage("
        )
        assertTrue(
            "exactly one re-ask: the first failed landing, then one retry",
            wait.count("focusRow(") == 1 && wait.count("focusMediaStrip(") == 1
        )
        assertTrue(
            "a retry that lands ends the move with that landing",
            wait.contains("if (retried) {") &&
                wait.contains("tracedVia = \"chain\"") &&
                wait.contains("tracedLanded = true") &&
                wait.contains("return true")
        )
    }

    @Test
    fun `the wait sits before the toggle, edge and geometry tail`() {
        val waitAt = move.indexOf("if (rowsStillArriving()) {")
        assertTrue("the wait exists", waitAt > 0)
        assertTrue(
            "F1: it runs after the landing it rescues",
            move.indexOf("focusRow(target, fromColumn, from, direction, presence)") < waitAt
        )
        assertTrue(
            "and before the fall-through that used to be the end of a loading press",
            waitAt < move.indexOf("val fellThrough = fallThroughIfMidPage(")
        )
        assertTrue(
            "before the toggle fallback, which is unchanged",
            waitAt < move.indexOf("val toggleLanded = focusOverviewToggle()")
        )
        assertTrue(
            "the edge verdict still ends the move exactly as it did",
            move.contains("if (DetailFocusChain.isPageEdge(from, direction, presence)) \"edge\"")
        )
        assertTrue(
            "and the audit's UP fix still runs BEFORE the wait, so an UP that lost " +
                "its header landing is not delayed by a loading page",
            move.indexOf("if (target == DetailRow.HEADER && direction == DetailFocusDirection.UP) {") <
                waitAt
        )
    }

    @Test
    fun `the retry uses the same landing shape as the first ask`() {
        val firstAsk = move.substringAfter("val landed =")
            .substringBefore("tracedVia = \"chain\"")
        val wait = move.slice(
            "if (rowsStillArriving()) {",
            "val fellThrough = fallThroughIfMidPage("
        )
        assertTrue(
            "the Media row's DOWN is still the strip, first time and on the retry - " +
                "a retry that asked for a card would change what the press means",
            firstAsk.contains("target == DetailRow.MEDIA && direction == DetailFocusDirection.DOWN") &&
                wait.contains("target == DetailRow.MEDIA && direction == DetailFocusDirection.DOWN") &&
                wait.contains("focusMediaStrip(from)")
        )
    }

    // ── F2: the trace names the loading state ────────────────────────────

    @Test
    fun `the move line carries the loading state and whether it waited`() {
        assertTrue(
            "F2: loading is captured as the press ARRIVED, so a move that started " +
                "loading and ended loaded still says so",
            move.contains("val tracedLoading = rowsStillArriving()") &&
                move.indexOf("val tracedLoading = rowsStillArriving()") <
                move.indexOf("try {")
        )
        assertTrue(
            "and the wait reports itself",
            move.contains("var tracedWaited = false") &&
                move.contains("tracedWaited = true")
        )
        assertTrue(
            "the line appends both, leaving every existing field where the paste " +
                "expects it",
            move.contains("\"landed=\$tracedLanded via=\$tracedVia loading=\$tracedLoading \"") &&
                move.contains("\"waited=\$tracedWaited\"") &&
                move.contains("\"detail move \$from \$direction -> ")
        )
    }

    // ── F3: a settled page is untouched ──────────────────────────────────

    @Test
    fun `nothing about a settled page changes`() {
        val wait = move.slice(
            "if (rowsStillArriving()) {",
            "val fellThrough = fallThroughIfMidPage("
        )
        assertTrue(
            "F3: one wait, behind the loading gate, and nothing else - the move has no " +
                "unconditional waiting loop",
            wait.count("while (rowsStillArriving() && waited < RETURN_FOCUS_STEPS)") == 1
        )
        assertFalse(
            "no wait on a settled page's edges: the tail still returns false for a " +
                "real edge rather than waiting",
            move.contains("if (!rowsStillArriving()) {") ||
                move.contains("while (true)")
        )
        assertTrue(
            "and the chain itself is not touched by this spec - the edge verdict is " +
                "still the chain's",
            DetailFocusChain.isPageEdge(
                DetailRow.GENRE,
                DetailFocusDirection.DOWN,
                presence(hasEpisodesRow = false)
            )
        )
    }

    private fun presence(hasEpisodesRow: Boolean) = DetailRowPresence(
        hasGenreRow = true,
        hasKeywordsRow = false,
        hasEpisodesRow = hasEpisodesRow,
        hasPeopleRow = false,
        hasNetworkRow = false,
        hasProductionRow = false,
        hasReviewsRow = false,
        hasCollectionRow = false,
        hasMediaRow = false,
        hasMoreLikeThisRow = false
    )

    private fun read(fileName: String): String =
        File(findMainSourceRoot(), "com/kennyb1201/kbstream/ui/detail/$fileName").readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

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

    private fun findMainSourceRoot(): File {
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
        const val DETAIL_FILE = "DetailScreen.kt"
    }
}
