package com.kennyb1201.kbstream.data.sync

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Outbox coalescing. The bug this pins: a burst of writes for the same title
 * (position saves, watched toggles, bulk import) must leave ONE pending row
 * carrying the LATEST payload — a stale row flushed after a fresh one is how
 * remote devices ended up with reverted state.
 */
class OutboxQueueTest {

    private fun payload(marker: String) = buildJsonObject { put("marker", marker) }

    private fun item(
        key: String,
        marker: String,
        table: String = "sync_watched_status",
        keyColumn: String = "item_key"
    ) = OutboxItem(
        table = table,
        keyColumn = keyColumn,
        key = key,
        payload = payload(marker),
        enqueuedAtMs = 1L
    )

    @Test
    fun `repeat writes for one key coalesce to a single row`() {
        val queue = OutboxQueue()
        queue.put(item("p:a:movie::tt1", "first"))
        queue.put(item("p:a:movie::tt1", "second"))
        queue.put(item("p:a:movie::tt1", "third"))

        assertEquals(1, queue.size)
        assertEquals("third", queue.snapshot().single().payload["marker"].toString().trim('"'))
    }

    @Test
    fun `different keys stay separate`() {
        val queue = OutboxQueue()
        queue.put(item("p:a:movie::tt1", "x"))
        queue.put(item("p:a:movie::tt2", "y"))
        assertEquals(2, queue.size)
    }

    @Test
    fun `same key in a different table or column is a different row`() {
        val queue = OutboxQueue()
        queue.put(item("p:a:tt1", "watched"))
        queue.put(item("p:a:tt1", "history", table = "sync_watch_history", keyColumn = "item_id"))
        queue.put(item("p:a:tt1", "prefs", table = "sync_prefs", keyColumn = "pref_key"))
        assertEquals(3, queue.size)
    }

    @Test
    fun `same key under two profiles is not coalesced`() {
        val queue = OutboxQueue()
        queue.put(item("p:a:movie::tt1", "profile-a"))
        queue.put(item("p:b:movie::tt1", "profile-b"))
        assertEquals(2, queue.size)
    }

    @Test
    fun `remove drops a row that is still the flushed one`() {
        val queue = OutboxQueue()
        val row = item("p:a:movie::tt1", "v1")
        queue.put(row)
        assertTrue(queue.remove(row))
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `remove keeps a write that landed while the flush was in flight`() {
        val queue = OutboxQueue()
        val flushed = item("p:a:movie::tt1", "v1")
        queue.put(flushed)
        // Same key, fresh payload, enqueued after the snapshot was taken.
        queue.put(item("p:a:movie::tt1", "v2"))

        assertFalse(queue.remove(flushed))
        assertEquals("v2", queue.snapshot().single().payload["marker"].toString().trim('"'))
    }

    @Test
    fun `pending count is reported on every mutation`() {
        val seen = mutableListOf<Int>()
        val queue = OutboxQueue { seen.add(it) }
        val row = item("p:a:movie::tt1", "v1")
        queue.put(row)
        queue.put(item("p:a:movie::tt2", "v2"))
        queue.remove(row)

        assertEquals(listOf(1, 2, 1), seen)
    }

    @Test
    fun `snapshot is a copy, not a live view`() {
        val queue = OutboxQueue()
        queue.put(item("p:a:movie::tt1", "v1"))
        val snap = queue.snapshot()
        queue.put(item("p:a:movie::tt2", "v2"))
        assertEquals(1, snap.size)
        assertEquals(2, queue.size)
    }
}
