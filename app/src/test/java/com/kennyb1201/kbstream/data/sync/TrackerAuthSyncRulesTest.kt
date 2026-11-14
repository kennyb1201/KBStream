package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three tracker sessions enrolled in 2026-11-13 cross devices between two
 * builds of the same login, so their rules are stricter than a preference's.
 *
 * Two halves are Trakt's, for the same reasons: a profile that never connected a
 * leg must publish nothing (the blob is a per-profile FULL REPLACE, and a blank
 * from a device that simply never logged in used to erase the account's session
 * everywhere), and only a tombstoned blank may clear a live session.
 *
 * The third differs per leg because the grants differ. Kitsu and MAL rotate a
 * refresh token, so a same-token blob is still adoptable - it may carry the new
 * expiry - and an already-expired incoming session IS adopted when there is no
 * local one, because its refresh token is the valuable half. AniList has no
 * refresh grant at all, so an expired token with nothing local is REFUSED: it
 * would report "connected" while every call fails.
 */
class TrackerAuthSyncRulesTest {

    // ── Kitsu ─────────────────────────────────────────────────────────────

    @Test
    fun `kitsu publishes a token or a deliberate disconnect, never a blank`() {
        assertTrue(KitsuAuthRules.shouldPublish("tok", signedOut = false))
        assertTrue(KitsuAuthRules.shouldPublish(null, signedOut = true))
        assertFalse(KitsuAuthRules.shouldPublish(null, signedOut = false))
        assertFalse(KitsuAuthRules.shouldPublish("   ", signedOut = false))
    }

    @Test
    fun `only a tombstoned blank clears the kitsu session`() {
        assertTrue(KitsuAuthRules.clearsSession("", signedOut = true))
        assertFalse(KitsuAuthRules.clearsSession("", signedOut = false))
        assertFalse(KitsuAuthRules.clearsSession("tok", signedOut = true))
    }

    @Test
    fun `kitsu adopts its first session even when the access token has expired`() {
        assertTrue(
            "the refresh token is the half that can mint a new access token; " +
                "refusing it would drop the login this row exists to share",
            kitsuAdopt(incomingAccess = "new", incomingExpiresAt = NOW - DAY)
        )
    }

    @Test
    fun `kitsu adopts the same access token so a sibling's refresh lands`() {
        assertTrue(
            kitsuAdopt(
                incomingAccess = "same",
                incomingExpiresAt = NOW + DAY,
                localAccess = "same",
                localExpiresAt = NOW + DAY / 2
            )
        )
    }

    @Test
    fun `a kitsu reconnect to another account wins even with an expired token`() {
        assertTrue(
            kitsuAdopt(
                incomingAccess = "other",
                incomingExpiresAt = NOW - DAY,
                incomingAccountId = "999",
                localAccess = "live",
                localExpiresAt = NOW + WEEK,
                localAccountId = "111"
            )
        )
    }

    @Test
    fun `an expired kitsu session never replaces a live local one`() {
        assertFalse(
            "the stale-overwrite race: a sibling that has not refreshed yet",
            kitsuAdopt(
                incomingAccess = "old",
                incomingExpiresAt = NOW - MINUTE,
                localAccess = "live",
                localExpiresAt = NOW + WEEK
            )
        )
    }

    @Test
    fun `two expired kitsu sessions still replace each other`() {
        assertTrue(
            kitsuAdopt(
                incomingAccess = "remote",
                incomingExpiresAt = NOW - DAY,
                localAccess = "local",
                localExpiresAt = NOW - WEEK
            )
        )
    }

    @Test
    fun `a blank incoming kitsu token is never adopted`() {
        assertFalse(kitsuAdopt(incomingAccess = "", incomingExpiresAt = NOW + DAY))
        assertFalse(kitsuAdopt(incomingAccess = null, incomingExpiresAt = NOW + DAY))
    }

    @Test
    fun `kitsu keeps the local refresh token when the remote one is blank or stale`() {
        assertNull(
            "nothing incoming, nothing local: still no token to install",
            KitsuAuthRules.adoptedRefreshToken(
                incomingAccess = "a",
                incomingRefresh = null,
                incomingExpiresAt = NOW + DAY,
                localAccess = null,
                localRefresh = null,
                localExpiresAt = 0L
            )
        )
        assertTrue(
            "a blank incoming refresh token never erases a local one",
            KitsuAuthRules.adoptedRefreshToken(
                incomingAccess = "a",
                incomingRefresh = "",
                incomingExpiresAt = NOW + DAY,
                localAccess = "a",
                localRefresh = "local-refresh",
                localExpiresAt = NOW + DAY
            ) == "local-refresh"
        )
        assertTrue(
            "a stale echo of a session this device already renewed keeps the " +
                "local (unconsumed) refresh token",
            KitsuAuthRules.adoptedRefreshToken(
                incomingAccess = "same",
                incomingRefresh = "consumed",
                incomingExpiresAt = NOW + MINUTE,
                localAccess = "same",
                localRefresh = "fresh",
                localExpiresAt = NOW + DAY
            ) == "fresh"
        )
        assertTrue(
            "a rotated pair is taken whole",
            KitsuAuthRules.adoptedRefreshToken(
                incomingAccess = "new",
                incomingRefresh = "rotated",
                incomingExpiresAt = NOW + DAY,
                localAccess = "old",
                localRefresh = "old-refresh",
                localExpiresAt = NOW + MINUTE
            ) == "rotated"
        )
    }

    // ── MAL ───────────────────────────────────────────────────────────────

    @Test
    fun `mal publishes on the kitsu rule`() {
        assertTrue(MalAuthRules.shouldPublish("tok", signedOut = false))
        assertTrue(MalAuthRules.shouldPublish(null, signedOut = true))
        assertFalse(MalAuthRules.shouldPublish(null, signedOut = false))
        assertTrue(MalAuthRules.clearsSession("", signedOut = true))
        assertFalse(MalAuthRules.clearsSession("", signedOut = false))
    }

    @Test
    fun `mal adopts its first session even when the access token has expired`() {
        assertTrue(malAdopt(incomingAccess = "new", incomingExpiresAt = NOW - DAY))
        assertTrue(malAdopt(incomingAccess = "same", incomingExpiresAt = NOW + DAY, localAccess = "same"))
        assertFalse(
            "and the stale-overwrite rule still holds",
            malAdopt(
                incomingAccess = "old",
                incomingExpiresAt = NOW - MINUTE,
                localAccess = "live",
                localExpiresAt = NOW + WEEK
            )
        )
        assertFalse(malAdopt(incomingAccess = "", incomingExpiresAt = NOW + DAY))
    }

    @Test
    fun `mal has no account fast path, and the absence is the rule`() {
        // Nothing local identifies the MAL user, so a "different account" test
        // cannot be written and must not be invented. What decides is liveness.
        assertFalse(
            malAdopt(
                incomingAccess = "someone-elses-expired-token",
                incomingExpiresAt = NOW - DAY,
                localAccess = "live",
                localExpiresAt = NOW + WEEK
            )
        )
        assertTrue(
            "a live session replaces an expired one, which is how a reconnect lands",
            malAdopt(
                incomingAccess = "reconnected",
                incomingExpiresAt = NOW + WEEK,
                localAccess = "dead",
                localExpiresAt = NOW - DAY
            )
        )
    }

    // ── AniList ───────────────────────────────────────────────────────────

    @Test
    fun `an expired anilist token with no local session is refused`() {
        assertFalse(
            "no refresh sibling exists, so an expired token would install a " +
                "session that says connected while every call fails",
            anilistAdopt(incomingAccess = "expired", incomingExpiresAt = NOW - MINUTE)
        )
    }

    @Test
    fun `an absent anilist expiry is still adopted`() {
        assertTrue(
            "a build that could not read the JWT's exp must not lose the login",
            anilistAdopt(incomingAccess = "tok", incomingExpiresAt = 0L)
        )
    }

    @Test
    fun `a live anilist token is adopted, and a reconnect to another viewer wins`() {
        assertTrue(anilistAdopt(incomingAccess = "tok", incomingExpiresAt = NOW + DAY))
        assertTrue(anilistAdopt(incomingAccess = "same", incomingExpiresAt = NOW + DAY, localAccess = "same"))
        assertTrue(
            anilistAdopt(
                incomingAccess = "other",
                incomingExpiresAt = NOW - DAY,
                incomingAccountId = "42",
                localAccess = "live",
                localExpiresAt = NOW + WEEK,
                localAccountId = "7"
            )
        )
    }

    @Test
    fun `an expired anilist session never replaces a live local one`() {
        assertFalse(
            anilistAdopt(
                incomingAccess = "old",
                incomingExpiresAt = NOW - MINUTE,
                localAccess = "live",
                localExpiresAt = NOW + WEEK
            )
        )
        assertFalse(anilistAdopt(incomingAccess = "", incomingExpiresAt = NOW + DAY))
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun kitsuAdopt(
        incomingAccess: String?,
        incomingExpiresAt: Long,
        incomingAccountId: String? = "111",
        localAccess: String? = null,
        localExpiresAt: Long = 0L,
        localAccountId: String? = null
    ): Boolean = KitsuAuthRules.shouldAdopt(
        incomingAccess = incomingAccess,
        incomingExpiresAt = incomingExpiresAt,
        incomingAccountId = incomingAccountId,
        localAccess = localAccess,
        localExpiresAt = localExpiresAt,
        localAccountId = localAccountId,
        nowMs = NOW
    )

    private fun malAdopt(
        incomingAccess: String?,
        incomingExpiresAt: Long,
        localAccess: String? = null,
        localExpiresAt: Long = 0L
    ): Boolean = MalAuthRules.shouldAdopt(
        incomingAccess = incomingAccess,
        incomingExpiresAt = incomingExpiresAt,
        localAccess = localAccess,
        localExpiresAt = localExpiresAt,
        nowMs = NOW
    )

    private fun anilistAdopt(
        incomingAccess: String?,
        incomingExpiresAt: Long,
        incomingAccountId: String? = "7",
        localAccess: String? = null,
        localExpiresAt: Long = 0L,
        localAccountId: String? = null
    ): Boolean = AniListAuthRules.shouldAdopt(
        incomingAccess = incomingAccess,
        incomingExpiresAt = incomingExpiresAt,
        incomingAccountId = incomingAccountId,
        localAccess = localAccess,
        localExpiresAt = localExpiresAt,
        localAccountId = localAccountId,
        nowMs = NOW
    )

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
        const val WEEK = 7 * DAY
        const val NOW = 1_000_000_000L
    }
}
