package com.kennyb1201.kbstream.domain.streamengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The addon half of the debrid tier.
 *
 * The set is the whole signal: an entry in it promotes an addon's links on the
 * claim that a debrid service will serve them, and the ranker cannot check that
 * claim itself. So the two things worth pinning are that the keys are spelled
 * the way the ranker compares them, and that the list stays small enough to
 * verify - every entry is a promise about an addon's links.
 */
class DebridAddonsTest {

    @Test
    fun `the set the ranker is handed is already normalized`() {
        assertEquals(
            "a raw id in the set would never match the normalized addon name the" +
                " resolve path carries",
            DebridAddons.KNOWN_DEBRID_ADDON_IDS
                .mapTo(mutableSetOf()) { SourceAddonPreference.normalize(it) },
            DebridAddons.normalizedIds()
        )
        assertTrue("aiostreams", "aiostreams" in DebridAddons.normalizedIds())
        assertTrue(
            "an empty key is never in the set: that is the ranker's \"no addon" +
                " known\" spelling, and it would match every entry with no addon",
            "" !in DebridAddons.normalizedIds()
        )
    }

    @Test
    fun `every spelling of a known addon reaches the same key`() {
        for (spelling in listOf("AIOStreams", "aiostreams", "AIO-Streams", "aiostreams ")) {
            assertTrue(
                "\"$spelling\" must key to the known addon",
                SourceAddonPreference.normalize(spelling) in DebridAddons.normalizedIds()
            )
        }
    }

    @Test
    fun `an addon that hands back resolved links is not listed`() {
        // Torrentio-style addons name the service in the URL they return, so the
        // host rule already covers them. Listing them would add a second, weaker
        // answer to a question the link settles on its own.
        assertTrue("torrentio" !in DebridAddons.normalizedIds())
        assertTrue(
            "a decorated display name does not match, which falls back to" +
                " today's behaviour rather than promoting unverifiable links",
            SourceAddonPreference.normalize("AIOStreams | ElfHosted") !in
                DebridAddons.normalizedIds()
        )
    }

    @Test
    fun `the list is deliberate`() {
        // An addon goes in only when it is VERIFIED debrid-first: a wrong entry
        // promotes links that may not resolve at all, which is worse than the
        // mis-ordering it was meant to fix.
        assertEquals(setOf("aiostreams"), DebridAddons.KNOWN_DEBRID_ADDON_IDS)
    }
}
