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
    fun `removeKeys drops only the listed keys in that table and column`() {
        val queue = OutboxQueue()
        queue.put(item("p:a:tt1", "h1", table = "sync_watch_history", keyColumn = "item_id"))
        queue.put(item("p:a:tt2", "h2", table = "sync_watch_history", keyColumn = "item_id"))
        queue.put(item("p:a:tt1", "w1"))

        val removed = queue.removeKeys(
            "sync_watch_history",
            "item_id",
            listOf("p:a:tt1")
        )

        assertEquals(1, removed)
        assertEquals(2, queue.size)
        assertEquals(
            setOf("p:a:tt2", "p:a:tt1"),
            queue.snapshot().map { it.key }.toSet()
        )
        // The watched marker for the same raw id is untouched: it lives in a
        // different table.
        assertTrue(queue.snapshot().any { it.payload["marker"].toString().trim('"') == "w1" })
    }

    @Test
    fun `removeProfileScoped drops a profile's rows and leaves siblings alone`() {
        val queue = OutboxQueue()
        queue.put(item("p:a:movie::tt1", "a-history", table = "sync_watch_history", keyColumn = "item_id"))
        queue.put(item("p:a:tt1", "a-watched"))
        queue.put(item("p:b:movie::tt1", "b-history", table = "sync_watch_history", keyColumn = "item_id"))
        queue.put(item("p:a:display_prefs", "prefs", table = "sync_prefs", keyColumn = "pref_key"))

        val history = queue.removeProfileScoped("sync_watch_history", "item_id", "a")
        val watched = queue.removeProfileScoped("sync_watched_status", "item_key", "a")

        assertEquals(1, history)
        assertEquals(1, watched)
        // Profile b's history and profile a's prefs blob survive.
        assertEquals(
            setOf("p:b:movie::tt1", "p:a:display_prefs"),
            queue.snapshot().map { it.key }.toSet()
        )
    }

    @Test
    fun `removeProfileScoped with a null profile only matches legacy rows`() {
        val queue = OutboxQueue()
        queue.put(item("movie::tt1", "legacy", table = "sync_watch_history", keyColumn = "item_id"))
        queue.put(item("p:a:movie::tt1", "scoped", table = "sync_watch_history", keyColumn = "item_id"))

        val removed = queue.removeProfileScoped("sync_watch_history", "item_id", null)

        assertEquals(1, removed)
        assertEquals("p:a:movie::tt1", queue.snapshot().single().key)
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
