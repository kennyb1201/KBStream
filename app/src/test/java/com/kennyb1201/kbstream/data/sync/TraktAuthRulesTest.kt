package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Trakt session blob crosses devices between two different builds of the
 * same login, so its rules are stricter than a preference's.
 *
 * Two are Simkl's, for the same reasons: a profile that never connected Trakt
 * must not publish its blanks over the account's session, and only a
 * tombstoned blank may clear a live session (a blank row is otherwise "this
 * device has never heard of Trakt", which must be inert).
 *
 * The third is Trakt-only. Its access tokens expire and its refresh tokens are
 * single-use, so a device that has not refreshed still holds a session that
 * LOOKS present. Adopting that over a sibling's freshly refreshed pair would
 * overwrite a live session with a dead one. [TraktAuthRules.shouldAdopt]
 * refuses that case while still letting a deliberate reconnect to a different
 * account win immediately, however soon that account's token expires.
 */
class TraktAuthRulesTest {

    @Test
    fun `a token is always published`() {
        assertTrue(TraktAuthRules.shouldPublish("tok", signedOut = false))
        // A live token wins over a stale disconnect marker.
        assertTrue(TraktAuthRules.shouldPublish("tok", signedOut = true))
    }

    @Test
    fun `a deliberate disconnect is published`() {
        assertTrue(TraktAuthRules.shouldPublish(null, signedOut = true))
        assertTrue(TraktAuthRules.shouldPublish("", signedOut = true))
    }

    @Test
    fun `a profile that never connected Trakt stays out of the cloud`() {
        assertFalse(TraktAuthRules.shouldPublish(null, signedOut = false))
        assertFalse(TraktAuthRules.shouldPublish("", signedOut = false))
        assertFalse(TraktAuthRules.shouldPublish("   ", signedOut = false))
    }

    @Test
    fun `only a tombstoned blank session clears`() {
        assertTrue(TraktAuthRules.clearsSession(null, signedOut = true))
        assertTrue(TraktAuthRules.clearsSession("", signedOut = true))
        assertFalse(
            "a blank without the tombstone is a device that never connected",
            TraktAuthRules.clearsSession("", signedOut = false)
        )
        assertFalse(TraktAuthRules.clearsSession(null, signedOut = false))
        assertFalse("a real token never clears", TraktAuthRules.clearsSession("tok", signedOut = true))
    }

    @Test
    fun `a first session is adopted`() {
        assertTrue(adopt(incomingAccess = "new", incomingExpiresAt = NOW + DAY))
    }

    @Test
    fun `the same access token is adopted even when it looks unchanged`() {
        assertTrue(
            "a refresh on the other device rotated the refresh token and pushed " +
                "the new expiry; this device must take it",
            adopt(
                incomingAccess = "same",
                incomingExpiresAt = NOW + DAY,
                localAccess = "same",
                localExpiresAt = NOW + DAY / 2
            )
        )
    }

    @Test
    fun `a deliberate reconnect to another account always wins`() {
        assertTrue(
            "even an already-expired token from a different account is a real " +
                "reconnect and must replace the local session",
            adopt(
                incomingAccess = "other-account",
                incomingExpiresAt = NOW - DAY,
                incomingAccountSlug = "someone-else",
                localAccess = "live",
                localExpiresAt = NOW + WEEK,
                localAccountSlug = "me"
            )
        )
    }

    @Test
    fun `a stale remote session never replaces a live local one`() {
        assertFalse(
            "the stale-overwrite race: an expired session pushed by a sibling " +
                "that has not refreshed yet",
            adopt(
                incomingAccess = "old",
                incomingExpiresAt = NOW - MINUTE,
                localAccess = "live",
                localExpiresAt = NOW + WEEK
            )
        )
    }

    @Test
    fun `two expired sessions still replace each other`() {
        assertTrue(
            "an expired local has nothing to lose, and the incoming one may " +
                "carry a usable refresh token",
            adopt(
                incomingAccess = "remote",
                incomingExpiresAt = NOW - DAY,
                localAccess = "local",
                localExpiresAt = NOW - WEEK
            )
        )
    }

    @Test
    fun `a blank incoming access token is never adopted`() {
        assertFalse(adopt(incomingAccess = "", incomingExpiresAt = NOW + DAY))
        assertFalse(adopt(incomingAccess = null, incomingExpiresAt = NOW + DAY))
    }

    private fun adopt(
        incomingAccess: String?,
        incomingExpiresAt: Long,
        incomingAccountSlug: String? = "me",
        localAccess: String? = null,
        localExpiresAt: Long = 0L,
        localAccountSlug: String? = null
    ): Boolean = TraktAuthRules.shouldAdopt(
        incomingAccess = incomingAccess,
        incomingExpiresAt = incomingExpiresAt,
        incomingAccountSlug = incomingAccountSlug,
        localAccess = localAccess,
        localExpiresAt = localExpiresAt,
        localAccountSlug = localAccountSlug,
        nowMs = NOW
    )

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
        const val WEEK = 7 * DAY
        const val NOW = 1_000_000_000L
    }
}
