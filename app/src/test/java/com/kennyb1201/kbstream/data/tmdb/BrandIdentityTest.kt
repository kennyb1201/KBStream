package com.kennyb1201.kbstream.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules that decide where a brand's logo may come from, pinned here
 * because both were wrong in ways only the artwork showed.
 *
 * A studio screen's header reads its mark from ONE id space when the rule is
 * narrow, and from an unrelated brand's page when it is too wide: TMDB numbers
 * companies and networks independently, so the same number is two different
 * things. Every name pair below is from the app's own catalogue, checked live
 * against TMDB.
 */
class BrandIdentityTest {

    // ── Which id spaces a brand's mark may come from ─────────────────────

    @Test
    fun `a network's own space is offered first`() {
        assertEquals(
            listOf(BrandIdSpace.NETWORK, BrandIdSpace.COMPANY),
            brandIdSpaces(isNetwork = true)
        )
    }

    @Test
    fun `a company's own space is offered first`() {
        assertEquals(
            listOf(BrandIdSpace.COMPANY, BrandIdSpace.NETWORK),
            brandIdSpaces(isNetwork = false)
        )
    }

    @Test
    fun `both spaces are offered whichever kind the brand is`() {
        // The other space is the second candidate, not a space this rule skips:
        // a brand TMDB filed only under the other kind still gets its mark, and
        // one whose own artwork is there keeps it ahead of any twin's.
        for (isNetwork in listOf(true, false)) {
            val spaces = brandIdSpaces(isNetwork)
            assertEquals(2, spaces.size)
            assertEquals(isNetwork, spaces.first() == BrandIdSpace.NETWORK)
            assertEquals(isNetwork, spaces.last() == BrandIdSpace.COMPANY)
        }
    }

    // ── Whether a same-number entry is the same brand ────────────────────

    @Test
    fun `one brand spelled two ways is one brand`() {
        // TMDB files Angel Studios as the network "Angel" and the company
        // "Angel Studios".
        assertTrue(entityNamesMatch("Angel Studios", "Angel"))
        assertTrue(entityNamesMatch("Angel", "Angel Studios"))
        assertTrue(entityNamesMatch("BBC Two", "bbc two"))
        assertTrue(entityNamesMatch("Channel 4", "Channel4"))
        assertTrue(entityNamesMatch("HBO Max", "HBO  Max"))
    }

    @Test
    fun `a numeric coincidence is not a twin`() {
        // Measured live: these are the same-number company/network pairs the
        // app's own id list resolves to, and none of them is one brand.
        assertFalse(entityNamesMatch("TNT", "Orion Pictures"))
        assertFalse(entityNamesMatch("E!", "Zentropa Entertainments"))
        assertFalse(entityNamesMatch("Bravo", "Moovie"))
        assertFalse(entityNamesMatch("CMT", "Konrad Pictures"))
        assertFalse(entityNamesMatch("BET", "Mikona Productions"))
        assertFalse(entityNamesMatch("TBS", "Big Primate Pictures"))
        assertFalse(entityNamesMatch("History", "Ulysse Production"))
    }

    @Test
    fun `a stub name never contains its way into a merge`() {
        // Containment needs a floor: a two-letter name is inside half the
        // catalogue. Equality is still enough on its own.
        assertFalse(entityNamesMatch("E!", "E! Entertainment"))
        assertFalse(entityNamesMatch("AXN", "AXN Latin America"))
        assertTrue(entityNamesMatch("E!", "E!"))
    }

    @Test
    fun `a missing name proves nothing`() {
        assertFalse(entityNamesMatch(null, "Angel Studios"))
        assertFalse(entityNamesMatch("Angel Studios", null))
        assertFalse(entityNamesMatch(null, null))
        assertFalse(entityNamesMatch("Angel Studios", ""))
        assertFalse(entityNamesMatch("Angel Studios", "   "))
        assertFalse(entityNamesMatch("!!!", "###"))
    }
}
