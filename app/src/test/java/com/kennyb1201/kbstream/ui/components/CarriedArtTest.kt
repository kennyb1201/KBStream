package com.kennyb1201.kbstream.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The carried-art rule a pinned rail card draws by.
 *
 * Reported problem: the Trakt pinned rails showed titles only while the BingeCat
 * rail beside them had posters, because a row that carried a poster URL switched
 * the TMDB resolvers off on the strength of the URL alone - and a URL that does
 * not load is not art. What is pinned here is the rule itself: a carried URL is
 * drawn while it loads, and stops being the card's art the moment its loader
 * says it will not load.
 */
class CarriedArtTest {

    @Test
    fun `a row with no art of its own never claims any`() {
        val none = CarriedArt(null)
        assertNull("no URL is no art", none.effective)
        assertFalse("so the resolvers are the card's only source", none.usable)

        val blank = CarriedArt("   ")
        assertNull("a blank URL is the same as none", blank.effective)
        assertFalse(blank.usable)
    }

    @Test
    fun `a carried poster is drawn, and costs no lookup, until it fails`() {
        val trakt = CarriedArt("https://walter.trakt.us/images/posters/1.jpg")
        assertEquals(
            "the row's own art is what the card draws",
            "https://walter.trakt.us/images/posters/1.jpg",
            trakt.effective
        )
        assertTrue(
            "which is what keeps the resolvers off - the happy path makes no " +
                "TMDB call at all",
            trakt.usable
        )
        assertFalse("and nothing has been reported against it", trakt.failed)

        val failed = trakt.failedToLoad()
        assertNull(
            "a poster that will not load is not the card's art, so the resolvers " +
                "are armed and TMDB's answer is drawn instead",
            failed.effective
        )
        assertFalse(failed.usable)
        assertTrue(failed.failed)
    }

    @Test
    fun `the failure keeps the URL, so a report can name what was tried`() {
        val failed = CarriedArt("https://walter.trakt.us/dead.jpg").failedToLoad()
        assertEquals(
            "the row still carries what it carried - the card just stops " +
                "drawing it",
            "https://walter.trakt.us/dead.jpg",
            failed.url
        )
    }

    @Test
    fun `a row that carried nothing is not changed by a failure`() {
        val none = CarriedArt(null)
        assertSame(
            "the fallback was already armed for it; a failure of the RESOLVED art " +
                "is not a statement about this row's own",
            none,
            none.failedToLoad()
        )
        assertEquals(
            "and no failure is recorded against art the row never had",
            CarriedArt(null),
            none.failedToLoad()
        )
    }

    @Test
    fun `a second failure is the same state, so the card cannot loop`() {
        val once = CarriedArt("https://walter.trakt.us/dead.jpg").failedToLoad()
        assertEquals(
            "re-reporting a load failure changes nothing - there is no state " +
                "beyond 'failed' for it to move to",
            once,
            once.failedToLoad()
        )
    }
}
