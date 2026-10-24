package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The free lineup installs into the EXTRA source slots, atomically, and removes
 * back out of them - the rules behind "one tap, no half-installed state".
 *
 * Every case here is a string function on the two prefs, which is the point:
 * the ViewModel keeps a single `SharedPreferences` edit for the pair, so what is
 * testable off-device is that both strings come out of [FreeLineup] ready to be
 * written together and that removing takes out exactly the two entries the
 * install put in.
 */
class FreeLineupTest {

    @Test
    fun `installing into an empty profile writes the playlist and the guide`() {
        val (playlists, guides) = FreeLineup.install("", "")

        assertEquals("$PLAYLIST_URL|Free TV", playlists)
        assertEquals(EPG_URL, guides)
        assertTrue(FreeLineup.isInstalled(playlists))
    }

    @Test
    fun `installing keeps everything the viewer already typed`() {
        val (playlists, guides) = FreeLineup.install(
            "http://host/mine.m3u|Mine",
            "http://host/guide.xml"
        )

        assertEquals("http://host/mine.m3u|Mine\n$PLAYLIST_URL|Free TV", playlists)
        assertEquals("http://host/guide.xml\n$EPG_URL", guides)
    }

    @Test
    fun `installing keeps the separator the field already uses`() {
        // Both ';' and newlines are accepted on read. A viewer's single-line
        // extras field must not be rewritten as a two-line one by a one-tap
        // install - it is their field, and it is visible in the form.
        val (playlists, _) = FreeLineup.install("http://host/a.m3u|A;http://host/b.m3u|B", "")

        assertEquals("http://host/a.m3u|A;http://host/b.m3u|B;$PLAYLIST_URL|Free TV", playlists)
    }

    @Test
    fun `installing twice changes nothing`() {
        val (oncePlaylists, onceGuides) = FreeLineup.install("", "")
        val (twicePlaylists, twiceGuides) = FreeLineup.install(oncePlaylists, onceGuides)

        assertEquals(oncePlaylists, twicePlaylists)
        assertEquals(onceGuides, twiceGuides)
        assertEquals(
            "the guide URL must not be added twice",
            1,
            onceGuides.split('\n').count { it.trim() == EPG_URL }
        )
    }

    @Test
    fun `a renamed entry still counts as installed`() {
        // The URL is the identity; a viewer who renamed the source (or an older
        // install that wrote the bare URL) must not be offered it again.
        assertTrue(FreeLineup.isInstalled("$PLAYLIST_URL|Free TV (mine)"))
        assertTrue(FreeLineup.isInstalled(PLAYLIST_URL))
        assertTrue(FreeLineup.isInstalled("http://host/a.m3u|A;$PLAYLIST_URL|Free TV"))
        assertFalse(FreeLineup.isInstalled("http://host/a.m3u|A"))
        assertFalse(FreeLineup.isInstalled(""))
    }

    @Test
    fun `removing takes out exactly the two entries it added`() {
        val (playlists, guides) = FreeLineup.remove(
            "http://host/mine.m3u|Mine\n$PLAYLIST_URL|Free TV",
            "http://host/guide.xml\n$EPG_URL"
        )

        assertEquals("http://host/mine.m3u|Mine", playlists)
        assertEquals("http://host/guide.xml", guides)
        assertFalse(FreeLineup.isInstalled(playlists))
    }

    @Test
    fun `removing the last source leaves both fields empty`() {
        val (installed, guide) = FreeLineup.install("", "")
        val (playlists, guides) = FreeLineup.remove(installed, guide)

        assertEquals("", playlists)
        assertEquals("", guides)
    }

    @Test
    fun `removing a lineup that was never installed changes nothing`() {
        val (playlists, guides) = FreeLineup.remove("http://host/mine.m3u|Mine", "")

        assertEquals("http://host/mine.m3u|Mine", playlists)
        assertEquals("", guides)
    }

    @Test
    fun `the sources are the endpoints the playlist itself declares`() {
        // Pinned deliberately: these two constants ARE the feature, and a change
        // to them is an app update rather than a runtime setting. The guide is
        // the address `us.m3u`'s own x-tvg-url header names - the old iptv-org
        // guide path answers 404 and must never come back as EPG_URL.
        assertEquals(
            "https://iptv-org.github.io/iptv/countries/us.m3u",
            FreeLineup.PLAYLIST_URL
        )
        assertEquals(
            "https://epg.kanya.io/us/guide.xml.gz",
            FreeLineup.EPG_URL
        )
        assertEquals(
            "the dead address stays named so old installs can be recognised",
            "https://iptv-org.github.io/epg/guides/all.xml",
            FreeLineup.LEGACY_EPG_URL
        )
        assertEquals("Free TV", FreeLineup.NAME)
        assertEquals("Free TV (built-in)", FreeLineup.INSTALLED_LABEL)
    }

    @Test
    fun `an install carrying the dead guide is brought forward on the next write`() {
        // The field case: an install from the previous build holds the legacy
        // line, so a bare re-point would leave it fetching a 404 forever.
        val repaired = FreeLineup.repairEpgEntry(
            extraPlaylistUrls = "$PLAYLIST_URL|Free TV",
            extraEpgUrls = "http://host/mine.xml\n$LEGACY_EPG_URL"
        )

        assertEquals("http://host/mine.xml\n$EPG_URL", repaired)
        assertEquals(
            "and the swap is idempotent",
            null,
            FreeLineup.repairEpgEntry("$PLAYLIST_URL|Free TV", repaired!!)
        )
    }

    @Test
    fun `the repair keeps the separator the field uses`() {
        assertEquals(
            "http://host/mine.xml;$EPG_URL",
            FreeLineup.repairEpgEntry(
                "$PLAYLIST_URL|Free TV",
                "http://host/mine.xml;$LEGACY_EPG_URL"
            )
        )
    }

    @Test
    fun `a legacy line without the free playlist is the viewer's own and stays`() {
        // The pairing is the fingerprint: standing alone the line may be a
        // manual entry, and the repair does not edit what it cannot attribute.
        assertNull(
            FreeLineup.repairEpgEntry("http://host/mine.m3u|Mine", LEGACY_EPG_URL)
        )
        assertNull(FreeLineup.repairEpgEntry("", LEGACY_EPG_URL))
    }

    @Test
    fun `nothing to swap means no write`() {
        assertNull(FreeLineup.repairEpgEntry("$PLAYLIST_URL|Free TV", ""))
        assertNull(FreeLineup.repairEpgEntry("$PLAYLIST_URL|Free TV", EPG_URL))
        assertNull(
            FreeLineup.repairEpgEntry("$PLAYLIST_URL|Free TV", "http://host/mine.xml")
        )
    }

    @Test
    fun `a blob holding both addresses keeps exactly one guide line`() {
        assertEquals(
            EPG_URL,
            FreeLineup.repairEpgEntry(
                "$PLAYLIST_URL|Free TV",
                "$LEGACY_EPG_URL\n$EPG_URL"
            )
        )
    }

    @Test
    fun `installing over an older install brings the guide forward`() {
        val (_, guides) = FreeLineup.install("$PLAYLIST_URL|Free TV", LEGACY_EPG_URL)

        assertEquals(EPG_URL, guides)
    }

    @Test
    fun `removing takes out either vintage of the guide line`() {
        // Removal on a not-yet-repaired install must not leave the dead line
        // behind - that is the line that 404s on every import.
        val (_, legacyGuides) = FreeLineup.remove(
            "$PLAYLIST_URL|Free TV",
            "http://host/mine.xml\n$LEGACY_EPG_URL"
        )
        assertEquals("http://host/mine.xml", legacyGuides)

        val (_, currentGuides) = FreeLineup.remove(
            "$PLAYLIST_URL|Free TV",
            "http://host/mine.xml\n$EPG_URL"
        )
        assertEquals("http://host/mine.xml", currentGuides)
    }

    @Test
    fun `both guide vintages count as the lineup's entry`() {
        assertTrue(FreeLineup.isFreeLineupEpgEntry(EPG_URL))
        assertTrue(FreeLineup.isFreeLineupEpgEntry(LEGACY_EPG_URL))
        assertFalse(FreeLineup.isFreeLineupEpgEntry("http://host/mine.xml"))
        assertFalse(FreeLineup.isFreeLineupEpgEntry(""))
    }

    private companion object {
        const val PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/us.m3u"
        const val EPG_URL = "https://epg.kanya.io/us/guide.xml.gz"
        const val LEGACY_EPG_URL = "https://iptv-org.github.io/epg/guides/all.xml"
    }
}
