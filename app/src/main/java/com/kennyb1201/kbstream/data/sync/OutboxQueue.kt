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
 * Durable mirror of an [OutboxQueue] mutation.
 *
 * The in-memory queue is what flushes; this is what makes a queued write
 * SURVIVE process death — a write enqueued while offline used to be lost the
 * moment the app was closed, so the cloud never saw it (see the sync layer's
 * outbox documentation). Implemented by the sync object against a Room table
 * and run off the main thread; kept as an interface so [OutboxQueue] itself
 * stays free of Android and Room and can be unit tested directly.
 */
internal interface OutboxStore {
    /** Persist [item], replacing any stored row for the same queue id. */
    fun save(item: OutboxItem)

    /** Drop the stored rows for [ids] (already removed from the in-memory queue). */
    fun delete(ids: Collection<String>)

    /** Drop EVERY stored row (sign-out: nothing may survive into another account). */
    fun clear()

    /**
     * ONE ordered, awaited durable write for a DELETE: the rows for
     * [removedIds] go away and [items] land in their place, in that order,
     * before this returns.
     *
     * [save] and [delete] are fire-and-forget on purpose - an ordinary queued
     * write that is lost only costs a re-push - but a delete has no second
     * chance: the local row is already gone, so the tombstone IS the repair.
     * The two calls above are also issued as separate launches on a pooled
     * dispatcher, which makes them unordered against each other: a delete whose
     * [delete] landed after its [save] dropped the tombstone it had just
     * stored, and one that lost the process before either landed left the
     * superseded row behind for the next startup to hydrate and flush - which
     * is how a deleted title comes back on the next pull. So a delete waits for
     * its write, and the drop and the tombstone share one lane.
     *
     * Returns whether the write is known to have landed. This is a user-visible
     * delete, so a false is a real failure, logged where this is implemented.
     */
    fun replaceNow(removedIds: Collection<String>, items: List<OutboxItem>): Boolean
}

/**
 * In-memory outbox with per-row coalescing: writes are keyed by
 * (table, key column, key), so writing the same title twice before a flush
 * leaves ONE row carrying the latest payload instead of uploading a stale
 * copy after a fresh one.
 *
 * [onChange] reports the pending count after every mutation, which is what
 * the Settings sync-health panel subscribes to. [store], when present,
 * persists every mutation so the queue is rebuilt after a restart. It is
 * declared FIRST so a trailing lambda still binds to [onChange].
 */
internal class OutboxQueue(
    private val store: OutboxStore? = null,
    private val onChange: (Int) -> Unit = {}
) {

    private val rows = ConcurrentHashMap<String, OutboxItem>()

    val size: Int get() = rows.size

    // Call the function explicitly: `rows.isEmpty` read as a synthetic property
    // is the shape newer Kotlin compilers reject when the getter is Kotlin's own
    // (kotlin.collections.Map.isEmpty), not Java's.
    val isEmpty: Boolean get() = rows.isEmpty()

    fun put(row: OutboxItem) {
        rows[id(row)] = row
        store?.save(row)
        onChange(rows.size)
    }

    /**
     * Removes a row only if it is still the one that was flushed (compare
     * and remove): a write that landed while the flush was in flight must
     * survive, not be dropped by the finishing flush.
     */
    fun remove(row: OutboxItem): Boolean {
        val removed = rows.remove(id(row), row)
        if (removed) {
            store?.delete(listOf(id(row)))
            onChange(rows.size)
        }
        return removed
    }

    /**
     * Drops every pending write for [keys] in [table]/[keyColumn].
     *
     * Used when a local row is DELETED rather than rewritten: the cloud needs
     * that deletion, so a queued upload of the same key must not be flushed
     * afterwards and resurrect the row. Unlike [remove] this is unconditional
     * — there is no "newer write" to protect, the row is going away.
     *
     * Returns how many pending writes went.
     */
    fun removeKeys(table: String, keyColumn: String, keys: Collection<String>): Int {
        var removed = 0
        val dropped = mutableListOf<String>()
        keys.forEach { key ->
            val rowId = id(table, keyColumn, key)
            if (rows.remove(rowId) != null) {
                dropped += rowId
                removed++
            }
        }
        if (removed > 0) {
            store?.delete(dropped)
            onChange(rows.size)
        }
        return removed
    }

    /**
     * Drops every pending write in [table]/[keyColumn] that belongs to
     * [profileId].
     *
     * The Settings "Clear Continue Watching" reset uses this: a queued
     * progress write for the profile would otherwise flush AFTER the reset and
     * push the very row the user just cleared, which the next pull restores.
     * Matched with the same profile-scope rule the sync layer uses, so a
     * sibling profile's pending writes are never touched.
     *
     * A wipe that publishes tombstones uses [replaceProfileScoped] instead:
     * dropping alone leaves the durable row for the next startup to hydrate and
     * flush. This is the drop-only form, for a caller with nothing to write in
     * its place.
     *
     * Returns how many pending writes went.
     */
    fun removeProfileScoped(
        table: String,
        keyColumn: String,
        profileId: String?
    ): Int {
        var removed = 0
        val dropped = mutableListOf<String>()
        rows.values.forEach { row ->
            if (
                row.table == table &&
                row.keyColumn == keyColumn &&
                SyncKeys.matchesProfile(row.key, profileId)
            ) {
                if (rows.remove(id(row), row)) {
                    dropped += id(row)
                    removed++
                }
            }
        }
        if (removed > 0) {
            store?.delete(dropped)
            onChange(rows.size)
        }
        return removed
    }

    /**
     * The tombstone/delete form of [removeKeys] + [put]: the pending writes for
     * [keys] go, [items] take their place, and the two reach the durable table
     * as ONE ordered write the caller waits for (see [OutboxStore.replaceNow]).
     *
     * Used by every path that turns a local delete into a cloud tombstone, so
     * the pair can no longer be reordered against each other or lost in the
     * window between the local delete and the write that repairs it.
     */
    fun replaceKeys(
        table: String,
        keyColumn: String,
        keys: Collection<String>,
        items: List<OutboxItem>
    ): Boolean {
        val dropped = mutableListOf<String>()
        keys.forEach { key ->
            val rowId = id(table, keyColumn, key)
            if (rows.remove(rowId) != null) dropped += rowId
        }
        return replace(dropped, items)
    }

    /**
     * [replaceKeys] for a profile-wide wipe (the Settings "Clear Continue
     * Watching" reset): every pending write in [table]/[keyColumn] belonging to
     * [profileId] goes, [items] take their place, and the pair is one awaited
     * write. Matched with the same profile-scope rule [removeProfileScoped]
     * uses, so a sibling profile's pending writes are never touched.
     */
    fun replaceProfileScoped(
        table: String,
        keyColumn: String,
        profileId: String?,
        items: List<OutboxItem>
    ): Boolean {
        val dropped = mutableListOf<String>()
        rows.values.forEach { row ->
            if (
                row.table == table &&
                row.keyColumn == keyColumn &&
                SyncKeys.matchesProfile(row.key, profileId) &&
                rows.remove(id(row), row)
            ) {
                dropped += id(row)
            }
        }
        return replace(dropped, items)
    }

    /**
     * Installs [items] in memory and hands [removedIds] and [items] to the
     * store as one awaited write - never as a [save]/[delete] pair. True when
     * the write landed, which is also the answer when there is no store to
     * write to.
     */
    private fun replace(removedIds: List<String>, items: List<OutboxItem>): Boolean {
        items.forEach { row -> rows[id(row)] = row }
        onChange(rows.size)
        return store?.replaceNow(removedIds, items) ?: true
    }

    /**
     * Drops every pending write, in memory and on disk.
     *
     * Used on sign-out: a queued write belongs to the account being LEFT, and
     * flushing it after a different account signs in would push it under that
     * account. The local Room state is already written, so a same-account
     * sign-in recovers it through the normal full push.
     */
    fun clear() {
        rows.clear()
        store?.clear()
        onChange(rows.size)
    }

    /**
     * Re-adds persisted rows on startup. Deliberately does NOT call [store]:
     * these came FROM the store, so re-saving them would be a pointless write,
     * and a row already present in memory is left alone (it is either the same
     * pending write or a newer one).
     */
    fun seed(items: Collection<OutboxItem>) {
        if (items.isEmpty()) return
        var added = false
        items.forEach { row ->
            if (rows.putIfAbsent(id(row), row) == null) added = true
        }
        if (added) onChange(rows.size)
    }

    fun snapshot(): List<OutboxItem> = rows.values.toList()

    /**
     * The pending write for [table]/[keyColumn]/[key], or null when nothing is
     * queued for it. Used by the pull to spot a delete that is queued but not
     * yet flushed (see [SupabaseSync.pullHistory]): its tombstone outranks a
     * still-live cloud copy, so the pull must not re-insert the row first.
     */
    fun pending(table: String, keyColumn: String, key: String): OutboxItem? =
        rows[id(table, keyColumn, key)]

    fun id(row: OutboxItem): String = id(row.table, row.keyColumn, row.key)

    companion object {
        fun id(table: String, keyColumn: String, key: String): String =
            "$table|$keyColumn|$key"
    }
}
