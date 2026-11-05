package com.kennyb1201.kbstream.data.sync

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
    fun `the first session and a refresh of the same one are adopted`() {
        assertTrue(
            "no local session: nothing to protect",
            BingeCatAuthRules.shouldAdopt("remote", now + hour, localAccess = null, localExpiresAt = 0, nowMs = now)
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
