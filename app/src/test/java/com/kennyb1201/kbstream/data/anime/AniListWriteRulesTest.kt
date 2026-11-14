package com.kennyb1201.kbstream.data.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions behind AniList's writes (spec 2026-11-13 phase 2, F1/F3).
 *
 * These are the parts a socket could not check anyway, and the parts that are
 * easy to get subtly wrong: the score scale, the create-versus-update start date,
 * the implicit-grant URL (which must carry no secret and no code challenge), and
 * the token a viewer pastes.
 */
class AniListWriteRulesTest {

    // ── The connect ────────────────────────────────────────────────────────

    @Test
    fun `the authorize URL is the code grant against the app's own redirect`() {
        val url = AniListWriteRules.authorizeUrl("1234", state = "abc")!!

        assertTrue(url.startsWith("https://anilist.co/api/v2/oauth/authorize?"))
        assertTrue(
            "the implicit grant is gone at AniList's end - it answers " +
                "unsupported_grant_type for response_type=token",
            url.contains("response_type=code") && !url.contains("response_type=token")
        )
        assertTrue(url.contains("client_id=1234"))
        assertTrue(
            "the app's own scheme is the redirect, so the code comes back to the app",
            url.contains("redirect_uri=kbstream%3A%2F%2Foauth%2Fanilist")
        )
        assertTrue(url.contains("state=abc"))
    }

    @Test
    fun `no secret and no code challenge are ever part of the authorize URL`() {
        val url = AniListWriteRules.authorizeUrl("1234")!!

        // The secret is one field of the exchange POST body and never rides a
        // URL; AniList's code grant takes no PKCE values at all.
        assertFalse(url.contains("client_secret"))
        assertFalse(url.contains("code_challenge"))
        assertFalse(url.contains("code_challenge_method"))
    }

    @Test
    fun `a build with no client id has no authorize URL at all`() {
        assertNull(AniListWriteRules.authorizeUrl(""))
        assertNull(AniListWriteRules.authorizeUrl("   "))
    }

    // ── The write values ───────────────────────────────────────────────────

    @Test
    fun `KBStream statuses map onto AniList's enum, and an unknown one sends nothing`() {
        assertEquals("CURRENT", AniListWriteRules.statusFor("watching"))
        assertEquals("COMPLETED", AniListWriteRules.statusFor("completed"))
        assertEquals("PAUSED", AniListWriteRules.statusFor("on_hold"))
        assertEquals("DROPPED", AniListWriteRules.statusFor("dropped"))
        assertEquals("PLANNING", AniListWriteRules.statusFor("plan_to_watch"))
        assertEquals("REPEATING", AniListWriteRules.statusFor("rewatching"))
        assertNull(AniListWriteRules.statusFor("something-else"))
        assertNull(AniListWriteRules.statusFor(null))
    }

    @Test
    fun `the score is sent as scoreRaw, never as the viewer's own format`() {
        assertEquals(80, AniListWriteRules.scoreRaw(8))
        assertEquals(100, AniListWriteRules.scoreRaw(10))
        // A score this app does not have is not a rating the viewer chose.
        assertNull(AniListWriteRules.scoreRaw(0))
        assertNull(AniListWriteRules.scoreRaw(11))
        assertNull(AniListWriteRules.scoreRaw(null))
    }

    @Test
    fun `the episode total decides completion, and a missing total completes nothing`() {
        assertEquals("CURRENT", AniListWriteRules.progressStatus(3, 12))
        assertEquals("COMPLETED", AniListWriteRules.progressStatus(12, 12))
        assertEquals("COMPLETED", AniListWriteRules.progressStatus(13, 12))
        // A continuing show has no total: it is never "finished" for that reason.
        assertEquals("CURRENT", AniListWriteRules.progressStatus(5, null))
        assertEquals("CURRENT", AniListWriteRules.progressStatus(5, 0))
        assertTrue(AniListWriteRules.completes(12, 12))
        assertFalse(AniListWriteRules.completes(11, 12))
    }

    // ── create versus update ───────────────────────────────────────────────

    @Test
    fun `a creating write starts the entry today`() {
        val variables = AniListWriteRules.saveVariables(
            mediaId = 1,
            status = "CURRENT",
            progress = 1,
            scoreRaw = null,
            creating = true,
            nowMs = 1_763_000_000_000L
        )

        assertEquals(1, variables.optInt("mediaId"))
        assertEquals("CURRENT", variables.optString("status"))
        assertEquals(1, variables.optInt("progress"))
        assertTrue("a new entry records the day it started", variables.has("startedAt"))
        assertEquals(
            2025,
            variables.optJSONObject("startedAt")?.optInt("year")
        )
        assertFalse("and it is not complete yet", variables.has("completedAt"))
    }

    @Test
    fun `an updating write never rewrites the start date`() {
        val variables = AniListWriteRules.saveVariables(
            mediaId = 1,
            status = "CURRENT",
            progress = 2,
            scoreRaw = null,
            creating = false,
            nowMs = 1_763_000_000_000L
        )

        assertFalse(
            "sending startedAt on every update would overwrite the real start day",
            variables.has("startedAt")
        )
        assertEquals(2, variables.optInt("progress"))
    }

    @Test
    fun `reaching the total sends COMPLETED with today's completion date`() {
        val variables = AniListWriteRules.saveVariables(
            mediaId = 1,
            status = "COMPLETED",
            progress = 12,
            scoreRaw = null,
            creating = false,
            nowMs = 1_763_000_000_000L
        )

        assertEquals("COMPLETED", variables.optString("status"))
        assertTrue(variables.has("completedAt"))
        assertFalse("the start date is not ours to rewrite", variables.has("startedAt"))
    }

    @Test
    fun `a rating alone updates without inventing a status`() {
        val updating = AniListWriteRules.saveVariables(
            mediaId = 1,
            status = null,
            progress = null,
            scoreRaw = 80,
            creating = false,
            nowMs = 1_763_000_000_000L
        )
        assertFalse("no status means leave it alone", updating.has("status"))
        assertFalse(updating.has("progress"))
        assertEquals(80, updating.optInt("scoreRaw"))

        // …but a rating that CREATES the entry still needs a status, and AniList's
        // own default for a new entry is CURRENT.
        val creating = AniListWriteRules.saveVariables(
            mediaId = 1,
            status = null,
            progress = null,
            scoreRaw = 80,
            creating = true,
            nowMs = 1_763_000_000_000L
        )
        assertEquals("CURRENT", creating.optString("status"))
    }

    @Test
    fun `a zero progress is omitted rather than sent`() {
        val variables = AniListWriteRules.saveVariables(
            mediaId = 1,
            status = "PLANNING",
            progress = 0,
            scoreRaw = null,
            creating = false,
            nowMs = 1_763_000_000_000L
        )
        assertFalse(variables.has("progress"))
    }

    // ── the mutations ──────────────────────────────────────────────────────

    @Test
    fun `the save mutation creates or updates by media id, and asks for the id back`() {
        val query = AniListWriteRules.SAVE_MUTATION

        assertTrue(query.contains("SaveMediaListEntry("))
        assertTrue("by mediaId, so no separate lookup is needed to create", query.contains("\$mediaId: Int"))
        assertTrue("scoreRaw is the format-independent score", query.contains("scoreRaw"))
        assertTrue("the entry id is what a later delete addresses", query.contains("{ id status progress score }"))
        assertTrue(query.contains("\$startedAt: FuzzyDateInput"))
        assertTrue(query.contains("\$completedAt: FuzzyDateInput"))
    }

    @Test
    fun `delete takes the entry id and the lookup takes the media id`() {
        assertTrue(AniListWriteRules.DELETE_MUTATION.contains("DeleteMediaListEntry(id: \$id)"))
        assertFalse(
            "deleting by media id is not the mutation's shape",
            AniListWriteRules.DELETE_MUTATION.contains("mediaId")
        )
        assertTrue(AniListWriteRules.ENTRY_ID_QUERY.contains("mediaListEntry { id }"))
    }

    @Test
    fun `the viewer query is the connect's own validation`() {
        assertTrue(AniListWriteRules.VIEWER_QUERY.contains("Viewer"))
        assertTrue(AniListWriteRules.VIEWER_QUERY.contains("id"))
        assertTrue(AniListWriteRules.VIEWER_QUERY.contains("name"))
    }

    // ── the token ──────────────────────────────────────────────────────────
    //
    // The connect field now takes a CODE, not a token (spec 2026-11-14, F3): the
    // paste-a-token path is gone with the implicit grant it belonged to, and a
    // token pasted into the code field is refused by name. What the field accepts
    // is pinned in AniListConnectRulesTest; what remains here is the session's
    // deadline, which is unchanged.

    @Test
    fun `the deadline comes from the token's own exp claim`() {
        // Inside the one-year cap, so this pins the claim rather than the clamp.
        val expSeconds = 1_770_000_000L
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"exp\":$expSeconds}".toByteArray()
        )
        val token = "header.$payload.signature"

        assertEquals(expSeconds * 1_000L, AniListWriteRules.expiryFromToken(token, 1_763_000_000_000L))
    }

    @Test
    fun `an unreadable claim falls back to a year, never to expired`() {
        val now = 1_763_000_000_000L
        val oneYear = 365L * 24L * 60L * 60L * 1_000L

        // Refusing a token the service would accept is the worse failure.
        assertEquals(oneYear + now, AniListWriteRules.expiryFromToken("garbage", now))
        val noExp = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"sub\":\"1\"}".toByteArray())
        assertEquals(oneYear + now, AniListWriteRules.expiryFromToken("h.$noExp.s", now))
    }

    @Test
    fun `a token already past its exp is not a session`() {
        val now = 1_763_000_000_000L
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"exp\":${now / 1_000L - 60}}".toByteArray()
        )

        assertEquals(now, AniListWriteRules.expiryFromToken("h.$payload.s", now))
    }

    // ── the park ───────────────────────────────────────────────────────────

    @Test
    fun `a 429 parks the leg for its Retry-After, and a minute when there is none`() {
        val now = 1_000_000L

        assertEquals(now + 30_000L, AniListWriteRules.parkMs("30", now))
        assertEquals(
            now + AniListWriteRules.DEFAULT_PARK_MS,
            AniListWriteRules.parkMs(null, now)
        )
        // An unreadable header is not "retry immediately".
        assertEquals(
            now + AniListWriteRules.DEFAULT_PARK_MS,
            AniListWriteRules.parkMs("soon", now)
        )
        // Clamped: a bogus far-future value cannot park the leg forever.
        assertEquals(now + 60L * 60L * 1_000L, AniListWriteRules.parkMs("999999", now))
    }
}
