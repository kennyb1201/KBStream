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
 * Publish/apply rules for the Simkl session blob.
 *
 * The blob is account-wide state written as a FULL REPLACE, and the applier
 * treats a blank token as "the user signed out". Those two halves were not
 * aware of each other: EVERY device published the blob, so a device that had
 * simply never connected Simkl published an empty token — and the moment its
 * push won the race (pressing "Sync now" pushes before it pulls), it erased
 * the account's Simkl session on every device. That is the "Simkl sign-in
 * didn't sync" report: the other TV wiped it instead of adopting it.
 *
 * The fix is one rule per side:
 *  - a device only PUBLISHES the blob when it has a token to share, or a
 *    deliberate disconnect to propagate (a fresh device publishes nothing),
 *  - a blank token only CLEARS the local session when it carries the explicit
 *    sign-out tombstone, so a blank row can never wipe a live session.
 */
internal object SimklAuthRules {

    /** Payload field marking a blank token as a deliberate sign-out. */
    const val SIGNED_OUT_FIELD = "signed_out"

    fun shouldPublish(accessToken: String?, signedOut: Boolean): Boolean =
        !accessToken.isNullOrBlank() || signedOut

    fun clearsSession(accessToken: String?, signedOut: Boolean): Boolean =
        accessToken.isNullOrBlank() && signedOut
}

/**
 * Merge rules for the display-prefs blob.
 *
 * That blob used to be WHOLE-BLOB last-write-wins with a timestamp stamped at
 * PUSH time, while every device pushed all of its synced keys. So a device
 * whose only change was an unrelated toggle re-published its own (older) copy
 * of every other key with a brand-new timestamp — and the next device to pull
 * it reverted the other TV's newer language / subtitle / badge edits. That is
 * the "language and subtitles didn't sync" report, and it is also how the
 * eye-badge toggle could switch itself off on a device nobody had touched.
 *
 * Per-key edit timestamps fix it: the blob carries the time each key last
 * CHANGED locally, and the applier merges key by key instead of wholesale.
 */
internal object DisplayPrefsRules {

    /** Field holding the per-key edit timestamps inside the blob. */
    const val TIMESTAMPS_FIELD = "timestamps"

    const val UPDATED_AT_FIELD = "updatedAt"

    /** A remote key wins only when its edit is at least as new as the local one. */
    fun remoteWins(remoteTs: Long, localTs: Long): Boolean =
        remoteTs >= localTs

    /**
     * Timestamps after observing [current] values, given the [previous]
     * observation. Only keys whose value actually CHANGED are stamped, so an
     * unrelated edit cannot re-publish — and thereby clobber — another
     * device's newer edits to the same blob.
     *
     * [hadSnapshot] is false on the first build after an upgrade: with no
     * local edit history there is nothing to claim, so nothing is stamped and
     * the cloud's timestamps stay authoritative.
     */
    fun stampChanged(
        previous: Map<String, String>,
        current: Map<String, String>,
        timestamps: Map<String, Long>,
        now: Long,
        hadSnapshot: Boolean
    ): Map<String, Long> {
        if (!hadSnapshot) return timestamps

        val updated =
            timestamps.toMutableMap()

        current.forEach { (key, value) ->
            if (previous[key] != value) {
                updated[key] = now
            }
        }

        // A key that vanished locally (pref cleared) is an edit too, unless
        // it was already empty.
        previous.forEach { (key, value) ->
            if (key !in current && value.isNotEmpty()) {
                updated[key] = now
            }
        }

        return updated
    }

    /**
     * Blob-level timestamp: the newest local edit, or [now] when the device
     * has no edit history yet. Older builds only understand this field, so it
     * must stay meaningful ("when did anything here last change?" — NOT "when
     * did I last push?").
     */
    fun blobUpdatedAt(
        timestamps: Map<String, Long>,
        now: Long
    ): Long =
        timestamps.values.maxOrNull() ?: now
}

/**
 * Publish/read rules for the watched-marker table (`sync_watched_status`),
 * the table behind the poster checkmark and the eye badge.
 *
 * Two halves were missing and each one alone breaks the eye badge:
 *  - the PUSH payload only carried [WATCHED_FIELD], so a row that this
 *    device had resolved as "started but not finished" travelled without its
 *    eye flag and arrived looking like a plain un-watched title, and
 *  - the PULL/apply paths built the entity without the flag at all, so the
 *    default (false) landed in the receiving profile's cache — where the
 *    merge treats that row's timestamp as fresh and stops re-resolving the
 *    key for the whole cache TTL. One sync was enough to hide the eye badge
 *    on the other TV for hours, which is the "no eye badges here, checkmarks
 *    there" report.
 *
 * [shouldPublish] then keeps the table deliberate: the bulk push streams
 * EVERY local row, and the overwhelming majority are this device's DERIVED
 * "nothing watched here" answers (one per preloaded rail item). Publishing
 * those stamps a brand-new timestamp on the same key in the cloud, so a
 * sibling device's correct checkmark/eye badge loses the last-write-wins
 * race to a negative it never asked about. Only rows carrying a marker are
 * published; a deliberate "Mark as Unwatched" still syncs, because
 * WatchedStatusRepository enqueues its tombstone at the moment of the action
 * rather than relying on this bulk pass.
 */
internal object WatchedMarkerRules {

    const val WATCHED_FIELD = "isWatched"
    const val PARTIAL_FIELD = "isPartiallyWatched"

    /** Both badge flags of one row. */
    data class Markers(
        val isWatched: Boolean,
        val isPartiallyWatched: Boolean
    )

    /** True when this row is a marker worth sending to the account's devices. */
    fun shouldPublish(isWatched: Boolean, isPartiallyWatched: Boolean): Boolean =
        isWatched || isPartiallyWatched

    /** The exact payload written for one entity (field names are wire format). */
    fun payload(
        key: String,
        imdbId: String,
        mediaType: String,
        isWatched: Boolean,
        isPartiallyWatched: Boolean,
        updatedAt: Long
    ): JsonObject =
        JsonObject(
            mapOf(
                "key" to JsonPrimitive(key),
                "imdbId" to JsonPrimitive(imdbId),
                "mediaType" to JsonPrimitive(mediaType),
                WATCHED_FIELD to JsonPrimitive(isWatched),
                PARTIAL_FIELD to JsonPrimitive(isPartiallyWatched),
                "updatedAt" to JsonPrimitive(updatedAt)
            )
        )

    /**
     * Reads both flags back out of a row payload. A row written by an older
     * build has no [PARTIAL_FIELD] and reads as false — the same thing those
     * builds meant, and the next resolution of that key repaints it.
     */
    fun read(payload: JsonObject): Markers =
        Markers(
            isWatched = flag(payload, WATCHED_FIELD),
            isPartiallyWatched = flag(payload, PARTIAL_FIELD)
        )

    private fun flag(payload: JsonObject, key: String): Boolean =
        (payload[key] as? JsonPrimitive)
            ?.content
            ?.toBooleanStrictOrNull()
            ?: false
}

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
