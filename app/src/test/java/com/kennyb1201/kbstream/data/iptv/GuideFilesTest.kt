package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a guide file is called, which is how many copies of one provider's guide
 * the device keeps.
 *
 * The naming decides two things at once: where a profile's guide is written, and
 * whether two profiles write the SAME rows twice. On the field TV that was
 * 132 + 124 + 118 MB of guide files for three profiles on one playlist, so the
 * rules below are the whole of the saving — and a mistake in them does not fail
 * loudly, it either keeps the copies or hands one profile another's file.
 */
class GuideFilesTest {

    // ---- the playlist behind the name ------------------------------------

    @Test
    fun `the main and extra playlist urls are one list`() {
        assertEquals(
            listOf("http://a/m3u", "http://b/m3u", "http://c/m3u"),
            playlistUrlsOf("http://a/m3u", "http://b/m3u;http://c/m3u")
        )
        // The main field is blank on an install that only has extras.
        assertEquals(listOf("http://b/m3u"), playlistUrlsOf("   ", "http://b/m3u"))
        assertEquals(emptyList<String>(), playlistUrlsOf(null, null))
    }

    @Test
    fun `a playlist listed twice is one playlist`() {
        assertEquals(
            listOf("http://a/m3u"),
            playlistUrlsOf("http://a/m3u", "http://a/m3u")
        )
    }

    // ---- the key ---------------------------------------------------------

    @Test
    fun `the same playlists in any order are the same key`() {
        assertEquals(
            playlistKey(listOf("http://a", "http://b")),
            playlistKey(listOf("http://b", "http://a"))
        )
    }

    @Test
    fun `a different playlist is a different key`() {
        assertNotEquals(
            playlistKey(listOf("http://a")),
            playlistKey(listOf("http://b"))
        )
        // The URL is compared verbatim: two accounts on one provider are two
        // playlists, and treating them as one would hand a profile another's.
        assertNotEquals(
            playlistKey(listOf("http://a/m3u")),
            playlistKey(listOf("http://a/m3u?user=1"))
        )
    }

    @Test
    fun `no playlist is no key`() {
        assertNull(playlistKey(emptyList()))
        assertNull(playlistKey(listOf("  ", "")))
    }

    @Test
    fun `the key is a stable, filename-safe label`() {
        val key = playlistKey(listOf("http://a"))!!

        assertEquals(12, key.length)
        assertTrue(key.all { character -> character in "0123456789abcdef" })
        // Stable across calls: this is a file name, not a hash of the moment.
        assertEquals(key, playlistKey(listOf("http://a")))
    }

    // ---- the name --------------------------------------------------------

    @Test
    fun `a profile with no shared playlist keeps its own name`() {
        assertEquals("profile-1.iptv_epg.db", guideNameFor("profile-1", null))
    }

    @Test
    fun `profiles on one playlist share one name`() {
        val key = playlistKey(listOf("http://a"))!!

        assertEquals("$key.iptv_epg.db", guideNameFor("profile-1", key))
        assertEquals(
            guideNameFor("profile-1", key),
            guideNameFor("profile-2", key)
        )
        assertNotEquals(
            guideNameFor("profile-1", null),
            guideNameFor("profile-1", key)
        )
    }

    @Test
    fun `every name is a guide file the sweep can see`() {
        // The sweep recognises guides by suffix, so both naming schemes have to
        // end in one — a name it could not see would be a file nothing ever
        // reclaims.
        assertTrue(guideNameFor("profile-1", null).endsWith(GuideStorage.DB_SUFFIX))
        assertTrue(guideNameFor("profile-1", "abc123abc123").endsWith(GuideStorage.DB_SUFFIX))
        assertNotEquals(GuideStorage.LEGACY_DB_NAME, guideNameFor("profile-1", null))
    }

    // ---- is the freshness marker about the guide being read? ---------------

    private val shared = "abc123abc123.iptv_epg.db"

    @Test
    fun `a marker for the guide being read is not stale`() {
        assertTrue(markerMatchesGuideFile(shared, shared, "profile-1"))
        assertTrue(
            markerMatchesGuideFile(
                "profile-1.iptv_epg.db",
                "profile-1.iptv_epg.db",
                "profile-1"
            )
        )
    }

    @Test
    fun `a marker from before the guide moved does not match`() {
        // The state this exists for: the profile's guide is now the shared file,
        // which is empty until a re-import, while the marker still says fresh.
        assertFalse(markerMatchesGuideFile("profile-1.iptv_epg.db", shared, "profile-1"))
    }

    @Test
    fun `a marker written before names were recorded refers to the profile file`() {
        // Every marker on a device that has never shared a guide is this one, and
        // it must keep meaning what it meant then — otherwise every install would
        // re-import its guide once on upgrade for nothing.
        assertTrue(
            markerMatchesGuideFile(null, "profile-1.iptv_epg.db", "profile-1")
        )
        assertFalse(markerMatchesGuideFile(null, shared, "profile-1"))
    }

    @Test
    fun `with no profile the guide is the legacy file`() {
        assertEquals(
            GuideStorage.LEGACY_DB_NAME,
            recordedGuideName(null, null)
        )
        assertTrue(
            markerMatchesGuideFile(null, GuideStorage.LEGACY_DB_NAME, null)
        )
        assertFalse(markerMatchesGuideFile("profile-1.iptv_epg.db", GuideStorage.LEGACY_DB_NAME, null))
    }

    @Test
    fun `a recorded name is compared verbatim`() {
        // The marker is a file name, not a key to re-derive: whatever was
        // written is what it is compared against, profile stored or not.
        assertTrue(markerMatchesGuideFile(shared, shared, "profile-2"))
        assertFalse(markerMatchesGuideFile(shared, "other.iptv_epg.db", "profile-2"))
    }
}
