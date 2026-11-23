package com.kennyb1201.kbstream.data.sync

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pull watermark's arithmetic (perf spec 2026-11-22, item 9).
 *
 * Every cycle used to download its whole table. The cursor that fixes that is
 * only safe with the two rules these cases pin: the window starts BEFORE the
 * stored mark (so a transaction that commits behind it is read again), and the
 * mark only ever advances to a timestamp the cycle actually saw.
 */
class SyncWatermarkTest {

    private fun millis(iso: String): Long =
        OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    fun `no stored mark means a full read, exactly as before`() {
        assertNull(SyncWatermark.windowStart(null))
    }

    @Test
    fun `the window starts one overlap before the stored mark`() {
        val stored = millis("2026-11-23T12:00:00Z")
        val start = SyncWatermark.windowStart(stored)
        requireNotNull(start)
        assertEquals(
            "the overlap is what covers a transaction that committed after its stamp",
            stored - SyncWatermark.OVERLAP_MS,
            millis(start)
        )
    }

    @Test
    fun `a cycle advances to the newest timestamp it saw`() {
        val current = millis("2026-11-23T10:00:00Z")
        val next = SyncWatermark.nextThrough(
            current,
            listOf(
                "2026-11-23T10:05:00+00:00",
                "2026-11-23T10:20:00.123456+00:00",
                "2026-11-23T10:11:00Z"
            )
        )
        assertEquals(millis("2026-11-23T10:20:00.123456+00:00"), next)
    }

    @Test
    fun `an empty or unreadable answer never moves the mark`() {
        val current = millis("2026-11-23T10:00:00Z")
        assertEquals(current, SyncWatermark.nextThrough(current, emptyList()))
        assertEquals(current, SyncWatermark.nextThrough(current, listOf("", "not-a-date")))
        assertNull(
            "and a first read that saw nothing stays a full read",
            SyncWatermark.nextThrough(null, listOf("not-a-date"))
        )
    }

    @Test
    fun `the mark never moves backwards`() {
        val current = millis("2026-11-23T10:00:00Z")
        val next = SyncWatermark.nextThrough(current, listOf("2026-11-22T10:00:00Z"))
        assertEquals(current, next)
    }

    @Test
    fun `PostgREST's timestamps parse in both spellings`() {
        assertEquals(
            millis("2026-11-23T10:20:00Z"),
            SyncWatermark.parseMillis("2026-11-23T10:20:00+00:00")
        )
        assertEquals(
            millis("2026-11-23T10:20:00Z"),
            SyncWatermark.parseMillis("2026-11-23T10:20:00Z")
        )
        assertNull(SyncWatermark.parseMillis("  "))
        assertNull(SyncWatermark.parseMillis("yesterday"))
    }

    @Test
    fun `the window is an ISO instant PostgREST accepts`() {
        val start = SyncWatermark.windowStart(millis("2026-11-23T12:00:00Z"))
        requireNotNull(start)
        assertTrue("expected an ISO offset timestamp, got $start", start.contains("T"))
        assertTrue(
            "the server compares a timestamptz, so the offset must be explicit",
            start.endsWith("Z") || start.contains("+00:00")
        )
    }
}
