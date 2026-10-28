package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto-play's walk down the list: what it starts, and what it may spend.
 *
 * Pinned by behaviour because the rules are pure suspend functions: a url row
 * starts without a round trip, a hash row is prepared and substituted in place,
 * a failure moves to the next candidate, and the resolve budget bounds the
 * ROUND TRIPS rather than the walk - so url rows behind a pile of hashes still
 * start once the budget is spent. That last one is the reported failure: the
 * cap used to `return null` before the walk had looked at the free rows after
 * it, which handed the viewer a picker while playable links sat in the list.
 */
class AutoplayWalkTest {

    private fun hash(title: String) = Stream(name = title, title = title, infoHash = "hash-$title")

    private fun link(title: String) = Stream(name = title, title = title, url = "https://host/$title")

    /** Always the head of what is left, like the real episode pick. */
    private val headPick: (List<Stream>) -> Stream? = { it.firstOrNull() }

    @Test
    fun `a url row starts without a resolve`() = runBlocking {
        val free = link("free")
        val hashRow = hash("slow")
        var resolves = 0
        val start = AutoplayWalk.start(
            candidates = listOf(free, hashRow),
            pick = headPick,
            resolve = { resolves++; null }
        )
        assertSame("the url-bearing head is what starts", free, start?.stream)
        assertFalse("and it was not resolved", start!!.resolved)
        assertEquals("nobody paid a round trip", 0, resolves)
    }

    @Test
    fun `a prepared hash is substituted in place, so the player list lines up`() = runBlocking {
        val hashRow = hash("first")
        val behind = link("behind")
        val prepared = link("prepared")
        val start = AutoplayWalk.start(
            candidates = listOf(hashRow, behind),
            pick = headPick,
            resolve = { prepared }
        )
        assertSame(prepared, start?.stream)
        assertTrue("the caller is told it was prepared", start!!.resolved)
        assertSame(
            "the prepared row replaces the hash where it stood",
            prepared,
            start.sources[0]
        )
        assertSame("and every other row keeps its place", behind, start.sources[1])
    }

    @Test
    fun `a failed hash moves the walk to the next candidate`() = runBlocking {
        val first = hash("first")
        val second = hash("second")
        val free = link("free")
        val tried = mutableListOf<Stream>()
        val start = AutoplayWalk.start(
            candidates = listOf(first, second, free),
            pick = headPick,
            resolve = { tried += it; null }
        )
        assertEquals("both hashes were tried, in order", listOf(first, second), tried)
        assertSame("then the free row started", free, start?.stream)
        assertFalse(start!!.resolved)
    }

    @Test
    fun `a spent resolve budget still starts a url row behind the hashes`() = runBlocking {
        val hashes = (1..4).map { hash("h$it") }
        val free = link("free")
        var resolves = 0
        val start = AutoplayWalk.start(
            candidates = hashes + free,
            pick = headPick,
            resolve = { resolves++; null },
            maxAttempts = AutoplayWalk.MAX_RESOLVE_ATTEMPTS
        )
        assertEquals(
            "the budget bounds resolves, not the walk",
            AutoplayWalk.MAX_RESOLVE_ATTEMPTS,
            resolves
        )
        assertSame(
            "and the free row after them still starts - a picker here was the bug",
            free,
            start?.stream
        )
    }

    @Test
    fun `a hash the walk refused is not handed to the player`() = runBlocking {
        val dead = hash("dead")
        val alsoDead = hash("also-dead")
        val free = link("free")
        val start = AutoplayWalk.start(
            candidates = listOf(dead, alsoDead, free),
            pick = headPick,
            resolve = { null }
        )
        assertSame(free, start?.stream)
        assertEquals(
            "the rows the walk gave up on are gone, not left for next-source to try",
            listOf(free),
            start!!.sources
        )
    }

    @Test
    fun `nothing startable is still a null answer`() = runBlocking {
        var resolves = 0
        val start = AutoplayWalk.start(
            candidates = listOf(hash("only")),
            pick = headPick,
            resolve = { resolves++; null }
        )
        assertNull("no free row and no prepared hash means the picker", start)
        assertEquals(1, resolves)
    }

    @Test
    fun `a pick that never leaves the list cannot spin the walk`() = runBlocking {
        val row = hash("only")
        val start = AutoplayWalk.start(
            candidates = listOf(row),
            pick = { row },
            resolve = { null },
            maxAttempts = 1
        )
        // One attempt, then the spent-budget branch must remove the row. A pick
        // that keeps naming a row already removed is the caller's bug, and the
        // walk answers null instead of looping forever.
        assertNull(start)
    }
}
