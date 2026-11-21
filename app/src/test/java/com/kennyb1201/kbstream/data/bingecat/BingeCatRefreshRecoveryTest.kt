package com.kennyb1201.kbstream.data.bingecat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rotation race, as rules.
 *
 * BingeCat consumes a refresh token when it is used, so two devices refreshing
 * the same pair leave one holding a token that no longer exists - while the
 * sibling's newer pair is in the account copy. These cases pin what may be
 * adopted in that moment, and what may not:
 *
 *  - never across a deliberate disconnect (a decision, not a rotation),
 *  - never the pair that was just refused (that just refuses again),
 *  - and always the account's pair when it differs from the refused one,
 *    whether the rotation moved both tokens or only the refresh token.
 */
class BingeCatRefreshRecoveryTest {

    private fun token(access: String, refresh: String?) = BingeCatToken(
        accessToken = access,
        refreshToken = refresh,
        expiresAtEpochMillis = 0L,
        scope = "read"
    )

    @Test
    fun `the pair that was refused is not an answer`() {
        val pair = token("a1", "r1")

        assertNull(
            "adopting the same pair would refresh with a token the server has already consumed",
            BingeCatRefreshRecovery.adoptable(
                signedOut = false,
                held = pair,
                account = token("a1", "r1"),
                refusedAccessToken = "a1"
            )
        )
    }

    @Test
    fun `a rotated pair in the account copy is adopted`() {
        val account = token("a2", "r2")

        val adopted = BingeCatRefreshRecovery.adoptable(
            signedOut = false,
            held = token("a1", "r1"),
            account = account,
            refusedAccessToken = "a1"
        )

        assertSame("the sibling's pair is the answer", account, adopted)
    }

    @Test
    fun `a refresh-only rotation is adopted too`() {
        // The access token can still be valid while the refresh token rotates,
        // which is the case the marker's access-token comparison misses: same
        // access, different refresh, and a profile stuck asking for a QR.
        val account = token("a1", "r2")

        val adopted = BingeCatRefreshRecovery.adoptable(
            signedOut = false,
            held = token("a1", "r1"),
            account = account,
            refusedAccessToken = "a1"
        )

        assertSame(account, adopted)
    }

    @Test
    fun `a deliberate disconnect is never recovered`() {
        assertNull(
            "a tombstone is a person's decision; no account pair may undo it",
            BingeCatRefreshRecovery.adoptable(
                signedOut = true,
                held = token("a1", "r1"),
                account = token("a2", "r2"),
                refusedAccessToken = "a1"
            )
        )
    }

    @Test
    fun `nothing is adopted when the account copy is empty`() {
        assertNull(
            BingeCatRefreshRecovery.adoptable(
                signedOut = false,
                held = token("a1", "r1"),
                account = null,
                refusedAccessToken = "a1"
            )
        )
    }

    @Test
    fun `with nothing held, only a pair the marker does not name is adopted`() {
        assertNull(
            "this is the very pair that was refused",
            BingeCatRefreshRecovery.adoptable(
                signedOut = false,
                held = null,
                account = token("a1", "r1"),
                refusedAccessToken = "a1"
            )
        )
        assertTrue(
            "a marker with no pair named, over a different account pair, heals",
            BingeCatRefreshRecovery.adoptable(
                signedOut = false,
                held = null,
                account = token("a2", "r2"),
                refusedAccessToken = null
            ) != null
        )
    }

    @Test
    fun `the verdict names the server's class, not just failure`() {
        assertEquals(
            "a consumed refresh token is the recoverable shape",
            "invalid_grant",
            BingeCatRefreshRecovery.verdict(400, "invalid_grant")
        )
        assertEquals(
            "an access-token refusal carries its status",
            "401",
            BingeCatRefreshRecovery.verdict(401, null)
        )
        assertEquals(
            "anything else keeps the code so a paste can tell a 400 from a 500",
            "http:500",
            BingeCatRefreshRecovery.verdict(500, null)
        )
        assertEquals(
            "the recovery line the next paste looks for",
            "refresh-recovered-by-sync",
            BingeCatRefreshRecovery.RECOVERED
        )
    }
}
