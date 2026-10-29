package com.kennyb1201.kbstream.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header entity-logo cache's two decisions, pinned without a device.
 *
 * The reported slowness (2026-10-28) is the HEADER brand mark on the Browse
 * screens - the network / studio / service logo, not the cards' corner
 * clearlogos. `getEntityLogoUrls` walks up to five network fallbacks and had no
 * cache anywhere in that path, so every screen load paid for the whole walk.
 *
 * What a JVM test can hold is the part that must not drift: which arguments form
 * a distinct entry (the two id spaces are the trap the resolution itself guards
 * against - 41 is TNT as a network and Orion as a company), and how long each
 * kind of answer may stand (a month with artwork, a day without).
 */
class EntityLogoCacheTest {

    private fun key(
        entityId: Int = 41,
        isNetwork: Boolean = true,
        providerId: Int? = null,
        originalsCompanyId: Int? = null,
        name: String? = null
    ) = entityLogoCacheKey(entityId, isNetwork, providerId, originalsCompanyId, name)

    // ── keying ──────────────────────────────────────────────────────────────

    @Test
    fun `identical arguments are one entry`() {
        assertEquals(key(name = "TNT"), key(name = "TNT"))
        assertEquals(
            "the same brand reached with the same arguments is one cache entry",
            key(providerId = 1899, originalsCompanyId = 7, name = "HBO Max"),
            key(providerId = 1899, originalsCompanyId = 7, name = "HBO Max")
        )
    }

    @Test
    fun `every argument that can change the answer makes a different entry`() {
        val base = key(name = "TNT")
        assertNotEquals("entity id", base, key(entityId = 42, name = "TNT"))
        assertNotEquals("provider id", base, key(providerId = 1899, name = "TNT"))
        assertNotEquals(
            "originals company",
            base,
            key(originalsCompanyId = 7, name = "TNT")
        )
        assertNotEquals("name", base, key(name = "TBS"))
    }

    @Test
    fun `network 41 and company 41 can never share an entry`() {
        // The twin-space trap: the number alone proves no relation.
        assertNotEquals(
            "a network and a company with the same id are different brands",
            key(entityId = 41, isNetwork = true),
            key(entityId = 41, isNetwork = false)
        )
    }

    @Test
    fun `names are normalized, and no name is its own spelling`() {
        assertEquals(
            "case and surrounding whitespace are the same brand",
            key(name = "HBO Max"),
            key(name = "  hbo max ")
        )
        assertEquals(
            "an absent name and a blank one are the same request",
            key(name = null),
            key(name = "   ")
        )
        assertNotEquals("but a supplied name is not the absent one", key(name = null), key(name = "TNT"))
    }

    @Test
    fun `the key carries the versioned prefix that drops a bad generation`() {
        assertTrue(
            "the disk row key must be bumpable in one place",
            key().startsWith(ENTITY_LOGO_DISK_PREFIX)
        )
        assertEquals("entity_logos_v1:", ENTITY_LOGO_DISK_PREFIX)
    }

    // ── TTLs ───────────────────────────────────────────────────────────────

    @Test
    fun `an entry with artwork stands for a month`() {
        val urls = listOf("https://image.tmdb.org/t/p/w500/logo.png")
        val day = 24L * 60L * 60L * 1000L

        assertTrue(
            "29 days old is still served",
            entityLogoDiskEntryIsFresh(29 * day, urls)
        )
        assertFalse(
            "31 days old is re-asked",
            entityLogoDiskEntryIsFresh(31 * day, urls)
        )
    }

    @Test
    fun `a proven logo-less brand stands for a day, not a month`() {
        val day = 24L * 60L * 60L * 1000L
        val hour = 60L * 60L * 1000L

        assertTrue(
            "23 hours old is still served",
            entityLogoDiskEntryIsFresh(23 * hour, emptyList())
        )
        assertFalse(
            "25 hours old is re-asked, so artwork uploaded later appears",
            entityLogoDiskEntryIsFresh(25 * hour, emptyList())
        )
        assertEquals("an empty answer's TTL", 24L * hour, entityLogoDiskTtlMs(emptyList()))
        assertEquals("artwork's TTL", 30 * day, entityLogoDiskTtlMs(listOf("x")))
    }

    @Test
    fun `the session memo holds either answer for half a day`() {
        val hour = 60L * 60L * 1000L
        assertTrue("11 hours old is served from memory", entityLogoMemoryEntryIsFresh(11 * hour))
        assertFalse("13 hours old is dropped on read", entityLogoMemoryEntryIsFresh(13 * hour))
        assertFalse("a clock that went backwards is not trusted", entityLogoMemoryEntryIsFresh(-1))
    }

    // ── the failure marker ──────────────────────────────────────────────────

    @Test
    fun `a failure has its own row, never the success key`() {
        val success = key(name = "TNT")
        val failure = entityLogoFailureKey(success)
        assertEquals("the marker is the success key plus its suffix", "$success:failed", failure)
        assertNotEquals(
            "a marker can never be read as a proven answer, or the reverse",
            success,
            failure
        )
        assertTrue(
            "and it stays inside the same versioned generation",
            failure.startsWith(ENTITY_LOGO_DISK_PREFIX)
        )
    }

    @Test
    fun `a failure is remembered for an hour, far short of a proven miss`() {
        val minute = 60L * 1000L
        val hour = 60L * minute
        assertEquals("the failure TTL", 60L * minute, ENTITY_LOGO_FAILURE_TTL_MS)
        assertTrue("59 minutes old still suppresses a repeat walk", entityLogoFailureMarkerIsFresh(59 * minute))
        assertFalse("61 minutes old walks again", entityLogoFailureMarkerIsFresh(61 * minute))
        assertFalse("a clock that went backwards is not trusted", entityLogoFailureMarkerIsFresh(-1))
        assertTrue(
            "an outage must expire well before a proven logo-less brand does",
            ENTITY_LOGO_FAILURE_TTL_MS < ENTITY_LOGO_MISS_TTL_MS / 4
        )
        assertTrue("and it is under the hour it claims", ENTITY_LOGO_FAILURE_TTL_MS <= hour)
    }
}
