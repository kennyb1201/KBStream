package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `the sources are the public-domain iptv-org endpoints`() {
        // Pinned deliberately: these two constants ARE the feature, and a change
        // to them is an app update rather than a runtime setting.
        assertEquals(
            "https://iptv-org.github.io/iptv/countries/us.m3u",
            FreeLineup.PLAYLIST_URL
        )
        assertEquals(
            "https://iptv-org.github.io/epg/guides/all.xml",
            FreeLineup.EPG_URL
        )
        assertEquals("Free TV", FreeLineup.NAME)
        assertEquals("Free TV (built-in)", FreeLineup.INSTALLED_LABEL)
    }

    private companion object {
        const val PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/us.m3u"
        const val EPG_URL = "https://iptv-org.github.io/epg/guides/all.xml"
    }
}
