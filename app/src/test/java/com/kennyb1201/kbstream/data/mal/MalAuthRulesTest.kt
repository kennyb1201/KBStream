package com.kennyb1201.kbstream.data.mal

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAL's OAuth shape (spec 2026-11-13, F1).
 *
 * MAL is the one tracker with no device flow, so its connect is Authorization
 * Code + PKCE and shares nothing with Trakt's or BingeCat's. The rules that
 * decide the flow are pure and pinned here: the verifier and challenge, the
 * authorize URL (including the one MAL quirk - `plain` only), the redirect parse
 * with its state check, the two form bodies, the token parse and the 429 window.
 */
class MalAuthRulesTest {

    // ── PKCE ──────────────────────────────────────────────────────────────

    @Test
    fun `a verifier is 43 to 128 unreserved characters`() {
        repeat(20) {
            val verifier = malCodeVerifier(Random(it))
            assertTrue("length ${verifier.length}", verifier.length in 43..128)
            assertTrue(
                "unreserved only: $verifier",
                verifier.all { ch ->
                    ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                        ch == '-' || ch == '.' || ch == '_' || ch == '~'
                }
            )
        }
    }

    @Test
    fun `two verifiers differ`() {
        // A repeated verifier is a reusable grant; each connect must be fresh.
        assertTrue(malCodeVerifier() != malCodeVerifier())
    }

    @Test
    fun `the challenge is the verifier, because MAL rejects S256`() {
        val verifier = malCodeVerifier()
        assertEquals(verifier, malCodeChallenge(verifier))
    }

    @Test
    fun `the state is random and hex`() {
        val first = malState()
        val second = malState()
        assertTrue(first != second)
        assertTrue(first.all { it in "0123456789abcdef" })
        assertEquals(32, first.length)
    }

    // ── The authorize URL ─────────────────────────────────────────────────

    @Test
    fun `the authorize URL carries the plain challenge and the registered redirect`() {
        val url = malAuthorizeUrl(clientId = "abc123", challenge = "verifier", state = "st")

        assertTrue(url.startsWith("https://myanimelist.net/v1/oauth2/authorize?"))
        listOf(
            "response_type=code",
            "client_id=abc123",
            "redirect_uri=kbstream%3A%2F%2Foauth%2Fmal",
            "code_challenge=verifier",
            "code_challenge_method=plain",
            "state=st"
        ).forEach { part ->
            assertTrue("missing '$part' in $url", url.contains(part))
        }
        assertFalse("S256 is rejected by MAL", url.contains("S256"))
    }

    // ── The redirect ──────────────────────────────────────────────────────

    @Test
    fun `a matching state yields the code`() {
        val outcome = malRedirectOutcome(
            "kbstream://oauth/mal?code=the-code&state=the-state",
            expectedState = "the-state"
        )
        assertEquals(MalRedirect.Code("the-code"), outcome)
    }

    @Test
    fun `a different state is ignored, not exchanged`() {
        // Any app on the device can send this scheme: the state is the only
        // thing that says the callback is the one this app asked for.
        val outcome = malRedirectOutcome(
            "kbstream://oauth/mal?code=the-code&state=forged",
            expectedState = "the-state"
        )
        assertTrue(outcome is MalRedirect.Ignored)
    }

    @Test
    fun `a missing state or code is ignored`() {
        assertTrue(
            malRedirectOutcome("kbstream://oauth/mal?code=c", expectedState = null)
                is MalRedirect.Ignored
        )
        assertTrue(
            malRedirectOutcome("kbstream://oauth/mal?state=s", expectedState = "s")
                is MalRedirect.Ignored
        )
    }

    @Test
    fun `a refusal is reported as refused`() {
        val outcome = malRedirectOutcome(
            "kbstream://oauth/mal?error=access_denied&state=the-state",
            expectedState = "the-state"
        )
        assertEquals(MalRedirect.Refused("access_denied"), outcome)
    }

    @Test
    fun `another link on the same scheme is not a MAL callback`() {
        assertTrue(
            malRedirectOutcome("kbstream://title/series/1396", expectedState = "s")
                is MalRedirect.Ignored
        )
        assertTrue(malRedirectOutcome(null, expectedState = "s") is MalRedirect.Ignored)
        assertTrue(malRedirectOutcome("", expectedState = "s") is MalRedirect.Ignored)
    }

    @Test
    fun `the redirect_uri registered on MAL is the one this app listens on`() {
        // MAL refuses an authorize request naming any other URI, and the manifest
        // filter is written by hand: one constant is what keeps them equal.
        assertEquals("kbstream://oauth/mal", MAL_REDIRECT_URI)
        assertTrue(malRedirectOutcome(MAL_REDIRECT_URI + "?code=c&state=s", "s") is MalRedirect.Code)
    }

    // ── The token forms ───────────────────────────────────────────────────

    @Test
    fun `the code exchange sends the verifier and the client id, and no secret`() {
        val form = malCodeExchangeForm("abc123", "the-code", "the-verifier")

        assertTrue(form.contains("grant_type=authorization_code"))
        assertTrue(form.contains("code=the-code"))
        assertTrue(form.contains("code_verifier=the-verifier"))
        assertTrue(form.contains("client_id=abc123"))
        assertTrue(form.contains("redirect_uri=kbstream%3A%2F%2Foauth%2Fmal"))
        assertFalse("this app type has no secret", form.contains("client_secret"))
    }

    @Test
    fun `the refresh grant names the refresh token`() {
        val form = malRefreshForm("abc123", "refresh-1")
        assertEquals(
            "grant_type=refresh_token&refresh_token=refresh-1&client_id=abc123",
            form
        )
    }

    // ── The token response ────────────────────────────────────────────────

    @Test
    fun `a token body becomes an absolute deadline`() {
        val token = malParseToken(
            """{"access_token":"a","refresh_token":"r","expires_in":3600,"token_type":"Bearer"}""",
            nowMs = 1_000_000L
        )
        assertEquals("a", token?.accessToken)
        assertEquals("r", token?.refreshToken)
        // One hour minus the minute of slack.
        assertEquals(1_000_000L + 3_540_000L, token?.expiresAtMs)
    }

    @Test
    fun `a body with no refresh token keeps the previous one`() {
        // MAL omits it on some responses; dropping it would strand the session
        // at the next hour.
        val token = malParseToken(
            """{"access_token":"a2","expires_in":3600}""",
            nowMs = 0L,
            previousRefresh = "kept"
        )
        assertEquals("kept", token?.refreshToken)
    }

    @Test
    fun `a body with no access token is not a token`() {
        assertNull(malParseToken("""{"refresh_token":"r"}""", nowMs = 0L))
        assertNull(malParseToken("not json", nowMs = 0L))
    }

    // ── Failure handling ──────────────────────────────────────────────────

    @Test
    fun `only a 401 asks for a refresh`() {
        assertTrue(malNeedsRefresh(401))
        assertFalse(malNeedsRefresh(403))
        assertFalse(malNeedsRefresh(429))
        assertFalse(malNeedsRefresh(500))
    }

    @Test
    fun `a 429 parks for Retry-After, and a minute when it says nothing`() {
        assertEquals(30_000L, malRetryAfterMs("30"))
        assertEquals(60_000L, malRetryAfterMs(null))
        assertEquals(60_000L, malRetryAfterMs("0"))
        assertEquals(60_000L, malRetryAfterMs("soon"))
    }

    @Test
    fun `the connection state is honest`() {
        assertEquals(
            MalConnectionState.NotConfigured,
            malConnectionState(configured = false, hasSession = true, needsReconnect = true)
        )
        assertEquals(
            MalConnectionState.NeedsReconnect,
            malConnectionState(configured = true, hasSession = true, needsReconnect = true)
        )
        assertEquals(
            MalConnectionState.Connected,
            malConnectionState(configured = true, hasSession = true, needsReconnect = false)
        )
        assertEquals(
            MalConnectionState.Disconnected,
            malConnectionState(configured = true, hasSession = false, needsReconnect = false)
        )
        // A dead grant with no session is still "needs reconnect", not "never
        // connected": the viewer's account is in a state only they can fix.
        assertEquals(
            MalConnectionState.NeedsReconnect,
            malConnectionState(configured = true, hasSession = false, needsReconnect = true)
        )
    }

    @Test
    fun `the state labels name themselves`() {
        assertEquals("not configured", MalConnectionState.NotConfigured.label())
        assertEquals("not connected", MalConnectionState.Disconnected.label())
        assertEquals("connected", MalConnectionState.Connected.label())
        assertEquals("needs reconnect", MalConnectionState.NeedsReconnect.label())
    }

    // ── the start link's outcome (spec 2026-11-14, F2a) ────────────────────

    @Test
    fun `an opened start link owes the viewer no sentence`() {
        // A QR that works must not add a message to the screen it just replaced.
        assertNull(malStartFailureMessage(MalStartOutcome.Opened))
    }

    @Test
    fun `a build with no app id says it is the build`() {
        val message = malStartFailureMessage(MalStartOutcome.NotConfigured).orEmpty()
        // The field report: the scan offered KBStream, the app opened, and the
        // browser never did. The sentence has to land on the cause a viewer can
        // act on - the phone's build - not on the scan or the code.
        assertTrue("names the build", message.contains("in this build"))
        assertTrue("names the missing app id", message.contains("app id"))
        assertTrue("names the phone", message.contains("phone"))
        assertTrue(
            "and says what the code did do, so the scan is not the suspect",
            message.contains("opened KBStream")
        )
        assertFalse("it is not a retry invitation", message.contains("try again"))
    }

    @Test
    fun `a device with no browser says that instead`() {
        val message = malStartFailureMessage(MalStartOutcome.NoBrowser).orEmpty()
        assertTrue(message.contains("no browser"))
        // The two failures must not share wording: one is a build, one is a device.
        assertFalse(message.contains("app id"))
        assertFalse(
            message == malStartFailureMessage(MalStartOutcome.NotConfigured)
        )
    }
}
