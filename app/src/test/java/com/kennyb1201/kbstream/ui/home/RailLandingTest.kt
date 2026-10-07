package com.kennyb1201.kbstream.ui.home

import androidx.compose.foundation.lazy.LazyListItemInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's rail landing, and the next rail's title it has to keep on screen.
 *
 * Two failures live here. An unfloored landing scrolled a poster rail's OWN
 * title under the hero; flooring it at "a whole title's worth" then bought the
 * focused heading back at the cost of the next rail's heading, which became a
 * row of white dots at the bottom of the panel.
 *
 * A third, the same mistake one level down, is pinned here too: the spec was
 * handed the focused CARD and the landing was computed from the card's bounds,
 * so a rail's own top landed wherever its card's inset put it. Every rail on
 * Home insets its card by ~39-42dp and the landing inset is 40dp, so nothing
 * showed until the Upcoming rail, whose card is inset ~52dp and whose title came
 * out with its top third above the viewport. The RAIL is what a landing is about
 * - its title is at its top, and its height is what the band below it is charged
 * against - so the request is mapped back to its item first
 * (see [railItemContaining]).
 *
 * The device these numbers are tight on is a 1080p TV at xhdpi: 540dp of
 * screen, the hero capped at 48% of it, and ~281dp of rails viewport left
 * below. The inputs below (panel, poster rail, landscape rail, title line) are
 * that case, stated here rather than read from the screen because they are what
 * the packing has to hold for. What the tests pin is the RELATIONSHIP: the
 * floor and the section gap are spent out of a budget that is barely wider than
 * a poster rail plus one line of text, so raising either one takes the next
 * title off the bottom of the panel and these tests say so.
 */
class RailLandingTest {

    /** A 1080p TV: 540dp tall, hero = 48% of it, rest is the rails viewport. */
    private val panel = 540f - (540f * 0.48f)

    /** A poster rail: ~25dp title, and a 180dp poster plus the glow its card box reserves. */
    private val posterRail = 234f

    /** A rail of landscape (16:9) cards, which is short enough for the roomier landing. */
    private val landscapeRail = 172f

    /** One line of a section title at the default type scale. */
    private val titleLine = 25f

    /**
     * A built-in rail (Continue Watching / Upcoming): the title, then a row of
     * its own with 10dp of top and 12dp of bottom padding around a card box that
     * reserves 24dp of focus headroom - 30 + 10 + 170 + 12.
     */
    private val builtinRail = 222f

    /** The focused card of a built-in rail, and how far into its rail it starts. */
    private val builtinCard = 146f
    private val builtinCardInset = 52f

    private val floor get() = RailLanding.HeaderFloor.value
    private val inset get() = RailLanding.HeaderInset.value
    private val gap get() = RailLanding.SectionGap.value

    private fun band(titleLinePx: Float) = railRevealBandPx(
        sectionGapPx = gap,
        titleHeightPx = titleLinePx,
        fallbackTitlePx = RailLanding.FallbackTitleHeight.value,
        sliverPx = RailLanding.NextTitleSliver.value
    )

    private fun landing(railHeight: Float) = railLandingTarget(
        topInsetPx = inset,
        floorPx = floor,
        revealTargetPx = panel - railHeight - band(titleLine)
    )

    @Test
    fun `a rail of landscape cards lands on the preferred inset`() {
        assertEquals(inset, landing(landscapeRail), 0.01f)
    }

    @Test
    fun `a poster rail lands on the floor, and the next title still fits below it`() {
        val landed = landing(posterRail)

        assertEquals(floor, landed, 0.01f)

        // The whole point of this module: the rail lands, and the next section's
        // title line is still on screen underneath it. Raise the floor or the
        // section gap far enough and this is what fails — which is the bug the
        // previous floor caused.
        val nextTitleBottom = landed + posterRail + gap + titleLine
        assertTrue(
            "next rail title bottom ($nextTitleBottom) must stay inside the $panel dp panel",
            nextTitleBottom <= panel
        )
    }

    @Test
    fun `the floor is clearance from the hero, not a reserved title line`() {
        // Flooring at a whole title is what ate the space below the rail: the
        // title is drawn inside the rail, so the floor only has to hold the rail
        // clear of the hero. If this ever stops being true, the packing above is
        // being paid for out of the focused rail's own heading.
        assertTrue("floor $floor should be a small clearance", floor < titleLine / 2f)
    }

    @Test
    fun `a rail too tall for its band is never pushed above the hero`() {
        // A poster rail's band does not fit; the pull-up stops at the floor
        // rather than landing above the viewport, which is what used to scroll
        // the focused rail's own title out of sight.
        assertTrue(landing(posterRail) > 0f)
        assertEquals(floor, railLandingTarget(topInsetPx = inset, floorPx = floor, revealTargetPx = -120f), 0.01f)
    }

    @Test
    fun `landing is never asked for above the preferred inset`() {
        // A very short rail (a row of small tiles) would have a huge reveal
        // target; it still lands on the inset rather than adrift in the panel.
        assertEquals(inset, railLandingTarget(topInsetPx = inset, floorPx = floor, revealTargetPx = 1_000f), 0.01f)
    }

    @Test
    fun `the band uses the measured title line once a rail has reported one`() {
        // 12 gap + the real 25dp line + 8 sliver -- NOT the 32dp stand-in: on a
        // panel this tight an over-estimate is charged to the next rail's title.
        assertEquals(gap + titleLine + RailLanding.NextTitleSliver.value, band(titleLine), 0.01f)
    }

    @Test
    fun `the band stands in a line of its own before any rail has drawn`() {
        assertEquals(
            gap + RailLanding.FallbackTitleHeight.value + RailLanding.NextTitleSliver.value,
            band(0f),
            0.01f
        )
    }

    @Test
    fun `a raised font scale reserves a taller line`() {
        val bigLine = 40f
        assertEquals(gap + bigLine + RailLanding.NextTitleSliver.value, band(bigLine), 0.01f)
        assertTrue(band(bigLine) > band(titleLine))
    }

    @Test
    fun `the Upcoming rail lands by its own top, so its title is never clipped`() {
        // The reported bug. Its card is inset furthest of any rail (title 30dp,
        // the row's 10dp top padding, 12dp of box centering), and a landing
        // built from the CARD aligns that card on the 40dp inset - which leaves
        // the rail's own top above the viewport, eating the top of its title.
        val cardAligned = railLandingTarget(
            topInsetPx = inset,
            floorPx = floor,
            revealTargetPx = panel - builtinCard - band(titleLine)
        )
        assertTrue(
            "the card-aligned landing ($cardAligned) is the bug: it puts this " +
                "rail's top at ${cardAligned - builtinCardInset}dp, above the viewport",
            cardAligned - builtinCardInset < 0f
        )

        // From the RAIL, the same screen lands it on screen with the title in
        // full - and still leaves the next rail its line.
        val landed = landing(builtinRail)
        assertTrue("the Upcoming title must clear the viewport top, got $landed dp", landed > 0f)
        assertTrue(
            "the next title must still fit below it",
            landed + builtinRail + gap + titleLine <= panel
        )
    }

    @Test
    fun `a focused card's leading edge maps back to its own rail`() {
        // The rail column as laid out: the hero spacer, the rails 12dp apart.
        val spacer = fakeItem(index = 0, offset = 0, size = 2)
        val upcoming = fakeItem(index = 1, offset = 14, size = builtinRail.toInt())
        val below = fakeItem(
            index = 2,
            offset = (14 + builtinRail + gap).toInt(),
            size = posterRail.toInt()
        )
        val items = listOf(spacer, upcoming, below)

        // A card 52dp into its rail belongs to THAT rail, and so does a card in
        // the last one - the mapping is what keeps the landing off the card.
        assertEquals(
            upcoming,
            railItemContaining(upcoming.offset.toFloat() + builtinCardInset, items)
        )
        assertEquals(below, railItemContaining(below.offset.toFloat() + 39f, items))

        // The gap between two rails belongs to neither, so the caller keeps the
        // child's own bounds rather than inventing a rail.
        assertNull(railItemContaining((upcoming.offset + upcoming.size + 4).toFloat(), items))
    }

    @Test
    fun `Home's spec maps each bring-into-view request back to its rail`() {
        // Pinned on the source because this is a wiring decision that compiles
        // cleanly either way - and it was wrong for as long as it was wrong.
        val home = read(HOME_SOURCE)
        val mapping = home.indexOf("railItemContaining(")
        val landing = home.indexOf("railLandingTarget(")
        assertTrue(
            "the rail spec must map the focused card back to its LazyColumn item " +
                "BEFORE computing the landing: a landing built from the card puts " +
                "a rail's title wherever that rail's card inset puts it",
            mapping in 1 until landing
        )
        assertTrue(
            "the mapping needs the rail column's own layout",
            home.contains("visibleItems = railListState.layoutInfo.visibleItemsInfo")
        )
        assertTrue(
            "the RAIL's height must replace the focused card's in the landing",
            home.contains("val railSize = rail?.size?.toFloat() ?: abs(size)")
        )
    }

    @Test
    fun `the sliver of the next rail's cards is the first thing given up`() {
        // Only the title has to fit; the cards behind it are the reserve. On a
        // panel where the band does not fit, the landing is floored, so what
        // shows below the title is whatever is left over rather than nothing.
        val landed = landing(posterRail)
        val visibleCards = panel - (landed + posterRail + gap + titleLine)
        assertTrue(
            "the title line must come before the sliver, but $visibleCards dp should still show",
            visibleCards > 0f && visibleCards <= RailLanding.NextTitleSliver.value + 0.01f
        )
    }

    private fun fakeItem(
        index: Int,
        offset: Int,
        size: Int
    ): LazyListItemInfo = object : LazyListItemInfo {
        override val index = index
        override val key = "item$index"
        override val offset = offset
        override val size = size
    }

    private fun read(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private val sourceRoot: File by lazy { findSourceRoot() }

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
        const val HOME_SOURCE = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
    }
}
