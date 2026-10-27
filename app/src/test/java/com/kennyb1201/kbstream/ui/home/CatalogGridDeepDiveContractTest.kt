package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog grid deep dive's findings, pinned at the source.
 *
 * "Open in Grid" reads as one screen but behaves as a route with a shared,
 * activity-scoped pager behind it, and most of what was wrong came from that
 * split:
 *
 *  - C1: a tile press disposes the screen (this app has no backstack), and the
 *    dispose dropped the pager AND the scroll state, so Back from a title
 *    re-paged the catalog from its first batch and landed at the top.
 *  - C2: the grid draws a FILTERED stream, so an all-hidden catalog arrived at
 *    the empty branch - which says the catalog has nothing in it, a different
 *    fact, and leaves the viewer no way out of that reading.
 *  - S1: the open retried for a fixed five seconds, which is shorter than a
 *    slow add-on can take, so a grid whose rail does exist backed out to Home.
 *  - S2: the route identified a catalog by (title, addonName), while a rail's
 *    real identity is (addonName, catalogId, type).
 *  - S3: nothing claimed the D-pad on entry, on a screen whose only focusable
 *    nodes are its tiles.
 *
 * I1 (the TV grid's unkeyed scroll state, which the phone's grid had already
 * fixed) and I2 (a paging-source branch no caller could reach) are pinned here
 * too.
 *
 * Read from the source: this is Compose effect wiring, a Paging 3 cache and a
 * route's fields, none of which a JVM test can press.
 */
class CatalogGridDeepDiveContractTest {

    // Declared FIRST, and that is not style: these are property initialisers, so
    // the fields below call [stripped] while this object is still being
    // constructed - and a `by lazy` declared after them is still null at that
    // point, which is an NPE in the constructor of every test in the class
    // (`Cannot invoke "kotlin.Lazy.getValue()" because "<local1>" is null`).
    private val sourceRoot: File by lazy { findSourceRoot() }

    private val tv = stripped(GRID_TV)
    private val phone = stripped(GRID_MOBILE)
    private val viewModel = stripped(HOME_VM)
    private val pagingSource = stripped(PAGING_SOURCE)
    private val activity = stripped(MAIN_ACTIVITY)
    private val navState = stripped(NAV_STATE)

    // ── C1: a title page is a detour, not an exit ────────────────────────

    @Test
    fun `both grids record a title page as a detour, by both ways into it`() {
        listOf(GRID_TV to tv, GRID_MOBILE to phone).forEach { (path, src) ->
            assertTrue(
                "$path: the tile press and the menu's Go to Details must both say " +
                    "that the grid is leaving for a title page - the dispose effect " +
                    "cannot ask where the app is going",
                count(src, "leftForDetail = true") >= 2
            )
            assertTrue(
                "$path: the flag is set BEFORE the navigation, so it is already " +
                    "true when the dispose runs",
                src.contains("leftForDetail = true onItemClick(meta)") &&
                    src.contains("leftForDetail = true onItemClick(target)")
            )
        }
    }

    @Test
    fun `the pager and the viewer's place survive the trip and go with any other exit`() {
        listOf(GRID_TV to tv, GRID_MOBILE to phone).forEach { (path, src) ->
            // Unit-keyed on purpose: the effect answers "is this screen
            // leaving", not "did the route change".
            val dispose = window(
                src,
                "DisposableEffect(Unit) {",
                "returnRestored by remember"
            )
            assertTrue(
                "$path: the detour keeps the position",
                dispose.contains(
                    "viewModel.rememberCatalogGridScroll(gridState.firstVisibleItemIndex)"
                )
            )
            assertTrue(
                "$path: every other departure drops the shared state, so a grid " +
                    "opened later cannot resume a catalog the viewer has left",
                dispose.contains("viewModel.closeCatalogGrid()")
            )
            assertTrue(
                "$path: and the keep comes first - the two are alternatives, not " +
                    "a sequence",
                dispose.indexOf("rememberCatalogGridScroll") <
                    dispose.indexOf("closeCatalogGrid")
            )
            assertTrue(
                "$path: keyed on the screen rather than the route, so the effect " +
                    "cannot also fire for the grid being replaced in the same " +
                    "composition slot - where the close belongs to the outgoing grid " +
                    "and the open to the incoming one",
                dispose.startsWith("DisposableEffect(Unit) {")
            )
            assertTrue(
                "$path: the return lands on the remembered tile",
                src.contains("val restored = viewModel.catalogGridReturnIndex()") &&
                    src.contains("gridState.scrollToItem(restored)")
            )
        }
    }

    @Test
    fun `the ViewModel caches the grid's pages for the detour and drops them on close`() {
        assertTrue(
            "the pages have to outlive the screen, or the return re-collects the " +
                "Pager flow from its first key - which is the re-paging half of C1",
            count(viewModel, "cachedIn(gridScope)") >= 2
        )
        assertTrue(
            "the cache gets the grid's own scope, a child of the ViewModel's, so " +
                "closing the grid really frees the pages instead of leaving them " +
                "cached for the rest of the process",
            viewModel.contains("SupervisorJob(viewModelScope.coroutineContext[Job])") &&
                viewModel.contains("private var catalogGridScope: CoroutineScope? = null")
        )
        assertTrue(
            "closing cancels it, and a fresh grid cancels whatever was open before it",
            count(viewModel, "catalogGridScope?.cancel()") >= 2
        )
        assertTrue(
            "and the re-open is a no-op for the SAME rail, which is what makes the " +
                "return a resume rather than a reload",
            viewModel.contains(
                "if (_catalogGridHeader.value?.identity == railKeyOf(rail)) { return }"
            )
        )
    }

    @Test
    fun `the remembered position belongs to one grid and one pager`() {
        assertTrue(
            "the write names the grid it is for, and refuses when no grid is open - " +
                "the route has already dropped the pager by then, and an index for a " +
                "pager that no longer exists would be restored onto a fresh one",
            viewModel.contains("fun rememberCatalogGridScroll(index: Int)") &&
                viewModel.contains(
                    "catalogGridScrollIdentity = current.identity"
                ) &&
                viewModel.contains("val current = _catalogGridHeader.value ?: return")
        )
        assertTrue(
            "the read answers only for the grid that is open now",
            viewModel.contains("fun catalogGridReturnIndex(): Int") &&
                viewModel.contains(
                    "return if (current.identity == catalogGridScrollIdentity) { " +
                        "catalogGridScrollIndex } else { 0 }"
                )
        )
        assertTrue(
            "and both are cleared with the pager (see closeCatalogGrid), so the " +
                "index only lives as long as the pages it describes",
            window(viewModel, "fun closeCatalogGrid() {", "fun rememberCatalogGridScroll")
                .contains("catalogGridScrollIdentity = null")
        )
    }

    // ── C2: an all-hidden grid is not an empty catalog ───────────────────

    @Test
    fun `the ViewModel says whether the pager holds anything at all`() {
        assertTrue(
            "the raw half of the guard, published for both grids",
            viewModel.contains("val catalogGridServedItems: StateFlow<Boolean>")
        )
        assertTrue(
            "set from the batch the rail already holds",
            viewModel.contains("_catalogGridServedItems.value = rail.items.isNotEmpty()")
        )
        assertTrue(
            "and from each page the source serves - the SERVED page, post kids and " +
                "upcoming filtering, so a catalog those filters emptied is not " +
                "mislabelled as hidden",
            viewModel.contains("if (filtered.isNotEmpty()) {") &&
                viewModel.contains("_catalogGridServedItems.value = true")
        )
    }

    @Test
    fun `both grids name hiding, and offer the one press that undoes it`() {
        assertTrue(
            "the branch needs BOTH halves: the served half is true on every " +
                "populated catalog, and only the filtered half distinguishes " +
                "\"hidden\" from \"nothing here\"",
            tv.contains("lazyItems.itemCount == 0 && catalogGridServed ->") &&
                phone.contains("lazyItems.itemCount == 0 && catalogGridServed ->")
        )
        assertTrue(
            "it comes before the empty branch it replaces, and after the error " +
                "branch - a failed first page is still a failure, not an empty grid",
            tv.indexOf("lazyItems.itemCount == 0 && catalogGridServed ->") in
                0 until tv.indexOf("message = \"Nothing to show here.\"") &&
                tv.indexOf("refreshState is LoadState.Error && lazyItems.itemCount == 0 ->") in
                0 until tv.indexOf("lazyItems.itemCount == 0 && catalogGridServed ->")
        )
        assertTrue(
            "the TV card says hiding is the cause and unhides in place",
            tv.contains("message = \"Every title here is hidden.\"") &&
                tv.contains("actionLabel = \"UNHIDE ALL\"") &&
                tv.contains("onRetry = { HiddenTitles.unhideAll(context) }")
        )
        assertTrue(
            "and the phone's status card does the same",
            phone.indexOf("lazyItems.itemCount == 0 && catalogGridServed ->") in
                0 until phone.indexOf("key = \"empty\"") &&
                phone.contains("message = \"Every title here is hidden.\"") &&
                phone.contains("action = \"UNHIDE ALL\"") &&
                phone.contains("onAction = { HiddenTitles.unhideAll(context) }")
        )
    }

    // ── S1: the wait is on the rails, not on a clock ─────────────────────

    @Test
    fun `the open waits for the rail build to settle instead of a fixed five seconds`() {
        listOf(GRID_TV to tv, GRID_MOBILE to phone).forEach { (path, src) ->
            assertFalse(
                "$path: the fixed fifty-attempt clock is what backed out of a grid " +
                    "whose rail was still coming",
                src.contains("repeat(50)")
            )
            assertTrue(
                "$path: the loop keeps trying while a build is running or the rail " +
                    "list is still landing, and gives up only once it has been still",
                src.contains("viewModel.isLoading.value || railCount != lastRailCount") &&
                    src.contains("settledMs = 0L") &&
                    src.contains("settledMs < CATALOG_GRID_OPEN_SETTLE_MS") &&
                    src.contains("elapsedMs < CATALOG_GRID_OPEN_MAX_MS")
            )
            assertTrue(
                "$path: with a hard ceiling, so a build that never settles cannot " +
                    "strand the viewer on a skeleton",
                src.contains("private const val CATALOG_GRID_OPEN_MAX_MS = 30_000L")
            )
            assertTrue(
                "$path: and the pop-back still happens once the rails have settled " +
                    "without this catalog",
                src.indexOf("lastRailCount = railCount") in
                    0 until src.indexOf("onBack()") &&
                    src.contains("viewModel.openCatalogInGrid(title, addonName, catalogId, type)")
            )
        }
    }

    // ── S2: the route carries the rail's real identity ───────────────────

    @Test
    fun `the grid is identified by the rail, not by its catalog name`() {
        val route = window(activity, "data class CatalogGrid(", ") : Screen()")
        assertTrue(
            "the route carries the catalog id and type a rail's identity is made of",
            route.contains("val catalogId: String? = null,") &&
                route.contains("val type: String? = null,")
        )
        assertTrue(
            "the long-press hands them over",
            window(activity, "onOpenCatalogGrid = { rail ->", "returnTo = Screen.Home")
                .let { open ->
                    open.contains("catalogId = rail.catalogId,") &&
                        open.contains("type = rail.type,")
                }
        )
        assertTrue(
            "and both grids are given them",
            count(activity, "catalogId = current.catalogId,") >= 2 &&
                count(activity, "type = current.type,") >= 2
        )
        assertTrue(
            "the ViewModel looks the rail up by that identity, which is the only " +
                "thing that tells two same-named rails apart",
            viewModel.contains("catalogRailKey(addonName, catalogId, type)") &&
                viewModel.contains("railKeyOf(rail) == wanted")
        )
        assertTrue(
            "with the name pair kept as the fallback for a catalog whose id its " +
                "add-on never named, or state saved by an older build",
            viewModel.contains("rail.catalogName == title && rail.addonName == addonName")
        )
        assertTrue(
            "and the route persists both, so a restore reopens the same rail",
            navState.contains("screen.catalogId?.let { put(\"catalogId\", it) }") &&
                navState.contains("type = json.optNullableString(\"type\"),")
        )
    }

    // ── S3: the D-pad has an entry anchor ────────────────────────────────

    @Test
    fun `the TV grid claims the D-pad on entry, from the tile at the top of the viewport`() {
        assertTrue(
            "the anchor aims at the tile the viewer is looking at, not at tile 0: " +
                "a return from a title page restores a deep position",
            tv.contains("items.peek(gridState.firstVisibleItemIndex)")
        )
        assertTrue(
            "claimed once, and only after the restored position has settled - an " +
                "anchor placed before it would name a tile that is about to scroll " +
                "away",
            tv.contains("var entryFocusPlaced by remember { mutableStateOf(false) }") &&
                tv.contains("if (entryFocusPlaced || !scrollSettled) return@LaunchedEffect") &&
                tv.contains("entryFocusPlaced = landFocusOn(cardFocusRequesters[topKey])")
        )
        assertTrue(
            "and released the moment focus lands anywhere in the grid, so a page " +
                "arriving later cannot pull the viewer off the tile they moved to",
            tv.contains(".onFocusChanged { if (it.hasFocus) entryFocusPlaced = true }")
        )
        assertFalse(
            "the phone needs none of it: a finger scroll has no focus to lose",
            phone.contains("entryFocusPlaced")
        )
    }

    // ── I1 / I2 ─────────────────────────────────────────────────────────

    @Test
    fun `both grids key their scroll state to the catalog they are showing`() {
        assertTrue(
            "unkeyed, a catalog opened in the same composition slot restored the " +
                "previous one's offset instead of starting at the top - the phone " +
                "fixed this and the TV grid carried the unfixed shape (I1)",
            tv.contains("val gridState = key(title, addonName) { rememberLazyGridState() }") &&
                phone.contains("val gridState = key(title, addonName) { rememberLazyGridState() }")
        )
    }

    @Test
    fun `the exhausted-catalog rule lives in the paging source, where it can run`() {
        assertTrue(
            "the source keeps the branch",
            pagingSource.contains("if (!moreAvailable) {")
        )
        assertTrue(
            "and the caller routes an exhausted rail THROUGH the source instead of " +
                "around it, which is what makes the branch reachable at all (I2)",
            viewModel.contains("moreAvailable = railKey !in exhaustedRails,")
        )
        assertFalse(
            "the second, unreachable spelling of the same rule is gone",
            viewModel.contains("info == null || railKey in exhaustedRails")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun stripped(path: String): String =
        stripComments(raw(path))
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun raw(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun count(haystack: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(haystack).count()

    /** The squashed source between the first [from] anchor and the next [to]. */
    private fun window(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("anchor missing: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("anchor missing after '$from': $to", end > start)
        return text.substring(start, end)
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
        const val GRID_TV = "com/kennyb1201/kbstream/ui/home/CatalogGridScreen.kt"
        const val GRID_MOBILE = "com/kennyb1201/kbstream/ui/mobile/CatalogGridScreenMobile.kt"
        const val HOME_VM = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val PAGING_SOURCE =
            "com/kennyb1201/kbstream/ui/home/CatalogGridPagingSource.kt"
        const val MAIN_ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
        const val NAV_STATE = "com/kennyb1201/kbstream/NavStateSerialization.kt"
    }
}
