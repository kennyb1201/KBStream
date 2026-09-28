package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
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
}
