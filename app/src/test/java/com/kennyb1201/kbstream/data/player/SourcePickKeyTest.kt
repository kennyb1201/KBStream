package com.kennyb1201.kbstream.data.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two keys a remembered source pick is stored under (spec 2026-11-15).
 *
 * The spec calls the identity "the part most likely to be wrong in the tree",
 * and it is right: a key that names the wrong episode leaks a pick into the next
 * one, and a key that matches the wrong FILE plays a different video. Both are
 * silent - the app just starts something. So the rules are pinned here, with no
 * device and no resolver in the way.
 */
class SourcePickKeyTest {

    // ── Which episode ─────────────────────────────────────────────────────

    @Test
    fun `a pick is keyed by show AND episode, so one season cannot leak into the next`() {
        val e1 = SourcePickKey.episodeKey("tt0903747", 1, 1)
        val e2 = SourcePickKey.episodeKey("tt0903747", 1, 2)
        val nextSeason = SourcePickKey.episodeKey("tt0903747", 2, 1)
        assertEquals("tt0903747|s1|e1", e1)
        assertNotEquals("S1E1's pick must not answer for S1E2", e1, e2)
        assertNotEquals("nor for the next season's premiere", e1, nextSeason)
    }

    @Test
    fun `a movie is its own id, in a shape no episode key can collide with`() {
        assertEquals("tt0133093|", SourcePickKey.episodeKey("tt0133093", null, null))
        assertNotEquals(
            "a movie and episode 1 of it are different keys",
            SourcePickKey.episodeKey("tt0133093", null, null),
            SourcePickKey.episodeKey("tt0133093", 1, 1)
        )
    }

    @Test
    fun `a show with no id has no key - nothing is stored for it`() {
        assertNull(SourcePickKey.episodeKey(null, 1, 1))
        assertNull(SourcePickKey.episodeKey("", 1, 1))
        assertNull(SourcePickKey.episodeKey("   ", null, null))
    }

    @Test
    fun `an id carrying the encoding separators cannot forge another key`() {
        val hostile = SourcePickKey.episodeKey("tt1\u001Ftt2|s9|e9", 1, 1)
        assertFalse(
            "the separator is stripped from the id, so an id cannot end the key " +
                "early and continue as another show's",
            hostile!!.contains('\u001F')
        )
    }

    // ── Which row ─────────────────────────────────────────────────────────

    @Test
    fun `a row with a URL is identified by that URL`() {
        assertEquals(
            "u:https://cdn.example/a.mkv?token=1",
            SourcePickKey.identityOf("https://cdn.example/a.mkv?token=1", "abc", 0)
        )
        assertEquals(
            "a TorBox cloud row keeps its own reference URL",
            "u:kbstream-torbox-cloud://123/4",
            SourcePickKey.identityOf("kbstream-torbox-cloud://123/4", null, null)
        )
    }

    @Test
    fun `a hash row is identified by infohash AND file index`() {
        assertEquals(
            "h:abc123:-1",
            SourcePickKey.identityOf(null, "ABC123", null)
        )
        assertEquals(
            "one torrent holds every episode of a season - the index says which file",
            "h:abc123:7",
            SourcePickKey.identityOf(null, "ABC123", 7)
        )
        assertNotEquals(
            "two files of one torrent are two different picks",
            SourcePickKey.identityOf(null, "abc123", 3),
            SourcePickKey.identityOf(null, "abc123", 4)
        )
        assertNotEquals(
            "two torrents are two different picks even at the same index",
            SourcePickKey.identityOf(null, "abc123", 3),
            SourcePickKey.identityOf(null, "def456", 3)
        )
    }

    @Test
    fun `a row with neither a URL nor a hash is not remembered`() {
        assertNull(SourcePickKey.identityOf(null, null, null))
        assertNull(SourcePickKey.identityOf("  ", "", 2))
    }

    // ── The re-signed link ────────────────────────────────────────────────

    @Test
    fun `the same file re-signed by its host still matches`() {
        assertTrue(
            "a host that mints a new token for the same path must not make the " +
                "viewer's pick look gone",
            SourcePickKey.sameAddress(
                "https://cdn.example/movies/a.mkv?token=new&exp=2",
                "u:https://cdn.example/movies/a.mkv?token=old&exp=1"
            )
        )
        assertTrue(
            "a fragment is not part of the file either",
            SourcePickKey.sameAddress(
                "https://cdn.example/a.mkv#t=10",
                "u:https://cdn.example/a.mkv"
            )
        )
    }

    @Test
    fun `a different file at the same host never matches`() {
        assertFalse(
            SourcePickKey.sameAddress(
                "https://cdn.example/movies/b.mkv?token=new",
                "u:https://cdn.example/movies/a.mkv?token=old"
            )
        )
        assertFalse(
            "a different host is a different file, even at the same path",
            SourcePickKey.sameAddress(
                "https://mirror.example/a.mkv",
                "u:https://cdn.example/a.mkv"
            )
        )
        assertFalse(
            "a bare host is not an address",
            SourcePickKey.sameAddress("https://cdn.example", "u:https://cdn.example")
        )
        assertFalse(
            "hash identities are not compared this way",
            SourcePickKey.sameAddress("https://cdn.example/a.mkv", "h:abc:0")
        )
    }
}
