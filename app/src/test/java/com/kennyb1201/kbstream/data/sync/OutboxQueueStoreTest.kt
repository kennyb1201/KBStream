package com.kennyb1201.kbstream.data.sync

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Durable-outbox contract. The bug this pins: a write enqueued while offline
 * was lost the moment the process died, so `sync_outbox` (via [OutboxStore])
 * must mirror every mutation and be re-seeded on startup (never re-saved).
 */
class OutboxQueueStoreTest {

    private class RecordingStore : OutboxStore {
        val saved = mutableListOf<OutboxItem>()
        val deleted = mutableListOf<String>()
        var cleared = false

        override fun save(item: OutboxItem) {
            saved += item
        }

        override fun delete(ids: Collection<String>) {
            deleted += ids
        }

        override fun clear() {
            cleared = true
        }
    }

    private fun item(key: String, marker: String = "v", table: String = "sync_watched_status") =
        OutboxItem(
            table = table,
            keyColumn = "item_key",
            key = key,
            payload = buildJsonObject { put("marker", marker) },
            enqueuedAtMs = 1L
        )

    @Test
    fun `put persists the row`() {
        val store = RecordingStore()
        val queue = OutboxQueue(store)
        queue.put(item("p:a:tt1"))
        assertEquals(listOf("p:a:tt1"), store.saved.map { it.key })
    }

    @Test
    fun `remove deletes only the flushed row`() {
        val store = RecordingStore()
        val queue = OutboxQueue(store)
        val flushed = item("p:a:tt1")
        queue.put(flushed)
        // A newer write for the same key arrived while the flush was in flight.
        queue.put(item("p:a:tt1", "newer"))

        assertTrue(!queue.remove(flushed))
        assertTrue(store.deleted.isEmpty())

        assertTrue(queue.remove(queue.snapshot().single()))
        assertEquals(
            listOf(OutboxQueue.id("sync_watched_status", "item_key", "p:a:tt1")),
            store.deleted
        )
    }

    @Test
    fun `removeKeys and removeProfileScoped delete the matching ids`() {
        val store = RecordingStore()
        val queue = OutboxQueue(store)
        queue.put(item("p:a:tt1", table = "sync_watch_history"))
        queue.put(item("p:b:tt2", table = "sync_watch_history"))
        store.deleted.clear()

        queue.removeProfileScoped("sync_watch_history", "item_key", "a")
        assertEquals(
            listOf(OutboxQueue.id("sync_watch_history", "item_key", "p:a:tt1")),
            store.deleted
        )
    }

    @Test
    fun `seed rebuilds without re-persisting and never overwrites a live row`() {
        val store = RecordingStore()
        val queue = OutboxQueue(store)
        val persisted = item("p:a:tt1", "persisted")
        queue.put(item("p:a:tt1", "live"))
        store.saved.clear()

        queue.seed(listOf(persisted, item("p:a:tt2", "second")))

        // Nothing written back for either seeded row.
        assertTrue(store.saved.isEmpty())
        // The live row for the shared key wins over the persisted one.
        assertEquals(2, queue.size)
        assertEquals(
            "live",
            queue.snapshot().first { it.key == "p:a:tt1" }
                .payload["marker"].toString().trim('"')
        )
    }

    @Test
    fun `clear empties the queue and the store`() {
        val store = RecordingStore()
        val queue = OutboxQueue(store)
        queue.put(item("p:a:tt1"))

        queue.clear()

        assertTrue(queue.isEmpty)
        assertTrue(store.cleared)
    }
}
