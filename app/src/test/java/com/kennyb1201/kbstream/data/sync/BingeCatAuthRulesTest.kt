package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules that keep the BingeCat session from deleting itself.
 *
 * Both failures are invisible on the device that caused them and both have
 * already happened once on this codebase (Simkl and Trakt): a device that never
 * connected publishes its blank over the account's session, and a device
 * holding an expired session overwrites a sibling's freshly refreshed one.
 * Written as plain JVM rules because these are the decisions, not the I/O.
 */
class BingeCatAuthRulesTest {

    private val hour = 3_600_000L
    private val now = 1_000_000_000L

    @Test
    fun `a profile that never connected publishes nothing`() {
        assertFalse(
            "a blank store with no tombstone is 'never connected', not 'signed out'",
            BingeCatAuthRules.shouldPublish(null, signedOut = false)
        )
        assertFalse(BingeCatAuthRules.shouldPublish("  ", signedOut = false))
        assertTrue(
            "a live session is exactly what the sibling needs",
            BingeCatAuthRules.shouldPublish("token", signedOut = false)
        )
    }

    @Test
    fun `only a deliberate disconnect publishes a blank`() {
        assertTrue(
            "without this half a sign-out could never reach the other device",
            BingeCatAuthRules.shouldPublish(null, signedOut = true)
        )
        assertTrue(BingeCatAuthRules.clearsSession(null, signedOut = true))
        assertFalse(
            "a blank without the marker must never clear a live session",
            BingeCatAuthRules.clearsSession(null, signedOut = false)
        )
        assertFalse(
            "and a live token with a stale marker is not a sign-out either",
            BingeCatAuthRules.clearsSession("token", signedOut = true)
        )
    }

    @Test
    fun `a stale echo cannot replace the live refresh token`() {
        assertEquals(
            "same access token and an older expiry is an echo of the pair already " +
                "renewed, and its refresh token is the one the rotation consumed",
            "local-refresh",
            BingeCatAuthRules.adoptedRefreshToken(
                incomingAccess = "same",
                incomingRefresh = "consumed",
                incomingExpiresAt = now - hour,
                localAccess = "same",
                localRefresh = "local-refresh",
                localExpiresAt = now + hour
            )
        )
        assertEquals(
            "a blank incoming refresh token never erases one (an older build publishes it blank)",
            "local-refresh",
            BingeCatAuthRules.adoptedRefreshToken(
                incomingAccess = "remote",
                incomingRefresh = "",
                incomingExpiresAt = now + hour,
                localAccess = "local",
                localRefresh = "local-refresh",
                localExpiresAt = now - hour
            )
        )
        assertEquals(
            "a newer pair's rotated refresh token is exactly what this device needs",
            "rotated",
            BingeCatAuthRules.adoptedRefreshToken(
                incomingAccess = "new",
                incomingRefresh = "rotated",
                incomingExpiresAt = now + 2 * hour,
                localAccess = "old",
                localRefresh = "local-refresh",
                localExpiresAt = now - hour
            )
        )
        assertEquals(
            "and a first session is taken whole",
            "first",
            BingeCatAuthRules.adoptedRefreshToken(
                incomingAccess = "first",
                incomingRefresh = "first",
                incomingExpiresAt = now + hour,
                localAccess = null,
                localRefresh = null,
                localExpiresAt = 0L
            )
        )
    }

    @Test
    fun `a blank session is never adopted`() {
        assertFalse(
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "",
                incomingExpiresAt = now + hour,
                localAccess = "local",
                localExpiresAt = now - hour,
                nowMs = now
            )
        )
    }

    @Test
    fun `the first session is adopted only while it can still be used`() {
        assertTrue(
            "a live first session is exactly what the row is for",
            BingeCatAuthRules.shouldAdopt("remote", now + hour, localAccess = null, localExpiresAt = 0, nowMs = now)
        )
        assertFalse(
            "a dead first session is not a connection: it cannot read, and its " +
                "refresh token is usually the one already consumed by the rotation",
            BingeCatAuthRules.shouldAdopt("dead", now - hour, localAccess = null, localExpiresAt = 0, nowMs = now)
        )
        assertTrue(
            "an absent expiry is not evidence of death (older builds publish it blank)",
            BingeCatAuthRules.shouldAdopt("unknown", 0L, localAccess = null, localExpiresAt = 0, nowMs = now)
        )
        assertTrue(
            "same access token means the sibling refreshed and rotated the pair",
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "same",
                incomingExpiresAt = now + hour,
                localAccess = "same",
                localExpiresAt = now - hour,
                nowMs = now
            )
        )
    }

    @Test
    fun `an expired remote cannot overwrite a live local session`() {
        assertFalse(
            "the stale-overwrite race: an expired session carries nothing usable",
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "old",
                incomingExpiresAt = now - hour,
                localAccess = "fresh",
                localExpiresAt = now + hour,
                nowMs = now
            )
        )
        assertTrue(
            "a live remote wins over a live local one - that is a reconnect",
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "other",
                incomingExpiresAt = now + hour,
                localAccess = "local",
                localExpiresAt = now + 2 * hour,
                nowMs = now
            )
        )
        assertTrue(
            "and with both expired there is nothing to protect",
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "old",
                incomingExpiresAt = now - hour,
                localAccess = "older",
                localExpiresAt = now - 2 * hour,
                nowMs = now
            )
        )
    }
}
