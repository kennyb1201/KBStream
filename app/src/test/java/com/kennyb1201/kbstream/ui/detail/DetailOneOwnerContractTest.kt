package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
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
            "a landing is confirmed on the TARGET or a declared step-over, with " +
                "one bounded re-ask",
            detail.contains("suspend fun verifyFocusLanded(") &&
                detail.contains("DetailFocusLandingRules.outcome(") &&
                detail.contains("suspend fun askRow(") &&
                detail.contains("detail focus verify ")
        )
        assertTrue(
            "and the move line reports whether it was verified, and WHERE focus " +
                "actually is",
            detail.contains("verified=\$tracedVerified") &&
                detail.contains("observed=\${tracedObserved?.name ?: \"none\"}") &&
                detail.contains("tracedVerified = landing.verified") &&
                detail.contains("tracedVerified = retried.verified") &&
                detail.contains("tracedVerified = secondAsk.verified") &&
                !detail.contains("tracedVerified = true")
        )
        assertTrue(
            "the pill itself reports its own gains and losses, so a dropped ask " +
                "cannot hide behind the strip container",
            detail.contains("detail pill GAIN ") && detail.contains("detail pill LOST ")
        )
        assertTrue(
            "every top-of-page ask names its caller",
            detail.contains("suspend fun focusTopOfPage(caller: String") &&
                detail.contains("detail top focus caller=\$caller landed=true")
        )
        assertFalse(
            "the header row no longer restores focus after the chain has placed it",
            detail.contains(
                ".focusGroup() .focusRestorer() .then(traceRowFocus(DetailRow.HEADER))"
            )
        )
    }

    @Test
    fun `an ask that returns true but lands on another row is not verified`() {
        val landing = DetailFocusLandingRules.outcome(
            target = DetailRow.TABS,
            before = DetailRow.GENRE,
            via = DetailFocusVia.CHAIN,
            observed = DetailRow.HEADER,
            attempted = setOf(DetailRow.TABS)
        )

        assertFalse(
            "this is the 443 failure: the pill accepted the ask and focus was " +
                "observed on HEADER, so the move did not land TABS",
            landing.landed
        )
        assertFalse("a wrong row is never verified", landing.verified)
    }

    @Test
    fun `a geometric fallback keeps its own via so it cannot launder a pill miss`() {
        val landing = DetailFocusLandingRules.outcome(
            target = DetailRow.TABS,
            before = DetailRow.GENRE,
            via = DetailFocusVia.GEOMETRY,
            observed = DetailRow.HEADER,
            attempted = setOf(DetailRow.TABS)
        )

        assertTrue("geometry really moved the focus, so it landed", landing.landed)
        assertFalse("but it did not land TABS", landing.verified)
        assertEquals(
            "the trace must say geometry, not chain - that is the laundering",
            DetailFocusVia.GEOMETRY,
            landing.via
        )

        val onTarget = DetailFocusLandingRules.outcome(
            target = DetailRow.TABS,
            before = DetailRow.GENRE,
            via = DetailFocusVia.GEOMETRY,
            observed = DetailRow.TABS,
            attempted = setOf(DetailRow.TABS)
        )
        assertTrue("geometry can land the target", onTarget.verified)
        assertEquals(
            "but the trace still says geometry, so a paste can tell how it got there",
            DetailFocusVia.GEOMETRY,
            onTarget.via
        )
    }

    @Test
    fun `a declared step-over is the only other row a landing may be verified on`() {
        val landing = DetailFocusLandingRules.outcome(
            target = DetailRow.EPISODES,
            before = DetailRow.TABS,
            via = DetailFocusVia.CHAIN,
            observed = DetailRow.PEOPLE,
            attempted = setOf(DetailRow.EPISODES, DetailRow.PEOPLE)
        )

        assertTrue("the walk declared PEOPLE as its step-over", landing.verified)
    }

    // ── Round 3: landings are not revoked after they stick ────────────────

    @Test
    fun `a focus revocation is reclaimed once and cannot loop`() {
        assertTrue(
            "the page remembers the row the chain last owned, not only the row " +
                "that is focused right now",
            detail.contains("var lastOwnedRow by remember") &&
                detail.contains("DetailFocusReclaim(owned, row)")
        )
        assertTrue(
            "the reclaim names the row and the row it was stolen by",
            detail.contains("detail focus reclaim row=") &&
                detail.contains("from=\${request.observed?.name ?: \"none\"}")
        )
        assertTrue(
            "a chain move, a hand-back, a drill-down return and an open menu all " +
                "suppress it",
            detail.contains("chainMoveActive ||") &&
                detail.contains("handFocusBack.pending != null ||") &&
                detail.contains("returnTarget != null ||") &&
                detail.contains("menuOpen")
        )
        assertTrue(
            "one reclaim per revocation: a failed reclaim is blocked, not retried",
            detail.contains("reclaimBlockedFor") &&
                detail.contains("detail focus reclaim failed row=")
        )
        assertTrue(
            "the reclaim retries inside that one attempt before calling it failed",
            detail.contains("while (attempts < RETURN_FOCUS_STEPS)") &&
                detail.contains("landed = reclaimFocusRow(request.row, request.observed)") &&
                detail.contains("delay(RETURN_FOCUS_DELAY_MS)")
        )
        assertTrue(
            "the page root no longer runs a restorer for every chain loss",
            !detail.substringAfter("Box( modifier = Modifier .fillMaxSize()")
                .substringBefore(") {")
                .contains(".focusRestorer()") &&
                detail.contains("screensaverWasVisible")
        )
        assertTrue(
            "consuming the request cannot cancel the effect that reclaims it",
            detail.contains("LaunchedEffect(") &&
                detail.contains("focusReclaimSerial,") &&
                detail.contains("focusReclaimSerial++")
        )
        assertTrue(
            "EPISODES is one logical row even though it is two focus containers",
            detail.contains("focusGainSerial") &&
                detail.contains("lastGainRow == row && focusGainSerial != serialAtLoss")
        )
    }

    @Test
    fun `reveals do not scroll a composed row and verify on the next frame`() {
        assertTrue(
            "a reveal for a row that is already composed does nothing",
            detail.contains(
                "if (layoutInfo.visibleItemsInfo.any { it.key == rowKey }) return true"
            )
        )
        assertTrue(
            "a neighbour that loses focus cancels its pending poster reveal",
            detail.contains("fun release(neighbourKey: String)") &&
                detail.contains("reveal.release(neighbourKey)")
        )
        val verify = detail.substringAfter("suspend fun verifyFocusLanded(")
            .substringBefore("suspend fun askRow(")
        assertTrue(
            "the landing is observed on the next frame",
            verify.contains("awaitFrame()")
        )
        assertFalse(
            "not after a 15-step poll window in which Play can paint",
            verify.contains("repeat(") || verify.contains("RETURN_FOCUS_DELAY_MS")
        )
        assertFalse("the old verify window constant is gone", detail.contains("FOCUS_VERIFY_STEPS"))
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
