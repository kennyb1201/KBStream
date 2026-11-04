package com.kennyb1201.kbstream.data.debrid

import com.kennyb1201.kbstream.data.addon.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cached-only rule itself (spec T1).
 *
 * The picker's filter is only as good as this object: it decides which row a
 * viewer loses, and every fail-open case in the ViewModel is a decision not to
 * call it. So the cases that matter here are the ones the rule must NOT hide -
 * a direct link, and anything the check could never have judged - beside the one
 * it must.
 */
class TorBoxCachedOnlyTest {

    private companion object {
        const val CACHED_HASH = "0123456789abcdef0123456789abcdef01234567"
        const val UNCACHED_HASH = "89abcdef0123456789abcdef0123456789abcdef"
        const val HEAVY = 40.0
    }

    private fun row(name: String, hash: String? = null) = Stream(
        name = "Penguplay",
        title = name,
        infoHash = hash,
        url = if (hash == null) "https://cdn.example/$name.mkv" else null
    )

    @Test
    fun `a hash row the account holds is kept`() {
        val cached = row("cached", CACHED_HASH)
        assertTrue(TorBoxCachedOnly.keeps(cached, setOf(CACHED_HASH)))
    }

    @Test
    fun `a hash row the account does not hold is dropped`() {
        val uncached = row("uncached", UNCACHED_HASH)
        assertFalse(
            "an uncached Comet row is dead on press; Cached-only is the setting " +
                "that says so before the press",
            TorBoxCachedOnly.keeps(uncached, setOf(CACHED_HASH))
        )
    }

    @Test
    fun `a direct row is kept whatever the answer says`() {
        val direct = row("direct")
        assertTrue(TorBoxCachedOnly.keeps(direct, emptySet()))
        assertTrue(TorBoxCachedOnly.keeps(direct, setOf(CACHED_HASH)))
    }

    @Test
    fun `a row whose hash the check could not look up counts as a direct row`() {
        // Blank, whitespace and too-short hashes are all "no hash the check can
        // query". Dropping them would hide rows the check never judged - the one
        // thing the filter must never do.
        listOf("", "   ", "abc123", "0123456789abcdef").forEach { malformed ->
            val stream = row("malformed", malformed)
            assertTrue(
                "a malformed hash is not evidence of anything: '$malformed'",
                TorBoxCachedOnly.keeps(stream, emptySet())
            )
        }
        val noHash = row("absent", null)
        assertTrue(TorBoxCachedOnly.keeps(noHash, emptySet()))
    }

    @Test
    fun `matching is on the normalized hash, the same normalization the check uses`() {
        val padded = row("padded", "  ${CACHED_HASH.uppercase()}  ")
        assertTrue(
            "the badge pass trims and lowercases before matching; the filter has " +
                "to read the same row the same way or a chipped row could be hidden",
            TorBoxCachedOnly.keeps(padded, setOf(CACHED_HASH))
        )
    }

    @Test
    fun `lookupHash answers null for anything the check would skip`() {
        assertNull(TorBoxCachedOnly.lookupHash(null))
        assertNull(TorBoxCachedOnly.lookupHash(""))
        assertNull(TorBoxCachedOnly.lookupHash("short"))
        assertEquals(CACHED_HASH, TorBoxCachedOnly.lookupHash(" $CACHED_HASH "))
    }

    @Test
    fun `rows filters in place and keeps the surviving order`() {
        val head = row("cached-head", CACHED_HASH)
        val direct = row("direct")
        val uncached = row("uncached", UNCACHED_HASH)
        val tail = row("cached-tail", "fedcba9876543210fedcba9876543210fedcba98")

        val kept = TorBoxCachedOnly.rows(
            listOf(head, direct, uncached, tail),
            setOf(CACHED_HASH, "fedcba9876543210fedcba9876543210fedcba98")
        )
        assertEquals(
            "rank order is the list's own; the filter only removes from it",
            listOf(head, direct, tail),
            kept
        )
    }

    @Test
    fun `an answered empty set hides every hash row and no direct row`() {
        val kept = TorBoxCachedOnly.rows(
            listOf(row("a", CACHED_HASH), row("b", UNCACHED_HASH), row("c")),
            emptySet()
        )
        assertEquals(
            "ANSWERED-none-cached is a real answer: it empties the hash rows, " +
                "and the picker's own empty state explains it (spec F4)",
            1,
            kept.size
        )
        assertNull(kept.single().infoHash)
    }

    // Guard against a filter that quietly started reading the stream's labels:
    // the rule is the hash, and nothing else about the row.
    @Test
    fun `size and title play no part in the rule`() {
        val small = row("small", UNCACHED_HASH)
        assertFalse(TorBoxCachedOnly.keeps(small.copy(description = "$HEAVY GB"), setOf(CACHED_HASH)))
        assertTrue(TorBoxCachedOnly.keeps(row("x", CACHED_HASH).copy(description = "0.1 GB"), setOf(CACHED_HASH)))
    }
}
