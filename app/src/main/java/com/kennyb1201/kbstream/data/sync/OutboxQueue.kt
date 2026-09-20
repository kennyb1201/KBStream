package com.kennyb1201.kbstream.data.sync

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * One pending local write waiting to be pushed to the cloud.
 *
 * [enqueuedAtMs] is uploaded as the row's `updated_at` column so a retried
 * STALE flush can never carry a newer timestamp than a fresh row that was
 * enqueued and flushed later.
 */
internal data class OutboxItem(
    val table: String,
    val keyColumn: String,
    val key: String,
    val payload: JsonObject,
    val enqueuedAtMs: Long = System.currentTimeMillis()
)

/**
 * In-memory outbox with per-row coalescing: writes are keyed by
 * (table, key column, key), so writing the same title twice before a flush
 * leaves ONE row carrying the latest payload instead of uploading a stale
 * copy after a fresh one.
 *
 * [onChange] reports the pending count after every mutation, which is what
 * the Settings sync-health panel subscribes to. Kept free of Android and
 * Room so the coalescing contract is unit tested directly.
 */
internal class OutboxQueue(
    private val onChange: (Int) -> Unit = {}
) {

    private val rows = ConcurrentHashMap<String, OutboxItem>()

    val size: Int get() = rows.size

    val isEmpty: Boolean get() = rows.isEmpty()

    fun put(row: OutboxItem) {
        rows[id(row)] = row
        onChange(rows.size)
    }

    /**
     * Removes a row only if it is still the one that was flushed (compare
     * and remove): a write that landed while the flush was in flight must
     * survive, not be dropped by the finishing flush.
     */
    fun remove(row: OutboxItem): Boolean {
        val removed = rows.remove(id(row), row)
        if (removed) onChange(rows.size)
        return removed
    }

    fun snapshot(): List<OutboxItem> = rows.values.toList()

    fun id(row: OutboxItem): String = id(row.table, row.keyColumn, row.key)

    companion object {
        fun id(table: String, keyColumn: String, key: String): String =
            "$table|$keyColumn|$key"
    }
}
