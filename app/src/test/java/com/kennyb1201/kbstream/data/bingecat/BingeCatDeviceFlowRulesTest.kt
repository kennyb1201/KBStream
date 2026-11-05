package com.kennyb1201.kbstream.data.bingecat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device flow's table, pinned without a network or a TV.
 *
 * BingeCat is a standard OAuth device flow: a poll that is still waiting is a
 * 400 with `authorization_pending` in it, not a failure. Reading that as an
 * error is what turns polling into an instant "could not connect" on a screen
 * the viewer is still reading, and reading `slow_down` as pending is what turns
 * it into a request storm.
 */
class BingeCatDeviceFlowRulesTest {

    private fun poll(
        status: Int,
        hasToken: Boolean = false,
        elapsed: Long = 1,
        expiresIn: Int = 600,
        error: String? = null
    ) = BingeCatDeviceFlowRules.poll(
        statusCode = status,
        hasToken = hasToken,
        elapsedSeconds = elapsed,
        expiresInSeconds = expiresIn,
        error = error
    )

    @Test
    fun `a 200 with a token is approved and terminal`() {
        assertEquals(
            BingeCatDeviceFlowRules.Poll.APPROVED,
            poll(200, hasToken = true)
        )
        // A 200 with no token is NOT an approval: it must never be reported as
        // one, because the code is consumed by the exchange either way.
        assertTrue(
            poll(200, hasToken = false) != BingeCatDeviceFlowRules.Poll.APPROVED
        )
    }

    @Test
    fun `waiting for approval is pending, not a failure`() {
        assertEquals(
            BingeCatDeviceFlowRules.Poll.PENDING,
            poll(400, error = "authorization_pending")
        )
        // The status-only fallback, for a body that would not parse.
        assertEquals(BingeCatDeviceFlowRules.Poll.PENDING, poll(400))
        assertEquals(BingeCatDeviceFlowRules.Poll.PENDING, poll(428))
    }

    @Test
    fun `polling too fast asks for a slower interval`() {
        assertEquals(
            BingeCatDeviceFlowRules.Poll.SLOW_DOWN,
            poll(400, error = "slow_down")
        )
        assertEquals(BingeCatDeviceFlowRules.Poll.SLOW_DOWN, poll(429))
        assertEquals(10, BingeCatDeviceFlowRules.slowedIntervalSeconds(5))
        assertEquals(5, BingeCatDeviceFlowRules.slowedIntervalSeconds(0))
    }

    @Test
    fun `a dead or denied code ends the flow with its own state`() {
        assertEquals(
            BingeCatDeviceFlowRules.Poll.EXPIRED,
            poll(400, error = "expired_token")
        )
        assertEquals(
            BingeCatDeviceFlowRules.Poll.DENIED,
            poll(400, error = "access_denied")
        )
        assertEquals(BingeCatDeviceFlowRules.Poll.DENIED, poll(403))
        assertEquals(BingeCatDeviceFlowRules.Poll.EXPIRED, poll(404))
        assertEquals(BingeCatDeviceFlowRules.Poll.EXPIRED, poll(410))
    }

    @Test
    fun `a rejected scope is its own answer, and names the fix`() {
        assertEquals(
            BingeCatDeviceFlowRules.Poll.SCOPE_REJECTED,
            poll(400, error = "invalid_scope")
        )
        val message = BingeCatDeviceFlowRules.scopeRejectionMessage()
        assertTrue(
            "the message must name the missing scope, or the viewer is stuck: $message",
            message.contains(BingeCatConfig.REQUIRED_LISTS_SCOPE)
        )
        assertTrue(
            "and it must not pretend the viewer's account is at fault: $message",
            message.contains("developer settings")
        )
    }

    @Test
    fun `a rejected client is not retried as pending`() {
        assertEquals(
            BingeCatDeviceFlowRules.Poll.MISCONFIGURED,
            poll(400, error = "invalid_client")
        )
        assertEquals(BingeCatDeviceFlowRules.Poll.MISCONFIGURED, poll(401))
    }

    @Test
    fun `expiry outranks a pending answer`() {
        // The server still says "wait", but the code cannot live that long: the
        // screen must say so rather than spinning for another interval.
        assertEquals(
            BingeCatDeviceFlowRules.Poll.EXPIRED,
            poll(400, elapsed = 600, expiresIn = 600, error = "authorization_pending")
        )
        assertTrue(BingeCatDeviceFlowRules.isExpired(elapsedSeconds = 601, expiresInSeconds = 600))
        assertFalse(BingeCatDeviceFlowRules.isExpired(elapsedSeconds = 599, expiresInSeconds = 600))
    }

    @Test
    fun `anything else is one retry, not a state`() {
        assertEquals(BingeCatDeviceFlowRules.Poll.FAILED, poll(500))
        assertEquals(BingeCatDeviceFlowRules.Poll.FAILED, poll(418, error = "teapot"))
    }

    @Test
    fun `the token expiry comes from the grant, not from the device clock alone`() {
        assertEquals(
            "created_at wins when it is present",
            1_700_000_000_000L + 3_600_000L,
            BingeCatDeviceFlowRules.expiresAtEpochMillis(
                createdAtEpochSeconds = 1_700_000_000L,
                expiresInSeconds = 3_600L,
                nowMs = 1_600_000_000_000L
            )
        )
        assertEquals(
            "and the local clock is the fallback",
            1_600_000_000_000L + 3_600_000L,
            BingeCatDeviceFlowRules.expiresAtEpochMillis(
                createdAtEpochSeconds = null,
                expiresInSeconds = 3_600L,
                nowMs = 1_600_000_000_000L
            )
        )
    }
}
