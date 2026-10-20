package com.kennyb1201.kbstream.data.trakt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The release-safe poll evidence.
 *
 * The debug log never reaches a signed APK, so the same four facts are kept in
 * [TraktPollTrace] for the Diagnostics report. What matters is that a line
 * carries exactly those facts - status, elapsed seconds, code lifetime and
 * resolved state - and that nothing else can appear in it.
 */
class TraktPollTraceTest {

    @Before
    fun reset() {
        TraktPollTrace.clear()
    }

    @Test
    fun `an expired poll renders status, elapsed, lifetime and state`() {
        TraktPollTrace.record(
            statusCode = 410,
            elapsedSeconds = 1,
            expiresInSeconds = 600,
            state = TraktDeviceFlowRules.Poll.EXPIRED
        )

        val line = TraktPollTrace.lines().single()
        assertEquals(
            "trakt.poll: status=410 elapsed=1s expiresIn=600s state=EXPIRED",
            line
        )
        // The whole line is those four fields: there is no slot a credential
        // could be interpolated into.
        assertTrue(
            "unexpected shape: $line",
            Regex("trakt\\.poll: status=\\d+ elapsed=\\d+s expiresIn=\\d+s state=[A-Z_]+")
                .matches(line)
        )
    }

    @Test
    fun `only the tail of the attempt is kept, newest last`() {
        repeat(12) { index ->
            TraktPollTrace.record(
                statusCode = 400,
                elapsedSeconds = index.toLong(),
                expiresInSeconds = 600,
                state = TraktDeviceFlowRules.Poll.PENDING
            )
        }

        val lines = TraktPollTrace.lines()
        assertEquals("one report cannot become a log", 8, lines.size)
        assertTrue("oldest surviving poll is the 5th", lines.first().contains("elapsed=4s"))
        assertTrue("the last poll is the newest", lines.last().contains("elapsed=11s"))
    }

    @Test
    fun `a new attempt starts with no evidence`() {
        TraktPollTrace.record(400, 1, 600, TraktDeviceFlowRules.Poll.PENDING)

        TraktPollTrace.clear()

        assertTrue(
            "an old run's polls must not be read as this attempt's",
            TraktPollTrace.lines().isEmpty()
        )
    }
}
