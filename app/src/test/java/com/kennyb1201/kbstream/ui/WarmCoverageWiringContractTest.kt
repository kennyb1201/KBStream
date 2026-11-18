package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The warm-ahead coverage wiring, surface by surface.
 *
 * Spec 2026-11-18, warm-ahead coverage: the rail warm existed but only the Home
 * rails and the TV search Titles rail used it, so a held D-pad down an add-on,
 * actor, studio, collection or keyword rail - or a fling in the phone guide -
 * met cards that decoded on arrival. These pins hold the wiring in place now
 * that it is there, and record WHY three of those rails stay cold: their cards
 * draw no art, so a warm would file a bitmap nothing reads.
 *
 * Source pins, since these are Compose surfaces.
 */
class WarmCoverageWiringContractTest {

    private val homeScreen = source(HOME_SCREEN)
    private val search = source(SEARCH)
    private val searchPart2 = source(SEARCH_PART2)
    private val mobileSearch = source(MOBILE_SEARCH)
    private val catalogGrid = source(CATALOG_GRID)
    private val mobileHome = source(MOBILE_HOME)
    private val mobileGuide = source(MOBILE_GUIDE)

    // ── Continue Watching / Upcoming take the same warm as a catalog rail ──

    @Test
    fun `the CW hero fires the near-field warm, exactly as the catalog hero does`() {
        val cw = block(homeScreen, "fun selectContinueWatchingHero(")
        assertTrue(
            "the row's own hero asks for the warm on focus",
            cw.contains("viewModel.warmHeroFocus(item)")
        )
        assertTrue(
            "and it does so after the focus state is set, not before",
            cw.indexOf("focusedContinueWatchingItem = upNextItem") <
                cw.indexOf("viewModel.warmHeroFocus(item)")
        )
        val catalog = block(homeScreen, "fun selectHero(")
        assertTrue(
            "the catalog hero's call is untouched",
            catalog.contains("viewModel.warmHeroFocus(item)")
        )
        assertTrue(
            "both call sites exist - the ViewModel's CW branch is only reachable from the second",
            homeScreen.split("viewModel.warmHeroFocus(item)").size - 1 == 2
        )
    }

    // ── TV search: every rail that draws art warms; the rest say why not ──

    @Test
    fun `the TV search rails carry the warm - and only the ones with art do`() {
        assertTrue(
            "the rail takes a warm at all",
            searchPart2.contains("warm: RailWarm? = null")
        )
        assertTrue(
            "and runs it against the row's own state and card size",
            block(searchPart2, "internal fun SearchRail(").let { rail ->
                rail.contains("WarmRowAhead(") &&
                    rail.contains("state = state") &&
                    rail.contains("sizePx = with(railDensity)")
            }
        )
        assertTrue(
            "an add-on rail warms its posters",
            search.contains("warm = RailWarm(group.results.size) { index ->")
        )
        assertTrue(
            "a Collections rail warms its posters",
            search.contains("warm = RailWarm(collectionResults.size) { index ->")
        )
        listOf("Actors" to "actorsRailState", "Studios" to "studiosRailState", "Keywords" to "keywordsRailState")
            .forEach { (title, state) ->
                assertTrue(
                    "$title: still declared without a warm, because its cards draw none",
                    search.contains("SearchRail(title = \"$title\", state = $state) {")
                )
            }
        assertFalse(
            "actors are text cards, not posters",
            block(searchPart2, "internal fun PersonResultCard(").contains("AsyncImage")
        )
        assertFalse(
            "studios too - the card is text-only and never reads a logo",
            block(searchPart2, "internal fun StudioResultCard(").contains("AsyncImage")
        )
        assertTrue(
            "a keyword is the app's chip, not a card around art it does not have",
            block(search, "SearchRail(title = \"Keywords\", state = keywordsRailState) {")
                .contains("SearchChip(")
        )
    }

    @Test
    fun `the phone search rails warm the same ones, through the same RailWarm`() {
        val rail = block(mobileSearch, "private fun MobileSearchRail(")
        assertTrue(
            "the rail supports a warm",
            rail.contains("warm: com.kennyb1201.kbstream.ui.search.RailWarm? = null")
        )
        assertTrue(
            "portrait only - a landscape tile reads resolved art, not a poster",
            rail.contains("if (!railLandscape) {") && rail.contains("WarmRowAhead(")
        )
        assertTrue(
            "Titles, add-on and Collections rails pass it",
            mobileSearch.split("com.kennyb1201.kbstream.ui.search.RailWarm(").size - 1 >= 3
        )
        listOf("Actors", "Studios", "Keywords").forEach { title ->
            assertTrue(
                "$title: cold, for the same reason the TV rail is",
                mobileSearch.contains("MobileSearchRail(title = \"$title\") {")
            )
        }
    }

    // ── The landscape half: grids and phone Home rails ──────────────────

    @Test
    fun `the TV catalog grid prefetches landscape art by its own cell keys`() {
        // Both warm branches sit in the grid's `when` arm, ahead of the grid itself
        // (which is why the window runs from one warm to the other).
        val grid = region(catalogGrid, "WarmGridAhead(", "LazyVerticalGrid(")
        assertTrue(
            "poster mode still warms posters",
            grid.contains("WarmGridAhead(") && grid.contains("lazyItems.peek(index)?.poster")
        )
        assertTrue(
            "landscape mode walks the SAME keys this grid lays out",
            grid.contains("PrefetchLandscapeAhead(") &&
                grid.contains("\"\${meta.type}:\${meta.id}#\$index\"") &&
                grid.contains("\"placeholder#\$index\"")
        )
        assertTrue(
            "and it never triggers a page load to warm one",
            grid.contains("lazyItems.peek(index)")
        )
    }

    @Test
    fun `the phone Home rails warm whichever bitmap they actually draw`() {
        val ladder = region(mobileHome, "if (!landscapeCards) {", "PrefetchLandscapeAhead(")
        assertTrue(
            "portrait rails warm their posters",
            ladder.contains("WarmRowAhead(") &&
                ladder.contains("{ index -> rail.items.getOrNull(index)?.poster }")
        )
        val landscape = region(mobileHome, "PrefetchLandscapeAhead(", "LandscapeArtRequest(")
        assertTrue(
            "the landscape walk uses the row's own key, character for character",
            landscape.contains("\"\${meta.type}:\${meta.id}:\${rail.catalogId}\" to")
        )
    }

    // ── The phone guide: memoised rows, warmed logos ─────────────────────

    @Test
    fun `the phone guide memoises its cells and warms the next channels' logos`() {
        assertTrue(
            "per-channel cells, remembered on exactly the three keys a page landing changes",
            mobileGuide.contains("remember(items, programsByChannel, window) {") &&
                mobileGuide.contains("GuideRowCellMemo.cells(") &&
                mobileGuide.contains("channelKey = item.channel.id")
        )
        val warm = region(mobileGuide, "WarmRowAhead(", "LazyColumn(")
        assertTrue(
            "the warm is the channel row itself",
            warm.contains("state = listState") && warm.contains("count = rows.size")
        )
        assertTrue(
            "at the 20dp the logo is drawn, not the plate around it",
            warm.contains("val side = 20.dp.roundToPx()") &&
                warm.contains("IntSize(side, side)")
        )
        assertTrue(
            "and it warms the logo the row actually shows, EPG icon included",
            warm.contains("row.channel.logoUrl ?: row.epgChannel?.iconUrl")
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** One function's (or call's) source, by matching its declaration and balancing braces. */
    private fun block(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        require(at >= 0) { "missing: $declaration" }
        val bodyStart = source.indexOf("{", at)
        require(bodyStart > at) { "no body: $declaration" }
        var depth = 0
        for (i in bodyStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        error("unbalanced: $declaration")
    }

    /** The source between two anchors - for calls whose arguments contain lambdas. */
    private fun region(source: String, from: String, to: String): String {
        val start = source.indexOf(from)
        require(start >= 0) { "missing: $from" }
        val end = source.indexOf(to, start + from.length)
        require(end > start) { "missing after $from: $to" }
        return source.substring(start, end)
    }

    private fun source(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source not found above ${System.getProperty("user.dir")}: $relative")
    }

    private companion object {
        const val HOME_SCREEN = "ui/home/HomeScreen.kt"
        const val SEARCH = "ui/search/SearchScreen.kt"
        const val SEARCH_PART2 = "ui/search/SearchScreenPart2.kt"
        const val MOBILE_SEARCH = "ui/mobile/SearchScreenMobile.kt"
        const val CATALOG_GRID = "ui/home/CatalogGridScreen.kt"
        const val MOBILE_HOME = "ui/mobile/HomeScreenMobile.kt"
        const val MOBILE_GUIDE = "ui/mobile/GuideGridMobileView.kt"
    }
}
