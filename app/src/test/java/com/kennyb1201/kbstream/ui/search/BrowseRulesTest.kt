package com.kennyb1201.kbstream.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browse browser's filter, counts and blurbs.
 *
 * These are the parts of the rebuild a viewer feels immediately: whether the
 * filter finds a chip (279 collections is not a list anyone scrolls), whether
 * "1 genre" reads correctly, and whether a tab explains where it goes. They
 * are also the parts that fail silently - a filter that drops a real match
 * still renders, it just never shows you what you asked for - which is why
 * they live in a pure file and are pinned here.
 */
class BrowseRulesTest {

    private fun entry(id: Int, name: String) = BrowseEntry(id, name)

    private val sample = listOf(
        entry(1, "Netflix"),
        entry(2, "Disney+"),
        entry(3, "Neon"),
        entry(4, "Netflix Originals"),
        entry(5, "A24")
    )

    @Test
    fun `a blank query keeps every entry`() {
        assertEquals(sample, filterBrowseEntries(sample, ""))
        assertEquals(sample, filterBrowseEntries(sample, "   "))
    }

    @Test
    fun `the filter is case-insensitive and matches anywhere in the name`() {
        assertEquals(
            listOf(entry(1, "Netflix"), entry(4, "Netflix Originals")),
            filterBrowseEntries(sample, "netF")
        )
        // Substring, not prefix: "on" is inside "Disney+"? no - inside
        // nothing here; but "flix" is inside both Netflix chips.
        assertEquals(
            listOf(entry(1, "Netflix"), entry(4, "Netflix Originals")),
            filterBrowseEntries(sample, "flix")
        )
    }

    @Test
    fun `the filter preserves the curated order`() {
        // Netflix (1) was curated before Netflix Originals (4); a relevance
        // re-rank would have to decide which is "better", and moving a chip
        // the viewer was about to press is worse than a stable order.
        val matched = filterBrowseEntries(sample, "net")
        assertEquals(listOf(1, 4), matched.map { it.id })
    }

    @Test
    fun `a query nothing matches keeps nothing`() {
        assertTrue(filterBrowseEntries(sample, "zzz").isEmpty())
    }

    @Test
    fun `a count reads in the category's own noun, singular or plural`() {
        assertEquals("26 genres", browseCountLabel(26, 26, "genres"))
        assertEquals("1 genre", browseCountLabel(1, 1, "genres"))
        assertEquals("279 collections", browseCountLabel(279, 279, "collections"))
        assertEquals("1 collection", browseCountLabel(1, 1, "collections"))
        assertEquals("91 services", browseCountLabel(91, 91, "services"))
        assertEquals("12 decades", browseCountLabel(12, 12, "decades"))
    }

    @Test
    fun `a filtered count says how much of the category is showing`() {
        assertEquals("3 of 279 collections", browseCountLabel(3, 279, "collections"))
        assertEquals("1 of 26 genres", browseCountLabel(1, 26, "genres"))
    }

    @Test
    fun `a category with no listed noun counts in entries`() {
        assertEquals("7 entries", browseCountLabel(7, 7, "something-new"))
        assertEquals("1 entry", browseCountLabel(1, 1, "something-new"))
        assertEquals("entry", browseCategoryNoun("something-new"))
        assertEquals("entries", browseCategoryNounPlural("something-new"))
    }

    @Test
    fun `a chip already on screen is revealed by no scroll`() {
        // The reported bug: "Add to Home" on a chip the viewer was looking at
        // still ran the grid's scroll-to-the-armed-chip, which jumped the rail
        // so that chip became its first row. A press that does not move the
        // chip may not move the view either.
        assertFalse(
            browseChipNeedsReveal(index = 4, showing = listOf(0, 1, 2, 3, 4, 5))
        )
        assertFalse(
            "a partially scrolled rail still counts its edge cells as showing",
            browseChipNeedsReveal(index = 19, showing = 10..25)
        )
    }

    @Test
    fun `a chip the rail has not laid out is revealed`() {
        assertTrue(
            "a lazy grid composes only what it shows, so a chip deep in a " +
                "279-entry submenu has no node to focus until the rail is " +
                "scrolled to it",
            browseChipNeedsReveal(index = 240, showing = listOf(0, 1, 2, 3, 4))
        )
    }

    @Test
    fun `an index outside the rail is never revealed`() {
        assertFalse(
            "a negative index is what a failed lookup answers, and scrolling " +
                "to it would be a jump to nowhere",
            browseChipNeedsReveal(index = -1, showing = listOf(0, 1, 2))
        )
    }

    // ── the grid-owned chip reveal ────────────────────────────────────

    /** The grid's viewport in these cases: 0..900 (a 360dp rail at 2.5x). */
    private val viewport = 0 to 900

    private fun reveal(
        index: Int,
        itemTop: Int,
        itemSize: Int,
        edgeInset: Int = 0
    ) = chipRevealTarget(
        index = index,
        itemTop = itemTop,
        itemSize = itemSize,
        viewportStart = viewport.first,
        viewportEnd = viewport.second,
        edgeInset = edgeInset
    )

    @Test
    fun `a row already inside the grid viewport is revealed by no scroll`() {
        // Scrolling a row that is fully visible is exactly the jitter the
        // consumed automatic request used to cause.
        assertEquals(ChipReveal.None, reveal(index = 3, itemTop = 300, itemSize = 120))
        assertEquals(
            "and with no room asked for, flush with both edges counts as visible",
            ChipReveal.None,
            reveal(index = 3, itemTop = 0, itemSize = 120)
        )
        assertEquals(
            "and flush with the bottom edge too",
            ChipReveal.None,
            reveal(index = 3, itemTop = 780, itemSize = 120)
        )
    }

    @Test
    fun `a row above the grid viewport aligns to the top edge`() {
        assertEquals(
            "the row starts above the viewport by 40px",
            ChipReveal.Top(6),
            reveal(index = 6, itemTop = -40, itemSize = 120)
        )
    }

    @Test
    fun `a row below the grid viewport aligns to the bottom edge`() {
        // The offset is the row's own height minus the viewport's, which is
        // the NEGATIVE number animateScrollToItem needs to put the row's
        // bottom on the viewport's bottom edge: 120 - 900 = -780. The caller
        // adds the cue room to it (see the landing test below).
        assertEquals(
            ChipReveal.Bottom(index = 9, scrollOffset = -780),
            reveal(index = 9, itemTop = 950, itemSize = 120)
        )
    }

    @Test
    fun `the offset comes from the row the grid measured, not a constant`() {
        // A two-line curated chip is taller than a one-line one. A hardcoded
        // row height would leave the taller row's last line clipped, which is
        // the whole reason the reveal is computed from the measurement.
        assertEquals(
            ChipReveal.Bottom(index = 4, scrollOffset = -720),
            reveal(index = 4, itemTop = 910, itemSize = 180)
        )
        assertNotEquals(
            reveal(index = 4, itemTop = 910, itemSize = 180),
            reveal(index = 4, itemTop = 910, itemSize = 120)
        )
    }

    @Test
    fun `a partial row is revealed even when it is mostly on screen`() {
        // Nearest-edge, not "mostly visible": a row 5px past the bottom edge
        // is revealed from the BOTTOM (so it lands flush with the viewport's
        // bottom, keeping the rows above it put), and one 5px above the top is
        // revealed from the top.
        assertEquals(ChipReveal.Bottom(3, -780), reveal(index = 3, itemTop = 784, itemSize = 120))
        assertEquals(ChipReveal.Top(3), reveal(index = 3, itemTop = -5, itemSize = 120))
    }

    @Test
    fun `a row landed on an edge keeps the room the focused chip's cue draws into`() {
        // Reported: "clipping on the last chips on search browse bottom of tile".
        // The reveal aligned a row's bottom flush with the grid's bottom edge,
        // and a chip's cue is not inside its layout rectangle - it grows by
        // KBFocusChip and carries a glow - so the row the viewer was ON lost its
        // 2dp border and its glow flat along the clip, with its last line of
        // text cut through. The room (the theme's KBFocusChipInset: 30px in
        // these cases) is taken off the edge the row lands on.
        assertEquals(
            "a row flush with the bottom edge lands with the room below it",
            ChipReveal.Bottom(index = 8, scrollOffset = -750),
            reveal(index = 8, itemTop = 780, itemSize = 120, edgeInset = 30)
        )
        assertEquals(
            "and one at the top edge lands with the room ABOVE it, which is why Top carries an offset",
            ChipReveal.Top(index = 2, scrollOffset = -30),
            reveal(index = 2, itemTop = 0, itemSize = 120, edgeInset = 30)
        )
    }

    @Test
    fun `a row with the room around it already is still revealed by no scroll`() {
        assertEquals(
            "clear of both edges by more than the room",
            ChipReveal.None,
            reveal(index = 3, itemTop = 40, itemSize = 120, edgeInset = 30)
        )
        assertEquals(
            "and exactly the room away from each edge is inside it",
            ChipReveal.None,
            reveal(index = 3, itemTop = 30, itemSize = 840, edgeInset = 30)
        )
    }

    @Test
    fun `a measurement the grid cannot trust never scrolls`() {
        assertEquals("a failed index lookup", ChipReveal.None, reveal(-1, 300, 120))
        assertEquals("an unmeasured row", ChipReveal.None, reveal(2, 300, 0))
        assertEquals(
            "an inverted viewport",
            ChipReveal.None,
            chipRevealTarget(index = 2, itemTop = 300, itemSize = 120, viewportStart = 900, viewportEnd = 0)
        )
    }

    @Test
    fun `every browse category has a noun and a blurb`() {
        val keys = BROWSE_CATEGORIES.map { it.key }
        assertTrue(keys.isNotEmpty())
        val blurbs = keys.map { key ->
            assertNotEquals("entry", browseCategoryNoun(key))
            browseCategoryBlurb(key).also { blurb ->
                assertTrue("blurb for $key is blank", blurb.isNotBlank())
            }
        }
        // One tab must not describe a different tab's screen.
        assertEquals(blurbs.size, blurbs.toSet().size)
    }
}
