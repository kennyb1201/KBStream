package com.kennyb1201.kbstream.data.kitsu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kitsu's write rules (spec 2026-11-13 phase 2, F4).
 *
 * These are the decisions that are cheap to get wrong and expensive to notice: a
 * PATCH without `type`/`id` is a 400, Kitsu's rating scale is 0-20 while this
 * app's is 1-10, its status spellings are not this app's, and a 429 with a naive
 * retry turns a soft limit into a hard one. All of it is testable with no socket.
 */
class KitsuWriteRulesTest {

    // ── Status ────────────────────────────────────────────────────────────

    @Test
    fun `the app's statuses map onto Kitsu's`() {
        assertEquals("current", KitsuWriteRules.statusFor("watching"))
        assertEquals("completed", KitsuWriteRules.statusFor("completed"))
        assertEquals("on_hold", KitsuWriteRules.statusFor("on_hold"))
        assertEquals("dropped", KitsuWriteRules.statusFor("dropped"))
        assertEquals("planned", KitsuWriteRules.statusFor("plan_to_watch"))
    }

    @Test
    fun `a status Kitsu does not have sends nothing, never a guess`() {
        assertNull(KitsuWriteRules.statusFor(null))
        assertNull(KitsuWriteRules.statusFor(""))
        assertNull(KitsuWriteRules.statusFor("something_else"))
    }

    // ── Rating ────────────────────────────────────────────────────────────

    @Test
    fun `the 1-10 score becomes Kitsu's 0-20 scale`() {
        assertEquals(16, KitsuWriteRules.ratingTwenty(8))
        assertEquals(2, KitsuWriteRules.ratingTwenty(1))
        assertEquals(20, KitsuWriteRules.ratingTwenty(10))
    }

    @Test
    fun `a score this app does not have is not clamped into one it never had`() {
        assertNull(KitsuWriteRules.ratingTwenty(null))
        assertNull(KitsuWriteRules.ratingTwenty(0))
        assertNull(KitsuWriteRules.ratingTwenty(11))
    }

    // ── Progress ──────────────────────────────────────────────────────────

    @Test
    fun `reaching the episode total completes the title`() {
        assertEquals("completed", KitsuWriteRules.progressStatus(progress = 12, totalEpisodes = 12))
        assertEquals("current", KitsuWriteRules.progressStatus(progress = 11, totalEpisodes = 12))
    }

    @Test
    fun `an unknown total never completes a title`() {
        // A continuing show, or an add-on with no episode count: the count is
        // authoritative for progress and says nothing about the end.
        assertEquals("current", KitsuWriteRules.progressStatus(progress = 500, totalEpisodes = null))
        assertEquals("current", KitsuWriteRules.progressStatus(progress = 3, totalEpisodes = 0))
    }

    // ── Bodies ────────────────────────────────────────────────────────────

    @Test
    fun `a patch carries type AND id, which Kitsu requires`() {
        val body = KitsuWriteRules.patchBody(
            entryId = "42",
            status = "current",
            progress = 7,
            ratingTwenty = 16
        )

        assertTrue(body.contains(""""type":"libraryEntries""""))
        assertTrue(body.contains(""""id":"42""""))
        assertTrue(body.contains(""""status":"current""""))
        assertTrue(body.contains(""""progress":7"""))
        assertTrue(body.contains(""""ratingTwenty":16"""))
        assertFalse("no relationships on an update", body.contains("relationships"))
    }

    @Test
    fun `a patch sends only the attributes being changed`() {
        val body = KitsuWriteRules.patchBody("42", status = null, progress = 3, ratingTwenty = null)

        assertTrue(body.contains(""""progress":3"""))
        assertFalse(body.contains("status"))
        assertFalse(body.contains("ratingTwenty"))
    }

    @Test
    fun `a create names the user and the anime as relationships`() {
        val body = KitsuWriteRules.createBody(
            userId = "9",
            kitsuAnimeId = "7442",
            status = "current",
            progress = 1,
            ratingTwenty = null
        )

        assertTrue(body.contains(""""type":"libraryEntries""""))
        assertTrue(body.contains(""""user":{"data":{"type":"users","id":"9"}}"""))
        assertTrue(body.contains(""""media":{"data":{"type":"anime","id":"7442"}}"""))
        assertTrue(body.contains(""""progress":1"""))
        assertFalse(body.contains("ratingTwenty"))
    }

    @Test
    fun `a cleared rating is an explicit null, not an absent field`() {
        val body = KitsuWriteRules.clearRatingBody("42")

        assertTrue(body.contains(""""ratingTwenty":null"""))
        assertTrue(body.contains(""""id":"42""""))
    }

    @Test
    fun `ids are strings on the wire and are escaped as such`() {
        val body = KitsuWriteRules.createBody("9", "7442", null, null, null)
        assertTrue(body.contains(""""id":"7442""""))
        assertFalse("an unquoted id is a 400", body.contains(""""id":7442"""))
    }

    // ── URLs ──────────────────────────────────────────────────────────────

    @Test
    fun `the entry lookup asks for one title and the fields a write needs`() {
        val url = KitsuWriteRules.entryLookupUrl("9", "7442")

        assertTrue(url.startsWith("https://kitsu.app/api/edge/library-entries?"))
        assertTrue(url.contains("filter[userId]=9"))
        assertTrue(url.contains("filter[kind]=anime"))
        assertTrue(url.contains("filter[animeId]=7442"))
        assertTrue(url.contains("fields[libraryEntries]=status,progress,ratingTwenty"))
        assertFalse("no battery of unasked fields", url.contains("include="))
    }

    // ── OAuth forms ───────────────────────────────────────────────────────

    @Test
    fun `the password grant encodes the password as RFC3986`() {
        val form = KitsuWriteRules.passwordForm("a@b.co", "p&ss=word+1", clientId = "abc")

        assertTrue(form.contains("grant_type=password"))
        assertTrue(form.contains("username=a%40b.co"))
        assertTrue("a raw & or = would truncate the field", form.contains("password=p%26ss%3Dword%2B1"))
        assertTrue(form.contains("client_id=abc"))
    }

    @Test
    fun `a build with no client id sends no client id`() {
        val form = KitsuWriteRules.passwordForm("a@b.co", "pw", clientId = "")
        assertFalse(form.contains("client_id"))
        assertTrue(form.contains("grant_type=password"))
    }

    @Test
    fun `the refresh grant carries the rotated token and the same client rule`() {
        val form = KitsuWriteRules.refreshForm("refresh-1", clientId = "abc")

        assertTrue(form.contains("grant_type=refresh_token"))
        assertTrue(form.contains("refresh_token=refresh-1"))
        assertTrue(form.contains("client_id=abc"))
        assertFalse(KitsuWriteRules.refreshForm("r", "").contains("client_id"))
    }

    // ── Rate limiting ─────────────────────────────────────────────────────

    @Test
    fun `a 429 parks for the Retry-After it names`() {
        assertEquals(30_000L, KitsuWriteRules.parkMs("30", nowMs = 1_000L) - 1_000L)
    }

    @Test
    fun `a 429 with no usable header parks conservatively, never not at all`() {
        assertTrue(KitsuWriteRules.parkMs(null, 0L) >= KitsuWriteRules.MIN_PARK_MS)
        assertTrue(KitsuWriteRules.parkMs("soon", 0L) >= KitsuWriteRules.MIN_PARK_MS)
        assertTrue(KitsuWriteRules.parkMs("0", 0L) >= KitsuWriteRules.MIN_PARK_MS)
    }

    @Test
    fun `an absurd Retry-After is capped`() {
        assertTrue(KitsuWriteRules.parkMs("999999", 0L) <= KitsuWriteRules.MAX_PARK_MS)
    }

    // ── Tokens ────────────────────────────────────────────────────────────

    @Test
    fun `a token is unusable once its skewed deadline passes`() {
        val token = KitsuToken(accessToken = "a", refreshToken = "r", expiresAtMs = 5_000L)

        assertTrue(token.isUsable(nowMs = 4_999L))
        assertFalse(token.isUsable(nowMs = 5_000L))
        assertFalse(KitsuToken(accessToken = "", refreshToken = null, expiresAtMs = 9_999L).isUsable(0L))
    }

    @Test
    fun `the four connection states have their own copy`() {
        assertEquals("Connected", KitsuConnectionState.Connected.label)
        assertEquals("Reconnect needed", KitsuConnectionState.NeedsReconnect.label)
        assertEquals("Not connected", KitsuConnectionState.Disconnected.label)
        assertTrue(KitsuConnectionState.NotConfigured.label.isNotEmpty())
    }
}
