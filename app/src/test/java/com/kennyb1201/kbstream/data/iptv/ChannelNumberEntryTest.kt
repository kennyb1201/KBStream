package com.kennyb1201.kbstream.data.iptv

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Channel-number entry: what a remote's digits mean.
 *
 * The player tunes on the result of [ChannelNumberEntry.target], so a wrong
 * answer here changes which channel the user lands on — including the case
 * where a playlist ships no channel numbers at all, which is common enough
 * that "type a number and nothing happens" would read as a broken feature.
 */
class ChannelNumberEntryTest {

    // ── digit decoding ──────────────────────────────────────────────

    @Test
    fun `top-row digits decode`() {
        assertEquals(0, ChannelNumberEntry.digitFor(KeyEvent.KEYCODE_0))
        assertEquals(7, ChannelNumberEntry.digitFor(KeyEvent.KEYCODE_7))
        assertEquals(9, ChannelNumberEntry.digitFor(KeyEvent.KEYCODE_9))
    }

    @Test
    fun `numpad digits decode the same way`() {
        assertEquals(0, ChannelNumberEntry.digitFor(KeyEvent.KEYCODE_NUMPAD_0))
        assertEquals(4, ChannelNumberEntry.digitFor(KeyEvent.KEYCODE_NUMPAD_4))
        assertEquals(9, ChannelNumberEntry.digitFor(KeyEvent.KEYCODE_NUMPAD_9))
    }

    @Test
    fun `non-digit keys carry no digit`() {
        assertNull(ChannelNumberEntry.digitFor(KeyEvent.KEYCODE_DPAD_UP))
        assertNull(ChannelNumberEntry.digitFor(KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(ChannelNumberEntry.digitFor(KeyEvent.KEYCODE_CHANNEL_UP))
    }

    // ── accumulation ────────────────────────────────────────────────

    @Test
    fun `digits accumulate`() {
        var entry = ""
        listOf(1, 2, 0, 4).forEach { entry = ChannelNumberEntry.push(entry, it) }
        assertEquals("1204", entry)
    }

    @Test
    fun `entry stops at the digit limit`() {
        var entry = "1234"
        // A fifth press must be ignored rather than shifting a channel away.
        entry = ChannelNumberEntry.push(entry, 5)
        assertEquals("1234", entry)
        assertEquals(ChannelNumberEntry.MAX_DIGITS, entry.length)
    }

    // ── matching ────────────────────────────────────────────────────

    private val numbered = listOf("101", "102", "1204", null, "  ")

    @Test
    fun `an exact channel number matches`() {
        assertEquals(1, ChannelNumberEntry.target(numbered, "102"))
        assertEquals(2, ChannelNumberEntry.target(numbered, "1204"))
    }

    @Test
    fun `leading zeros are ignored`() {
        // Remote users type 07 for channel 7, however many zeros they add.
        assertEquals(1, ChannelNumberEntry.target(numbered, "0102"))
        assertEquals(1, ChannelNumberEntry.target(numbered, "000102"))
    }

    @Test
    fun `a number absent from the lineup does not match`() {
        assertEquals(-1, ChannelNumberEntry.target(numbered, "999"))
        // Prefixes are not matches: "12" is not channel "1204".
        assertEquals(-1, ChannelNumberEntry.target(numbered, "12"))
    }

    @Test
    fun `blank entries never match`() {
        assertEquals(-1, ChannelNumberEntry.target(numbered, ""))
        assertEquals(-1, ChannelNumberEntry.target(numbered, "   "))
    }

    @Test
    fun `blank channel numbers are skipped, not treated as zero`() {
        // A null / whitespace chno must not answer to "0".
        assertEquals(-1, ChannelNumberEntry.target(listOf(null, "  ", "5"), "0"))
        assertEquals(2, ChannelNumberEntry.target(listOf(null, "  ", "5"), "5"))
    }

    @Test
    fun `a playlist with no numbers falls back to positions`() {
        val unnumbered = listOf(null, "", null, null)
        assertEquals(0, ChannelNumberEntry.target(unnumbered, "1"))
        assertEquals(3, ChannelNumberEntry.target(unnumbered, "4"))
    }

    @Test
    fun `positions outside the lineup do not match`() {
        val unnumbered = listOf(null, null)
        assertEquals(-1, ChannelNumberEntry.target(unnumbered, "0"))
        assertEquals(-1, ChannelNumberEntry.target(unnumbered, "3"))
        assertEquals(-1, ChannelNumberEntry.target(emptyList(), "1"))
    }

    @Test
    fun `a numbered lineup never falls back to positions`() {
        // Positional matching here would send "2" to the second channel while
        // the user meant channel 2 — wrong channel, silently.
        val oneNumbered = listOf("9", null, null)
        assertEquals(-1, ChannelNumberEntry.target(oneNumbered, "2"))
    }
}
