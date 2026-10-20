package com.kennyb1201.kbstream.data.trakt

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two rules that stop a successful Trakt approval from being reported as
 * "That code expired".
 *
 * Live evidence (2026-10-20): polls at 6/12/18s answered 400, the 24s poll got
 * the 200 that traded the code for a token, and the 30s poll got 404 - so the
 * 24s exchange had succeeded and something after the HTTP call had thrown. The
 * loop counted that as "one more failed poll" and retried a code Trakt deletes
 * on exchange.
 *
 * The exchange is used directly here: a real 200-with-token against a store
 * that throws, and the request count is the assertion. One request, one
 * distinct outcome, and the 200 still in the diagnostics.
 */
class TraktPollExchangeTest {

    private val access = "test-access-token"
    private val refresh = "test-refresh-token"

    @Before
    fun reset() {
        TraktPollTrace.clear()
    }

    private fun granted() = TraktPollHttp(
        statusCode = 200,
        body = TraktTokenResponse(
            accessToken = access,
            refreshToken = refresh,
            expiresInSeconds = 604_800L,
            createdAtEpochSeconds = 1_000L
        )
    )

    @Test
    fun `a 200 the store cannot keep is an approved save failure, not a retry`() = runBlocking {
        var requests = 0
        val exchange = TraktPollExchange(
            fetch = {
                requests++
                granted()
            },
            save = { throw IllegalStateException("encrypted prefs unavailable") },
            nowMs = { 1_000_000L }
        )

        val result = exchange.pollOnce(
            deviceCode = "device-code",
            elapsedSeconds = 24,
            expiresInSeconds = 600
        )

        assertEquals(
            "Trakt did approve this device",
            TraktDeviceFlowRules.Poll.APPROVED,
            result.state
        )
        assertNull("a token that was not persisted is not reported as persisted", result.token)
        assertNotNull("the screen has to be able to say WHY it failed", result.saveError)
        assertTrue(
            "and why means the storage fault, not the code: ${result.saveError}",
            result.saveError.orEmpty().contains("encrypted prefs unavailable")
        )
        assertEquals(
            "the code was consumed by that 200, so a second request could only read 404",
            1,
            requests
        )
    }

    @Test
    fun `the 200 poll is in the diagnostics even when the save throws`() = runBlocking {
        val exchange = TraktPollExchange(
            fetch = { granted() },
            save = { throw IllegalStateException("keystore entry missing") },
            nowMs = { 1_000_000L }
        )

        exchange.pollOnce("device-code", elapsedSeconds = 24, expiresInSeconds = 600)

        val lines = TraktPollTrace.lines()
        assertEquals("the poll AND the storage fault are both evidence: $lines", 2, lines.size)
        assertEquals(
            "trakt.poll: status=200 elapsed=24s expiresIn=600s state=APPROVED",
            lines.first()
        )
        assertTrue(
            "the second line names the fault: $lines",
            lines.last().startsWith("trakt.save: failed=")
        )
        listOf("device-code", "test-access-token", "test-refresh-token").forEach { secret ->
            assertTrue(
                "never a credential: $lines",
                lines.none { it.contains(secret) }
            )
        }
    }

    @Test
    fun `an approved poll saves exactly once and hands the token back`() = runBlocking {
        val saved = mutableListOf<TraktToken>()
        var requests = 0
        val exchange = TraktPollExchange(
            fetch = {
                requests++
                granted()
            },
            save = { saved += it },
            nowMs = { 1_000_000L }
        )

        val result = exchange.pollOnce("device-code", 24, 600)

        assertEquals(TraktDeviceFlowRules.Poll.APPROVED, result.state)
        assertNull(result.saveError)
        assertEquals(access, result.token?.accessToken)
        assertEquals("the pair is written once", 1, saved.size)
        assertEquals(1, requests)
    }

    @Test
    fun `a waiting poll touches neither the store nor a second request`() = runBlocking {
        var requests = 0
        var saves = 0
        val exchange = TraktPollExchange(
            fetch = {
                requests++
                TraktPollHttp(statusCode = 400)
            },
            save = { saves++ }
        )

        val result = exchange.pollOnce("device-code", elapsedSeconds = 6, expiresInSeconds = 600)

        assertEquals(TraktDeviceFlowRules.Poll.PENDING, result.state)
        assertEquals(0, saves)
        assertEquals(1, requests)
    }
}
