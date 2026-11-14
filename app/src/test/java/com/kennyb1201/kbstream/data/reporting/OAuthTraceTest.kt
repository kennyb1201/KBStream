package com.kennyb1201.kbstream.data.reporting

import com.kennyb1201.kbstream.data.anime.AniListConnectRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OAuth arrival trace, pure (spec 2026-11-14, F1/F2).
 *
 * Kenny's 0.9.310 attempts left the paste saying "recent errors: none this
 * session" - the fix is to record the arrival and the router's verdict, but only
 * ever the link's SHAPE. These tests feed real-shaped redirects carrying a
 * one-time code, a state, a token and a client id and assert that none of those
 * values can appear in the string the app records.
 */
class OAuthTraceTest {

    private val code = "THE_ONE_TIME_CODE"
    private val state = "THE_STATE_VALUE"
    private val clientId = "12345"

    @Test
    fun `a callback arrival names the shape and its parameter names, never values`() {
        val link = "kbstream://oauth/mal?code=$code&state=$state"
        val line = OAuthTrace.arrivalLine(warm = true, rawLink = link)

        assertEquals("oauth arrival warm: kbstream://oauth/mal params=code,state", line)
        listOf(code, state, "code=", "state=").forEach { secret ->
            assertFalse("the arrival line must not carry '$secret': $line", line.contains(secret))
        }
    }

    @Test
    fun `the AniList callback records the same way`() {
        val line = OAuthTrace.arrivalLine(
            warm = false,
            rawLink = "kbstream://oauth/anilist?code=$code&state=$state"
        )
        assertEquals("oauth arrival cold: kbstream://oauth/anilist params=code,state", line)
        assertFalse(line.contains(code))
    }

    @Test
    fun `a start link records its parameter name`() {
        assertEquals(
            "oauth arrival warm: kbstream://oauth/mal params=connect",
            OAuthTrace.arrivalLine(warm = true, rawLink = "kbstream://oauth/mal?connect=1")
        )
    }

    @Test
    fun `a no-data arrival is recorded, because it is the line that names a lost redirect`() {
        assertEquals("oauth arrival warm: no data", OAuthTrace.arrivalLine(warm = true, rawLink = null))
        assertEquals("oauth arrival warm: no data", OAuthTrace.arrivalLine(warm = true, rawLink = ""))
        assertEquals(
            "oauth arrival cold: launcher intent, no data",
            OAuthTrace.arrivalLine(warm = false, rawLink = null)
        )
    }

    @Test
    fun `the shape drops the query and refuses to echo an opaque link`() {
        assertEquals(
            "kbstream://oauth/mal",
            OAuthTrace.linkShape("kbstream://oauth/mal?code=$code#frag")
        )
        assertEquals("<https link>", OAuthTrace.linkShape("https://evil.example/cb?code=$code"))
        assertFalse(OAuthTrace.linkShape("https://evil.example/cb?code=$code").contains(code))
        assertEquals("<opaque link>", OAuthTrace.linkShape("not a uri $code"))
        assertFalse(OAuthTrace.linkShape("not a uri $code").contains(code))
    }

    @Test
    fun `credentials in the authority are dropped with the port and userinfo`() {
        assertEquals(
            "kbstream://oauth/anilist",
            OAuthTrace.linkShape("kbstream://user:pass@oauth:8080/anilist?code=$code")
        )
    }

    @Test
    fun `parameter names are read in link order, values never decoded`() {
        assertEquals(
            "code,state",
            OAuthTrace.paramNames("kbstream://oauth/mal?code=$code&state=$state")
        )
        assertEquals("none", OAuthTrace.paramNames("kbstream://oauth/mal"))
        assertEquals("none", OAuthTrace.paramNames(null))
    }

    @Test
    fun `route lines are fixed verdicts, and the not-a-route verdict names params only`() {
        assertEquals(
            "oauth route mal: duplicate callback ignored",
            OAuthTrace.routeLine("mal", "duplicate callback ignored")
        )
        val verdict = OAuthTrace.notARouteVerdict(
            "kbstream://oauth/mal?code=$code&client_id=$clientId"
        )
        assertEquals("not a callback (params=code,client_id)", verdict)
        assertFalse(verdict.contains(code))
        assertFalse(verdict.contains(clientId))
    }

    @Test
    fun `only oauth links are arrivals, and each provider is recognised by its path`() {
        assertTrue(OAuthTrace.isOAuthLink("kbstream://oauth/mal?code=$code"))
        assertTrue(OAuthTrace.isOAuthLink("kbstream://oauth/anilist?code=$code"))
        assertFalse(OAuthTrace.isOAuthLink("kbstream://title/123"))
        assertFalse(OAuthTrace.isOAuthLink("https://anilist.co/api/v2/oauth/authorize"))
        assertFalse(OAuthTrace.isOAuthLink(null))
        assertTrue(OAuthTrace.isFor("kbstream://oauth/mal?connect=1", "mal"))
        assertFalse(OAuthTrace.isFor("kbstream://oauth/mal?connect=1", "anilist"))
        assertTrue(OAuthTrace.isFor("kbstream://oauth/anilist?code=$code", "anilist"))
    }

    @Test
    fun `the AniList failure sentence is MAL's shape`() {
        val message = AniListConnectRules.anilistFailureMessage("state mismatch")
        assertEquals("AniList didn't connect: state mismatch", message)
    }
}
