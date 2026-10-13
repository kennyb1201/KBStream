package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The P1 focus/navigation batch, on the surfaces that are not Home: an entry
 * focus that has to be VERIFIED before the page calls itself focused, a hub
 * that has to claim focus at all, a live poll that has to stop when the hub is
 * not on screen, a grid that has to come back where it was, a browse chip whose
 * Hide has to hand focus over before it leaves, and a player picker that must
 * not open empty.
 *
 * Read from the source: this is Compose focus wiring, a ViewModel's coroutine
 * scope and a View's visibility, none of which a JVM test can press.
 */
class EntryFocusBatchContractTest {

    private val decade = normalized(stripComments(source(DECADE)))
    private val studio = normalized(stripComments(source(STUDIO)))
    private val tag = normalized(stripComments(source(TAG)))
    private val sports = normalized(stripComments(source(SPORTS)))
    private val sportsViewModel = normalized(stripComments(source(SPORTS_VM)))
    private val search = normalized(stripComments(source(SEARCH)))
    private val browser = normalized(stripComments(source(BROWSER)))
    private val player = normalized(stripComments(source(PLAYER)))

    // ── Decade / Studio / Tag: the flag may not be set before the landing ──

    @Test
    fun `the rails pages mark themselves focused only once the landing answered`() {
        listOf(DECADE to decade, STUDIO to studio, TAG to tag).forEach { (path, src) ->
            assertTrue(
                "$path: the landing must answer whether it landed, so the caller " +
                    "can key the flag off it",
                src.contains("suspend fun focusFirstCard(): Boolean")
            )
            assertTrue(
                "$path: the landing is retried across frames and read from the " +
                    "request's OWN answer - an unattached requester answers false " +
                    "rather than throwing",
                src.contains(
                    "landed = runCatching { firstItemFocusRequester.requestFocus() } " +
                        ".getOrDefault(false)"
                )
            )
            assertTrue(
                "$path: the flag comes FROM the landing. Setting it true first " +
                    "was the bug: this effect restarts whenever another rail " +
                    "streams in, so an attempt cancelled inside its delay left " +
                    "the flag true, the restarted effect skipped the branch, and " +
                    "an ordinary first open had nothing focused and a dead D-pad.",
                src.contains("focusPlaced = focusFirstCard()")
            )
            assertFalse(
                "$path: the fixed delay + single unverified request is gone",
                src.contains("suspend fun focusFirstCard() { delay(100) runCatching")
            )
        }
    }

    // ── Sports hub: initial focus, the live poll, and the grid's place ────

    @Test
    fun `the hub claims focus on entry, from the selected tab, without stealing a reopened sheet`() {
        assertTrue(
            "the selected tab carries a requester the screen can name (the " +
                "row is built by LeagueTabRow, so it is passed down)",
            sports.contains("val selectedTabFocus = remember { FocusRequester() }") &&
                sports.contains("selectedFocusRequester = selectedTabFocus,") &&
                sports.contains("selectedFocusRequester: FocusRequester? = null,")
        )
        assertTrue(
            "and the landing is retried across frames, read from the request's " +
                "return value - runCatching's isSuccess only ever means \"no " +
                "exception was thrown\", which is always true",
            sports.contains("landed = runCatching { selectedTabFocus.requestFocus() } " +
                ".getOrDefault(false)")
        )
        assertTrue(
            "the claim is one-shot: a league switched on later must not pull the " +
                "viewer off the card they are reading",
            sports.contains("var entryFocusPlaced by remember { mutableStateOf(false) }") &&
                sports.contains("if (entryFocusPlaced || leagues.isEmpty()) return@LaunchedEffect") &&
                sports.contains("entryFocusPlaced = landed")
        )
        assertTrue(
            "and a return from the player that reopens a sheet is left alone - " +
                "that sheet parks focus on its own WATCH button",
            sports.contains(
                "if (initialDetailGameId != null || initialDetailTournamentId != null) { " +
                    "entryFocusPlaced = true return@LaunchedEffect }"
            )
        )
        assertFalse(
            "and nothing anywhere in the hub reads a landing off a swallowed " +
                "call (runCatching's isSuccess is true for every call ever made)",
            sports.contains("requestFocus() }.isSuccess") ||
                sports.contains("requestFocus() .isSuccess")
        )
    }

    @Test
    fun `the live tick stops when the hub is not the screen in front of the viewer`() {
        assertTrue(
            "the ViewModel can stop and re-arm the tick",
            sportsViewModel.contains("fun pauseLivePoll() {") &&
                sportsViewModel.contains("fun resumeLivePoll() {")
        )
        assertTrue(
            "a stopped tick is not re-armed by the hub's own state changes",
            sportsViewModel.contains("if (pollPaused) return")
        )
        assertFalse(
            "and the KDoc no longer claims onCleared is what ends it - the " +
                "ViewModel is activity-scoped, so that fires only with the activity",
            sportsViewModel.contains("nothing polls once the hub is gone")
        )
        assertTrue(
            "the screen stops it on the way out and re-arms it on the way back in: " +
                "the player is a Screen in the same activity, so it disposes this " +
                "composable without dying",
            sports.contains("viewModel.resumeLivePoll()") &&
                sports.contains("viewModel.pauseLivePoll()") &&
                sports.contains("DisposableEffect(Unit) {")
        )
    }

    @Test
    fun `the hub's grid comes back where it was, and a tab switch starts its own slate at the top`() {
        assertTrue(
            "the list is seeded from the ViewModel's memory of it, because the " +
                "screen does not survive the player",
            sports.contains(
                "val listState = rememberLazyGridState( " +
                    "initialFirstVisibleItemIndex = viewModel.hubScrollIndex, " +
                    "initialFirstVisibleItemScrollOffset = viewModel.hubScrollOffset )"
            )
        )
        assertTrue(
            "and written back on the way out",
            sports.contains(
                "viewModel.rememberHubScroll( listState.firstVisibleItemIndex, " +
                    "listState.firstVisibleItemScrollOffset )"
            )
        )
        assertTrue(
            "the ViewModel holds it",
            sportsViewModel.contains("var hubScrollIndex: Int = 0") &&
                sportsViewModel.contains("fun rememberHubScroll(index: Int, offset: Int)")
        )
        assertTrue(
            "one list state is shared by every tab, so a switch must not inherit " +
                "the league's before it",
            sports.contains(
                "if (path != selectedPath) { viewModel.forgetHubScroll() " +
                    "scrollScope.launch { listState.scrollToItem(0) } }"
            ) && sportsViewModel.contains("fun forgetHubScroll()")
        )
    }

    // ── Search: the Hide hand-off ────────────────────────────────────────

    @Test
    fun `hiding a browse chip hands focus to its successor before the chip goes`() {
        assertTrue(
            "the requesters live at the screen, where the menu action can reach " +
                "them (the grid's per-item remember is unreachable from here)",
            search.contains(
                "val browseChipFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }"
            ) && search.contains("chipFocusRequesters = browseChipFocusRequesters,")
        )
        assertTrue(
            "the grid takes the caller's registry rather than making its own",
            browser.contains("chipFocusRequesters: MutableMap<String, FocusRequester>") &&
                browser.contains("chipFocusRequesters.getOrPut(chipKey)")
        )
        assertFalse(
            "and the per-item remember it used to make is gone",
            browser.contains("val chipFocusRequester = remember(entry.name) { FocusRequester() }")
        )
        val successor = search.indexOf("BrowseChipVisibility.successorKey(")
        val request = search.indexOf("browseChipFocusRequesters[key]?.requestFocus()")
        val hide = search.indexOf("viewModel.hideBrowseChip(categoryKey, entry)")
        assertTrue("the successor is resolved", successor >= 0 && request > successor)
        assertTrue(
            "and it takes the focus BEFORE the chip leaves the list - the " +
                "request only lands while the menu is still in the tree, and the " +
                "hidden chip's own node is the one going away",
            hide > request
        )
        assertTrue(
            "the successor rule is the shared clamped one the results rail's Hide " +
                "uses too: the next chip, or the previous one when the hidden chip " +
                "was last - never the first, which is the jump this exists to prevent",
            normalized(stripComments(source(PATH_CHIP_VISIBILITY))).contains(
                "val successor = successorIndexAfter(names.size, index) ?: return null"
            ) && normalized(stripComments(source(PATH_RULES))).contains(
                "internal fun successorIndexAfter(size: Int, index: Int): Int? { " +
                    "if (index !in 0 until size) return null " +
                    "if (index + 1 < size) return index + 1 " +
                    "return (index - 1).takeIf { it >= 0 } }"
            )
        )
    }

    // ── Player: the picker may not open empty ────────────────────────────

    @Test
    fun `the exo picker builds its rows before anything becomes visible`() {
        val showPicker = player.indexOf("private fun showPicker(mode: PickerMode) {")
        assertTrue("the picker is still there", showPicker >= 0)
        val items = player.indexOf("val items = when (mode) {", showPicker)
        val guard = player.indexOf("if (items.isEmpty()) return", items)
        val visible = player.indexOf("pickerContainer.visibility = View.VISIBLE", guard)
        assertTrue("the visible list is built first", items > showPicker)
        assertTrue(
            "an empty list abandons the call - a visible, empty, dimmed picker " +
                "has no adapter, never reaches its posted requestFocus, and " +
                "disables the chrome auto-hide, so only Back gets out of it",
            guard > items
        )
        assertTrue(
            "and only THEN does the container come up",
            visible > guard
        )
        val midStateReturn = player.indexOf("currentTracks ?: return", showPicker)
        assertTrue(
            "a released player is an empty track list, not a mid-state return " +
                "that leaves the panel up. Scoped to this function: another " +
                "caller legitimately returns null on a null track list",
            midStateReturn == -1 || midStateReturn > guard
        )
    }

    private fun source(path: String): String =
        File(findMainSourceRoot(), path).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ").trim()

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
        const val DECADE = "com/kennyb1201/kbstream/ui/decade/DecadeScreen.kt"
        const val STUDIO = "com/kennyb1201/kbstream/ui/studio/StudioScreen.kt"
        const val TAG = "com/kennyb1201/kbstream/ui/tag/TagScreen.kt"
        const val SPORTS = "com/kennyb1201/kbstream/ui/sports/SportsHubScreen.kt"
        const val SPORTS_VM = "com/kennyb1201/kbstream/ui/sports/SportsHubViewModel.kt"
        const val SEARCH = "com/kennyb1201/kbstream/ui/search/SearchScreen.kt"
        const val BROWSER = "com/kennyb1201/kbstream/ui/search/BrowseBrowser.kt"
        const val PATH_CHIP_VISIBILITY =
            "com/kennyb1201/kbstream/ui/search/BrowseChipVisibility.kt"
        const val PATH_RULES = "com/kennyb1201/kbstream/ui/search/BrowseRules.kt"
        const val PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
