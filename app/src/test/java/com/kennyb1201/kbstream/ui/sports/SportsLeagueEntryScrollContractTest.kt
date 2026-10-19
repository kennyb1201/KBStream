package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entering a league starts at its top, and the live-arrival guard belongs to
 * one league.
 *
 * Reported (field, 2026-10-18): in the sports hub the live games are first in
 * the data but do not SHOW first until the viewer scrolls down into the league
 * and back up - which is just the viewer reaching the top where the live games
 * always were.
 *
 * Two causes, both keyed-state mistakes rather than a wrong order:
 *
 * - One `LazyGridState` is owned by the hub and shared by every tab, so a
 *   league entered in place inherited the previous league's index into a
 *   different list.
 * - `atTopBeforeLive` was `remember`ed with NO keys at the one call site all
 *   league tabs render through, so it kept the previous league's "the viewer
 *   scrolled" verdict. A new league that already had live games never ran the
 *   `if (live.isEmpty())` sampler, so the guard could never become true and the
 *   arrival effect could never scroll.
 *
 * What is pinned here is the wiring of both fixes, plus the two ways each can
 * go wrong silently: a switch effect keyed on `section` (a new instance every
 * 30-second poll, so it would yank the scroll mid-browse), and a guard that is
 * re-created without the sampler that writes it being restarted (a running
 * collector writes through the state it captured, so the new one would never
 * receive an update). The arrival path and the hub's scroll memory are pinned
 * as UNCHANGED, because both were correct.
 */
class SportsLeagueEntryScrollContractTest {

    private val screen: String by lazy { source().replace(Regex("\\s+"), " ") }

    /** The league body, from its declaration to the first card helper. */
    private val leagueBody: String by lazy { slice("private fun LeagueBody(", "fun LazyGridScope.gameItems(") }

    /** The switch rule itself, isolated from the arrival sampler above it. */
    private val entryEffect: String by lazy {
        slice("var lastLeaguePath by remember", "// Two cards to a row")
    }

    /** The arrival sampler, which the spec says stays as it is. */
    private val arrival: String by lazy {
        slice("var atTopBeforeLive by remember(leaguePath)", "// ── Entering a league starts at its top")
    }

    @Test
    fun `the scroll guard is keyed on the league, not on the composition node`() {
        assertTrue(
            "a league starts with no verdict of its own rather than inheriting the last one",
            leagueBody.contains("var atTopBeforeLive by remember(leaguePath) { mutableStateOf(false) }")
        )
        assertTrue(
            "the path is the league's own stable identity, so a poll cannot change it",
            leagueBody.contains("val leaguePath = section.league.path")
        )
        assertFalse(
            "\"remembered\" with no keys is the bug: every league tab shares this call site",
            leagueBody.contains("var atTopBeforeLive by remember { mutableStateOf(false) }")
        )
    }

    @Test
    fun `the sampler restarts with the guard it writes`() {
        assertTrue(
            "a collector left running writes through the state instance it captured, so the " +
                "re-keyed guard would never be updated - the sampler has to restart with it",
            arrival.contains("LaunchedEffect(listState, leaguePath) {")
        )
        assertTrue(
            "and it still samples the top of the shared list",
            arrival.contains("listState.firstVisibleItemIndex == 0 &&") &&
                arrival.contains("listState.firstVisibleItemScrollOffset == 0")
        )
        assertTrue(
            "sampling only while there is nothing live to land on",
            arrival.contains("if (live.isEmpty()) {")
        )
    }

    @Test
    fun `a league entered in place begins at its top`() {
        assertTrue(
            "the previous path is tracked next to the guard, initially null",
            entryEffect.contains("var lastLeaguePath by remember { mutableStateOf<String?>(null) }")
        )
        assertTrue(
            "a switch is still the old null-guarded rule, so an in-place tab press always scrolls",
            entryEffect.contains("val switched = lastLeaguePath != null && lastLeaguePath != leaguePath")
        )
        assertTrue(
            "scroll on a switch, not only when the new league has live games",
            entryEffect.contains("listState.scrollToItem(0)")
        )
        assertTrue(
            "keyed on the path and the list state - never on `section`, which the 30s poll replaces",
            entryEffect.contains("LaunchedEffect(leaguePath, listState) {") &&
                !entryEffect.contains("LaunchedEffect(section")
        )
    }

    /**
     * The amendment (field, 2026-10-18): the null initial used to suppress the
     * entry scroll unconditionally, which let a STALE hubScrollIndex win over
     * live games already in hand on a fresh hub composition. The restore is
     * only honoured when there is nothing live to land on.
     */
    @Test
    fun `a fresh entry with live games in hand lands on the live block`() {
        assertTrue(
            "the second reason to scroll is a fresh entry whose league is live now",
            entryEffect.contains(
                "val freshWithLive = lastLeaguePath == null && live.isNotEmpty()"
            )
        )
        assertTrue(
            "and the scroll is gated behind BOTH reasons, so entry with nothing live keeps " +
                "the restored position",
            entryEffect.contains("if (!switched && !freshWithLive) return@LaunchedEffect")
        )
        assertTrue(
            "nothing scrolls before that gate - an early scroll would bypass it",
            entryEffect.indexOf("listState.scrollToItem(0)") >
                entryEffect.indexOf("if (!switched && !freshWithLive) return@LaunchedEffect")
        )
        assertTrue(
            "the scroll is still keyed on the league path and the list state only, so a poll " +
                "cannot re-read `live` and yank the scroll mid-browse",
            entryEffect.contains("LaunchedEffect(leaguePath, listState) {") &&
                !entryEffect.contains("LaunchedEffect(leaguePath, live")
        )
    }

    @Test
    fun `the live-arrival path is untouched`() {
        assertTrue(
            "still keyed on the arrival alone",
            leagueBody.contains("LaunchedEffect(live.isNotEmpty()) {")
        )
        assertTrue(
            "and still only while the viewer has not moved",
            leagueBody.contains("if (live.isNotEmpty() && atTopBeforeLive) {") &&
                leagueBody.contains("listState.scrollToItem(0)")
        )
    }

    @Test
    fun `the D-pad lands on a card the switch left visible`() {
        assertTrue(
            "the grid reports whether it holds focus",
            leagueBody.contains(".onFocusChanged { gridHasFocus = it.hasFocus }")
        )
        assertTrue(
            "only a grid that had focus is repaired - a tab press must keep the tab row's focus",
            entryEffect.contains("val repairFocus = gridHasFocus") &&
                entryEffect.indexOf("val repairFocus = gridHasFocus") <
                entryEffect.indexOf("listState.scrollToItem(0)") &&
                entryEffect.indexOf("listState.scrollToItem(0)") <
                entryEffect.indexOf("if (!repairFocus) return@LaunchedEffect")
        )
        assertTrue(
            "the requester is on the FIRST card of whichever group leads the league",
            leagueBody.contains("val firstCardFocus = remember { FocusRequester() }") &&
                leagueBody.contains("focusRequester = firstCardFocus") &&
                leagueBody.contains("focusRequester = firstCardFocus.takeIf { upcomingLeads }") &&
                leagueBody.contains("focusRequester = firstCardFocus.takeIf { finalLeads }")
        )
        assertTrue(
            "a freshly composed card is not attached until laid out, so the request is retried " +
                "and its return value is the test",
            entryEffect.contains("runCatching { firstCardFocus.requestFocus() }.getOrDefault(false)") &&
                entryEffect.contains("attempts < HUB_ENTRY_FOCUS_ATTEMPTS")
        )
    }

    @Test
    fun `the hub's own scroll memory is not disturbed`() {
        assertTrue(
            "the list is still seeded from the ViewModel's memory of the last visit",
            screen.contains("initialFirstVisibleItemIndex = viewModel.hubScrollIndex") &&
                screen.contains("initialFirstVisibleItemScrollOffset = viewModel.hubScrollOffset")
        )
        assertTrue(
            "and still written on the way out, which is what the player's Back restores",
            screen.contains("viewModel.rememberHubScroll(")
        )
    }

    // ── Source access ───────────────────────────────────────────────────

    private fun slice(startMarker: String, endMarker: String): String {
        val start = screen.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = screen.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return screen.substring(start, end)
    }

    private fun source(): String {
        val file = File(findSourceRoot(), SCREEN)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
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
        const val SCREEN = "com/kennyb1201/kbstream/ui/sports/SportsHubScreen.kt"
    }
}
