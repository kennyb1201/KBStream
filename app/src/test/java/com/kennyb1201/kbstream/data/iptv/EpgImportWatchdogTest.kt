package com.kennyb1201.kbstream.data.iptv

import com.kennyb1201.kbstream.work.WorkPolicies
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The watchdog that turns a wedged guide download into a visible error.
 *
 * Reported from the field: the TV guide sat on "IMPORTING..." with
 * "0.0 MB · 0 programs" for twenty minutes, because the provider had stopped
 * sending while the connection stayed open - and the twenty-minute call timeout
 * was the only thing that would ever have ended it. These pin the three
 * decisions that fix that, and the two it must NOT make: a source that is slow
 * but moving keeps its connection, and the one-transaction save at the end of an
 * import is not a stall just because neither counter moves through it.
 *
 * Timings are compressed (a 120 ms deadline, a 10 ms poll) rather than
 * virtualised - there is no coroutine test dispatcher in this module - but every
 * assertion is about the ORDER of events, not about how long they took.
 */
class EpgImportWatchdogTest {

    private val stallMs = 120L
    private val pollMs = 10L

    private fun watchdog(progress: MutableStateFlow<GuideImportState?>) =
        EpgImportWatchdog(
            progress = progress,
            stallTimeoutMs = stallMs,
            pollMs = pollMs
        )

    @Test
    fun `a source that stops moving is aborted and re-opened once`() = runBlocking {
        val progress = MutableStateFlow<GuideImportState?>(GuideImportState())
        val attempts = mutableListOf<Int>()
        var abandoned = 0

        val value = watchdog(progress).runWithStallRetry { attempt ->
            attempts += attempt
            if (attempt == 0) {
                try {
                    awaitCancellation()
                } finally {
                    abandoned++
                }
            }
            "imported"
        }

        assertEquals(
            "the first attempt has to be abandoned and the source opened again",
            listOf(0, 1),
            attempts
        )
        assertEquals("the stalled fetch is cancelled, not merely left running", 1, abandoned)
        assertEquals("the retry's result is the import's result", "imported", value)
    }

    @Test
    fun `a second stall fails loudly instead of hanging on`() = runBlocking {
        val progress = MutableStateFlow<GuideImportState?>(GuideImportState())
        var attempts = 0

        try {
            watchdog(progress).runWithStallRetry {
                attempts++
                awaitCancellation()
            }
            fail("a source that never sends anything must not report success")
        } catch (stalled: EpgStalledException) {
            assertTrue(
                "the banner has to name what happened: ${stalled.message}",
                stalled.message.orEmpty().contains("stalled")
            )
            assertTrue(
                "and say how long it waited: ${stalled.message}",
                stalled.message.orEmpty().contains("no data for")
            )
        }

        assertEquals("exactly one retry, then the loud failure", 2, attempts)
    }

    @Test
    fun `a slow source that keeps moving is never aborted`() = runBlocking {
        val progress = MutableStateFlow<GuideImportState?>(GuideImportState())

        val value = withTimeout(10_000) {
            watchdog(progress).runWithStallRetry { attempt ->
                // A retry inside this assertion fails the test: a source whose
                // bytes keep arriving is a healthy slow import, however long it
                // takes, and must never be killed mid-download.
                assertEquals("a moving import is never retried", 0, attempt)
                repeat(12) { step ->
                    delay(pollMs * 2)
                    progress.value = progress.value!!.copy(bytesRead = (step + 1) * 1_024L)
                }
                "done"
            }
        }

        assertEquals("done", value)
    }

    @Test
    fun `rows alone keep the clock alive`() = runBlocking {
        val progress = MutableStateFlow<GuideImportState?>(GuideImportState())

        val value = withTimeout(10_000) {
            watchdog(progress).runWithStallRetry { attempt ->
                assertEquals(0, attempt)
                repeat(12) { step ->
                    delay(pollMs * 2)
                    progress.value = progress.value!!.copy(rowsParsed = (step + 1) * 500)
                }
                "done"
            }
        }

        assertEquals("done", value)
    }

    @Test
    fun `the one-transaction save is not a stall`() = runBlocking {
        val progress = MutableStateFlow<GuideImportState?>(
            GuideImportState(rowsParsed = 128_400, phase = GuideImportPhase.SAVING)
        )

        val value = withTimeout(10_000) {
            watchdog(progress).runWithStallRetry { attempt ->
                assertEquals("the save is never aborted", 0, attempt)
                // Well past the deadline, with neither counter moving - which
                // is what a staging-to-live swap looks like from the outside.
                delay(stallMs * 3)
                "saved"
            }
        }

        assertEquals("saved", value)
    }

    @Test
    fun `an import that publishes no pass is never judged`() = runBlocking {
        val progress = MutableStateFlow<GuideImportState?>(null)

        val value = withTimeout(10_000) {
            watchdog(progress).runWithStallRetry { attempt ->
                assertEquals(0, attempt)
                delay(stallMs * 3)
                "done"
            }
        }

        assertEquals("done", value)
    }

    @Test
    fun `the screen going away is not a stall`() = runBlocking {
        val progress = MutableStateFlow<GuideImportState?>(GuideImportState())
        var attempts = 0
        val finished = CompletableDeferred<Unit>()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                watchdog(progress).runWithStallRetry {
                    attempts++
                    awaitCancellation()
                }
            } finally {
                finished.complete(Unit)
            }
        }
        delay(pollMs * 2)
        job.cancelAndJoin()

        assertTrue("the cancelled pass still has to end", finished.isCompleted)
        assertEquals(
            "the caller's cancellation is not the watchdog's signal, so it must " +
                "not be turned into a retry (or into a stall error)",
            1,
            attempts
        )
    }

    @Test
    fun `the deadline sits under every bound that would otherwise catch a stall`() {
        assertEquals("three minutes, not a rounded-off multiple", 3L * 60L * 1_000L, EPG_STALL_TIMEOUT_MS)
        assertEquals(30L * 60L * 1_000L, EPG_ENTRY_RETRY_COOLDOWN_MS)
        assertTrue(
            "the worker's per-source budget is the outer bound; the watchdog has " +
                "to notice a stall well before it",
            EPG_STALL_TIMEOUT_MS < WorkPolicies.GUIDE_SOURCE_BUDGET_MS
        )
        assertTrue(
            "and so is the HTTP call timeout - the watchdog exists because " +
                "waiting for that one is what took twenty minutes in the field",
            EPG_STALL_TIMEOUT_MS < IptvHttpClient.CALL_TIMEOUT_MS
        )
    }
}
