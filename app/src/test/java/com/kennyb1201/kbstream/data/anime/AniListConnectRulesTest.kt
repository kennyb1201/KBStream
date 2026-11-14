package com.kennyb1201.kbstream.data.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AniList's Authorization Code grant, pure (spec 2026-11-14, F2/F3).
 *
 * The connect used to be the implicit grant, and it is dead at AniList's end: the
 * authorize endpoint answers `unsupported_grant_type` for `response_type=token`
 * (Kenny's phone on 2026-11-14, and two independent apps' documented live tests in
 * 2026-06). The replacement is a code grant whose exchange needs the app's secret,
 * which ships in the APK by Kenny's decision of the same day.
 *
 * What is checked here is the shape that decision rests on: the URL AniList is
 * asked with, the redirect the code comes back on, the state that makes a foreign
 * callback refuse, the form body - the one place the secret may appear - and what
 * the dialog is allowed to tell a viewer when the exchange fails.
 */
class AniListConnectRulesTest {

    private val jwt = "eyJ0eXAiOiJKV1QifQ.eyJleHAiOjQxMDI0NDQ4MDB9.c2lnbmF0dXJl"

    // ── the authorize URL (F2) ────────────────────────────────────────────

    @Test
    fun `the URL asks for a code against the app's own redirect`() {
        val url = AniListConnectRules.authorizeUrl("1234", state = "abc")!!

        assertTrue(url.startsWith("https://anilist.co/api/v2/oauth/authorize?"))
        assertTrue(url.contains("response_type=code"))
        assertFalse(
            "response_type=token is the grant AniList removed",
            url.contains("response_type=token")
        )
        assertTrue(url.contains("client_id=1234"))
        assertTrue(
            "the app's own scheme, so the code returns to the app instead of a " +
                "pin page that no longer exists",
            url.contains("redirect_uri=kbstream%3A%2F%2Foauth%2Fanilist")
        )
        assertTrue(url.contains("state=abc"))
    }

    @Test
    fun `the secret never rides the authorize URL`() {
        val url = AniListConnectRules.authorizeUrl("1234")!!
        assertFalse("the secret belongs to the exchange POST body alone", url.contains("client_secret"))
        assertFalse("AniList's code grant takes no PKCE", url.contains("code_challenge"))
    }

    @Test
    fun `an advertise URL without a state is still a full code-grant URL`() {
        // The TV prints and encodes this one: the phone that approves it never
        // generated this device's state, so the advertised URL carries none.
        val url = AniListConnectRules.authorizeUrl("1234", state = null)!!
        assertTrue(url.contains("response_type=code"))
        assertFalse(url.contains("state="))
    }

    @Test
    fun `a build with no client id has no authorize URL at all`() {
        assertNull(AniListConnectRules.authorizeUrl(""))
        assertNull(AniListConnectRules.authorizeUrl("   "))
    }

    // ── the redirect and its state (F3) ───────────────────────────────────

    @Test
    fun `a callback carrying the state this device generated is exchanged`() {
        val outcome = AniListConnectRules.redirectOutcome(
            "kbstream://oauth/anilist?code=the-code&state=abc",
            expectedState = "abc"
        )
        assertEquals(AniListRedirect.Code("the-code"), outcome)
    }

    @Test
    fun `a forged callback with another state is refused`() {
        val outcome = AniListConnectRules.redirectOutcome(
            "kbstream://oauth/anilist?code=the-code&state=attacker",
            expectedState = "abc"
        )
        assertTrue("a mismatched state must not reach the exchange", outcome is AniListRedirect.Ignored)
        assertTrue((outcome as AniListRedirect.Ignored).reason.contains("state mismatch"))
    }

    @Test
    fun `a device waiting for a state refuses a callback that omits it`() {
        val outcome = AniListConnectRules.redirectOutcome(
            "kbstream://oauth/anilist?code=the-code",
            expectedState = "abc"
        )
        assertTrue(outcome is AniListRedirect.Ignored)
    }

    @Test
    fun `the cross-device arrival is accepted when no flow is pending`() {
        // S2: a TV shows the URL, a PHONE approves it, and AniList sends the code
        // to the phone - which never generated the TV's state. Requiring a match
        // there would make the TV's QR fail every time.
        val outcome = AniListConnectRules.redirectOutcome(
            "kbstream://oauth/anilist?code=the-code",
            expectedState = null
        )
        assertEquals(AniListRedirect.Code("the-code"), outcome)
    }

    @Test
    fun `a callback presenting a state to a device that issued none is refused`() {
        // The other half of the same guard: this is the forged-callback shape,
        // and it must not become the hole the cross-device path opens.
        val outcome = AniListConnectRules.redirectOutcome(
            "kbstream://oauth/anilist?code=the-code&state=whatever",
            expectedState = null
        )
        assertTrue(outcome is AniListRedirect.Ignored)
    }

    @Test
    fun `a refusal is named, not exchanged`() {
        val outcome = AniListConnectRules.redirectOutcome(
            "kbstream://oauth/anilist?error=access_denied&state=abc",
            expectedState = "abc"
        )
        assertEquals(AniListRedirect.Refused("access_denied"), outcome)
    }

    @Test
    fun `links that are not this callback are ignored`() {
        listOf(
            "kbstream://oauth/mal?code=x&state=abc",
            "https://anilist.co/api/v2/oauth/anilist?code=x",
            "kbstream://oauth/anilist?code=&state=abc",
            "kbstream://oauth/anilist?state=abc"
        ).forEach { link ->
            assertTrue(
                "$link must not be exchanged",
                AniListConnectRules.redirectOutcome(link, "abc") !is AniListRedirect.Code
            )
        }
    }

    // ── the paste fallback (F3/S3) ────────────────────────────────────────

    @Test
    fun `a bare code is the fallback's value`() {
        assertEquals(
            AniListPastedValue.Code("0123456789abcdef"),
            AniListConnectRules.pastedValue(" 0123456789abcdef ")
        )
    }

    @Test
    fun `the whole redirect URL is parsed exactly like a callback`() {
        val value = AniListConnectRules.pastedValue(
            "kbstream://oauth/anilist?code=the-code&state=abc"
        )
        assertEquals(
            AniListPastedValue.Redirect("kbstream://oauth/anilist?code=the-code&state=abc"),
            value
        )
    }

    @Test
    fun `a token pasted into the code field is refused by name`() {
        // The old flow handed tokens out; the new one must not send one to the
        // exchange, where it would fail as a bad code and read as an outage.
        assertEquals(AniListPastedValue.Token, AniListConnectRules.pastedValue(jwt))
        assertEquals(AniListPastedValue.Token, AniListConnectRules.pastedValue("  $jwt "))
        assertTrue(AniListConnectRules.TOKEN_PASTED_MESSAGE.contains("not the code"))
    }

    @Test
    fun `nothing typed is nothing to exchange`() {
        assertEquals(AniListPastedValue.Missing, AniListConnectRules.pastedValue(""))
        assertEquals(AniListPastedValue.Missing, AniListConnectRules.pastedValue("   "))
        assertEquals(AniListPastedValue.Missing, AniListConnectRules.pastedValue(null))
    }

    // ── the exchange (F1: the secret's one place) ─────────────────────────

    @Test
    fun `the exchange form is the one place the secret appears`() {
        val form = AniListConnectRules.codeExchangeForm(
            clientId = "1234",
            clientSecret = "s3cr3t value",
            code = "the-code"
        )
        assertTrue(form.contains("grant_type=authorization_code"))
        assertTrue(form.contains("client_id=1234"))
        assertTrue(form.contains("client_secret=s3cr3t%20value"))
        assertTrue(form.contains("code=the-code"))
        assertTrue(
            "the redirect must be the same one the authorize URL named",
            form.contains("redirect_uri=kbstream%3A%2F%2Foauth%2Fanilist")
        )
        listOf("code_challenge", "code_verifier", "code_challenge_method").forEach { pkce ->
            assertFalse("AniList's code grant rejects PKCE ($pkce)", form.contains(pkce))
        }
    }

    @Test
    fun `a token response is read by its access_token`() {
        val body = """{"access_token":"$jwt","token_type":"Bearer","expires_in":31536000}"""
        assertEquals(jwt, AniListConnectRules.tokenFromExchange(body))
        assertNull(AniListConnectRules.tokenFromExchange("""{"token_type":"Bearer"}"""))
        assertNull(AniListConnectRules.tokenFromExchange("not json"))
    }

    @Test
    fun `a refused exchange says what AniList said`() {
        val body =
            """{"error":"invalid_client","message":"The client credentials are invalid","hint":"Check the client secret"}"""
        val message = AniListConnectRules.exchangeFailure(body, 400)
        assertTrue(message.contains("invalid_client"))
        assertTrue(message.contains("Check the client secret"))
        assertTrue(
            "nothing from the request may appear here - the secret is in it",
            !message.contains("client_secret=")
        )
    }

    @Test
    fun `an empty failure body still names the status`() {
        val message = AniListConnectRules.exchangeFailure("", 500)
        assertTrue(message.contains("500"))
    }

    @Test
    fun `a state is fresh each time and shaped like a token, not a sentence`() {
        val first = AniListConnectRules.state()
        val second = AniListConnectRules.state()
        assertEquals(32, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
        assertFalse("two connects must not share a state", first == second)
    }
}
