package com.kennyb1201.kbstream.data.remote

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the phone remote (spec 2026-11-13), without any Android.
 *
 * These are the parts a field test can only see the END of: whether a code is
 * still valid, whether a token matches, whether a resolved address is really on
 * the LAN. Each one is a security or a "never a dead button" property, so each
 * gets pinned here rather than trusted to the screen that uses it.
 */
class RemoteRulesTest {

    // ── The PIN ───────────────────────────────────────────────────────────

    @Test
    fun `a pin is always four digits, leading zeros included`() {
        repeat(200) { seed ->
            val pin = RemoteRules.newPin(Random(seed))
            assertEquals("a pin is four characters: $pin", RemoteRules.PIN_DIGITS, pin.length)
            assertTrue("a pin is all digits: $pin", pin.all { it.isDigit() })
        }
        // A pin below 1000 must not lose its leading zero - the TV draws four
        // boxes and the phone types four digits.
        val low = RemoteRules.newPin(Random(7))
        assertTrue(low.length == 4 && low.all { it.isDigit() })
    }

    @Test
    fun `a pin pairs only inside its five minutes and only once`() {
        val issued = 1_000_000L
        assertTrue(
            "the code shown on the TV works immediately",
            RemoteRules.pinAccepts("1234", issued, issued, "1234")
        )
        assertTrue(
            "and still works a second before it expires",
            RemoteRules.pinAccepts("1234", issued, issued + RemoteRules.PIN_TTL_MS - 1, "1234")
        )
        assertFalse(
            "expired means unusable even with the right digits",
            RemoteRules.pinAccepts("1234", issued, issued + RemoteRules.PIN_TTL_MS, "1234")
        )
    }

    @Test
    fun `a pin rejects anything but the exact four digits`() {
        val issued = 5_000L
        assertFalse(
            "a wrong code",
            RemoteRules.pinAccepts("1234", issued, issued + 10, "1235")
        )
        assertFalse(
            "a short code",
            RemoteRules.pinAccepts("1234", issued, issued + 10, "123")
        )
        assertFalse(
            "letters",
            RemoteRules.pinAccepts("1234", issued, issued + 10, "12a4")
        )
        assertFalse(
            "no PIN was issued",
            RemoteRules.pinAccepts(null, issued, issued + 10, "1234")
        )
        assertFalse(
            "a blank candidate never matches an issued code",
            RemoteRules.pinAccepts("", issued, issued + 10, "")
        )
        assertFalse(
            "a missing candidate",
            RemoteRules.pinAccepts("1234", issued, issued + 10, null)
        )
    }

    @Test
    fun `surrounding spaces are tolerated on both sides`() {
        assertTrue(RemoteRules.pinAccepts(" 1234 ", 0L + 1, 2L, "1234 "))
    }

    // ── The token ──────────────────────────────────────────────────────────

    @Test
    fun `a token is long, hex, and matched exactly`() {
        val token = RemoteRules.newToken(Random(3))
        assertEquals(RemoteRules.TOKEN_LENGTH, token.length)
        assertTrue(token.all { it in "0123456789abcdef" })

        assertTrue(RemoteRules.tokenMatches(token, token))
        assertFalse("a different token", RemoteRules.tokenMatches(token, RemoteRules.newToken()))
        assertFalse("a prefix of it", RemoteRules.tokenMatches(token, token.dropLast(1)))
        assertFalse("nothing stored", RemoteRules.tokenMatches(null, token))
        assertFalse("nothing offered", RemoteRules.tokenMatches(token, null))
        assertFalse("two blanks", RemoteRules.tokenMatches("", ""))
    }

    @Test
    fun `two phones never get the same token`() {
        val seen = HashSet<String>()
        repeat(500) { assertTrue("a token repeated", seen.add(RemoteRules.newToken())) }
    }

    // ── The keys ───────────────────────────────────────────────────────────

    @Test
    fun `only the closed key list is accepted, in any case`() {
        assertEquals("UP", RemoteRules.normaliseKey("up"))
        assertEquals("OK", RemoteRules.normaliseKey(" Ok "))
        assertEquals("PLAY_PAUSE", RemoteRules.normaliseKey("play_pause"))
        assertNull("an invented key", RemoteRules.normaliseKey("KEYCODE_ESCAPE"))
        assertNull("a raw android name", RemoteRules.normaliseKey("KEYCODE_HOME"))
        assertNull("an empty code", RemoteRules.normaliseKey(""))
        assertNull(RemoteRules.normaliseKey(null))
    }

    @Test
    fun `the list is exactly the spec's remote, no more`() {
        assertEquals(
            setOf(
                "UP", "DOWN", "LEFT", "RIGHT", "OK", "BACK", "HOME",
                "PLAY_PAUSE", "SEEK_BACK", "SEEK_FORWARD",
                "VOLUME_UP", "VOLUME_DOWN", "MUTE"
            ),
            RemoteRules.KEYS.toSet()
        )
    }

    // ── The network ────────────────────────────────────────────────────────

    @Test
    fun `only private addresses count as the LAN`() {
        assertTrue(RemoteRules.isLanHost("192.168.1.42"))
        assertTrue(RemoteRules.isLanHost("10.0.0.7"))
        assertTrue(RemoteRules.isLanHost("172.16.4.9"))
        assertTrue(RemoteRules.isLanHost("172.31.255.1"))
        assertTrue("a link-local TV is still on this network", RemoteRules.isLanHost("169.254.3.4"))

        assertFalse("a public address", RemoteRules.isLanHost("8.8.8.8"))
        assertFalse("172.32 is outside the private block", RemoteRules.isLanHost("172.32.0.1"))
        assertFalse("loopback", RemoteRules.isLanHost("127.0.0.1"))
        assertFalse("a hostname, not a resolved address", RemoteRules.isLanHost("tv.local"))
        assertFalse("an out-of-range octet", RemoteRules.isLanHost("192.168.1.300"))
        assertFalse("too few octets", RemoteRules.isLanHost("192.168.1"))
        assertFalse(RemoteRules.isLanHost(""))
        assertFalse(RemoteRules.isLanHost(null))
    }

    @Test
    fun `the device label is never blank`() {
        assertEquals("Onn 4K Plus", RemoteRules.deviceLabel("Onn 4K Plus"))
        assertEquals("Onn 4K Plus", RemoteRules.deviceLabel("  Onn   4K  Plus "))
        assertEquals("KBStream TV", RemoteRules.deviceLabel(""))
        assertEquals("KBStream TV", RemoteRules.deviceLabel("   "))
        assertEquals("KBStream TV", RemoteRules.deviceLabel(null))
    }

    @Test
    fun `the service type and paths are the ones the spec names`() {
        assertEquals("_kbstream-remote._tcp", RemoteRules.SERVICE_TYPE)
        assertEquals("/remote", RemoteRules.WEBSOCKET_PATH)
        assertEquals(5L * 60L * 1000L, RemoteRules.PIN_TTL_MS)
    }
}
