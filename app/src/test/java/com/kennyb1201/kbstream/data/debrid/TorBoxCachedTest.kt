package com.kennyb1201.kbstream.data.debrid

import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.badges.StreamBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading TorBox's cached list, and badging the sources it names.
 *
 * Both halves are pure and both fail silently in production — a parse that reads
 * the wrong field badges nothing, and a badge written before the pack step is
 * discarded — so the shape of the response and the merge into a stream's own
 * badges are pinned here rather than discovered on the TV.
 */
class TorBoxCachedTest {

    private val hashA = "0123456789abcdef0123456789abcdef01234567"
    private val hashB = "89abcdef0123456789abcdef0123456789abcdef"

    private fun stream(hash: String? = null, badges: List<StreamBadge> = emptyList()) =
        Stream(
            name = "Release",
            url = "https://cdn.example.com/f.mp4",
            infoHash = hash,
            badges = badges
        )

    // ── parsing the response ─────────────────────────────────────────────────

    @Test
    fun `the list format yields the cached hashes`() {
        val body = """{"success":true,"error":null,"detail":"ok","data":[
            {"hash":"$hashA","name":"A","size":100},
            {"hash":"$hashB","name":"B","size":200}
        ]}"""
        assertEquals(setOf(hashA, hashB), TorBoxClient.parseCached(body))
    }

    @Test
    fun `hashes are lowercased for matching`() {
        val body = """{"data":[{"hash":"${hashA.uppercase()}"}]}"""
        assertEquals(setOf(hashA), TorBoxClient.parseCached(body))
    }

    @Test
    fun `the object format yields its keys`() {
        val body = """{"data":{"$hashA":{"name":"A"},"$hashB":{"name":"B"}}}"""
        assertEquals(setOf(hashA, hashB), TorBoxClient.parseCached(body))
    }

    @Test
    fun `an empty or malformed body yields nothing`() {
        assertTrue(TorBoxClient.parseCached("").isEmpty())
        assertTrue(TorBoxClient.parseCached("not json").isEmpty())
        assertTrue(TorBoxClient.parseCached("""{"success":false,"data":null}""").isEmpty())
    }

    @Test
    fun `an entry without a hash is ignored`() {
        val body = """{"data":[{"name":"no hash"},{"hash":"$hashA"}]}"""
        assertEquals(setOf(hashA), TorBoxClient.parseCached(body))
    }

    // ── badging the matching sources ─────────────────────────────────────────

    @Test
    fun `a cached hash gets the chip`() {
        val cached = stream(hash = hashA)
        val marked = TorBoxCachedBadges.mark(listOf(cached), setOf(hashA))
        assertEquals(1, marked.size)
        assertTrue(marked[0].badges.any { it.name == TorBoxCachedBadges.BADGE_NAME })
    }

    @Test
    fun `a hash that is not cached is left alone`() {
        val other = stream(hash = hashB)
        val marked = TorBoxCachedBadges.mark(listOf(other), setOf(hashA))
        assertTrue(marked[0].badges.isEmpty())
    }

    @Test
    fun `a source with no hash is never badged`() {
        val direct = stream(hash = null)
        val marked = TorBoxCachedBadges.mark(listOf(direct), setOf(hashA))
        assertTrue(marked[0].badges.isEmpty())
    }

    @Test
    fun `an empty cached set short-circuits`() {
        val list = listOf(stream(hash = hashA))
        assertSame(list, TorBoxCachedBadges.mark(list, emptySet()))
    }

    @Test
    fun `the chip is appended after a badge pack's own badges`() {
        val packBadge = StreamBadge(name = "4K", tagColor = "#000000")
        val cached = stream(hash = hashA, badges = listOf(packBadge))
        val marked = TorBoxCachedBadges.mark(listOf(cached), setOf(hashA))
        assertEquals(listOf("4K", TorBoxCachedBadges.BADGE_NAME), marked[0].badges.map { it.name })
    }

    @Test
    fun `a pack that already marked the copy cached is not doubled`() {
        val cached = stream(
            hash = hashA,
            badges = listOf(StreamBadge(name = TorBoxCachedBadges.BADGE_NAME))
        )
        val marked = TorBoxCachedBadges.mark(listOf(cached), setOf(hashA))
        assertEquals(1, marked[0].badges.count { it.name == TorBoxCachedBadges.BADGE_NAME })
    }

    @Test
    fun `an unmatched mixed list keeps the uncached sources untouched`() {
        val a = stream(hash = hashA)
        val b = stream(hash = hashB)
        val marked = TorBoxCachedBadges.mark(listOf(a, b), setOf(hashA))
        assertTrue(marked[0].badges.isNotEmpty())
        assertFalse(marked[1].badges.isNotEmpty())
    }
}
