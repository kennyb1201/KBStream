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
 *
 * The 2026-11-27 rotation-race spec (F1/F2/F3) added the two shapes that turn a
 * single lost race into all three devices needing a QR, and both are pinned
 * here: a pair the device cannot vouch for must not be PUBLISHED at all, and a
 * pair that is not strictly newer must not be ADOPTED.
 */
class BingeCatAuthRulesTest {

    private val hour = 3_600_000L
    private val now = 1_000_000_000L

    // ── publishing (F1) ───────────────────────────────────────────────

    @Test
    fun `a profile that never connected publishes nothing`() {
        assertFalse(
            "a blank store with no tombstone is 'never connected', not 'signed out'",
            BingeCatAuthRules.shouldPublish(
                accessToken = null,
                signedOut = false,
                needsReconnect = false,
                expiresAtEpochMillis = 0L,
                nowMs = now
            )
        )
        assertFalse(
            BingeCatAuthRules.shouldPublish(
                accessToken = "  ",
                signedOut = false,
                needsReconnect = false,
                expiresAtEpochMillis = 0L,
                nowMs = now
            )
        )
        assertTrue(
            "a live session is exactly what the sibling needs",
            BingeCatAuthRules.shouldPublish(
                accessToken = "token",
                signedOut = false,
                needsReconnect = false,
                expiresAtEpochMillis = now + hour,
                nowMs = now
            )
        )
    }

    @Test
    fun `only a deliberate disconnect publishes a blank`() {
        assertTrue(
            "without this half a sign-out could never reach the other device",
            BingeCatAuthRules.shouldPublish(
                accessToken = null,
                signedOut = true,
                needsReconnect = false,
                expiresAtEpochMillis = 0L,
                nowMs = now
            )
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
    fun `a refused pair is never published`() {
        // The cascade's step 2: the loser of a rotation keeps its consumed pair
        // (that is the store's deliberate behaviour) and used to publish it with
        // a FRESH updatedAt - the account row is last-write-wins, so the dead
        // pair overwrote the winner's live one and every device then adopted it
        // or refreshed it into `invalid_grant`.
        assertFalse(
            "a pair the server refused must never reach the account's row",
            BingeCatAuthRules.shouldPublish(
                accessToken = "consumed",
                signedOut = false,
                needsReconnect = true,
                expiresAtEpochMillis = now + hour,
                nowMs = now
            )
        )
        assertFalse(
            "even a pair that still looks live: the marker is the server's verdict",
            BingeCatAuthRules.shouldPublish(
                accessToken = "consumed",
                signedOut = false,
                needsReconnect = true,
                expiresAtEpochMillis = now + 30 * hour,
                nowMs = now
            )
        )
    }

    @Test
    fun `an expired pair this device has not refreshed is never published`() {
        assertFalse(
            "writing a merely-expired pair over a live row is how one device's " +
                "expiry becomes every device's dead session",
            BingeCatAuthRules.shouldPublish(
                accessToken = "expired",
                signedOut = false,
                needsReconnect = false,
                expiresAtEpochMillis = now - hour,
                nowMs = now
            )
        )
        assertTrue(
            "an absent expiry is not evidence of death (older builds publish it blank)",
            BingeCatAuthRules.shouldPublish(
                accessToken = "unknown-expiry",
                signedOut = false,
                needsReconnect = false,
                expiresAtEpochMillis = 0L,
                nowMs = now
            )
        )
    }

    // ── adopting (F2/F3) ──────────────────────────────────────────────

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
    fun `a newer generation carries its refresh token even at the same access token`() {
        // A sibling refreshed and the access token happened to be the same string
        // - the row's generation is what says the pair moved, so the incoming
        // refresh token is the live one even though the expiry comparison would
        // have called it a stale echo.
        assertEquals(
            "rotated",
            BingeCatAuthRules.adoptedRefreshToken(
                incomingAccess = "same",
                incomingRefresh = "rotated",
                incomingExpiresAt = now + hour,
                localAccess = "same",
                localRefresh = "local-refresh",
                localExpiresAt = now + hour,
                incomingGeneration = 7L,
                localGeneration = 6L
            )
        )
        assertEquals(
            "an older generation's refresh token is never taken, whatever its expiry",
            "local-refresh",
            BingeCatAuthRules.adoptedRefreshToken(
                incomingAccess = "old",
                incomingRefresh = "consumed",
                incomingExpiresAt = now + 2 * hour,
                localAccess = "live",
                localRefresh = "local-refresh",
                localExpiresAt = now + hour,
                incomingGeneration = 5L,
                localGeneration = 6L
            )
        )
    }

    @Test
    fun `a blank session is never adopted`() {
        assertFalse(
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "",
                incomingExpiresAt = now + hour,
                incomingGeneration = 9L,
                localAccess = "local",
                localGeneration = 1L,
                nowMs = now
            )
        )
    }

    @Test
    fun `the first session is adopted only while it can still be used`() {
        assertTrue(
            "a live first session is exactly what the row is for",
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "remote",
                incomingExpiresAt = now + hour,
                incomingGeneration = 1L,
                localAccess = null,
                localGeneration = 0L,
                nowMs = now
            )
        )
        assertFalse(
            "a dead first session is not a connection: it cannot read, and its " +
                "refresh token is usually the one already consumed by the rotation",
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "dead",
                incomingExpiresAt = now - hour,
                incomingGeneration = 1L,
                localAccess = null,
                localGeneration = 0L,
                nowMs = now
            )
        )
        assertTrue(
            "an absent expiry is not evidence of death (older builds publish it blank)",
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "unknown",
                incomingExpiresAt = 0L,
                incomingGeneration = 0L,
                localAccess = null,
                localGeneration = 0L,
                nowMs = now
            )
        )
        assertTrue(
            "same access token means the sibling refreshed and rotated the pair",
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "same",
                incomingExpiresAt = now + hour,
                incomingGeneration = 3L,
                localAccess = "same",
                localGeneration = 2L,
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
                incomingGeneration = 2L,
                localAccess = "fresh",
                localGeneration = 2L,
                nowMs = now
            )
        )
        assertTrue(
            "a live remote wins over a live local one - that is a reconnect",
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "other",
                incomingExpiresAt = now + hour,
                incomingGeneration = 2L,
                localAccess = "local",
                localGeneration = 2L,
                nowMs = now
            )
        )
        assertFalse(
            "and with BOTH expired there is still nothing to gain: the local " +
                "refresh token is either valid (refresh it) or dead (the incoming " +
                "one is no better, and adopting it destroys the local copy) - " +
                "this is the window the rotation cascade used, F2",
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "old",
                incomingExpiresAt = now - hour,
                incomingGeneration = 2L,
                localAccess = "older",
                localGeneration = 2L,
                nowMs = now
            )
        )
    }

    @Test
    fun `an older generation is refused and a newer one adopted`() {
        // F3: the explicit ordering. Expiry cannot order two pairs - a consumed
        // pair and a live one can both be expired - so the counter decides, and
        // the expiry only breaks a tie.
        assertFalse(
            "an older generation is a stale echo, however new its expiresAt looks",
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "echo",
                incomingExpiresAt = now + 30 * hour,
                incomingGeneration = 4L,
                localAccess = "live",
                localGeneration = 5L,
                nowMs = now
            )
        )
        assertTrue(
            "a newer generation is the sibling's rotation - even expired, because " +
                "the local pair is one behind it",
            BingeCatAuthRules.shouldAdopt(
                incomingAccess = "rotated",
                incomingExpiresAt = now - hour,
                incomingGeneration = 6L,
                localAccess = "older",
                localGeneration = 5L,
                nowMs = now
            )
        )
    }
}
