package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kids Mode rating-ceiling matrix. These tests pin the contract that
 * "PG or lower" actually blocks R/NC-17/TV-MA (the original 0.1.0 bug:
 * ceilings were stored on an age-like 8/13/14 scale but compared against
 * the 0-10 rating rank, silently allowing adult titles through).
 */
class KidsModeTest {

    // ── normalize: current ordinals pass through ────────────────────────

    @Test
    fun `normalize passes through current ordinals`() {
        assertEquals(null, KidsMode.normalize(null))
        assertEquals(KidsMode.CEIL_PG13, KidsMode.normalize(KidsMode.CEIL_PG13))
        assertEquals(KidsMode.CEIL_PG, KidsMode.normalize(KidsMode.CEIL_PG))
        assertEquals(KidsMode.CEIL_G, KidsMode.normalize(KidsMode.CEIL_G))
    }

    // ── normalize: legacy synced values map onto the right ceiling ──────

    @Test
    fun `normalize maps legacy values onto correct ordinals`() {
        // Legacy blobs (14/13/8) must land on their namesake ceilings.
        assertEquals(KidsMode.CEIL_PG13, KidsMode.normalize(14))
        assertEquals(KidsMode.CEIL_PG, KidsMode.normalize(13))
        assertEquals(KidsMode.CEIL_G, KidsMode.normalize(8))
    }

    @Test
    fun `normalize maps junk to PG default`() {
        // Unknown values degrade to the builder's default, never to OFF.
        // (0 is NOT junk - it is the legal CEIL_G ordinal.)
        assertEquals(KidsMode.CEIL_PG, KidsMode.normalize(99))
        assertEquals(KidsMode.CEIL_PG, KidsMode.normalize(-1))
        assertEquals(KidsMode.CEIL_PG, KidsMode.normalize(5))
        assertEquals(KidsMode.CEIL_PG, KidsMode.normalize(10))
    }

    // ── allowed: kids mode OFF ──────────────────────────────────────────

    @Test
    fun `null ceiling allows every rating`() {
        for (rating in listOf("G", "PG", "PG-13", "R", "NC-17", "TV-Y", "TV-MA", null, "", "junk")) {
            assertTrue("null ceiling must allow $rating", KidsMode.allowed(null, rating))
        }
    }

    // ── allowed: the matrix (each ceiling x each rating) ────────────────

    @Test
    fun `G ceiling blocks R NC-17 TV-MA and TV-14`() {
        val g = KidsMode.CEIL_G
        assertTrue(KidsMode.allowed(g, "G"))
        assertTrue(KidsMode.allowed(g, "TV-Y"))
        assertFalse("G must block R", KidsMode.allowed(g, "R"))
        assertFalse("G must block NC-17", KidsMode.allowed(g, "NC-17"))
        assertFalse("G must block TV-MA", KidsMode.allowed(g, "TV-MA"))
        assertFalse("G must block TV-14", KidsMode.allowed(g, "TV-14"))
    }

    @Test
    fun `PG ceiling admits PG and TV-PG but blocks R TV-MA`() {
        val pg = KidsMode.CEIL_PG
        assertTrue(KidsMode.allowed(pg, "G"))
        assertTrue(KidsMode.allowed(pg, "PG"))
        assertTrue(KidsMode.allowed(pg, "TV-Y7"))
        assertTrue(KidsMode.allowed(pg, "TV-PG"))
        assertFalse("PG must block R", KidsMode.allowed(pg, "R"))
        assertFalse("PG must block NC-17", KidsMode.allowed(pg, "NC-17"))
        assertFalse("PG must block TV-MA", KidsMode.allowed(pg, "TV-MA"))
        assertFalse("PG must block TV-14", KidsMode.allowed(pg, "TV-14"))
    }

    @Test
    fun `PG-13 ceiling admits PG-13 and blocks R and up`() {
        val pg13 = KidsMode.CEIL_PG13
        assertTrue(KidsMode.allowed(pg13, "G"))
        assertTrue(KidsMode.allowed(pg13, "PG"))
        assertTrue(KidsMode.allowed(pg13, "PG-13"))
        assertTrue(KidsMode.allowed(pg13, "TV-PG"))
        assertFalse("PG-13 must block R", KidsMode.allowed(pg13, "R"))
        assertFalse("PG-13 must block NC-17", KidsMode.allowed(pg13, "NC-17"))
        assertFalse("PG-13 must block TV-MA", KidsMode.allowed(pg13, "TV-MA"))
        assertFalse("PG-13 must block TV-14", KidsMode.allowed(pg13, "TV-14"))
    }

    @Test
    fun `rating parser is whitespace and case insensitive`() {
        assertEquals(6, KidsMode.ratingToAge(" pg-13 "))
        assertEquals(7, KidsMode.ratingToAge("r"))
        assertEquals(0, KidsMode.ratingToAge("TV-Y"))
    }

    // ── allowed: the known-unknown rule ─────────────────────────────────

    @Test
    fun `unknown certification kept only on the loosest ceiling`() {
        // Unresolvable rating: PG-13 keeps the title, PG and G drop it.
        assertTrue(KidsMode.allowed(KidsMode.CEIL_PG13, null))
        assertTrue(KidsMode.allowed(KidsMode.CEIL_PG13, "Unrated-TV-Show"))
        assertFalse(KidsMode.allowed(KidsMode.CEIL_PG, null))
        assertFalse(KidsMode.allowed(KidsMode.CEIL_PG, "some junk cert"))
        assertFalse(KidsMode.allowed(KidsMode.CEIL_G, null))
        assertFalse(KidsMode.allowed(KidsMode.CEIL_G, "whatever"))
    }

    @Test
    fun `normalize inside allowed makes legacy values behave`() {
        // A profile synced from an old build stores 14 (legacy PG-13).
        // allowed() must normalize, so 14 blocks R exactly like CEIL_PG13.
        assertFalse("legacy 14 must block R", KidsMode.allowed(14, "R"))
        assertTrue("legacy 14 must admit PG-13", KidsMode.allowed(14, "PG-13"))
        assertFalse("legacy 8 (G) must block R", KidsMode.allowed(8, "R"))
        assertTrue("legacy 8 (G) must admit G", KidsMode.allowed(8, "G"))
    }

    // ── isRestricted ────────────────────────────────────────────────────

    @Test
    fun `isRestricted matches kids-mode presence`() {
        assertFalse(KidsMode.isRestricted(null))
        assertTrue(KidsMode.isRestricted(KidsMode.CEIL_G))
        assertTrue(KidsMode.isRestricted(KidsMode.CEIL_PG13))
    }
}
