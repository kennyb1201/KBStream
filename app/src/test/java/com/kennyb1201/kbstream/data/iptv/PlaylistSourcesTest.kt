package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rows Playlist Setup shows, and the line Remove takes out.
 *
 * Reported (field, 2026-10-24): there was no way to remove a playlist. The form
 * held the sources as raw text - one primary URL/name pair and one extras blob -
 * so the only affordances were editing the blob by hand or CLEAR, which blanks
 * every field at once. These are the two pure rules behind the rows, asserted
 * here because the panel itself is a Compose screen a JVM test cannot press: the
 * blob's shape is what a mistake here would quietly damage, and it is also what
 * a viewer sees in their own field.
 */
class PlaylistSourcesTest {

    @Test
    fun `the main playlist is a row, named or bare`() {
        val named = PlaylistSources.rows(
            playlistUrl = "http://host/main.m3u",
            playlistName = "My provider",
            extraPlaylistUrls = ""
        )
        assertEquals(1, named.size)
        assertTrue(named[0].isMain)
        assertEquals("My provider", named[0].label)
        assertEquals("http://host/main.m3u", named[0].url)

        val unnamed = PlaylistSources.rows(
            playlistUrl = "http://host/main.m3u",
            playlistName = "   ",
            extraPlaylistUrls = ""
        )
        assertEquals(
            "an unnamed source is still a source: the URL is its label",
            "http://host/main.m3u",
            unnamed[0].label
        )
    }

    @Test
    fun `every extra is a row, in stored order, by its name or its URL`() {
        val rows = PlaylistSources.rows(
            playlistUrl = "http://host/main.m3u",
            playlistName = "",
            extraPlaylistUrls = "http://host/a.m3u|Provider A\nhttp://host/b.m3u"
        )
        assertEquals(listOf(true, false, false), rows.map { it.isMain })
        assertEquals(
            listOf("http://host/main.m3u", "Provider A", "http://host/b.m3u"),
            rows.map { it.label }
        )
        assertEquals(
            listOf("http://host/main.m3u", "http://host/a.m3u", "http://host/b.m3u"),
            rows.map { it.url }
        )
    }

    @Test
    fun `a blank slot is not a row`() {
        assertEquals(
            "nothing installed means no rows - the offer's state, not an error",
            emptyList<PlaylistSources.Source>(),
            PlaylistSources.rows(
                playlistUrl = "",
                playlistName = "leftover name",
                extraPlaylistUrls = "\n  \n"
            )
        )
    }

    @Test
    fun `the free lineup is never a row`() {
        val rows = PlaylistSources.rows(
            playlistUrl = "",
            playlistName = "",
            extraPlaylistUrls = "http://host/a.m3u|A;" + FreeLineup.ENTRY
        )
        assertEquals(
            "it has its own block, with a Remove that also strips its guide entry: " +
                "a second row would be two Remove buttons that behave differently",
            listOf("http://host/a.m3u"),
            rows.map { it.url }
        )
        assertFalse(
            "matched through the same rule the install and removal use",
            PlaylistSources.rows(
                playlistUrl = "",
                playlistName = "",
                extraPlaylistUrls = FreeLineup.PLAYLIST_URL + "|Free TV (renamed)"
            ).any { !it.isMain }
        )
    }

    @Test
    fun `removing an extra takes its line and keeps the rest exactly as typed`() {
        val blob = "http://host/a.m3u|Provider A\nhttp://host/b.m3u|B\nhttp://host/c.m3u"
        assertEquals(
            "the surviving lines keep their names and their order",
            "http://host/a.m3u|Provider A\nhttp://host/c.m3u",
            PlaylistSources.removeEntry(blob, "http://host/b.m3u")
        )
    }

    @Test
    fun `a semicolon field keeps its semicolons`() {
        assertEquals(
            "the viewer's own separator is not rewritten under them",
            "http://host/a.m3u|A;http://host/c.m3u",
            PlaylistSources.removeEntry(
                "http://host/a.m3u|A;http://host/b.m3u|B;http://host/c.m3u",
                "http://host/b.m3u"
            )
        )
    }

    @Test
    fun `removing the only entry empties the field`() {
        assertEquals(
            "the field is cleared, which is what the next load reads as no extra source",
            "",
            PlaylistSources.removeEntry("http://host/only.m3u|Only", "http://host/only.m3u")
        )
    }

    @Test
    fun `every copy of the removed URL goes`() {
        assertEquals(
            "two identical lines are one source to every reader of this blob; a " +
                "Remove that left the duplicate behind would leave the row standing",
            "http://host/keep.m3u",
            PlaylistSources.removeEntry(
                "http://host/dup.m3u\nhttp://host/keep.m3u\nhttp://host/dup.m3u|Dup again",
                "http://host/dup.m3u"
            )
        )
    }

    @Test
    fun `removing something that is not there changes nothing`() {
        val blob = "http://host/a.m3u|A\nhttp://host/b.m3u"
        assertEquals(blob, PlaylistSources.removeEntry(blob, "http://host/missing.m3u"))
        assertEquals(
            "a blank URL names no source",
            blob,
            PlaylistSources.removeEntry(blob, "  ")
        )
    }

    @Test
    fun `the free lineup's own entry is matched the way its block matches it`() {
        assertTrue(FreeLineup.isFreeLineupEntry(FreeLineup.ENTRY))
        assertTrue(FreeLineup.isFreeLineupEntry(FreeLineup.PLAYLIST_URL))
        assertTrue(
            "a renamed entry is still the free lineup",
            FreeLineup.isFreeLineupEntry(FreeLineup.PLAYLIST_URL + "|Free TV (mine)")
        )
        assertFalse(FreeLineup.isFreeLineupEntry("http://host/a.m3u|A"))
    }
}
