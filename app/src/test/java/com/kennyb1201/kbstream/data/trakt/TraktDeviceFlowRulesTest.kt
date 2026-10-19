package com.kennyb1201.kbstream.data.trakt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The poll table, pinned.
 *
 * The one that matters is 400: it is the NORMAL answer while the viewer is still
 * typing the code on their phone. Treating it as an error is what turns a
 * working flow into "couldn't connect" before the person has finished reading
 * the screen.
 */
class TraktDeviceFlowRulesTest {

    @Test
    fun `unapproved is pending, not an error`() {
        assertEquals(
            TraktDeviceFlowRules.Poll.PENDING,
            TraktDeviceFlowRules.poll(400, hasToken = false, elapsedSeconds = 10, expiresInSeconds = 600)
        )
    }

    @Test
    fun `an approved poll is approved only when a token actually arrived`() {
        assertEquals(
            TraktDeviceFlowRules.Poll.APPROVED,
            TraktDeviceFlowRules.poll(200, hasToken = true, elapsedSeconds = 10, expiresInSeconds = 600)
        )
        assertEquals(
            TraktDeviceFlowRules.Poll.FAILED,
            TraktDeviceFlowRules.poll(200, hasToken = false, elapsedSeconds = 10, expiresInSeconds = 600)
        )
    }

    @Test
    fun `the flow's failure codes each mean one thing`() {
        assertEquals(
            TraktDeviceFlowRules.Poll.SLOW_DOWN,
            TraktDeviceFlowRules.poll(429, false, 10, 600)
        )
        assertEquals(
            TraktDeviceFlowRules.Poll.DENIED,
            TraktDeviceFlowRules.poll(418, false, 10, 600)
        )
        listOf(404, 409, 410).forEach { code ->
            assertEquals(
                "HTTP $code retires the code",
                TraktDeviceFlowRules.Poll.EXPIRED,
                TraktDeviceFlowRules.poll(code, false, 10, 600)
            )
        }
        assertEquals(
            TraktDeviceFlowRules.Poll.FAILED,
            TraktDeviceFlowRules.poll(500, false, 10, 600)
        )
    }

    @Test
    fun `a pending answer after expiry is expired, not pending`() {
        assertEquals(
            TraktDeviceFlowRules.Poll.EXPIRED,
            TraktDeviceFlowRules.poll(400, false, elapsedSeconds = 600, expiresInSeconds = 600)
        )
    }

    @Test
    fun `a rejected client is not "still waiting"`() {
        assertEquals(
            "a public client that Trakt will not accept must not poll into expiry",
            TraktDeviceFlowRules.Poll.MISCONFIGURED,
            TraktDeviceFlowRules.poll(400, false, 10, 600, error = "invalid_client")
        )
        assertEquals(
            "a real pending answer still reads as pending",
            TraktDeviceFlowRules.Poll.PENDING,
            TraktDeviceFlowRules.poll(400, false, 10, 600, error = "authorization_pending")
        )
    }

    @Test
    fun `a 429 widens the interval by five seconds`() {
        assertEquals(10, TraktDeviceFlowRules.nextIntervalSeconds(5))
        assertEquals(10_000L, TraktDeviceFlowRules.intervalMillis(10))
        assertEquals(1_000L, TraktDeviceFlowRules.intervalMillis(0))
    }

    @Test
    fun `expiry is derived from Trakt's own created_at when it is there`() {
        // created_at is in seconds; the result is in milliseconds.
        assertEquals(
            1_000_000_000L + 7L * 24 * 60 * 60 * 1_000,
            TraktDeviceFlowRules.expiresAtEpochMillis(
                createdAtEpochSeconds = 1_000_000L,
                expiresInSeconds = 7L * 24 * 60 * 60,
                nowMs = 9_999_999L
            )
        )
        assertEquals(
            "no created_at: measured from now",
            5_000L + 60_000L,
            TraktDeviceFlowRules.expiresAtEpochMillis(
                createdAtEpochSeconds = null,
                expiresInSeconds = 60L,
                nowMs = 5_000L
            )
        )
        assertEquals(
            "no lifetime: already due",
            5_000L,
            TraktDeviceFlowRules.expiresAtEpochMillis(null, 0L, 5_000L)
        )
    }

    @Test
    fun `refresh happens a day early, and always for an unknown expiry`() {
        val expiresAt = 100L * 60 * 60 * 1_000 // 100 hours from epoch
        assertFalse(TraktDeviceFlowRules.refreshDue(expiresAt, nowMs = expiresAt - 48L * 60 * 60 * 1_000))
        assertTrue(TraktDeviceFlowRules.refreshDue(expiresAt, nowMs = expiresAt - 23L * 60 * 60 * 1_000))
        assertTrue(TraktDeviceFlowRules.refreshDue(expiresAt, nowMs = expiresAt + 1L))
        assertTrue(TraktDeviceFlowRules.refreshDue(0L, nowMs = 1L))
    }
}
