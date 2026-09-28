package com.kennyb1201.kbstream.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract behind [runCatchingCancellable]: it is [runCatching] except for
 * cancellation, and cancellation must never come back as a `Result` — not as
 * `failure`, and not as "the block finished anyway".
 *
 * Where the point is the difference, the tests are written as a pair with plain
 * `runCatching`, so the reason this helper exists stays pinned even if someone
 * later "simplifies" it back to the stdlib call.
 */
class RunCatchingCancellableTest {

    @Test
    fun `a returned value is a success`() {
        val result = runCatchingCancellable { 42 }
        assertEquals(42, result.getOrNull())
        assertTrue(result.isSuccess)
    }

    @Test
    fun `an ordinary failure is still captured, not thrown`() {
        val thrown = IllegalStateException("bad stream")
        val result = runCatchingCancellable { throw thrown }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() === thrown)
    }

    @Test
    fun `a thrown CancellationException is rethrown`() {
        val observed = runCatching {
            runCatchingCancellable { throw CancellationException("cancelled") }
        }.exceptionOrNull()
        assertTrue(
            "runCatchingCancellable must let cancellation through, got $observed",
            observed is CancellationException
        )
    }

    /**
     * The real shape of the bug, with a genuinely cancelled coroutine: the
     * plain call records the cancellation as a failure, while the helper leaves
     * the work stopped and records nothing.
     */
    @Test
    fun `a cancelled job is not turned into a recorded failure`() = runBlocking {
        var swallowed: Result<Unit>? = null
        var rethrown: Result<Unit>? = null

        val plain = launch { swallowed = runCatching { delay(Long.MAX_VALUE) } }
        val safe = launch { rethrown = runCatchingCancellable { delay(Long.MAX_VALUE) } }
        // Let both coroutines reach the suspension point before cancelling: a
        // launch that has not started yet cannot show either behaviour.
        delay(50)
        plain.cancel()
        safe.cancel()
        plain.join()
        safe.join()

        val recorded = swallowed
        assertTrue("plain runCatching records the cancellation as a failure", recorded!!.isFailure)
        assertTrue(
            "and the recorded failure IS the cancellation",
            recorded.exceptionOrNull() is CancellationException
        )
        assertNull("runCatchingCancellable rethrew, so nothing was recorded", rethrown)
    }

    /**
     * The same difference against a timeout — the one cancellation the helper
     * also rethrows (see its doc): a blocked stream must be abandoned by
     * `withTimeoutOrNull`, not allowed to keep going.
     */
    @Test
    fun `a timeout abandons the block instead of being captured`() = runBlocking {
        var plainFinished = false
        withTimeoutOrNull(30) {
            runCatching { delay(Long.MAX_VALUE) }
            plainFinished = true
        }
        assertTrue("plain runCatching lets the coroutine carry on", plainFinished)

        var safeFinished = false
        val timedOut = withTimeoutOrNull(30) {
            runCatchingCancellable { delay(Long.MAX_VALUE) }
            safeFinished = true
        }
        assertFalse("runCatchingCancellable must not run past the timeout", safeFinished)
        assertNull("and the timeout stays a timeout", timedOut)
    }
}
