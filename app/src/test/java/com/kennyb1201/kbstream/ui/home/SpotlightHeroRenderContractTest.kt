package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Spotlight hero's render path, second pass (spec 2026-11-12 follow-up).
 *
 * Build 0.9.249 answered the first render spec's diagnostics and nothing more:
 * two pastes show the gate open (`layout=spotlight spotlightActive=yes`), the
 * pool healthy (20 dealt, 20 settled, 0 null) and no hero - deterministically,
 * in a session 47 minutes old, so not a startup transient. B1 (layout) and B3
 * (a stale enabled flag) are dead by that evidence, which leaves three:
 *
 *  - B2, the item is not in the list;
 *  - B4, the section draws a page with no art at all;
 *  - B5, the item is there but off the top / measured at nothing.
 *
 * The paste could not tell them apart, so this pins the instrumentation that
 * can (the `render` line, written from both halves of the path) and the two
 * render defects it fixes outright:
 *
 *  1. THE ENTRY LANDING (B5). The landing's hero floor was taken from the laid
 *     out hero item and answered 0f when that item was not yet in
 *     `visibleItemsInfo` - which handed the first landing to the fixed header
 *     floor and scrolled the whole 52% hero off the top. It is now the hero's
 *     own height whenever the list is at its start.
 *  2. THE ART (B4). A page can settle with no art at all; the hero then drew a
 *     black frame. It now draws the next settled page's art instead, without
 *     ever letting that fallback replace a page's own poster.
 *
 * The dwell and the panel are untouched (S3), and pinned so they stay that way.
 */
class SpotlightHeroRenderContractTest {

    private val home by lazy { source(HOME_SCREEN) }
    private val section by lazy { source(SPOTLIGHT_HOME) }
    private val mobile by lazy { source(MOBILE_HOME) }
    private val rules by lazy { source(BILLBOARD_RULES) }

    // ── F1: both halves of the render, in the paste ───────────────────────

    @Test
    fun `the Home reports the hero item, its measured height and where the list rests`() {
        val block = home.substringAfter("if (spotlightActive) {\n                    SideEffect {")
            .substringBefore("LazyColumn(")
        assertTrue("the list half is written", block.contains("SpotlightDiagTrace.billboardRenderList("))
        assertTrue(
            "it finds the hero by its own key",
            block.contains("item.key == SPOTLIGHT_HERO_ITEM_KEY")
        )
        assertTrue(
            "and reports the measured height against the viewport, in dp",
            block.contains("(it.size / density.density).toInt()") &&
                block.contains("(info.viewportSize.height / density.density).toInt()")
        )
        assertTrue(
            "plus where the list rests, which is what separates 'absent' from 'scrolled'",
            block.contains("firstIndex = railListState.firstVisibleItemIndex") &&
                block.contains("firstOffset = railListState.firstVisibleItemScrollOffset") &&
                block.contains("itemCount = info.totalItemsCount")
        )
    }

    @Test
    fun `the pager reports the page in front, its art, and the gate that returned`() {
        assertTrue(
            "the pager half names the page and what art it has",
            mobile.contains("SpotlightDiagTrace.billboardRenderHero(") &&
                mobile.contains("pageKey = page?.key") &&
                mobile.contains("\"no-page\"") &&
                mobile.contains("\"borrowed\"")
        )
        val gate = section.substringAfter("if (!billboardEnabled || pages.none { page -> page.settled }) {")
            .substringBefore("SpotlightDiagTrace.billboardRenderHero(")
        assertTrue(
            "the section reports WHICH gate returned, instead of returning silently",
            section.contains("SpotlightDiagTrace.billboardRenderHero(") &&
                section.contains(
                    "gate = if (!billboardEnabled) \"!billboardEnabled\" else \"!settled\""
                ) && gate.isNotBlank()
        )
    }

    // ── B5: the entry landing keeps the hero on screen ────────────────────

    @Test
    fun `the landing's hero floor is the hero's height, not a laid-out item that may be missing`() {
        assertTrue(
            "the floor is taken while the list is at its start",
            home.contains(
                "val heroFloorPx = if (spotlightActive && !railListState.canScrollBackward) {"
            )
        )
        assertTrue(
            "from the height the Home already handed the item",
            home.contains("with(density) { spotlightHeroItemHeight.toPx() }")
        )
        assertFalse(
            "and not from a visibleItemsInfo lookup that answers 0f when nothing is laid out yet - " +
                "which handed the first landing to the header floor and scrolled the hero away",
            home.contains(
                "railListState.layoutInfo.visibleItemsInfo\n" +
                    "                        .firstOrNull { item -> item.key == SPOTLIGHT_HERO_ITEM_KEY }\n" +
                    "                        ?.let { item -> (item.offset + item.size).toFloat() }"
            )
        )
    }

    // ── B4: never a black frame ───────────────────────────────────────────

    @Test
    fun `a page with no art borrows the next settled page's, and its own art always wins`() {
        assertTrue(
            "one rule for 'what art does this page have'",
            rules.contains("internal fun billboardPageArt(page: BillboardPage): String?") &&
                rules.contains("listOf(page.backdropUrl, page.meta.background, page.meta.poster)")
        )
        val fallback = rules.substringAfter("internal fun billboardHeroArt(")
            .substringBefore("internal fun billboardSweep(")
        assertTrue(
            "a page that HAS art keeps exactly what it had",
            fallback.contains("if (billboardPageArt(page) != null) return page.backdropUrl")
        )
        assertTrue(
            "and a page that has none borrows the next SETTLED page's, wrapping once",
            fallback.contains("for (step in 1 until pages.size)") &&
                fallback.contains("if (!next.settled) continue")
        )
        assertTrue(
            "the hero draws through that rule, so a black frame is not reachable",
            mobile.contains("backdropUrl = billboardHeroArt(pages, index)")
        )
        assertFalse(
            "not the raw field, which is what let the frame be blank",
            mobile.contains("backdropUrl = page.backdropUrl,")
        )
    }

    // ── S3: nothing else moved ────────────────────────────────────────────

    @Test
    fun `the dwell, the panel and the scroll-away are untouched`() {
        assertTrue(
            "the dwell still keys on the held card",
            section.contains("LaunchedEffect(heldCard, expandDelayMs, menuOpen) {")
        )
        assertTrue(
            "the hero is still the list's first item, so it still scrolls away",
            home.contains("key = SPOTLIGHT_HERO_ITEM_KEY") &&
                home.contains(".height(spotlightHeroItemHeight)")
        )
        assertTrue(
            "and the section still returns early on its own gate",
            section.contains("if (!billboardEnabled || pages.none { page -> page.settled }) {")
        )
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private companion object {
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val SPOTLIGHT_HOME = "com/kennyb1201/kbstream/ui/home/SpotlightHome.kt"
        const val MOBILE_HOME = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
        const val BILLBOARD_RULES = "com/kennyb1201/kbstream/ui/home/BillboardRules.kt"
    }
}
