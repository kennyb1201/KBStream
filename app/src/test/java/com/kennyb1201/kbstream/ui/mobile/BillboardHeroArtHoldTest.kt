package com.kennyb1201.kbstream.ui.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The hold the first-entry flash fix is built on (spec 2026-11-17, F1).
 *
 * The bug was the art under a showing page CHANGING while other pages settled:
 * the borrowed fallback is chosen from the live page list, so every settle moved
 * it, and each move was a flash. The rule has to make the first art a page
 * showed sticky - and still let the page's own art take over exactly once.
 */
class BillboardHeroArtHoldTest {

    @Test
    fun `a page with its own art shows it, and never consults the hold`() {
        assertEquals(
            "own art wins every pass, so a page that always had art never swaps",
            "own.jpg",
            billboardStickyHeroArt(ownArt = "own.jpg", heldArt = "held.jpg", liveFallback = "next.jpg")
        )
    }

    @Test
    fun `a held borrowed page ignores the live fallback moving under it`() {
        assertEquals(
            "the settle burst may move the live fallback; the frame must not move with it",
            "first.jpg",
            billboardStickyHeroArt(ownArt = null, heldArt = "first.jpg", liveFallback = "later.jpg")
        )
    }

    @Test
    fun `with nothing held, the live fallback is the first choice`() {
        assertEquals(
            "a page's very first frame has to draw something",
            "next.jpg",
            billboardStickyHeroArt(ownArt = null, heldArt = null, liveFallback = "next.jpg")
        )
    }

    @Test
    fun `held art is what the page showed even when the page now has its own`() {
        // This is the one legal swap: own art lands over the held borrowed art.
        assertEquals(
            "the own art takes over the moment it exists",
            "own.jpg",
            billboardStickyHeroArt(ownArt = "own.jpg", heldArt = "first.jpg", liveFallback = "later.jpg")
        )
    }

    @Test
    fun `a page with no art anywhere stays none - never a black frame, never a wrong one`() {
        assertNull(
            "B4 covers this case: no art at all is the caller's borrowed image, not this rule's",
            billboardStickyHeroArt(ownArt = null, heldArt = null, liveFallback = null)
        )
    }
}
