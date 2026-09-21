package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These rules decide whether a playlist channel matches a guide channel, and a
 * wrong answer is invisible: the channel shows no programmes at all, or borrows
 * another channel's. M3U and XMLTV spellers disagree about case, decorations,
 * `&`/`+` and punctuation, so each of those cases is pinned.
 */
class EpgKeysTest {

    // ── Lookup keys (what a channel is matched by) ────────────────────────

    @Test
    fun `lookup keys fold case and remove whitespace outright`() {
        // Whitespace is deleted, not collapsed: "BBC One" and "BBCOne" must
        // reach the same key, because guides use both spellings.
        assertEquals("bbcone", epgLookupKey("  BBC   One  "))
        assertEquals(epgLookupKey("Sky Sports F1"), epgLookupKey("skysports f1"))
    }

    @Test
    fun `lookup keys drop bracketed and parenthesized decorations`() {
        assertEquals("amc", epgLookupKey("AMC (US) [HD]"))
        assertEquals("bbcone", epgLookupKey("BBC One [East]"))
    }

    @Test
    fun `lookup keys expand ampersands and pluses before stripping`() {
        // Both spellings have to land on the same key or the channel is
        // unmatched, so the expansion happens before whitespace is removed.
        assertEquals(epgLookupKey("A and E"), epgLookupKey("A&E"))
        assertEquals(epgLookupKey("Discovery plus"), epgLookupKey("Discovery+"))
        assertEquals("aande", epgLookupKey("A&E"))
        assertEquals("discoveryplus", epgLookupKey("Discovery+"))
    }

    @Test
    fun `lookup keys keep dots so dotted channel ids stay addressable`() {
        assertEquals("bbc1.uk", epgLookupKey("bbc1.uk"))
        assertEquals("bbc1.co.uk", epgLookupKey(" BBC1.CO.UK "))
    }

    @Test
    fun `lookup keys drop other punctuation`() {
        assertEquals("skysports1", epgLookupKey("Sky Sports 1!"))
        assertEquals("attheraces", epgLookupKey("At the Races?"))
    }

    // ── Alias keys (every spelling a guide channel is findable under) ─────

    @Test
    fun `alias keys cover the raw id, the normalized id and each display name`() {
        val keys = epgAliasKeys("BBC1.uk", listOf("BBC One", "BBC1"))

        assertTrue(keys.contains("BBC1.uk"))
        assertTrue(keys.contains("bbc1.uk"))
        assertTrue(keys.contains("BBC One"))
        assertTrue(keys.contains("bbc one"))
        // "BBC One" simplifies to a run-together form, which is what catches
        // the single-word spellings guides actually use.
        assertTrue(keys.contains("bbcone"))
    }

    @Test
    fun `alias keys drop blanks and collapse duplicates`() {
        val keys = epgAliasKeys("id", listOf("", "   ", "ID", "id"))

        assertEquals(listOf("id", "ID"), keys)
    }

    @Test
    fun `simplified names strip decorations and quality qualifiers`() {
        assertEquals("bbcone", simplifyEpgChannelName("BBC One HD (East)"))
        assertEquals("bbcone", simplifyEpgChannelName("BBC ONE [SD]"))
        assertEquals("discoveryplus", simplifyEpgChannelName("Discovery+ 4K"))
        // Region and country suffixes are qualifiers too, so a US/UK feed folds
        // onto the same simplified key as the base channel.
        assertEquals("amc", simplifyEpgChannelName("AMC US"))
        assertEquals("bbc", simplifyEpgChannelName("BBC UK"))
    }

    @Test
    fun `a name with nothing left to key on returns null`() {
        assertNull(simplifyEpgChannelName(""))
        assertNull(simplifyEpgChannelName("   "))
        assertNull(simplifyEpgChannelName("[HD]"))
        assertNull(simplifyEpgChannelName("(US) 4K"))
    }

    @Test
    fun `normalized channel keys only fold case and whitespace`() {
        // Deliberately milder than the simplified form: a guide may legitimately
        // list "Sky Sports 1" separately from "Sky Sports 2".
        assertEquals("sky sports 1", normalizeEpgChannelKey(" Sky Sports 1 "))
        assertEquals("sky sports 1", normalizeEpgChannelKey("SKY SPORTS 1"))
    }

    // ── Guide-window fingerprint ─────────────────────────────────────────

    private val loaded = setOf("bbc1", "itv1")

    private fun fingerprint(
        channels: List<IptvChannel>,
        ids: Set<String> = loaded,
        guideUrls: List<String> = listOf("https://guide.example/epg.xml")
    ) = guideWindowFingerprint(
        sourceUrl = "https://provider.example/playlist.m3u",
        guideUrls = guideUrls,
        channelIds = ids,
        channels = channels
    )

    private fun channel(
        id: String,
        name: String = "Channel $id",
        tvgId: String? = id,
        logoUrl: String? = null,
        streamUrl: String = "http://host/$id.ts"
    ) = IptvChannel(
        id = id,
        name = name,
        displayName = name,
        streamUrl = streamUrl,
        groupTitle = "General",
        logoUrl = logoUrl,
        tvgId = tvgId,
        tvgName = name,
        tvgChno = null,
        catchup = null,
        catchupDays = null,
        catchupSource = null,
        providerChannelId = null
    )

    @Test
    fun `an empty guide window has no fingerprint to compare`() {
        assertNull(fingerprint(listOf(channel("bbc1")), ids = emptySet()))
    }

    @Test
    fun `the same channels produce the same fingerprint`() {
        val channels = listOf(channel("bbc1"), channel("itv1"), channel("other"))
        assertEquals(fingerprint(channels), fingerprint(channels.toList()))
    }

    @Test
    fun `channel details outside the guide window do not change it`() {
        // A background refresh routinely touches logos and stream URLs; that
        // must not throw away loaded programmes and re-run every batch.
        val before = listOf(channel("bbc1"), channel("itv1"))
        val after = listOf(
            channel("bbc1", name = "Channel bbc1", logoUrl = "http://logo/new.png"),
            channel("itv1", name = "Channel itv1", streamUrl = "http://host/moved.ts")
        )
        assertEquals(fingerprint(before), fingerprint(after))
    }

    @Test
    fun `matcher inputs do change it`() {
        val before = listOf(channel("bbc1", name = "BBC One"), channel("itv1"))
        // Rename: the guide is matched by name, so programmes may differ now.
        assertNotEquals(
            fingerprint(before),
            fingerprint(listOf(channel("bbc1", name = "BBC One HD"), channel("itv1")))
        )
        // tvg-id is the strongest match key there is.
        assertNotEquals(
            fingerprint(before),
            fingerprint(listOf(channel("bbc1", name = "BBC One", tvgId = "bbc1.uk"), channel("itv1")))
        )
    }

    @Test
    fun `a different guide source or a lost channel does change it`() {
        val before = listOf(channel("bbc1"), channel("itv1"))
        assertNotEquals(
            fingerprint(before),
            fingerprint(before, guideUrls = listOf("https://other.example/epg.xml"))
        )
        // Requested but no longer in the playlist: the window really shrinks.
        assertNotEquals(
            fingerprint(before),
            fingerprint(listOf(channel("bbc1")))
        )
    }
}
