package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Just US stuff": the built-in Free TV lineup's country rule.
 *
 * iptv-org's `us.m3u` is a country list that still carries a handful of
 * international feeds (LBC International `.lb`, Star Channel International
 * `.gr`, TNT International `.ru`), and every one of them names its country in
 * its `tvg-id`. That id is the only evidence used.
 *
 * The rule is positive evidence only: a code that is not `us` drops an entry,
 * and anything unprovable - no id, no dot, an empty tail - is kept. Losing a
 * real US affiliate because a provider left its id malformed is the worse
 * trade; an unidentifiable foreign row can be hidden by the viewer.
 *
 * Language is never consulted: a channel that broadcasts in French or Spanish
 * but is a US channel (`3ABNFrench.us@SD`) stays.
 */
class FreeLineupUsChannelTest {

    // ── the country in the id ───────────────────────────────────────────

    @Test
    fun `the country is the last dotted segment before the feed`() {
        assertEquals("us", FreeLineup.countryOf("ABC.us@WFTV"))
        assertEquals("lb", FreeLineup.countryOf("LBCInternational.lb@SD"))
        assertEquals("gr", FreeLineup.countryOf("StarChannelInternational.gr@HD"))
        assertEquals("ru", FreeLineup.countryOf("TNTInternational.ru@SD"))
        assertEquals("us", FreeLineup.countryOf("Channel.us"))
        assertEquals("us", FreeLineup.countryOf("some.name.with.dots.us@HD"))
        assertEquals(
            "a dot inside the feed half is still not a country",
            "us",
            FreeLineup.countryOf("abc.us@WFTV.extra")
        )
    }

    @Test
    fun `the code is read case-insensitively`() {
        assertEquals("us", FreeLineup.countryOf("ABC.US@WFTV"))
        assertEquals("us", FreeLineup.countryOf("ABC.Us@WFTV"))
        assertEquals("uk", FreeLineup.countryOf("Channel.UK@HD"))
    }

    @Test
    fun `the feed suffix is never part of the country`() {
        // The `@...` half is a callsign, SD/HD, or a regional marker.
        assertEquals("us", FreeLineup.countryOf("AE.us@East"))
        assertEquals("us", FreeLineup.countryOf("ABC.us@WFTV"))
        assertEquals("us", FreeLineup.countryOf("ABC.us@HD"))
    }

    @Test
    fun `an id with no country names none`() {
        assertNull(FreeLineup.countryOf(null))
        assertNull(FreeLineup.countryOf(""))
        assertNull(FreeLineup.countryOf("   "))
        assertNull("a bare name has no dotted segment", FreeLineup.countryOf("ABC"))
        assertNull("a trailing dot leaves an empty segment", FreeLineup.countryOf("ABC."))
        assertNull("no dot before the feed either", FreeLineup.countryOf("ABC@WFTV"))
    }

    // ── the decision the load makes ─────────────────────────────────────

    @Test
    fun `a US entry stays, whatever its feed or language`() {
        listOf(
            "ABC.us@WFTV", // a local affiliate
            "AE.us@East", // a national, regional feed
            "3ABNFrench.us@SD", // US channel, French-language
            "Channel.us", // no feed suffix at all
            "ABC.US@WFTV" // the same affiliate, uppercase
        ).forEach { tvgId ->
            assertTrue("$tvgId is a US channel and must stay", FreeLineup.isUsChannel(channel(tvgId)))
        }
    }

    @Test
    fun `a positively non-US entry goes`() {
        listOf(
            "LBCInternational.lb@SD",
            "StarChannelInternational.gr@HD",
            "TNTInternational.ru@SD",
            "Channel.uk@HD"
        ).forEach { tvgId ->
            assertFalse("$tvgId is not a US channel and must go", FreeLineup.isUsChannel(channel(tvgId)))
        }
    }

    @Test
    fun `an unprovable entry is always kept`() {
        listOf(null, "", "   ", "ABC").forEach { tvgId ->
            assertTrue(
                "an id that names no country must never cost a channel",
                FreeLineup.isUsChannel(channel(tvgId))
            )
        }
    }

    @Test
    fun `the decision never reads the provider id or the name`() {
        // A foreign-looking provider id is not evidence: it is opaque, and the
        // rule stays on the tvg-id the guide matches by.
        assertTrue(
            FreeLineup.isUsChannel(
                channel(null).copy(providerChannelId = "LBC.lb", name = "LBC International")
            )
        )
        // And a US channel whose name reads foreign keeps its place: this is a
        // country filter, not a language one.
        assertTrue(FreeLineup.isUsChannel(channel("3ABNFrench.us@SD").copy(name = "3ABN French")))
    }

    private fun channel(tvgId: String?) = IptvChannel(
        id = "ch",
        name = "Channel",
        displayName = "Channel",
        streamUrl = "https://stream.example/ch.m3u8",
        groupTitle = null,
        logoUrl = null,
        tvgId = tvgId,
        tvgName = null,
        tvgChno = null,
        catchup = null,
        catchupDays = null,
        catchupSource = null,
        providerChannelId = null
    )
}
