package com.kennyb1201.kbstream.ui.kb

import com.kennyb1201.kbstream.data.kb.KBContentItem
import com.kennyb1201.kbstream.data.kb.KBRail
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The KB folder page's deep-dive findings, each pinned where it lives.
 *
 *  - **C1**: the page is built entirely from the ACTIVE profile - its imported
 *    collection URLs, its add-ons, its Trakt account, its kids ceiling and its
 *    own watch-history file - but the ViewModel is activity-scoped and
 *    `loadById` answers immediately for a folder it already holds, so reopening
 *    the same folder after a switch showed the profile being LEFT: rails, paging
 *    pointers and badges that no longer had a preload behind them.
 *  - **C2 / C3**: a press resolved TMDB→IMDb and then navigated only on a
 *    non-blank answer, and the menu's watch toggle returned on the same null, so
 *    both were silent no-ops for every title the capped resolution pass missed.
 *  - **C4**: "Open in Grid" was `remember`ed by a composable that a tile press
 *    DISPOSES, so the way back out of a title's Detail page came back in the
 *    manifest's layout - where the grid's rail-less return record had nothing to
 *    land on.
 *  - **C5**: hidden titles are filtered out of the rails, so hiding all of them
 *    read as a folder with no content and offered no way back.
 *  - **S2**: the only `requestFocus` on the page was the return machinery, so
 *    where an ordinary open landed the D-pad was whatever the framework's own
 *    traversal reached - the hero and the source chips are focusable too.
 *
 * Read out of the source where it has to be (this module has no Compose harness
 * for a screen that wants a ViewModel), and executed where the rule is a pure
 * function.
 */
class KBFolderDeepDiveContractTest {

    private val screen by lazy { source(TV_SCREEN) }
    private val layouts by lazy { source(LAYOUTS) }
    private val viewModel by lazy { source(VIEW_MODEL) }
    private val mobile by lazy { source(MOBILE_SCREEN) }

    // ── C1: a profile switch is a reload, not a stale page ───────────────

    @Test
    fun `the folder is dropped when the profile changes, so the next entry loads it again`() {
        assertTrue(
            "the switch is observed from the ViewModel's own constructor: the page is " +
                "activity-scoped and outlives the picker round trip",
            viewModel.contains("init {\n        observeProfileSwitches()\n    }")
        )
        assertTrue(
            "only a genuinely DIFFERENT profile is a switch - editing one re-emits the " +
                "same id (the rule HomeViewModel.observeProfileSwitches follows)",
            viewModel.contains("if (profile?.id == lastProfileId) return@collect")
        )

        val drop = window(viewModel, "private fun dropForProfileSwitch() {", "companion object {")
        assertTrue(
            "the in-flight load is cancelled and its generation bumped BEFORE the state " +
                "is cleared, so a rail still streaming from the profile being left cannot " +
                "publish into what the next profile will read",
            drop.contains("loadJob?.cancel()") &&
                drop.indexOf("loadGeneration += 1") < drop.indexOf("_state.value = UiState()")
        )
        assertTrue(
            "everything the page drew from that profile goes with it: the rails (through " +
                "the state), the paging sources, the resolved ids, the hero, the source " +
                "tab and the visit's grid override",
            drop.contains("tmdbRailSources.clear()") &&
                drop.contains("currentFolderId = null") &&
                drop.contains("gridOverrideFolderId = null") &&
                drop.contains("_resolvedIds.value = emptyMap()") &&
                drop.contains("_heroMeta.value = null")
        )
        assertTrue(
            "and the FOLDER is not held: that is what makes the screen's own entry effect " +
                "load it again, against the incoming profile - the guard that used to " +
                "answer for a folder it already had is the one that has to fail here",
            drop.contains("_state.value = UiState()") &&
                viewModel.contains(
                    "if (_state.value.folder?.id == folderId && !_state.value.isLoading) return"
                ) &&
                screen.contains("viewModel.loadById(folderId)")
        )
        assertTrue(
            "the reload is also the only trigger the badge preload has here: the cache a " +
                "switch clears is refilled as rails arrive (publishRail -> " +
                "resolveAndPreload -> preload), never by a page that does not reload",
            viewModel.contains("resolveAndPreload(listOf(rail))") &&
                viewModel.contains("watchedStatusRepository.preload(")
        )
        assertTrue(
            "and a switch that lands while the page is still OPEN (a synced profile " +
                "change, not the picker's own) is reloaded by the page itself: the epoch " +
                "its entry effect is keyed on is bumped with the drop, or the page would " +
                "sit on the emptied state nothing reloads",
            viewModel.contains("_profileEpoch.value = _profileEpoch.value + 1") &&
                viewModel.contains(
                    "val profileEpoch: StateFlow<Int> = _profileEpoch.asStateFlow()"
                ) &&
                screen.contains("LaunchedEffect(folderId, profileEpoch) {") &&
                mobile.contains("LaunchedEffect(folderId, profileEpoch) {")
        )
    }

    // ── C2 / C3: the press and the mark have an id to write ──────────────

    @Test
    fun `a press and a menu mark fall back to the id the detail route can resolve`() {
        val navigate = window(viewModel, "fun resolveAndNavigate(", "fun markAsWatched(")
        assertTrue(
            "the press opens the page under the title's own tmdb:<n> flavor when the IMDb " +
                "resolution came back empty - the Detail route resolves a TMDB id itself, " +
                "which is the sibling browse pages' rule",
            navigate.contains("val routeId = imdbId?.takeIf { it.isNotBlank() }") &&
                navigate.contains("?: item.tmdbId?.let { WatchedStatusKeys.tmdbKeyId(it) }") &&
                navigate.contains("if (routeId != null) {") &&
                navigate.contains("onNavigateDetail(normalized, routeId)")
        )
        assertFalse(
            "the silent press it replaced is gone",
            navigate.contains("if (!imdbId.isNullOrBlank()) {")
        )
        assertTrue(
            "and an add-on card with no TMDB id still opens when its own id is already a " +
                "route id the Detail page reads",
            navigate.contains("id.startsWith(\"tmdb:\") || id.all(Char::isDigit)")
        )

        val mark = window(
            viewModel,
            "private suspend fun resolveForWatchAction(",
            "private fun normalizeType("
        )
        assertTrue(
            "the toggle keys its write under the flavor the badge actually reads when " +
                "there is no IMDb id: returning null there was a no-op on the row the " +
                "viewer had just pressed",
            mark.contains("return imdbId?.takeIf { it.isNotBlank() }") &&
                mark.contains("?: item.tmdbId?.let { WatchedStatusKeys.tmdbKeyId(it) }")
        )
        assertEquals(
            "the tile press and the menu's Go to Details are the SAME call, so neither can " +
                "be fixed without the other",
            2,
            screen.split("viewModel.resolveAndNavigate(").size - 1
        )
        assertEquals(
            "on the phone too",
            2,
            mobile.split("viewModel.resolveAndNavigate(").size - 1
        )
    }

    // ── C4: the grid override survives the Detail detour ─────────────────

    @Test
    fun `the grid override outlives the trip into a title page`() {
        assertTrue(
            "held by the ViewModel, which is the only thing that outlives the trip, and " +
                "keyed to the folder it was raised for so another folder never opens in " +
                "a grid the viewer asked for elsewhere",
            viewModel.contains("private var gridOverrideFolderId: String? = null") &&
                viewModel.contains("fun setGridOverride(folderId: String?)") &&
                viewModel.contains("fun gridOverrideActive(folderId: String): Boolean")
        )
        assertTrue(
            "RECORDED, not just remembered in memory: the pin used to last exactly as " +
                "long as this ViewModel did, so Back out of the folder and the same " +
                "folder came back in its manifest layout",
            viewModel.contains("AppPreferences.setKbFolderGridPin(getApplication(), folderId)") &&
                viewModel.contains(
                    "gridOverrideFolderId ?: AppPreferences.getKbFolderGridPin(getApplication())"
                )
        )
        assertTrue(
            "and re-read on every load, so a relaunch opens the folder the way it was " +
                "left rather than flipping once the rails have landed",
            viewModel.contains(
                "gridOverrideFolderId = AppPreferences.getKbFolderGridPin(getApplication())"
            )
        )
        assertTrue(
            "the screen seeds its layout from it on the FIRST frame, so a return does not " +
                "draw the rails and then flip to the grid",
            screen.contains("var showAsGrid by remember(folderId) {") &&
                screen.contains("mutableStateOf(viewModel.gridOverrideActive(folderId))")
        )
        assertTrue(
            "the menu records the pin while it is still up (not after), and is also the " +
                "way back out of it: unaided, a recorded layout would have no off switch",
            screen.contains("viewModel.setGridOverride(if (showAsGrid) folderId else null)") &&
                screen.contains("showAsGrid = !showAsGrid") &&
                screen.indexOf("showAsGrid = !showAsGrid") <
                screen.indexOf("viewModel.setGridOverride(")
        )
        assertFalse(
            "Back therefore only leaves the folder - it used to restore the folder's own " +
                "layout first, which is exactly what threw the pin away",
            screen.contains("viewModel.setGridOverride(null)")
        )
        assertTrue(
            "the layout that comes back is the one that can honour a rail-less grid " +
                "record: the grid attaches the page's return requester to the card the " +
                "record names",
            layouts.contains("isReturnItem ->\n                                                Modifier.focusRequester(returnFocusRequester)")
        )
    }

    // ── C5: a folder whose titles are all hidden ─────────────────────────

    @Test
    fun `an all-hidden folder is not an empty one`() {
        assertEquals(
            "all three TV layouts draw the hidden state instead of the empty one, and " +
                "before it",
            3,
            layouts.split("folderAllHidden(state.rails, selectedSourceId, ").size - 1
        )
        assertEquals(
            "each offers the app's one way back from it",
            3,
            layouts.split("actionLabel = \"UNHIDE ALL\"").size - 1
        )
        assertTrue(
            "through the hidden manager's own action, the one Home and the browse pages " +
                "call - not a second rule",
            layouts.contains("onRetry = { HiddenTitles.unhideAll(context) }") &&
                mobile.contains("onAction = { HiddenTitles.unhideAll(context) }")
        )
        assertEquals(
            "and the phone page draws it too",
            1,
            mobile.split(
                "folderAllHidden(state.rails, selectedSourceId, visibleRails)"
            ).size - 1
        )
        assertTrue(
            "both branches are still reachable: the empty message is what a folder that " +
                "served nothing keeps",
            layouts.contains(
                "FolderStatus(\"Nothing to show here yet.\", icon = KB_STATUS_ICON_EMPTY)"
            ) &&
                mobile.contains("message = \"Nothing to show here yet.\",")
        )
    }

    @Test
    fun `the all-hidden rule needs both facts, and is scoped to the selected source`() {
        val first = rail("a", "1", "2")
        val empty = rail("b")
        val second = rail("b", "3", "4", "5")

        assertTrue(
            "served but nothing visible: every title is hidden",
            folderAllHidden(listOf(first), null, emptyList())
        )
        assertFalse(
            "a folder that served nothing is the EMPTY state, not the hidden one - the " +
                "half the browse pages' own branch documents",
            folderAllHidden(emptyList(), null, emptyList())
        )
        assertFalse(
            "and a rail that came back with no titles is not a served one either",
            folderAllHidden(listOf(empty), null, emptyList())
        )
        assertFalse(
            "a populated page never reads as hidden",
            folderAllHidden(listOf(first), null, listOf(first))
        )
        assertTrue(
            "a source tab is judged by ITS OWN rail: hiding every title of the selected " +
                "source is the hidden state even while the other rails still have rows",
            folderAllHidden(listOf(first, second), "b", emptyList()) &&
                folderAllHidden(listOf(first, second), "a", emptyList())
        )
        assertFalse(
            "and a tab with titles still on it is not",
            folderAllHidden(listOf(first, second), "b", listOf(second))
        )
        assertEquals(
            "the selection the rule judges is the page's own: one rail for a tab",
            listOf("b"),
            folderSelectedRails(listOf(first, second), "b").map { it.sourceId }
        )
        assertEquals(
            "and every rail, in page order, for All",
            listOf("a", "b"),
            folderSelectedRails(listOf(first, second), null).map { it.sourceId }
        )
    }

    // ── S2: an ordinary open takes the page's own first card ─────────────

    @Test
    fun `an ordinary open puts the D-pad on the page's own first card`() {
        assertTrue(
            "the page owns one entry requester, the way every sibling browse page does",
            screen.contains("val entryFocusRequester = remember { FocusRequester() }")
        )
        assertTrue(
            "an ordinary open - no record waiting for this page - lands on it, and a " +
                "miss leaves the flag false so the next rail that arrives asks again",
            screen.contains("if (recorded == null) {") &&
                screen.contains("returnHandled = landFocusOn(entryFocusRequester)")
        )
        assertEquals(
            "and so does a return this page could never put back, instead of leaving the " +
                "D-pad wherever the framework dropped it",
            2,
            screen.split("landFocusOn(entryFocusRequester)").size - 1
        )
        assertEquals(
            "the requester reaches every layout, through the page's own body",
            4,
            layouts.split("entryFocusRequester: FocusRequester\n) {").size - 1
        )
        assertTrue(
            "the hero rails' first rail marks its first card",
            layouts.contains("isEntryItem = isFirstRail && itemIndex == 0,") &&
                layouts.contains("isFirstRail = railIndex == 0,")
        )
        assertTrue(
            "the rows layout marks its own",
            layouts.contains("isEntryItem = railIndex == 0 && itemIndex == 0,")
        )
        assertTrue(
            "and the grid marks its first poster",
            layouts.contains("val isEntryItem = itemIndex == 0")
        )
        assertTrue(
            "one card per page carries it, and the request is made against that card",
            layouts.contains("isEntryItem && entryFocusRequester != null ->") &&
                layouts.contains("Modifier.focusRequester(entryFocusRequester)")
        )
        assertTrue(
            "the screen hands it down with the rest of the page's focus plumbing",
            screen.contains("entryFocusRequester = entryFocusRequester,")
        )
    }

    // ── plumbing ─────────────────────────────────────────────────────────

    private fun rail(sourceId: String, vararg ids: String) = KBRail(
        sourceId = sourceId,
        title = sourceId,
        providerLabel = "",
        items = ids.map { id ->
            KBContentItem(
                id = id,
                type = "movie",
                title = "Title $id",
                posterUrl = null,
                backdropUrl = null,
                year = "2020",
                rating = null
            )
        }
    )

    private fun source(path: String): String {
        val file = File(findMainSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun window(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing marker: $start", from >= 0)
        val to = source.indexOf(end, from + start.length)
        assertTrue("missing marker: $end", to > from)
        return source.substring(from, to)
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
        const val TV_SCREEN = "com/kennyb1201/kbstream/ui/kb/KBFolderScreen.kt"
        const val LAYOUTS = "com/kennyb1201/kbstream/ui/kb/KBFolderLayouts.kt"
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/kb/KBFolderViewModel.kt"
        const val MOBILE_SCREEN =
            "com/kennyb1201/kbstream/ui/mobile/KBFolderScreenMobile.kt"
    }
}
