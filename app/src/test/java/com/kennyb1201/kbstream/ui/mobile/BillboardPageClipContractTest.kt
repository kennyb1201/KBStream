package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-17: the billboard's page clips to its own bounds.
 *
 * The bug is a sliver of the NEXT title's art at the billboard's right edge on a
 * page sitting still (phone, builds 363/365; TV, every page). Two earlier fixes
 * did not clear it because neither is the mechanism: the pager was never
 * off-centre (`billboard: rest page=9 offset=0.00 corrections=0`), and the sweep
 * fix covers the current page's own frame. The strip is the NEIGHBOUR's art.
 *
 * A page that is not current is held at its sweep's START scale - 1.04 on an
 * even page, 1.10 on an odd one - centred, so it is DRAWN 2 % / 5 % of a page
 * wider than its own box on each side. Nothing in the billboard path clipped it,
 * and a later page paints over an earlier one in that overlap. Clipping each
 * page is the fix; the motion itself must not change.
 *
 * These pins hold the clip, the motion, and the neighbours it must not cut.
 */
class BillboardPageClipContractTest {

    private val mobile by lazy { source("ui/mobile/HomeScreenMobile.kt") }
    private val rules by lazy { source("ui/home/BillboardRules.kt") }

    /** The MobileHero body, up to the next top-level function. */
    private fun hero(): String = mobile.substring(
        mobile.indexOf("private fun MobileHero("),
        mobile.indexOf("private fun billboardArtSource(")
    )

    /** The pager's own call, up to its first page body. */
    private fun pagerSetup(): String = mobile
        .substringAfter("            HorizontalPager(")
        // The sweep no longer takes a clock (spec audit 2026-11-18, Home L2), so
        // this anchor lost its `cycleMs` - a stale one silently returned the
        // whole file, which is how the "no contentPadding" pin below started
        // reading the phone Home's own lists.
        .substringBefore("                val sweep = remember(")

    // ── F1: the page clips ────────────────────────────────────────────────

    @Test
    fun `MobileHero's root clips the page to its own bounds`() {
        assertTrue(
            "the modifier is imported, not fully qualified inline",
            mobile.contains("import androidx.compose.ui.draw.clipToBounds")
        )
        val hero = hero()
        val root = hero.substringAfter("    Box(\n        modifier = Modifier")
            .substringBefore("    ) {")
        assertTrue(
            "the page root carries the clip",
            root.contains(".clipToBounds()")
        )
        assertTrue(
            "after the size modifiers, so the clip is the page's own box",
            root.indexOf(".clipToBounds()") > root.indexOf("Modifier.fillMaxWidth()")
        )
        assertTrue(
            "and before the tap, so the touch target is the page either way",
            root.indexOf(".clipToBounds()") < root.indexOf(".clickable(onClick = onClick)")
        )
        assertFalse(
            "one clip per page: the neighbour's overflow is what leaked, and it " +
                "leaks at the page edge",
            hero.split(".clipToBounds()").size > 2
        )
    }

    @Test
    fun `only the shared billboard is clipped`() {
        // F5: the clip is the pager's own containment. Nothing else in Home gains
        // a clip from this fix, so no other surface's drawing changes.
        assertTrue(
            "the pager's frame keeps its rounded clip as well - the page clip is " +
                "rectangular and sits inside it",
            mobile.contains(".clip(RoundedCornerShape(20.dp))")
        )
        assertTrue(
            "the neighbour is still composed, so the strip's absence is the clip " +
                "and not a lost page",
            pagerSetup().contains("beyondViewportPageCount = 1")
        )
    }

    // ── F2: the sweep is unchanged ────────────────────────────────────────

    @Test
    fun `the Ken Burns motion is untouched`() {
        assertTrue(
            "1.10 at the far end",
            rules.contains("BILLBOARD_KEN_BURNS_MAX_SCALE = 1.10f")
        )
        assertTrue(
            "and the 1.04 floor, which is what the neighbour rests at",
            rules.contains(
                "BILLBOARD_KEN_BURNS_MIN_SCALE =\n    1f + 2f * BILLBOARD_KEN_BURNS_PAN_FRACTION"
            ) && rules.contains("BILLBOARD_KEN_BURNS_PAN_FRACTION = 0.02f")
        )
        val hero = hero()
        assertTrue(
            "the page still scales and pans in its own graphics layer - the clip " +
                "contains the motion, it does not replace it",
            hero.contains("val scale = sweep.startScale +") &&
                hero.contains("scaleX = scale") &&
                hero.contains("translationX = (")
        )
    }

    @Test
    fun `the current page's art still fills its frame edge to edge`() {
        // The clip is the page's bounds, which is where the frame's own edges are:
        // the current page is at the sweep's scale with its pan, i.e. at or above
        // 1.0 across the frame, so nothing of ITS art is cut.
        assertTrue(
            "the backdrop is drawn full-size and cropped to the page",
            hero().contains(".fillMaxSize()") &&
                hero().contains("contentScale = ContentScale.Crop")
        )
        assertTrue(
            "and the sweep never scales below 1",
            rules.contains("1f + 2f * BILLBOARD_KEN_BURNS_PAN_FRACTION")
        )
    }

    // ── F3: nothing else in the page is cut ───────────────────────────────

    @Test
    fun `the chevrons, the close mark and the copy sit inside the page`() {
        val hero = hero()
        assertTrue(
            "the chevrons are page children at the two edges, 2dp in - a 48dp " +
                "circle whose focus ring is drawn inside it",
            hero.contains("align(Alignment.CenterStart)") &&
                hero.contains("padding(start = 2.dp)") &&
                hero.contains("align(Alignment.CenterEnd)") &&
                hero.contains("padding(end = 2.dp)")
        )
        assertTrue(
            "the trailer's close mark is the top-end corner, 4dp in",
            hero.contains("align(Alignment.TopEnd)") && hero.contains("padding(4.dp)")
        )
        assertTrue(
            "the copy column is the bottom block, 16dp off the sides and 40dp off " +
                "the bottom (which is where the segments row is)",
            hero.contains("align(Alignment.BottomStart)") &&
                hero.contains("padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 40.dp)")
        )
    }

    @Test
    fun `the segments are the caller's, so the page clip cannot touch them`() {
        // Drawn by MobileBillboard over the pager, not inside a page.
        val hero = hero()
        assertFalse(
            "the segments row is not a page child",
            hero.contains("Page \${pagerState.currentPage + 1} of \${pages.size}")
        )
        assertTrue(
            "and it is still drawn where it was - over the pager, bottom-centre",
            mobile.contains("align(Alignment.BottomCenter)") &&
                mobile.contains("pagerState.animateScrollToPage(index)")
        )
    }

    // ── F4: the rest-offset observer stays ────────────────────────────────

    @Test
    fun `the rest-offset observer is still there`() {
        assertTrue(
            "it is the field's proof that the pager was centred - the line that " +
                "rules this mechanism out next time",
            mobile.contains("billboardRestOffCenter(offset)") &&
                mobile.contains("pagerState.requestScrollToPage(pagerState.currentPage)")
        )
        assertTrue(
            "and it still runs after every move rather than only at open",
            mobile.contains("settleRestOffset()")
        )
    }

    // ── F5: the pager setup is untouched ──────────────────────────────────

    @Test
    fun `the pager's own setup did not move`() {
        val pager = pagerSetup()
        assertTrue(
            "one neighbour either side, and that is what the clip contains",
            pager.contains("beyondViewportPageCount = 1")
        )
        assertFalse(
            "no pageSpacing was introduced to hide the strip",
            pager.contains("pageSpacing")
        )
        assertFalse(
            "and no contentPadding either",
            pager.contains("contentPadding")
        )
        assertTrue(
            "the pager is still the full width of the frame",
            pager.contains("modifier = Modifier.fillMaxWidth()")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }
}
