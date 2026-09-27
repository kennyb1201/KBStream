package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Redaction is the last line before a supabase-kt exception (which embeds the
 * request URL and bearer token) or an Xtream playlist URL (which embeds the
 * account username and password) reaches Sentry or a public bug report. The
 * contract that matters is negative — the secret must not survive — so most of
 * these assert absence rather than an exact string.
 */
class RedactionTest {

    @Test
    fun `xtream playlist credentials in the query string are masked`() {
        val url = "http://iptv.example/get.php" +
            "?username=alice&password=s3cret&type=m3u_plus&output=ts"

        val masked = Redaction.url(url)

        assertFalse(masked, masked.contains("alice"))
        assertFalse(masked, masked.contains("s3cret"))
        // The rest of the URL stays readable so the log is still useful.
        assertTrue(masked, masked.contains("iptv.example/get.php"))
        assertTrue(masked, masked.contains("type=m3u_plus"))
        assertEquals(
            "http://iptv.example/get.php?username=***&password=***&type=m3u_plus&output=ts",
            masked
        )
    }

    @Test
    fun `a bearer token is masked`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIn0.signature"
        val masked = Redaction.text("Authorization: Bearer $jwt")

        assertFalse(masked, masked.contains(jwt))
        assertTrue(masked, masked.contains(Redaction.MASK))
    }

    @Test
    fun `a bare jwt anywhere in the text is masked`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoiYW5vbiJ9.abc123"
        val masked = Redaction.text("request to sync_prefs failed: $jwt")

        assertFalse(masked, masked.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(masked, masked.contains("request to sync_prefs failed"))
    }

    @Test
    fun `credentials embedded in a url authority are masked`() {
        val masked = Redaction.text("GET http://user:p4ss@tv.example:8080/live.m3u failed")

        assertFalse(masked, masked.contains("p4ss"))
        assertFalse(masked, masked.contains("user:p4ss"))
        assertTrue(masked, masked.contains("tv.example:8080/live.m3u"))
    }

    @Test
    fun `a sensitive json field is masked`() {
        val masked = Redaction.text("""{"access_token":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig"}""")

        assertFalse(masked, masked.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(masked, masked.contains("access_token"))
    }

    @Test
    fun `apikey and refresh token query params are masked`() {
        val masked = Redaction.text(
            "https://x.supabase.co/rest/v1/rpc?apikey=anon-key-value&refresh_token=rt_123"
        )

        assertFalse(masked, masked.contains("anon-key-value"))
        assertFalse(masked, masked.contains("rt_123"))
        assertTrue(masked, masked.contains("x.supabase.co/rest/v1/rpc"))
    }

    @Test
    fun `clean text is left alone`() {
        val message = "new row violates row-level security policy for table sync_prefs"
        assertEquals(message, Redaction.text(message))
    }

    @Test
    fun `an email is reduced to first character and domain`() {
        assertEquals("a***@example.com", Redaction.email("alice@example.com"))
        assertEquals("b***@gmail.com", Redaction.email("bob.smith@gmail.com"))
    }

    @Test
    fun `a value that is not an email is returned unchanged`() {
        assertEquals("", Redaction.email(null))
        assertEquals("", Redaction.email(""))
        assertEquals("not-an-email", Redaction.email("not-an-email"))
        assertEquals("@leading", Redaction.email("@leading"))
    }

    @Test
    fun `throwable keeps the stack trace and scrubs the message`() {
        val original = IllegalStateException(
            "GET http://iptv.example/get.php?username=alice&password=s3cret failed"
        )

        val redacted = Redaction.throwable(original)

        assertFalse(redacted.message.orEmpty(), redacted.message.orEmpty().contains("s3cret"))
        assertTrue(redacted.message.orEmpty(), redacted.message.orEmpty().contains("IllegalStateException"))
        assertArrayEquals(original.stackTrace, redacted.stackTrace)
    }

    @Test
    fun `throwable with a clean message is returned unchanged`() {
        val clean = IllegalStateException("plain failure")

        assertSame(clean, Redaction.throwable(clean))
    }
}
