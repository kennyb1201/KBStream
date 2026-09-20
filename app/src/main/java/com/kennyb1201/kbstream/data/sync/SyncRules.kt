package com.kennyb1201.kbstream.data.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure rules behind the sync layer, extracted so they can be unit tested
 * without Android, Room, or a live Supabase client.
 *
 * Everything here is deliberately side-effect free: the correctness of
 * profile isolation and of the poisoned-row cleanup is decided by these
 * functions, and the (unavailable in a plain JVM test) I/O is done by the
 * callers.
 */

/**
 * Profile-scoped row keys: "<stored>" is either the legacy bare key
 * ("movie::tt123") or the scoped form "p:<profileId>:movie::tt123".
 */
internal object SyncKeys {

    const val SCOPE_PREFIX = "p:"

    fun scoped(originalKey: String, profileId: String?): String {
        profileId ?: return originalKey
        return "$SCOPE_PREFIX$profileId:$originalKey"
    }

    fun unscoped(storedKey: String): String =
        if (storedKey.startsWith(SCOPE_PREFIX)) {
            storedKey.substringAfter(SCOPE_PREFIX).substringAfter(':')
        } else {
            storedKey
        }

    /** The profile id a stored key is scoped to, or null for legacy rows. */
    fun scopeOf(storedKey: String): String? {
        if (!storedKey.startsWith(SCOPE_PREFIX)) return null
        return storedKey.substringAfter(SCOPE_PREFIX).substringBefore(':').ifEmpty { null }
    }

    /**
     * True when [storedKey] belongs to [profileId]. A null [profileId] means
     * "no profile system yet" (legacy startup), which only matches legacy
     * unscoped rows.
     */
    fun matchesProfile(storedKey: String, profileId: String?): Boolean {
        profileId ?: return !storedKey.startsWith(SCOPE_PREFIX)
        return storedKey.startsWith("$SCOPE_PREFIX$profileId:")
    }
}

/**
 * Merge decision for every pulled row: the remote copy wins only when it is
 * strictly NEWER. Equal timestamps keep the local row, which is what stops a
 * pull from re-writing (and re-invalidating caches for) every row on every
 * sync.
 */
internal fun remoteWins(remoteUpdated: Long, localUpdated: Long): Boolean =
    remoteUpdated > localUpdated

/**
 * Detects rows that a mid-flight profile switch stamped under the WRONG
 * profile scope (the phantom watched markers / continue-watching cards).
 *
 * Signature: the same item exists under two profile scopes of one account
 * with the SAME payload — or the same payload timestamp. That is the
 * push-race fingerprint (one local write duplicated into a second scope).
 * Two profiles genuinely watching the same title produce different
 * timestamps, because they are different playback sessions.
 */
internal object PoisonDetector {

    /** Minimal view of a cloud row: its stored key plus the raw payload. */
    data class Row(val storedKey: String, val payload: JsonObject)

    /**
     * @param rows every row of the table for this account
     * @param profileOrder profile ids, oldest first (earliest createdAt)
     * @param localPids profiles that exist on THIS device — only those can
     *        have been poisoned locally, and a profile created on another
     *        device must never have its rows touched
     * @return stored keys whose rows should be deleted
     */
    fun crossScopeDuplicates(
        rows: List<Row>,
        profileOrder: List<String>,
        localPids: Set<String>
    ): List<String> {
        val byItem = LinkedHashMap<String, MutableList<Row>>()
        for (row in rows) {
            val pid = SyncKeys.scopeOf(row.storedKey) ?: continue // legacy: not attributable
            if (pid !in localPids) continue
            byItem.getOrPut(SyncKeys.unscoped(row.storedKey)) { mutableListOf() }.add(row)
        }

        val doomed = mutableListOf<String>()
        for ((_, group) in byItem) {
            if (group.size < 2) continue
            val owner = profileOrder
                .firstNotNullOfOrNull { pid ->
                    group.firstOrNull { SyncKeys.scopeOf(it.storedKey) == pid }
                } ?: continue
            for (row in group) {
                if (row === owner || row.storedKey == owner.storedKey) continue
                if (sameWrite(owner.payload, row.payload)) doomed.add(row.storedKey)
            }
        }
        return doomed
    }

    /**
     * True when two payloads came from the same local write: identical
     * content, or an identical `updatedAt` (payload fields can differ by app
     * version, the timestamp cannot by accident).
     */
    fun sameWrite(a: JsonObject, b: JsonObject): Boolean {
        if (a == b) return true
        val ta = timestampOf(a) ?: return false
        return ta == timestampOf(b)
    }

    fun timestampOf(payload: JsonObject): Long? =
        (payload["updatedAt"] as? JsonPrimitive)?.content?.toLongOrNull()

    /**
     * Watched-override sets that are an exact copy of an EARLIER profile's
     * set. Overrides sync as a full-replace blob, so the same race that
     * duplicated rows also copied one profile's whole set onto another. Only
     * exact duplicates of a non-empty set qualify — a genuinely different set
     * (the user marked different titles) is never touched.
     *
     * @param sets profile id to its override keys, oldest profile first
     * @return profile ids whose local set (and cloud blob) should be cleared
     */
    fun duplicateOverrideOwners(sets: List<Pair<String, Set<String>>>): List<String> {
        val duplicates = mutableListOf<String>()
        for (index in sets.indices) {
            val (_, keys) = sets[index]
            if (keys.isEmpty()) continue
            val copied = sets.take(index).any { (_, earlier) -> earlier == keys }
            if (copied) duplicates.add(sets[index].first)
        }
        return duplicates
    }
}
