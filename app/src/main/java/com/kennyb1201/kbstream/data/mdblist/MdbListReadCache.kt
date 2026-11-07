package com.kennyb1201.kbstream.data.mdblist

/**
 * A small in-memory TTL cache for the client's list reads.
 *
 * Pinned MDBList rails re-read their list on every Home entry with no cache at
 * all: one request per load for a list under 1,000 items, one per page above
 * that, on every trip through Home. The public-list directories have had
 * `PUBLIC_LISTS_TTL_MS` + a cache for exactly this reason; the account's own
 * lists (a pinned rail, the Library's rows, the picker, the reconcile) had
 * nothing.
 *
 * In-memory only, matching the public-directory cache: a process restart
 * re-downloads, which the watched snapshot's disk layer already accepts
 * elsewhere in the client.
 *
 * Three rules are baked in rather than left to each caller:
 *
 *  - an entry older than [ttlMs] is a miss and is dropped, so a caller cannot
 *    accidentally serve a stale list by forgetting the age check;
 *  - [put] takes a NON-NULL value only - a failed walk is not an answer, and
 *    the client distinguishes "no answer" (null) from "this list is empty",
 *    which the reconcile reads as "these lists are gone";
 *  - [invalidate] is per key, so a write drops the one entry it changed and
 *    leaves every other list's read alone.
 *
 * [now] is injectable so the age rules are testable without a clock.
 */
internal class MdbListReadCache<V>(
    private val ttlMs: Long,
    private val now: () -> Long = System::currentTimeMillis
) {

    private class Entry<V>(val value: V, val atMs: Long)

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry<V>>()

    /** The entry's value while it is fresh, or null (a miss). */
    fun get(key: String): V? {
        val entry = entries[key] ?: return null
        if (now() - entry.atMs > ttlMs) {
            entries.remove(key, entry)
            return null
        }
        return entry.value
    }

    /** Stores an answer that a later read inside [ttlMs] should be served. */
    fun put(key: String, value: V) {
        entries[key] = Entry(value, now())
    }

    /** Drops the named entries; every other list keeps its read. */
    fun invalidate(vararg keys: String) {
        keys.forEach { entries.remove(it) }
    }

    /** Entries held, for diagnostics and tests. */
    fun size(): Int = entries.size
}
