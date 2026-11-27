package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-11-21 Detail tabbed revamp, as wiring.
 *
 * [DetailTabsTest] pins the rules (which tabs exist, which one a page opens on,
 * what LEFT/RIGHT does, which rows a tab owns). What that cannot check is that
 * the screen actually USES them: that every section renders behind its tab,
 * that the strip is painted where it can be reached, that a tab choice survives
 * the trip to the player, and that the vertical chain is narrowed to the tab
 * the viewer is looking at. That is a composable and a key handler, so it is
 * pinned from the source in the style of the page's other wiring tests.
 */
class DetailTabsWiringContractTest {

    /** The detail screen, comments removed, then whitespace collapsed. */
    private val screen: String by lazy { normalized(stripComments(read(SCREEN_FILE))) }

    @Test
    fun `the strip is painted under the header and above the list`() {
        val strip = screen.indexOf("DetailTabStrip(")
        assertTrue("the page paints the tab strip", strip >= 0)
        assertTrue(
            "the strip is composed before the list, so it is pinned above the " +
                "content rather than scrolling with it",
            strip < screen.indexOf("state = detailListState")
        )
        assertTrue(
            "and it is handed the tab set, the selection and the per-pill " +
                "requesters the chain lands with",
            screen.contains("DetailTabStrip( tabs = availableTabs, selected = activeTab,") &&
                screen.contains("requesters = tabFocusRequesters,")
        )
    }

    @Test
    fun `every section renders behind its tab`() {
        // The gating is ONE condition added to the guard each section already
        // had, so "the tab exists" (DetailTabPresence, read from the same row
        // presence) and "the section renders" cannot drift.
        assertTrue(
            screen.contains("activeTab == DetailTab.DETAILS &&") &&
                screen.contains("if (activeTab == DetailTab.DETAILS && keywords.isNotEmpty()) {")
        )
        assertTrue(
            "the episode section is behind the Episodes tab",
            screen.contains("activeTab == DetailTab.EPISODES &&")
        )
        assertTrue(
            "cast, More Like This, reviews and media each behind their own",
            screen.contains("if (activeTab == DetailTab.CAST && hasPeopleRow) {") &&
                screen.contains(
                    "if (activeTab == DetailTab.MORE_LIKE_THIS && hasCollectionRow) {"
                ) &&
                screen.contains("if (activeTab == DetailTab.REVIEWS && reviews.isNotEmpty()) {") &&
                screen.contains("if (activeTab == DetailTab.MEDIA && hasMediaRow) {")
        )
        assertTrue(
            "and the details tab keeps the network and production chips",
            screen.contains("if (activeTab == DetailTab.DETAILS && hasNetworkRow) {") &&
                screen.contains("if (activeTab == DetailTab.DETAILS && hasProductionRow) {")
        )
    }

    @Test
    fun `a tab choice is remembered per title, before the content composes`() {
        assertTrue(
            "the page opens on the remembered tab (or the title's own default) " +
                "synchronously, so a return from the player rebuilds with the " +
                "right tab's rows",
            screen.contains(
                "DetailTabMemory.read(detailKey)?.takeIf { it in availableTabs }"
            ) &&
                screen.contains(
                    "rememberedTab ?: DetailTabChain.stableDefaultTab(isSeriesTitle)"
                )
        )
        assertTrue(
            "and every deliberate change is recorded for that title",
            screen.contains("DetailTabMemory.record(detailKey, tab)") &&
                screen.contains("DetailTabMemory.record(detailKey, next)")
        )
    }

    @Test
    fun `only an unstarted series gets the history-derived Details default`() {
        // F6 (spec 2026-11-27). The watched state lands after the first frame,
        // so the rule may move the tab AT MOST ONCE - only while the viewer has
        // not used the strip, and never past a remembered tab (pinned above).
        assertTrue(
            "the screen reads the ViewModel's presence instead of re-reading history",
            screen.contains(
                "val historyPresence by viewModel.historyPresence.collectAsStateWithLifecycle()"
            )
        )
        assertTrue(
            "the move is gated on not-remembered, not-chosen, not-already-decided " +
                "and known",
            screen.contains("rememberedTab != null || tabChosenByViewer ||") &&
                screen.contains("historyDefaultDecided ||") &&
                screen.contains("historyPresence == DetailHistoryPresence.UNKNOWN")
        )
        assertTrue(
            "and it happens at most once: the first decision is final",
            screen.contains("historyDefaultDecided = true")
        )
        assertTrue(
            "and it is the rule function that decides the tab",
            screen.contains("DetailTabChain.historyDefaultTab(") &&
                screen.contains("hasHistory = historyPresence == DetailHistoryPresence.SOME")
        )
        assertTrue(
            "the strip's own presses pin the tab before the move can run",
            screen.contains("onSelect = { tab -> tabChosenByViewer = true") &&
                screen.contains("tabChosenByViewer = true activeTab = next")
        )
        assertTrue(
            "and a move while the viewer stands on the strip puts the focus on " +
                "the pill it selected, not on the one it left",
            screen.contains(
                "if (focusedRowNow == DetailRow.TABS) { runCatching { tabFocusRequesters[derived]?.requestFocus() } }"
            )
        )
        assertTrue(
            "and the derived tab is always one the series strip paints: the rule " +
                "answers only Details or Episodes, and a series strip always has " +
                "Episodes",
            DetailTabChain.historyDefaultTab(isSeries = true, hasHistory = true) in
                DetailTabChain.stableTabs(isSeries = true)
        )
    }

    @Test
    fun `each tab has its own list state and its own memory of where the viewer was`() {
        assertTrue(
            "the list is handed the state of the SELECTED tab",
            screen.contains("val detailListState = when (activeTab) {")
        )
        listOf(
            "episodesTabListState",
            "detailsTabListState",
            "castTabListState",
            "moreLikeThisTabListState",
            "reviewsTabListState",
            "mediaTabListState"
        ).forEach { state ->
            assertTrue("$state exists", screen.contains("val $state = rememberIdleLazyListState()"))
        }
        assertTrue(
            "the rows published to the presses are the selected tab's own",
            screen.contains("DetailTabChain.rowPresence(activeTab, pageRowPresence)")
        )
        assertTrue(
            "and the screen remembers the row the viewer left each tab on, so DOWN " +
                "out of the strip comes back to it",
            screen.contains("val tabLastRow = remember { mutableStateMapOf<DetailTab, DetailRow>() }") &&
                screen.contains("tabLastRow[activeTab] = target")
        )
    }

    @Test
    fun `the strip owns its presses and the content is entered through it`() {
        assertTrue(
            "LEFT/RIGHT switch tabs through the chain's clamp rule",
            screen.contains("val next = DetailTabChain.step(activeTab, delta, availableTabs)")
        )
        assertTrue(
            "DOWN enters the selected tab's content through one function",
            screen.contains("suspend fun enterTabContent(): Boolean") &&
                screen.contains("DetailTabChain.firstRow(activeTab, rows)")
        )
        assertTrue(
            "and the strip itself is a chain row the focus can land on - from a " +
                "rail's UP and from the genre chips' DOWN",
            screen.contains("suspend fun focusTabStrip") &&
                screen.contains("DetailRow.TABS -> { if (focusTabStrip())")
        )
    }

    @Test
    fun `the chain is narrowed to the selected tab`() {
        assertTrue(
            "the chain a press is answered with is the SELECTED tab's row presence, " +
                "so a press inside a tab can never walk into a row painted in another",
            screen.contains(
                "DetailTabChain.rowPresence(activeTab, pageRowPresence)"
            ) && screen.contains("DetailTabChain.rowPresence(activeTab, page)")
        )
        assertTrue(
            "the overview toggle is a Details-tab-only stop again (2026-11-22), " +
                "so it is part of that tab's row presence",
            screen.contains("hasOverviewRow = overviewToggleAvailable")
        )
        assertTrue(
            "the page declares one tab list, and DetailTab.DETAILS is always in it",
            DetailTabChain.tabs(
                DetailTabPresence(
                    hasEpisodesTab = false,
                    hasCastTab = false,
                    hasMoreLikeThisTab = false,
                    hasReviewsTab = false,
                    hasMediaTab = false
                )
            ) == listOf(DetailTab.DETAILS)
        )
    }

    @Test
    fun `the strip is the stable, keyed, title-decided one`() {
        assertTrue(
            "the pills come from the title, not the payload",
            screen.contains("DetailTabChain.stableTabs(isSeriesTitle)") &&
                !screen.contains("DetailTabChain.tabs(tabPresence)")
        )
        assertTrue(
            "each pill is keyed by its tab, so a recomposition cannot recreate " +
                "the focused node",
            screen.contains("key(tab) { DetailTabPill(")
        )
    }

    @Test
    fun `the six labels are the ones the page names`() {
        assertEquals(
            listOf("Details", "Episodes", "Cast", "More Like This", "Reviews", "Media"),
            DetailTab.entries.map { it.label }
        )
    }

    private fun read(relative: String): String =
        File(findMainSourceRoot(), relative).readText()

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
        const val SCREEN_FILE = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }
}
