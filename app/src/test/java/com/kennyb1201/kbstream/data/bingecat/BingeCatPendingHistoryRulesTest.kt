package com.kennyb1201.kbstream.data.bingecat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue behind BingeCat's completions (spec audit 2026-11-18, Data M2).
 *
 * These are the decisions that go wrong SILENTLY on a device: a duplicate entry
 * turns one click into two plays, a missing cap grows a file forever, a missing
 * age/attempt bound retries a dead entry at every launch, and a missing profile
 * filter files a child's play against a parent's account. None of that is
 * visible on a TV, so it is pinned here.
 */
class BingeCatPendingHistoryRulesTest {

    private val day = 24L * 60L * 60L * 1_000L

    private fun entry(
        key: String,
        profileId: Long = 7L,
        queuedAtMs: Long = 1_000L,
        attempts: Int = 0
    ) = BingeCatPendingPlay(
        mappedProfileId = profileId,
        key = key,
        body = "{}",
        label = "label $key",
        queuedAtMs = queuedAtMs,
        attempts = attempts
    )

    @Test
    fun `one play has one identity`() {
        val a = BingeCatPendingHistoryRules.key(
            BingeCatPendingHistoryRules.Kind.EPISODES, "tt1", 42, 2, listOf(3, 4)
        )
        val b = BingeCatPendingHistoryRules.key(
            BingeCatPendingHistoryRules.Kind.EPISODES, "tt1", 42, 2, listOf(4, 3)
        )
        assertEquals("an episode list is sorted into the key, so the same two plays are one key", a, b)
        assertNotEquals(
            "a different season is a different play",
            a,
            BingeCatPendingHistoryRules.key(
                BingeCatPendingHistoryRules.Kind.EPISODES, "tt1", 42, 3, listOf(3, 4)
            )
        )
        assertNotEquals(
            "a movie and a show are not the same play",
            BingeCatPendingHistoryRules.key(
                BingeCatPendingHistoryRules.Kind.MOVIE, "tt1", null, null, emptyList()
            ),
            BingeCatPendingHistoryRules.key(
                BingeCatPendingHistoryRules.Kind.SHOW, "tt1", null, null, emptyList()
            )
        )
        assertNotEquals(
            "an absent id cannot collide with a present one that reads the same",
            BingeCatPendingHistoryRules.key(
                BingeCatPendingHistoryRules.Kind.MOVIE, null, 5, null, emptyList()
            ),
            BingeCatPendingHistoryRules.key(
                BingeCatPendingHistoryRules.Kind.MOVIE, "5", null, null, emptyList()
            )
        )
    }

    @Test
    fun `re-completing a play replaces its entry instead of adding a second`() {
        val first = entry("k1", queuedAtMs = 1_000L)
        val queued = BingeCatPendingHistoryRules.enqueue(emptyList(), first, 1_000L)
        assertEquals(1, queued.size)

        val again = entry("k1", queuedAtMs = 2_000L)
        val replaced = BingeCatPendingHistoryRules.enqueue(queued, again, 2_000L)
        assertEquals("one click must never become two plays", 1, replaced.size)
        assertEquals("and the newer attempt is the one that stands", 2_000L, replaced[0].queuedAtMs)
    }

    @Test
    fun `different plays stack, and the cap keeps the newest`() {
        var entries = emptyList<BingeCatPendingPlay>()
        val total = BingeCatPendingHistoryRules.MAX_ENTRIES + 5
        repeat(total) { index ->
            entries = BingeCatPendingHistoryRules.enqueue(
                entries,
                entry("k$index", queuedAtMs = index.toLong()),
                index.toLong()
            )
        }
        assertEquals(
            "the queue is bounded",
            BingeCatPendingHistoryRules.MAX_ENTRIES,
            entries.size
        )
        assertEquals("oldest first", "k5", entries.first().key)
        assertEquals("and the newest play is never the one dropped", "k${total - 1}", entries.last().key)
    }

    @Test
    fun `an entry ages out rather than being retried forever`() {
        val old = entry("old", queuedAtMs = 0L)
        val fresh = entry("fresh", queuedAtMs = BingeCatPendingHistoryRules.MAX_AGE_MS)
        val kept = BingeCatPendingHistoryRules.prune(
            listOf(old, fresh),
            BingeCatPendingHistoryRules.MAX_AGE_MS + 1L
        )
        assertEquals("a play a week old is not a play any more", listOf("fresh"), kept.map { it.key })
        assertTrue(
            "and the boundary itself is still young enough",
            BingeCatPendingHistoryRules.prune(
                listOf(fresh),
                BingeCatPendingHistoryRules.MAX_AGE_MS
            ).isNotEmpty()
        )
    }

    @Test
    fun `a success drops the entry and a failure counts an attempt`() {
        val queued = listOf(entry("k1"), entry("k2"))
        assertEquals(
            "a landed replay leaves the queue",
            listOf("k2"),
            BingeCatPendingHistoryRules.settle(queued, "k1", success = true).map { it.key }
        )
        val failed = BingeCatPendingHistoryRules.settle(queued, "k1", success = false)
        assertEquals(1, failed.first { it.key == "k1" }.attempts)
        assertEquals("the other entry is untouched", 0, failed.first { it.key == "k2" }.attempts)
    }

    @Test
    fun `an entry is given up on after its attempts`() {
        var entries = listOf(entry("k1"))
        repeat(BingeCatPendingHistoryRules.MAX_ATTEMPTS) {
            entries = BingeCatPendingHistoryRules.settle(entries, "k1", success = false)
        }
        assertTrue("the third failure drops it: nothing retries a dead play forever", entries.isEmpty())
    }

    @Test
    fun `only the mapped profile's plays are ever replayed`() {
        val mine = entry("mine", profileId = 7L)
        val theirs = entry("theirs", profileId = 9L)
        val usable = BingeCatPendingHistoryRules.usable(listOf(mine, theirs), 7L, 1_000L)
        assertEquals(
            "another profile's play is dropped, never filed against this one",
            listOf("mine"),
            usable.map { it.key }
        )
        assertTrue(
            "and with no mapping there is nothing to replay",
            BingeCatPendingHistoryRules.usable(listOf(mine), 0L, 1_000L).isEmpty()
        )
    }

    @Test
    fun `the oldest play is replayed first`() {
        val usable = BingeCatPendingHistoryRules.usable(
            listOf(
                entry("late", queuedAtMs = 900L),
                entry("early", queuedAtMs = 100L),
                entry("middle", queuedAtMs = 500L)
            ),
            7L,
            1_000L
        )
        assertEquals(
            "history is written in the order it happened",
            listOf("early", "middle", "late"),
            usable.map { it.key }
        )
    }
}
