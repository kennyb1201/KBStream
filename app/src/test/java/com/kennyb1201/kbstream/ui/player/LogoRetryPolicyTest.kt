package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When an unlanded logo request may be issued again (spec 2026-11-24, F2).
 *
 * The matrix exists because the 2026-11-24 paste is a load that was issued and
 * never called back: no `loaded`, no `load-failed`, no `cancelled`. The old rule
 * skipped on REQUESTED, so one silent death was permanent for the session - the
 * second splash and the overlay both carried no wordmark for the whole load.
 */
class LogoRetryPolicyTest {

    private fun retry(
        repaint: LogoRepaint,
        attempts: Int = 1,
        sinceRequestMs: Long = 0L,
        firstFrameSeen: Boolean = false
    ): Boolean = LogoRetryPolicy.shouldRetry(
        repaint = repaint,
        attempts = attempts,
        requestedAtMs = 1_000L,
        nowMs = 1_000L + sinceRequestMs,
        firstFrameSeen = firstFrameSeen
    )

    @Test
    fun `the setup paint never re-issues`() {
        // Setup runs twice back to back on both engines; re-issuing there is the
        // cancel round 2 fixed.
        assertFalse(retry(LogoRepaint.SETUP, firstFrameSeen = true))
        assertFalse(retry(LogoRepaint.SETUP, sinceRequestMs = 60_000L))
    }

    @Test
    fun `a source switch re-issues unconditionally`() {
        // The splash is back up: the "second splash" is exactly where the
        // 2026-11-24 report saw no logo.
        assertTrue(retry(LogoRepaint.SWITCH, sinceRequestMs = 0L))
    }

    @Test
    fun `a first-frame repaint re-issues because the frame has arrived`() {
        assertTrue(retry(LogoRepaint.FIRST_FRAME, firstFrameSeen = true))
    }

    @Test
    fun `a plain repaint waits out the grace`() {
        assertFalse(retry(LogoRepaint.FIRST_FRAME, firstFrameSeen = false, sinceRequestMs = 5_000L))
        assertTrue(
            retry(
                LogoRepaint.FIRST_FRAME,
                firstFrameSeen = false,
                sinceRequestMs = LogoRetryPolicy.GRACE_MS
            )
        )
    }

    @Test
    fun `the retries stop at the cap`() {
        assertTrue(
            retry(
                LogoRepaint.SWITCH,
                attempts = LogoRetryPolicy.MAX_ATTEMPTS - 1
            )
        )
        assertFalse(
            "three issues of one URL is the answer, not an infinite fetch",
            retry(LogoRepaint.SWITCH, attempts = LogoRetryPolicy.MAX_ATTEMPTS)
        )
        assertFalse(
            "and a landed-then-silent surface does not keep trying past it either",
            retry(LogoRepaint.FIRST_FRAME, attempts = LogoRetryPolicy.MAX_ATTEMPTS + 5)
        )
    }
}
