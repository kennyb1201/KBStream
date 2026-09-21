package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * XMLTV dates are the messiest input the guide receives — the importer keeps a
 * budgeted "DATE PARSE FAILED" log because real guides emit forms no single
 * format matches. A wrong parse is silent (a program lands at epoch 0 and is
 * dropped, or spans the wrong hours), so the accepted spellings are pinned here.
 *
 * 2026-09-21T12:00:00Z is the reference instant (1789992000000).
 */
class XmltvDatesTest {

    private val noonUtc = 1_789_992_000_000L

    @Test
    fun `compact timestamps parse as UTC`() {
        assertEquals(noonUtc, parseXmltvDateMillis("20260921120000"))
        assertEquals(noonUtc, parseXmltvDateMillis("  20260921120000  "))
    }

    @Test
    fun `truncated compact timestamps pad to seconds`() {
        // 12 digits: seconds omitted. 10: minutes+seconds omitted. 8: date only.
        assertEquals(noonUtc, parseXmltvDateMillis("202609211200"))
        assertEquals(noonUtc, parseXmltvDateMillis("2026092112"))
        assertEquals(1_789_948_800_000L, parseXmltvDateMillis("20260921"))
    }

    @Test
    fun `a whitespace separated offset is honoured`() {
        assertEquals(noonUtc, parseXmltvDateMillis("20260921120000 +0000"))
        assertEquals(noonUtc, parseXmltvDateMillis("20260921120000 +00:00"))
        assertEquals(noonUtc, parseXmltvDateMillis("20260921120000 Z"))
        assertEquals(noonUtc, parseXmltvDateMillis("20260921120000 UTC"))
        // 14:00 in +02:00 is the same instant as 12:00 UTC.
        assertEquals(noonUtc, parseXmltvDateMillis("20260921140000 +0200"))
        // ...and 10:00 in -02:00 is too.
        assertEquals(noonUtc, parseXmltvDateMillis("20260921100000 -0200"))
    }

    @Test
    fun `an offset with an unrecognised spelling falls back rather than guessing`() {
        // A tz we cannot read leaves the compact form unparseable, so the ISO
        // path gets its chance; the point is that it never silently becomes UTC.
        assertNull(parseXmltvDateMillis("20260921120000 CET"))
    }

    @Test
    fun `iso-like timestamps parse with or without a separator or a Z`() {
        assertEquals(noonUtc, parseXmltvDateMillis("2026-09-21T12:00:00Z"))
        assertEquals(noonUtc, parseXmltvDateMillis("2026-09-21T12:00:00"))
        assertEquals(noonUtc, parseXmltvDateMillis("2026-09-21 12:00:00"))
        assertEquals(noonUtc, parseXmltvDateMillis("2026-09-21 12:00:00Z"))
        assertEquals(noonUtc, parseXmltvDateMillis("2026-09-21T14:00:00+02:00"))
    }

    @Test
    fun `unparseable and absent values return null instead of epoch zero`() {
        // Null (not 0L) is what lets the importer log a real failure: 0L is
        // indistinguishable from a value that genuinely parsed to the epoch.
        assertNull(parseXmltvDateMillis(null))
        assertNull(parseXmltvDateMillis(""))
        assertNull(parseXmltvDateMillis("   "))
        assertNull(parseXmltvDateMillis("not a date"))
        assertNull(parseXmltvDateMillis("2026-13-45"))
        assertNull(parseXmltvDateMillis("123"))
    }
}
