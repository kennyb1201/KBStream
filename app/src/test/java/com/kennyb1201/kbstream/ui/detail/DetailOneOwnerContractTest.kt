package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six rules of the 2026-11-21 Detail focus spec, as wiring.
 *
 * The pure rules live in [DetailTabsTest], [DetailFocusChainTest] and
 * [DetailHeaderBudgetTest]. What none of them can check is that the SCREEN
 * obeys them: that the content list stopped making its own focus decisions,
 * that nothing asks for focus when data arrives, that the strip is keyed, that
 * the sink hands off and verifies, and that posters wait behind the episode
 * list. Those are composables and effects, so they are pinned from the source
 * the way this page's other wiring tests are.
 */
class DetailOneOwnerContractTest {

    private val detail: String by lazy {
        normalized(stripComments(File(findMainSourceRoot(), DETAIL).readText()))
    }

    private val posters: String by lazy {
        normalized(stripComments(File(findMainSourceRoot(), POSTERS).readText()))
    }

    // ── Rule 1: one owner ──────────────────────────────────────────────────

    @Test
    fun `the content list no longer restores focus on its own`() {
        assertFalse(
            "the outer list's focusRestorer is gone: it used to take a verified " +
                "landing back a frame later",
            detail.contains(
                "weight(1f, fill = true) .fillMaxWidth() .focusGroup() .focusRestorer()"
            )
        )
        assertTrue(
            "the list is still a focus group and still wraps the tab's content",
            detail.contains("weight(1f, fill = true) .fillMaxWidth() .focusGroup()")
        )
    }

    // ── Rule 2: data arrival never moves focus ─────────────────────────────

    @Test
    fun `no effect asks for focus when a section lands`() {
        assertFalse(
            "the per-card resume auto-focus effect is gone (it yanked the D-pad " +
                "into the list when the episodes arrived)",
            detail.contains("shouldFocusThisCard")
        )
        assertFalse(
            "and no effect changes the selected tab when a payload lands",
            detail.contains("LaunchedEffect(detailKey, availableTabs") ||
                detail.contains("LaunchedEffect(availableTabs)")
        )
        assertTrue(
            "the default is applied before the first composition instead",
            detail.contains(
                "DetailTabMemory.read(detailKey)?.takeIf { it in availableTabs } " +
                    "?: DetailTabChain.stableDefaultTab(isSeriesTitle)"
            )
        )
    }

    // ── Rule 3: a strip stable from the first frame ────────────────────────

    @Test
    fun `the strip is title-decided, keyed and never empties itself`() {
        assertTrue(
            "the tab list is the title's answer, read at screen scope",
            detail.contains("val availableTabs = remember(isSeriesTitle) {") &&
                detail.contains("DetailTabChain.stableTabs(isSeriesTitle)")
        )
        assertTrue(
            "pills are keyed by tab so a recomposition cannot recreate the " +
                "focused node",
            detail.contains("key(tab) { DetailTabPill(")
        )
        assertTrue(
            "an empty or loading tab paints a sentence inside itself",
            detail.contains("val emptyTabMessage = when (activeTab)") &&
                detail.contains("DetailTabChain.placeholder(activeTab")
        )
    }

    // ── Rule 4: every press lands somewhere real ───────────────────────────

    @Test
    fun `the content sink holds focus, hands off, and answers its own presses`() {
        assertTrue(
            "the sink reports that it holds the focus",
            detail.contains("var tabContentSinkHoldsFocus by remember") &&
                detail.contains("tabContentSinkHoldsFocus = state.isFocused")
        )
        assertTrue(
            "the moment the tab's first row composes it takes the focus, traced",
            detail.contains("detail sink handoff -> ")
        )
        assertTrue(
            "DOWN from the sink is the first row, never a dead key",
            detail.contains("detail sink DOWN ->")
        )
        assertTrue(
            "UP from the sink is the strip",
            detail.contains("Key.DirectionUp -> { scope.launch { focusTabStrip() }")
        )
    }

    // ── Rule 5: focus is verified, not assumed ─────────────────────────────

    @Test
    fun `landings are observed on the rows themselves`() {
        val rows = listOf(
            "HEADER",
            "GENRE",
            "OVERVIEW",
            "TABS",
            "KEYWORDS",
            "EPISODES",
            "PEOPLE",
            "NETWORK",
            "PRODUCTION",
            "COLLECTION",
            "MORE_LIKE_THIS",
            "REVIEWS",
            "MEDIA"
        )
        rows.forEach { row ->
            assertTrue(
                "the $row row reports focus gains and losses",
                detail.contains("traceRowFocus(DetailRow.$row)")
            )
        }
        assertTrue(
            "the trace names both directions of the observation",
            detail.contains("detail focus GAIN ") &&
                detail.contains("detail focus LOST ")
        )
        assertTrue(
            "a landing is confirmed by observation, with one bounded re-ask",
            detail.contains("suspend fun verifyFocusMoved(") &&
                detail.contains("suspend fun askRow(") &&
                detail.contains("detail focus verify ")
        )
        assertTrue(
            "and the move line reports whether it was verified",
            detail.contains("verified=\$tracedVerified")
        )
    }

    // ── Rule 6: the header height budget ───────────────────────────────────

    @Test
    fun `the overview's lines are measured against the viewport`() {
        assertTrue(
            "collapsed and expanded both come from the budget object",
            detail.contains("DetailHeaderBudget.collapsedLines(screenHeightDp)") &&
                detail.contains("DetailHeaderBudget.expandedLines(screenHeightDp)")
        )
        assertTrue(
            "and the block paints those measured counts",
            detail.contains("maxLines = if (overviewExpanded) { overviewExpandedLines }")
        )
    }

    // ── Rule 7: episodes are interactive fast ──────────────────────────────

    @Test
    fun `season posters wait behind the selected season's episodes`() {
        assertTrue(
            "the row is told when the episode list is loading",
            detail.contains("episodesPaused = episodesLoading")
        )
        assertTrue(
            "and the lookup itself refuses to run while paused",
            posters.contains("if (!resolver.canResolve || paused) return@LaunchedEffect")
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

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
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val POSTERS = "com/kennyb1201/kbstream/ui/detail/SeasonPosterRow.kt"
    }
}
