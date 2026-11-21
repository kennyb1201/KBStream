package com.kennyb1201.kbstream.data.simkl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The completion queue's identity and retry arithmetic (spec 2026-11-20, F2).
 *
 * These are the rules a drained queue depends on: the same episode queued twice
 * is ONE write (the latch re-posts completions, so duplicates are ordinary), a
 * 429 is not an attempt (it is a park), and a write that keeps failing is dropped
 * after five tries instead of pinning the queue forever.
 */
class SimklCompletionQueueRulesTest {

    private fun episode(
        imdbId: String = "tt7678620",
        season: Int = 3,
        episode: Int = 40,
        attempts: Int = 0,
        lastAttemptAt: Long = 0L
    ) = SimklCompletionEntry(
        kind = SimklCompletionKind.PUSH_EPISODE.name,
        imdbId = imdbId,
        tmdbId = 1234,
        title = "A Show",
        season = season,
        episodes = listOf(episode),
        attempts = attempts,
        lastAttemptAt = lastAttemptAt
    )

    @Test
    fun `the same episode queued twice is one write, kept where it was`() {
        val first = episode(episode = 40)
        val second = episode(episode = 39)
        val requeued = episode(episode = 40)

        var pending = SimklCompletionQueueRules.coalesce(emptyList(), first)
        pending = SimklCompletionQueueRules.coalesce(pending, second)
        pending = SimklCompletionQueueRules.coalesce(pending, requeued)

        assertEquals("the re-queued episode must not become a third entry", 2, pending.size)
        assertEquals(
            "and it must keep its place in the FIFO order, not move behind 39",
            40,
            pending.first().episodes.first()
        )
    }

    @Test
    fun `a re-queued write carries its attempt count forward`() {
        val failing = episode(attempts = 3, lastAttemptAt = 1_000L)

        val pending = SimklCompletionQueueRules.coalesce(listOf(failing), episode())

        assertEquals(
            "a re-queue proves the viewer watched it again, not that Simkl accepts it",
            3,
            pending.single().attempts
        )
    }

    @Test
    fun `episodes, seasons and whole titles are different writes`() {
        val sameShowOtherSeason = episode(season = 2, episode = 40)
        val pushSeason = SimklCompletionEntry(
            kind = SimklCompletionKind.PUSH_SEASON.name,
            imdbId = "tt7678620",
            season = 3,
            episodes = listOf(40)
        )
        val pushMovie = SimklCompletionEntry(
            kind = SimklCompletionKind.PUSH_MOVIE.name,
            imdbId = "tt7678620"
        )

        val keys = setOf(
            episode().targetKey,
            sameShowOtherSeason.targetKey,
            pushSeason.targetKey,
            pushMovie.targetKey
        )

        assertEquals("four distinct writes must be four distinct entries", 4, keys.size)
    }

    @Test
    fun `an episode push ignores the order its numbers were listed in`() {
        val ascending = SimklCompletionEntry(
            kind = SimklCompletionKind.PUSH_SEASON.name,
            imdbId = "tt1",
            season = 1,
            episodes = listOf(1, 2, 3)
        )
        val shuffled = ascending.copy(episodes = listOf(3, 1, 2))

        assertEquals(ascending.targetKey, shuffled.targetKey)
    }

    @Test
    fun `the backoff follows the ratings schedule and caps out at five`() {
        assertEquals(60_000L, SimklCompletionQueueRules.backoffFor(1))
        assertEquals(5 * 60_000L, SimklCompletionQueueRules.backoffFor(2))
        assertEquals(30 * 60_000L, SimklCompletionQueueRules.backoffFor(3))
        assertEquals(120 * 60_000L, SimklCompletionQueueRules.backoffFor(4))
        assertEquals(720 * 60_000L, SimklCompletionQueueRules.backoffFor(5))
    }

    @Test
    fun `a never-tried entry is due immediately, a failed one after its wait`() {
        val fresh = episode()
        assertTrue(SimklCompletionQueueRules.isDue(fresh, 0L))

        val failed = episode(attempts = 1, lastAttemptAt = 10_000L)
        assertFalse(
            "one failed attempt waits a minute",
            SimklCompletionQueueRules.isDue(failed, 10_000L + 59_999L)
        )
        assertTrue(SimklCompletionQueueRules.isDue(failed, 10_000L + 60_000L))
    }

    @Test
    fun `an entry is dropped rather than tried a sixth time`() {
        val fourthFailure = episode(attempts = 4, lastAttemptAt = 0L)

        val fifth = SimklCompletionQueueRules.afterFailure(fourthFailure, 5_000L, "no answer")

        assertEquals(5, fifth?.attempts)
        assertTrue(SimklCompletionQueueRules.isSpent(fifth!!))
        assertNull(
            "the cap drops it instead of recording a sixth",
            SimklCompletionQueueRules.afterFailure(fifth, 6_000L, "no answer")
        )
    }
}
