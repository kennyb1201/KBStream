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
        val replaced = mutableListOf<Pair<List<String>, List<OutboxItem>>>()
        var cleared = false

        override fun save(item: OutboxItem) {
            saved += item
        }

        override fun delete(ids: Collection<String>) {
            deleted += ids
        }

        override fun replaceNow(
            removedIds: Collection<String>,
            items: List<OutboxItem>
        ): Boolean {
            replaced += removedIds.toList() to items
            return true
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
    fun `a delete hands its drop and its tombstone over as one awaited write`() {
        // The delete paths go through `replaceNow` instead of `removeKeys` +
        // `put`: the two fire-and-forget calls above are separate launches on a
        // pooled dispatcher, so the drop could land AFTER the tombstone it was
        // meant to precede - dropping the tombstone - and either could be lost
        // to process death in the window where the local row is already gone.
        val store = RecordingStore()
        val queue = OutboxQueue(store)
        queue.put(item("p:a:tt1", table = "sync_watch_history"))
        store.saved.clear()
        val tombstone = OutboxItem(
            table = "sync_watch_history",
            keyColumn = "item_key",
            key = "p:a:tt1",
            payload = buildJsonObject { put("deletedAt", 5L) },
            enqueuedAtMs = 5L
        )

        assertTrue(
            queue.replaceKeys(
                "sync_watch_history",
                "item_key",
                listOf("p:a:tt1"),
                listOf(tombstone)
            )
        )

        assertEquals(1, store.replaced.size)
        assertEquals(
            listOf(OutboxQueue.id("sync_watch_history", "item_key", "p:a:tt1")),
            store.replaced.single().first
        )
        assertEquals(listOf(tombstone), store.replaced.single().second)
        assertTrue(
            "the pair must not go out through the unordered calls",
            store.deleted.isEmpty() && store.saved.isEmpty()
        )
        assertEquals(
            "and the queue holds the tombstone, not the superseded write",
            listOf(tombstone),
            queue.snapshot()
        )
    }

    @Test
    fun `a profile wipe replaces that profile's writes in one awaited write`() {
        val store = RecordingStore()
        val queue = OutboxQueue(store)
        queue.put(item("p:a:tt1", table = "sync_watch_history"))
        queue.put(item("p:a:tt2", table = "sync_watch_history"))
        queue.put(item("p:b:tt3", table = "sync_watch_history"))
        store.saved.clear()
        val cleared = OutboxItem(
            table = "sync_watch_history",
            keyColumn = "item_key",
            key = "p:a:tt1",
            payload = buildJsonObject { put("deletedAt", 7L) },
            enqueuedAtMs = 7L
        )

        assertTrue(
            queue.replaceProfileScoped("sync_watch_history", "item_key", "a", listOf(cleared))
        )

        assertEquals(1, store.replaced.size)
        assertEquals(
            "only this profile's queued writes go",
            listOf(
                OutboxQueue.id("sync_watch_history", "item_key", "p:a:tt1"),
                OutboxQueue.id("sync_watch_history", "item_key", "p:a:tt2")
            ).sorted(),
            store.replaced.single().first.sorted()
        )
        assertTrue(store.deleted.isEmpty())
        assertEquals(
            "the sibling profile's write is untouched, and the clear took its place",
            listOf("p:a:tt1", "p:b:tt3"),
            queue.snapshot().map { it.key }.sorted()
        )
        assertTrue(queue.snapshot().contains(cleared))
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
