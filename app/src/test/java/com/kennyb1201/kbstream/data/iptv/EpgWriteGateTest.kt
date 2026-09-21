package com.kennyb1201.kbstream.data.iptv

import kotlin.system.measureTimeMillis
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate exists to keep bulk guide writes off the player's back, and the two
 * ways it can go wrong are opposite: never yielding (starving the 12-hour EPG
 * refresh) and never holding (the rebuffer stall it was written for). These
 * pin both ends, plus the session budget that bounds the hold.
 */
class EpgWriteGateTest {

    /** Long enough to measure reliably, short enough to keep the suite quick. */
    private val holdMs = 400L

    @After
    fun tearDown() {
        // The gate is process-wide state: never leak an "active player" into
        // another test (or into whatever runs next).
        EpgWriteGate.setPlayerActive(false)
        EpgWriteGate.setInlinePlaybackActive(false)
    }

    @Test
    fun `writes run straight through while no player is up`() = runBlocking {
        EpgWriteGate.setPlayerActive(false)
        assertFalse(EpgWriteGate.isPlayerActive)

        val elapsed = measureTimeMillis { EpgWriteGate.holdWhilePlaying(holdMs) }

        assertTrue("idle hold took ${elapsed}ms", elapsed < holdMs / 2)
    }

    @Test
    fun `a write waits for playback and resumes the moment it ends`() = runBlocking {
        EpgWriteGate.setPlayerActive(true)
        assertTrue(EpgWriteGate.isPlayerActive)

        // Default budget (a minute): only the player stopping can release this.
        val held = async { EpgWriteGate.holdWhilePlaying() }
        try {
            delay(150L)
            assertFalse("write should still be held during playback", held.isCompleted)

            EpgWriteGate.setPlayerActive(false)
            withTimeout(2_000L) { held.await() }
        } finally {
            held.cancel()
        }
    }

    @Test
    fun `the session budget stops an import being starved by a long viewing`() = runBlocking {
        EpgWriteGate.setPlayerActive(true)

        val elapsed = measureTimeMillis { EpgWriteGate.holdWhilePlaying(holdMs) }

        assertTrue("hold ignored its budget: ${elapsed}ms", elapsed >= holdMs - 50L)
        assertTrue(EpgWriteGate.isPlayerActive)
    }

    @Test
    fun `the budget is shared across every write in the session`() = runBlocking {
        EpgWriteGate.setPlayerActive(true)

        val first = measureTimeMillis { EpgWriteGate.holdWhilePlaying(holdMs) }
        val second = measureTimeMillis { EpgWriteGate.holdWhilePlaying(holdMs) }

        assertTrue("first hold took ${first}ms", first >= holdMs - 50L)
        assertTrue("second hold should find the budget spent: ${second}ms", second < holdMs / 2)
    }

    @Test
    fun `inline hero playback holds writes too, on a shorter leash`() = runBlocking {
        EpgWriteGate.setPlayerActive(false)
        EpgWriteGate.setInlinePlaybackActive(true)
        assertTrue(EpgWriteGate.isPlayerActive)

        // The leash is reported by the cap, so this stays a fast test rather
        // than one that sits through the full inline hold.
        assertTrue(EpgWriteGate.holdCapMs() == EpgWriteGate.INLINE_MAX_HOLD_MS)
        assertTrue(EpgWriteGate.holdCapMs(EpgWriteGate.INLINE_MAX_HOLD_MS / 2) < EpgWriteGate.INLINE_MAX_HOLD_MS)

        val held = async { EpgWriteGate.holdWhilePlaying() }
        try {
            delay(150L)
            assertFalse("inline playback should hold writes", held.isCompleted)
        } finally {
            held.cancel()
        }
    }

    @Test
    fun `the fullscreen player is not leashed to the inline budget`() = runBlocking {
        EpgWriteGate.setInlinePlaybackActive(false)
        EpgWriteGate.setPlayerActive(true)

        assertTrue(EpgWriteGate.holdCapMs() == EpgWriteGate.MAX_HOLD_MS)
    }

    @Test
    fun `one source stopping does not release a hold the other still needs`() = runBlocking {
        EpgWriteGate.setPlayerActive(true)
        EpgWriteGate.setInlinePlaybackActive(true)

        // The hero pausing behind the fullscreen player must not hand guide
        // writes back mid-title.
        EpgWriteGate.setInlinePlaybackActive(false)
        assertTrue(EpgWriteGate.isPlayerActive)

        val held = async { EpgWriteGate.holdWhilePlaying() }
        try {
            delay(150L)
            assertFalse("still held by the fullscreen player", held.isCompleted)
        } finally {
            held.cancel()
        }
    }

    @Test
    fun `the budget is not reset while something is still playing`() = runBlocking {
        EpgWriteGate.setPlayerActive(true)
        val spent = measureTimeMillis { EpgWriteGate.holdWhilePlaying(holdMs) }
        assertTrue("first hold took ${spent}ms", spent >= holdMs - 50L)

        // A second source joining (or the hero pausing) is not a new session:
        // re-granting the budget here is how a long title ends up holding
        // writes back over and over.
        EpgWriteGate.setInlinePlaybackActive(true)
        val again = measureTimeMillis { EpgWriteGate.holdWhilePlaying(holdMs) }

        assertTrue("budget was re-granted mid-session: ${again}ms", again < holdMs / 2)
    }

    @Test
    fun `a new viewing session grants a fresh budget`() = runBlocking {
        EpgWriteGate.setPlayerActive(true)
        EpgWriteGate.holdWhilePlaying(holdMs)

        // Player leaves and comes back: the spent budget must not carry over,
        // or the second viewing would never hold writes back again.
        EpgWriteGate.setPlayerActive(false)
        EpgWriteGate.setPlayerActive(true)

        val elapsed = measureTimeMillis { EpgWriteGate.holdWhilePlaying(holdMs) }

        assertTrue("fresh session held only ${elapsed}ms", elapsed >= holdMs - 50L)
    }
}
