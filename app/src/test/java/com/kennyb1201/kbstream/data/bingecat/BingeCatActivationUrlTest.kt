package com.kennyb1201.kbstream.data.bingecat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the connect screen's QR actually opens.
 *
 * The TV report is the spec here: typing the code at the bare activation page
 * failed twice with `invalid_user_code`, while the direct `?user_code=` link
 * approved first time. The QR has to encode the workaround, and it has to be
 * safe to run on a URL that already carries a code, because a server-provided
 * `verification_url` may start doing exactly that.
 */
class BingeCatActivationUrlTest {

    private val activate = "https://bingecat.com/activate"
    private val code = "ABCD2345"

    @Test
    fun `a plain activation page gains the code as its only query parameter`() {
        assertEquals(
            "$activate?user_code=$code",
            bingecatActivationUrlForQr(activate, code)
        )
    }

    @Test
    fun `an existing query string is extended, not replaced`() {
        assertEquals(
            "https://bingecat.com/activate?lang=en&user_code=$code",
            bingecatActivationUrlForQr("https://bingecat.com/activate?lang=en", code)
        )
        assertEquals(
            "https://bingecat.com/activate?user_code=$code",
            bingecatActivationUrlForQr("https://bingecat.com/activate?", code)
        )
    }

    @Test
    fun `a URL that already carries a code is never double-appended`() {
        val server = "$activate?user_code=ZZZZ9999"
        assertEquals(
            "the server's own code wins; two user_code parameters would be a " +
                "different, wrong request",
            server,
            bingecatActivationUrlForQr(server, code)
        )
        assertEquals(
            "$activate?lang=en&user_code=ZZZZ9999",
            bingecatActivationUrlForQr("$activate?lang=en&user_code=ZZZZ9999", code)
        )
    }

    @Test
    fun `a blank code never produces a user_code parameter`() {
        assertEquals(activate, bingecatActivationUrlForQr(activate, ""))
        assertEquals(activate, bingecatActivationUrlForQr(activate, "   "))
    }

    @Test
    fun `surrounding whitespace from a paste does not reach the QR`() {
        assertEquals(
            "$activate?user_code=$code",
            bingecatActivationUrlForQr("  $activate  ", " $code ")
        )
    }

    @Test
    fun `a different code is a different URL, so the QR cannot be reused`() {
        val first = bingecatActivationUrlForQr(activate, "ABCD2345")
        val second = bingecatActivationUrlForQr(activate, "WXYZ6789")
        assertEquals(
            "KBQrCode keys its bitmap on the URL string - equal URLs would show " +
                "the previous code's QR",
            false,
            first == second
        )
    }
}
