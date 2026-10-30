package com.kennyb1201.kbstream.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry a mid-play death is owed, as arithmetic.
 *
 * The 2026-10-30 report is the Detail hero's trailer dying at ~20 s at the same
 * spot on BOTH attempts and then handing the backdrop back - on the phone only,
 * over Wi-Fi, while the same clip plays on the TV. A death that survives a
 * re-sign at the same position is persistent at that position, so the retry has
 * to change the REQUEST: another client's URL class when one is left, and a
 * resume point past the failing range when one is not.
 *
 * Everything here is a decision, not a device: which class the replacement
 * avoids, where it starts, and when the ladder is done. The one number that is
 * not a decision - the skip - is derived from the stream's own length, and the
 * derivation is pinned too, because "skip a chunk" is meaningless in a timeline
 * unless the byte size is converted.
 */
class HeroTrailerRetryPlanTest {

    /** The five clients that can sign a source (`YouTubeClients.all`'s keys). */
    private val clients = setOf("android", "tv", "ios", "web_embedded", "android_vr")

    private fun plan(
        positionMs: Long = 20_400L,
        durationMs: Long = 140_849L,
        deadClient: String? = "android_vr",
        excluded: Set<String> = emptySet(),
        identical: Int = 0,
        attempts: Int = 0,
        chunkBytes: Long = YoutubeChunkedDataSourceFactory.CHUNK_SIZE,
        totalBytes: Long? = null
    ) = heroTrailerRetryPlan(
        positionMs = positionMs,
        durationMs = durationMs,
        deadClient = deadClient,
        excludedClients = excluded,
        identicalReplays = identical,
        resumeAttempts = attempts,
        chunkBytes = chunkBytes,
        totalBytes = totalBytes,
        allClientKeys = clients
    )

    @Test
    fun `a death of a known class changes the request and resumes at the death`() {
        val decision = plan(deadClient = "android_vr")

        assertEquals(HeroTrailerResumeVerdict.RESUME, decision.verdict)
        assertTrue("the class that died must not sign the replacement", decision.changedClass)
        assertEquals(setOf("android_vr"), decision.excludedClients)
        assertEquals(
            "a different stream has no reason to fail where the old one did",
            20_400L,
            decision.resumeAtMs
        )
    }

    @Test
    fun `an identical replay resumes PAST the death, never at it`() {
        val decision = plan(deadClient = null)

        assertEquals(HeroTrailerResumeVerdict.RESUME, decision.verdict)
        assertFalse("nothing new to exclude: this is the same class", decision.changedClass)
        assertEquals("the death position is exactly what a replay must not re-read",
            IDENTICAL_REPLAY_SKIP_MS, decision.resumeAtMs - 20_400L)
    }

    @Test
    fun `a chunk's own length turns the skip into media time`() {
        // 5 MB of a 50 s stream: one 1 MB chunk is ~10.5 s of playback, so an
        // identical replay has to start that far past the death.
        val decision = plan(positionMs = 20_000L, durationMs = 50_000L, totalBytes = 5_000_000L)

        assertEquals(HeroTrailerResumeVerdict.RESUME, decision.verdict)
        assertEquals(
            "one chunk of THIS stream, not a guessed constant",
            30_485L,
            decision.resumeAtMs
        )
    }

    @Test
    fun `the skip arithmetic refuses to invent numbers it does not have`() {
        assertNull("no length, no duration: nothing to derive", chunkSpanMs(1_048_576L, null, 50_000L))
        assertNull(chunkSpanMs(1_048_576L, 5_000_000L, 0L))
        assertNull(chunkSpanMs(0L, 5_000_000L, 50_000L))
        assertEquals(
            "a one-byte chunk is still at least a millisecond of media",
            1L,
            chunkSpanMs(1L, 5_000_000_000L, 50_000L)
        )
    }

    @Test
    fun `a resume point never lands on the end`() {
        // 2 s left: there is nowhere past the death to go, so the death position
        // is kept rather than seeking to the end and calling it a retry.
        assertEquals(20_000L, resumePastFailureMs(positionMs = 20_000L, durationMs = 22_000L))
        // A known duration with room to spare moves by the constant skip when
        // the stream's own length is not known.
        assertEquals(
            22_000L,
            resumePastFailureMs(positionMs = 20_000L, durationMs = 140_849L)
        )
    }

    @Test
    fun `the budget is spent by a class change, not by an attempt`() {
        val second = plan(deadClient = "tv", excluded = setOf("android_vr"), attempts = 1)

        assertEquals(HeroTrailerResumeVerdict.RESUME, second.verdict)
        assertTrue("two deaths of two different classes are two changes", second.changedClass)
        assertEquals(setOf("android_vr", "tv"), second.excludedClients)

        // The table is what bounds it: once every class has died there is no
        // change left, and the one identical replay is the last thing owed.
        val allDead = plan(deadClient = "ios", excluded = clients - "ios", attempts = 4)
        assertFalse("a full set cannot sign anything", allDead.changedClass)
        assertEquals(HeroTrailerResumeVerdict.RESUME, allDead.verdict)
        assertEquals(
            "a set naming every class is dropped, or the resolve has nothing to pick",
            emptySet<String>(),
            allDead.excludedClients
        )
    }

    @Test
    fun `an identical replay is allowed once and then the ladder is done`() {
        val first = plan(deadClient = null)
        assertEquals(HeroTrailerResumeVerdict.RESUME, first.verdict)

        val second = plan(deadClient = null, identical = MAX_IDENTICAL_REPLAYS)
        assertEquals(
            "same spot, same request, twice, is what the field report was",
            HeroTrailerResumeVerdict.POISON,
            second.verdict
        )
        assertEquals("a poison plans no resume at all", 20_400L, second.resumeAtMs)
        assertFalse(second.changedClass)
    }

    @Test
    fun `the ladder is bounded by the class table even when every death names one`() {
        assertEquals(
            "five clients, five steps, then nothing",
            HeroTrailerResumeVerdict.POISON,
            plan(deadClient = "tv", excluded = setOf("android_vr"), attempts = clients.size).verdict
        )
    }

    @Test
    fun `a blank signing client is unknown too`() {
        val decision = plan(deadClient = "  ")

        assertFalse("a blank key must not enter the set", decision.changedClass)
        assertEquals(emptySet<String>(), decision.excludedClients)
        assertEquals(HeroTrailerResumeVerdict.RESUME, decision.verdict)
    }
}
