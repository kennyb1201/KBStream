package com.kennyb1201.kbstream.data.sync

import com.kennyb1201.kbstream.data.cache.WatchedStatusEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
 * Which profile a namespaced store resolves to.
 *
 * [storedActiveId] is the id the profiles store has persisted and [ids] is the
 * profile list in its stored order — the same pair (stored id, else first
 * profile) [ProfileManager.init] activates. Both halves must agree: there is a
 * real window where nothing is bound in memory yet (Application.onCreate
 * constructs profile-scoped singletons before MainActivity runs init), and in
 * that window a store that cannot name a profile falls back to the legacy
 * un-namespaced name — which is how one profile's data got read from, and
 * written into, the shared store instead of its own.
 */
internal object ProfileScopeRules {

    fun resolve(storedActiveId: String?, ids: List<String>): String? =
        ids.firstOrNull { it == storedActiveId } ?: ids.firstOrNull()
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
 * The history merge decision: the same newest-write-wins rule as [remoteWins],
 * plus a deterministic tie-break for an exact-millisecond collision.
 *
 * Equal stamps are vanishingly rare (two devices have to write the same row in
 * the same millisecond), but the old rule simply kept the local copy on a tie,
 * so the two devices each kept their own forever and never converged. When
 * both sides can hand in a [remoteToken]/[localToken] the larger one wins on
 * BOTH devices, so a tie resolves to one shared row instead of two diverging
 * ones. [HistoryTombstoneRules.mergeToken] supplies those tokens, and makes a
 * delete outrank a live row at the same stamp.
 *
 * A missing token (a row written by a build that predates this) falls back to
 * the old behaviour: keep the local row.
 */
internal fun historyRemoteWins(
    remoteUpdated: Long,
    remoteToken: String,
    localUpdated: Long,
    localToken: String?
): Boolean {
    if (remoteUpdated != localUpdated) return remoteUpdated > localUpdated
    if (localToken == null) return false
    return remoteToken > localToken
}

/**
 * Scope rule for the bulk push passes (history, watched markers, prefs).
 *
 * Every pass captures the ACTIVE profile id first and then reads rows or
 * builds blobs — both of which resolve the active profile AGAIN. A profile
 * switch in between made the DATA one profile's and the SCOPE another's. Two
 * reports came out of that: phantom watched markers on the profile you
 * switched to, and "my Simkl session / MDBList key followed me onto another
 * profile" — the tracker credentials of the profile you left, filed under the
 * profile you switched to, which then pushed its plays into the wrong
 * account.
 *
 * A pass may therefore only publish while the profile it captured is still
 * the active one. A switched-away pass is simply dropped: the incoming
 * profile's own push is triggered by the switch, and rows already enqueued
 * live carry their scope from enqueue time.
 */
internal object PushScopeRules {

    fun scopeStillActive(capturedProfileId: String?, activeProfileId: String?): Boolean =
        capturedProfileId == activeProfileId
}

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
 * The exact JSON payload written for a watch-history row.
 *
 * Extracted from [SupabaseSync.enqueueHistory] so the merge paths can rebuild
 * a local row's payload from its entity: the deterministic tie-break
 * ([historyRemoteWins]) compares those payloads, and a tie can only resolve to
 * the same winner on both devices if the rebuild is byte-for-byte the payload
 * the producing device pushed.
 */
internal object HistoryRowRules {

    const val UPDATED_AT_FIELD = "updatedAt"

    fun payload(entity: WatchHistoryEntity): JsonObject =
        buildJsonObject {
            put("id", entity.id)
            put("parentId", entity.parentId)
            put("type", entity.type)
            put("name", entity.name)
            entity.episodeTitle?.let { put("episodeTitle", it) }
            entity.overview?.let { put("overview", it) }
            entity.clearLogo?.let { put("clearLogo", it) }
            entity.backdropUrl?.let { put("backdropUrl", it) }
            entity.totalEpisodesInSeason?.let { put("totalEpisodesInSeason", it) }
            entity.poster?.let { put("poster", it) }
            entity.streamUrl?.let { put("streamUrl", it) }
            entity.season?.let { put("season", it) }
            entity.episode?.let { put("episode", it) }
            entity.episodeStreamId?.let { put("episodeStreamId", it) }
            put("positionMs", entity.positionMs)
            put("durationMs", entity.durationMs)
            put(UPDATED_AT_FIELD, entity.updatedAt)
            put("isCompleted", entity.isCompleted)
            entity.completedAt?.let { put("completedAt", it) }
        }
}

/**
 * Deletion tombstones for watch-history rows.
 *
 * A local delete used to hard-DELETE the cloud row (fire-and-forget). The
 * deletion was then invisible to the merge: a DELETED local row has no
 * timestamp, so any surviving cloud copy won [remoteWins] and came straight
 * back on the next pull or realtime event; a sibling device that still held the
 * row re-uploaded it on its next full push; and realtime dropped the DELETE
 * event entirely. That is the "deleted items resurrect" report.
 *
 * A tombstone makes the delete a first-class, NEWER write instead of the
 * absence of one. Wire shape: the SAME cloud row (same item_id), with its
 * payload replaced by a deletion marker carrying `deletedAt` (epoch ms) — and
 * [HistoryRowRules.UPDATED_AT_FIELD] set to that same instant, so every
 * existing last-write-wins comparison already treats the tombstone as the
 * newest write. No schema change is needed: the marker lives inside the JSON
 * payload, so it rides the outbox, the pull, and realtime unchanged — and a
 * tombstone arrives as an INSERT/UPDATE, which the realtime applier already
 * handles.
 */
internal object HistoryTombstoneRules {

    /** Payload field that marks a row as a deletion tombstone (epoch ms). */
    const val DELETED_AT_FIELD = "deletedAt"

    fun isTombstone(payload: JsonObject): Boolean = deletedAt(payload) != null

    /** Epoch ms the row was deleted at, or null for a live row. */
    fun deletedAt(payload: JsonObject): Long? =
        (payload[DELETED_AT_FIELD] as? JsonPrimitive)?.content?.toLongOrNull()

    /** The payload written in place of a deleted row. */
    fun tombstone(id: String, deletedAt: Long): JsonObject =
        buildJsonObject {
            put("id", id)
            put(DELETED_AT_FIELD, deletedAt)
            put(HistoryRowRules.UPDATED_AT_FIELD, deletedAt)
        }

    /**
     * Whether a pulled tombstone removes the local row. It carries its own
     * write stamp, so it only wins over a row at least as old — a resume
     * position recorded AFTER the delete (the same title played again) is
     * genuinely newer and must survive.
     */
    fun tombstoneWins(tombstoneUpdatedAt: Long, localUpdatedAt: Long): Boolean =
        remoteWins(tombstoneUpdatedAt, localUpdatedAt)

    /**
     * Whether this device may re-publish [localUpdatedAt] for a row whose cloud
     * copy is the tombstone written at [cloudDeletedAt]. Only a genuinely
     * NEWER local write may: re-uploading the row the user deleted is exactly
     * the resurrection being fixed.
     */
    fun shouldPublishOverTombstone(localUpdatedAt: Long, cloudDeletedAt: Long): Boolean =
        localUpdatedAt > cloudDeletedAt

    /**
     * The deterministic tie token for one history payload, compared only when
     * two writes carry the same `updatedAt` (see [historyRemoteWins]).
     *
     * A tombstone always outranks a live row at the same stamp: the token is
     * prefixed with `~` (0x7E), which sorts after the `{` a JSON payload
     * starts with, so an exact-millisecond collision can never resurrect a
     * deleted title. Otherwise the payload's own text is the token, which
     * every device computes identically for the same row, so both devices
     * pick the SAME winner and converge.
     */
    fun mergeToken(payload: JsonObject): String =
        deletedAt(payload)?.let { at -> "~tombstone:$at" } ?: payload.toString()
}

/**
 * Rules for the ACCOUNT-SCOPED staging of deletes made while SIGNED OUT (see
 * [SyncDeferredDeletes]).
 *
 * A delete made while signed out cannot be enqueued in the ordinary outbox —
 * that is cleared on sign-out so a queued write never lands under the wrong
 * account. It is staged instead, tagged with the account it belongs to, and
 * replayed only when THAT account signs back in. The account identity the app
 * already persists is the email, so it is normalized here (trimmed, lowercased)
 * so a case/whitespace difference between sign-in and the stored id cannot make
 * a delete replay under the wrong account or fail to replay under the right one.
 */
internal object DeferredDeleteRules {

    /** Normalized account id, or null when there is nothing to attribute to. */
    fun normalizeAccount(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** Stable identity of one staged delete (dedupe on re-stage). */
    fun id(accountId: String, table: String, keyColumn: String, key: String): String =
        "$accountId|$table|$keyColumn|$key"

    /**
     * True when a delete staged under [stagedAccountId] may replay because
     * [signingInAccount] is that same account. Both sides normalized; a blank
     * signing-in account replays nothing.
     */
    fun mayReplay(stagedAccountId: String, signingInAccount: String?): Boolean {
        val target = normalizeAccount(signingInAccount) ?: return false
        return normalizeAccount(stagedAccountId) == target
    }

    /**
     * Every identity one account is known by, normalized and de-duplicated:
     * the Supabase user id and the account email.
     *
     * A staged delete is tagged with whichever identity the device knew at the
     * time. The user id is preferred (it survives an email change) and is what
     * new stages carry, but an entry staged by a build that only had the email
     * must still replay - so matching is against the WHOLE set, not one string.
     * Without this, changing the account's email stranded every delete staged
     * under the old form: it never replayed, and the rows it was meant to
     * delete simply came back on the next pull (SD-5).
     */
    fun identities(accountId: String?, email: String?): Set<String> = buildSet {
        listOf(accountId, email).forEach { raw ->
            normalizeAccount(raw)?.let { add(it) }
        }
    }
}

/**
 * Publish/read rules for the watched-marker table (`sync_watched_status`),
 * the table behind the poster checkmark and the eye badge.
 *
 * Two halves were missing and each one alone breaks the eye badge:
 *  - the PUSH payload only carried [WATCHED_FIELD], so a row that this
 *    device had resolved as "started but not finished" traveled without its
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

    /**
     * True when the local marker may overwrite the account's copy.
     *
     * Publishing is conditional because a device only knows what it last
     * pulled: a TV still holding "started, unfinished" (t1) would otherwise
     * overwrite its sibling's "finished" (t2 > t1) in the cloud, and the two
     * would then ignore each other forever — the cloud row had regressed to the
     * older marker, so neither side saw anything newer to adopt. An absent
     * cloud row (fresh account, or this device pushing first) always publishes.
     */
    fun shouldPush(localUpdatedAt: Long, cloudUpdatedAt: Long?): Boolean =
        localUpdatedAt > (cloudUpdatedAt ?: 0L)

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
 * One remote marker row merged into the local cache: null when the remote copy
 * does not win (an older row, or a payload without a usable timestamp),
 * otherwise the row to store.
 *
 * This is the ONLY place a marker row is turned into a cached entity, shared by
 * the pull path and the realtime applier, so the two can never disagree about
 * which flags travel. Both do: a variant that read `isWatched` alone silently
 * dropped the eye badge on the receiving device (see [WatchedMarkerRules]).
 */
internal fun watchedMarkerRow(
    key: String,
    remote: JsonObject,
    localUpdatedAt: Long
): WatchedStatusEntity? {
    val remoteUpdated = remote["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
    if (!remoteWins(remoteUpdated, localUpdatedAt)) return null
    val markers = WatchedMarkerRules.read(remote)
    return WatchedStatusEntity(
        key = key,
        imdbId = remote["imdbId"]?.jsonPrimitive?.content ?: "",
        mediaType = remote["mediaType"]?.jsonPrimitive?.content ?: "movie",
        isWatched = markers.isWatched,
        isPartiallyWatched = markers.isPartiallyWatched,
        updatedAt = remoteUpdated
    )
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

    private fun fieldStr(payload: JsonObject, key: String): String? =
        (payload[key] as? JsonPrimitive)?.content

    private fun fieldLng(payload: JsonObject, key: String): Long? =
        fieldStr(payload, key)?.toLongOrNull()

    private fun fieldInt(payload: JsonObject, key: String): Int? =
        fieldStr(payload, key)?.toIntOrNull()

    private fun fieldBool(payload: JsonObject, key: String): Boolean? =
        fieldStr(payload, key)?.toBooleanStrictOrNull()

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
    ): List<String> = crossScopeDuplicates(rows, profileOrder, localPids, ::sameWrite)

    /**
     * Same scan with a caller-supplied identity rule. History rows pass
     * [sameHistoryWrite], which also pairs two payloads that describe the
     * SAME playback but carry different write stamps — the drift that left
     * those rows unpairable and kept resurrecting the phantom markers.
     */
    fun crossScopeDuplicates(
        rows: List<Row>,
        profileOrder: List<String>,
        localPids: Set<String>,
        matches: (JsonObject, JsonObject) -> Boolean
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
                if (matches(owner.payload, row.payload)) doomed.add(row.storedKey)
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
     * Identity rule for HISTORY rows: the strict [sameWrite] plus the case
     * that matters here — two rows describing the same playback (same
     * episode, same position, same duration, same completion) from the SAME
     * source. A copy carries its original's stream URL verbatim however many
     * times it has been re-stamped on the way, whereas two profiles watching
     * the same episode resolved their own stream and stopped at their own
     * position.
     */
    fun sameHistoryWrite(a: JsonObject, b: JsonObject): Boolean {
        if (sameWrite(a, b)) return true
        // Both sides are handed the same placeholder key: the item is already
        // equal by construction here, so only the playback is being compared.
        val wa = writeOf(ITEM_PLACEHOLDER, a) ?: return false
        val wb = writeOf(ITEM_PLACEHOLDER, b) ?: return false
        return samePlayback(wa, wb)
    }

    /** An item id that cannot collide with a real scoped key. */
    private const val ITEM_PLACEHOLDER = "item"

    /**
     * One watch-history write: the row id (item-based, so the same
     * title/episode has the same id in every profile), its write stamp, and
     * the playback it records.
     *
     * The playback fields are nullable so a payload written by an older app
     * version (or a legacy row) simply cannot content-match instead of
     * matching by accidental default.
     */
    data class LocalWrite(
        val id: String,
        val updatedAt: Long,
        val parentId: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val positionMs: Long? = null,
        val durationMs: Long? = null,
        val isCompleted: Boolean? = null,
        val streamUrl: String? = null
    )

    /** A cloud history row's payload as a comparable write. */
    fun writeOf(storedKey: String, payload: JsonObject): LocalWrite? =
        LocalWrite(
            id = SyncKeys.unscoped(storedKey),
            updatedAt = timestampOf(payload) ?: 0L,
            parentId = fieldStr(payload, "parentId"),
            season = fieldInt(payload, "season"),
            episode = fieldInt(payload, "episode"),
            positionMs = fieldLng(payload, "positionMs"),
            durationMs = fieldLng(payload, "durationMs"),
            isCompleted = fieldBool(payload, "isCompleted"),
            streamUrl = fieldStr(payload, "streamUrl")
        )

    /**
     * True when two rows are the same playback:
     *
     *  - the full playback content matches and both name the same source
     *    (stream URL), which no second viewing can reproduce by accident; or
     *  - the write stamps match — the original, stamp-only fingerprint.
     *
     * Deliberately NOT enough on its own: identical content with two
     * DIFFERENT sources, which is what two profiles genuinely playing the
     * same episode looks like.
     */
    fun samePlayback(a: LocalWrite, b: LocalWrite): Boolean {
        if (a.id != b.id) return false
        val contentMatches =
            a.parentId != null && a.parentId == b.parentId &&
                a.season != null && a.season == b.season &&
                a.episode != null && a.episode == b.episode &&
                a.positionMs != null && a.positionMs == b.positionMs &&
                a.durationMs != null && a.durationMs == b.durationMs &&
                a.isCompleted != null && a.isCompleted == b.isCompleted
        if (contentMatches) {
            val sa = a.streamUrl?.trim().orEmpty()
            val sb = b.streamUrl?.trim().orEmpty()
            if (sa.isNotEmpty() && sa == sb) return true
        }
        return a.updatedAt > 0L && a.updatedAt == b.updatedAt
    }

    /**
     * Local counterpart of [crossScopeDuplicates].
     *
     * The cloud pass can only pair scopes it can still SEE. The earlier sweep
     * deletes the poisoned CLOUD row on whichever device runs it first, so on
     * the other device the surviving local copy has no cloud twin left and is
     * never cleaned — and because the eye badge is re-derived from that local
     * resume row, clearing the derived caches doesn't help either: the phantom
     * markers come straight back. Comparing the profile databases ON THIS
     * DEVICE catches exactly that leftover.
     *
     * A row is doomed when a NEWER profile holds the same write as an OLDER
     * one: same row id AND same `updatedAt` (the same playback cannot land in
     * two profiles in the same millisecond). The oldest profile keeps its row,
     * and a genuinely separate watch of the same title on another profile
     * carries its own timestamp, so it is never touched.
     *
     * @param perProfile profile id to its local rows, oldest profile first
     * @return (profileId, rowId) pairs to delete
     */
    fun localHistoryDuplicates(
        perProfile: List<Pair<String, List<LocalWrite>>>
    ): List<Pair<String, String>> {
        // Grouped by ITEM, not by the exact write: the copy may have been
        // re-stamped on its way into the newer profile, which is precisely
        // why the stamp-only fingerprint missed it. [samePlayback] does the
        // deciding, so a re-stamped copy is still attributed while a genuine
        // second viewing (own position, own source) never is.
        val byId = LinkedHashMap<String, MutableList<Pair<String, LocalWrite>>>()
        for ((pid, rows) in perProfile) {
            for (row in rows) {
                byId.getOrPut(row.id) { mutableListOf() }.add(pid to row)
            }
        }

        val doomed = mutableListOf<Pair<String, String>>()
        for ((id, holders) in byId) {
            if (holders.size < 2) continue
            for (index in 1 until holders.size) {
                val (pid, row) = holders[index]
                // holders follow the caller's oldest-first order, so only an
                // EARLIER profile can own the write this row copied.
                val copied = holders.subList(0, index).any { (_, other) ->
                    samePlayback(other, row)
                }
                if (copied) doomed.add(pid to id)
            }
        }
        return doomed
    }

    /**
     * Local rows that are copies of an OLDER profile's CLOUD row — the same
     * write witnessed on one side by a local database row and on the other by
     * a cloud row.
     *
     * This is the [localHistoryDuplicates] blind spot. The race applied a bulk
     * pull's rows to whichever database the captured Room instance resolved to
     * after a switch, so profile A's pulled rows could land in profile B's
     * database without ever being written to A's — there is then no local row
     * to pair with, but A's copy is still in the cloud, because that is where
     * the pull read it from.
     *
     * @param cloudRows every history row for this account (scoped keys)
     * @param perProfile profile id to its local rows, oldest profile first
     * @param profileOrder profile ids, oldest first (earliest createdAt)
     * @return (profileId, rowId) pairs to delete — locally, and under that
     *         profile's scope in the cloud
     */
    fun localCopiesOfOlderScopes(
        cloudRows: List<Row>,
        perProfile: List<Pair<String, List<LocalWrite>>>,
        profileOrder: List<String>
    ): List<Pair<String, String>> {
        val rank = profileOrder.withIndex().associate { (index, pid) -> pid to index }

        val cloudById = HashMap<String, MutableList<Pair<Int, LocalWrite>>>()
        for (row in cloudRows) {
            val pid = SyncKeys.scopeOf(row.storedKey) ?: continue
            val write = writeOf(row.storedKey, row.payload) ?: continue
            cloudById.getOrPut(write.id) { mutableListOf() }
                .add((rank[pid] ?: Int.MAX_VALUE) to write)
        }

        val doomed = mutableListOf<Pair<String, String>>()
        for ((pid, rows) in perProfile) {
            val myRank = rank[pid] ?: continue
            for (row in rows) {
                val holders = cloudById[row.id] ?: continue
                // This profile's own cloud row is just this row, synced —
                // only a strictly OLDER profile owning the same playback
                // makes the local row a copy of it.
                val copied = holders.any { (heldRank, held) ->
                    heldRank < myRank && samePlayback(row, held)
                }
                if (copied) doomed.add(pid to row.id)
            }
        }
        return doomed
    }

    /**
     * The exact mirror of [localCopiesOfOlderScopes]: CLOUD rows that record
     * an OLDER profile's LOCAL row.
     *
     * This is the gap that survives every other rule. On the device that runs
     * the sweep second the poisoned cloud row is already gone (the first
     * device deleted it) AND the poisoned local row may be gone too, so the
     * newer profile is left holding nothing but a cloud row whose only
     * witness — the older profile's own row — is local. Without this, that
     * row re-downloads on the next pull and re-draws the phantom markers no
     * matter how many times the derived caches are wiped.
     *
     * @param cloudRows every history row for this account (scoped keys)
     * @param perProfile profile id to its local rows, oldest profile first
     * @param profileOrder profile ids, oldest first (earliest createdAt)
     * @return (profileId, rowId) pairs to delete — the cloud row under that
     *         profile's scope, and its local copy if one exists
     */
    fun cloudCopiesOfOlderLocalWitnesses(
        cloudRows: List<Row>,
        perProfile: List<Pair<String, List<LocalWrite>>>,
        profileOrder: List<String>
    ): List<Pair<String, String>> {
        val rank = profileOrder.withIndex().associate { (index, pid) -> pid to index }

        val localById = HashMap<String, MutableList<Pair<Int, LocalWrite>>>()
        for ((pid, rows) in perProfile) {
            val myRank = rank[pid] ?: continue
            for (row in rows) {
                localById.getOrPut(row.id) { mutableListOf() }.add(myRank to row)
            }
        }

        val doomed = mutableListOf<Pair<String, String>>()
        for (row in cloudRows) {
            val pid = SyncKeys.scopeOf(row.storedKey) ?: continue
            val myRank = rank[pid] ?: continue
            val write = writeOf(row.storedKey, row.payload) ?: continue
            val witnesses = localById[write.id] ?: continue
            val copied = witnesses.any { (heldRank, held) ->
                heldRank < myRank && samePlayback(held, write)
            }
            if (copied) doomed.add(pid to write.id)
        }
        return doomed
    }

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
    /**
     * Watched-marker rows that only existed because of a history row the
     * sweep just PROVED was a cross-profile copy, and that have no local
     * viewing evidence left under that profile.
     *
     * The eye badge renders from this marker row, and the marker row comes
     * back on every pull — so removing the copied history row is not enough
     * on its own: the marker that was derived from it has to go too, or the
     * phantom badge is re-drawn moments later and the cleanup looks like it
     * did nothing.
     *
     * Only ever a PARTIAL-ONLY marker (eye badge, no checkmark): a watched
     * marker is never touched, and a marker a tracker genuinely backs is
     * re-derived from that tracker and re-published, so this cannot take a
     * badge away from a profile that really started the show.
     *
     * @param watchedRows every watched-marker row for this account
     * @param orphanedParents profile id to the parent item ids whose last
     *        local evidence was just deleted
     * @param localPids profiles that exist on THIS device
     * @return stored keys to delete
     */
    fun orphanedPartialMarkers(
        watchedRows: List<Row>,
        orphanedParents: Map<String, Set<String>>,
        localPids: Set<String>
    ): List<String> {
        if (orphanedParents.isEmpty()) return emptyList()
        return watchedRows.mapNotNull { row ->
            val pid = SyncKeys.scopeOf(row.storedKey) ?: return@mapNotNull null
            if (pid !in localPids) return@mapNotNull null
            val parents = orphanedParents[pid] ?: return@mapNotNull null
            val imdb = fieldStr(row.payload, "imdbId")?.trim().orEmpty()
            if (imdb.isEmpty()) return@mapNotNull null
            if (parents.none { parentMatches(it, imdb) }) return@mapNotNull null
            val watched = fieldBool(row.payload, "isWatched") ?: false
            val partial = fieldBool(row.payload, "isPartiallyWatched") ?: false
            if (watched || !partial) return@mapNotNull null
            row.storedKey
        }
    }

    /**
     * EVERY eye-badge marker under [profileId]'s scope: a partial-only row
     * (started-but-unfinished with no completed flag).
     *
     * Deliberately attribution-FREE and scope-limited. The cleanup built on
     * [orphanedPartialMarkers] has to prove a row is a copy before touching it,
     * which leaves the one case nothing can prove — a phantom marker whose
     * twin was already deleted on another device, so no rule can pair it. This
     * is the list the user-triggered reset uses instead: it does not matter
     * WHICH of these are phantoms, because the user asked for the eye badges of
     * this profile to go away.
     */
    fun partialMarkersForScope(watchedRows: List<Row>, profileId: String): List<String> =
        watchedRows.mapNotNull { row ->
            if (!SyncKeys.matchesProfile(row.storedKey, profileId)) return@mapNotNull null
            val watched =
                fieldBool(row.payload, WatchedMarkerRules.WATCHED_FIELD) ?: false
            val partial =
                fieldBool(row.payload, WatchedMarkerRules.PARTIAL_FIELD) ?: false
            // A completed row is a checkmark, not an eye badge, and is never
            // part of this cleanup.
            if (watched || !partial) return@mapNotNull null
            row.storedKey
        }

    /**
     * True when a history row's parent id names the same title as a marker
     * row's imdb id. Tolerant on purpose: history rows store the parent as it
     * was resolved (often the bare IMDb id, sometimes prefixed), a mismatch
     * merely means the marker is left alone.
     */
    private fun parentMatches(parentId: String, imdb: String): Boolean {
        val parent = parentId.trim()
        if (parent.equals(imdb, ignoreCase = true)) return true
        if (parent.substringAfter("::", "").equals(imdb, ignoreCase = true)) return true
        if (parent.substringAfterLast(":").equals(imdb, ignoreCase = true)) return true
        return false
    }

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

/**
 * Merge rules for the addons sync blob (`sync_prefs` key "addons").
 *
 * That blob carries the whole `installed_addons_json`, which is where the
 * user's per-catalog settings live: the GLOBAL catalog order, each catalog's
 * display-name override, and each catalog's Home visibility. It used to be
 * WHOLE-BLOB last-write-wins with the timestamp stamped at PUSH time, and the
 * applier replaced the local list unconditionally. So a device that was not
 * set up like its sibling — a second TV signed in fresh, whose catalogs still
 * sat in the manifest's default order — re-published that default order with a
 * brand-new timestamp, and the next device to pull it silently adopted the
 * messed-up order. That is the "catalog naming/ordering/hiding doesn't sync,
 * and the second device pushed its order onto my good one" report.
 *
 * The fix follows the display-prefs pattern, lifted to the whole blob:
 *  - the published timestamp is when the CONFIGURATION last changed locally,
 *    not when it was pushed,
 *  - a device only publishes when its configuration is newer than the account
 *    copy it last adopted, so an untouched device never re-seeds the cloud,
 *  - the applier adopts the remote blob only when it is strictly newer than
 *    the local configuration, and demotes its own stamp to the adopted one so
 *    the same blob is not echoed straight back,
 *  - a device whose configuration has never been deliberately changed (only
 *    the built-in default catalogs, in their default order) has no opinion and
 *    is never allowed to claim an edit — this is what makes the guarantee
 *    "a fresh second device cannot overwrite a configured first device" hold
 *    even if it pushes before it has pulled.
 */
internal object AddonsConfigRules {

    /** Payload field holding when this device's configuration last changed. */
    const val CONFIG_EDITED_AT_FIELD = "configEditedAt"

    /** A remote configuration wins only when its edit is strictly newer. */
    fun remoteConfigWins(remoteEditedAt: Long, localEditedAt: Long): Boolean =
        remoteEditedAt > localEditedAt

    /** A device publishes only when its configuration out-runs the cloud copy. */
    fun shouldPublish(localEditedAt: Long, cloudEditedAt: Long): Boolean =
        localEditedAt > cloudEditedAt

    /**
     * One catalog's sync-relevant settings. [userHidden] is deliberately NOT
     * the raw Home visibility: many addons ship catalogs hidden by design
     * (director rails, search placeholders), so only a catalog the USER hid
     * away from its manifest default counts as a configuration.
     */
    data class Catalog(
        val type: String,
        val id: String,
        val order: Int,
        val customName: String?,
        val userHidden: Boolean
    )

    /**
     * One addon's sync-relevant settings. [customName] is the addon's own
     * display-name override — the sibling of [Catalog.customName], and just as
     * much a configuration.
     */
    data class Addon(
        val id: String,
        val enabled: Boolean,
        val catalogs: List<Catalog>,
        val customName: String? = null
    )

    /**
     * Stable fingerprint of the user-visible configuration. A write whose
     * fingerprint did not change is not an edit — which is how a manifest
     * refresh that only updates metadata (or a re-publish of an already
     * adopted blob) is prevented from claiming a newer timestamp.
     *
     * The ADDON list order is part of the fingerprint: the addons screen
     * renders the list in exactly this order and "move up/down" rewrites it,
     * and a rename is carried by [Addon.customName]. The order catalogs happen
     * to sit in inside one addon is NOT part of it, because a catalog carries
     * its global order explicitly ([Catalog.order]).
     *
     * That split is the point. Treating the addon list as unordered storage
     * meant a renamed or moved addon produced an unchanged fingerprint - no
     * fingerprint change, no edit stamp (see [shouldStampEdit]), so the next
     * pull from the account copy replaced the user's names and order and the
     * work had to be done again.
     */
    fun signature(addons: List<Addon>): String =
        addons.joinToString("\n") { addon ->
            val catalogs =
                addon.catalogs.sortedBy { it.order }.joinToString(",") { catalog ->
                    val type = catalog.type.lowercase()
                    "$type:${catalog.id}:${catalog.order}:" +
                        "${catalog.customName.orEmpty()}:${catalog.userHidden}"
                }
            "${addon.id}:${addon.enabled}:${addon.customName.orEmpty()}:$catalogs"
        }

    /**
     * True when a configuration write should claim a new edit timestamp.
     *
     * A change the USER made is an edit by definition, whatever it was
     * (renaming or moving a catalog, renaming or moving an addon, hiding a
     * catalog, enabling an addon), so it always stamps. [looksConfigured] is
     * only consulted for the rollout case - the first time a device is seen at
     * all - where the question is whether the configuration it is already
     * holding is deliberate or just the built-in defaults. That is what keeps
     * the guarantee "an untouched box can never out-stamp a configured one"
     * without also discarding a real edit the heuristics cannot see.
     */
    fun shouldStampEdit(
        previousSignature: String?,
        signature: String,
        userEdit: Boolean,
        configured: Boolean
    ): Boolean {
        if (previousSignature == signature) return false
        if (userEdit) return true
        return previousSignature == null && configured
    }

    /**
     * True when this device's configuration is deliberate: a catalog was
     * renamed, hidden from Home, or catalogs from different addons were
     * interleaved into a custom global order. A box that still holds only the
     * built-in defaults answers false and therefore publishes no edit stamp.
     *
     * Interleaving is the order signal: a default configuration keeps each
     * addon's catalogs in one contiguous run of the global order, so an addon
     * id appearing in two separate runs can only come from a user reorder.
     */
    fun looksConfigured(addons: List<Addon>): Boolean {
        if (addons.any { addon -> addon.customName != null }) return true

        if (addons.any { addon ->
                addon.catalogs.any { catalog ->
                    catalog.customName != null || catalog.userHidden
                }
            }
        ) {
            return true
        }

        val orderedAddonIds =
            addons.flatMap { addon -> addon.catalogs.map { addon.id to it.order } }
                .sortedBy { it.second }
                .map { it.first }
        if (orderedAddonIds.isEmpty()) return false

        var runs = 0
        var previous: String? = null
        orderedAddonIds.forEach { id ->
            if (id != previous) runs++
            previous = id
        }
        val addonsWithCatalogs =
            addons.filter { it.catalogs.isNotEmpty() }.map { it.id }.distinct().size
        return runs > addonsWithCatalogs
    }
}

/**
 * Publish/apply gate for the small opaque "things the user added to Home"
 * blobs: the imported collections list and the browse chips mirrored to Home.
 *
 * Both are FULL REPLACE on apply, so the account copy is whatever the last
 * writer pushed. That makes an empty local list dangerous — every device runs
 * the same periodic bulk push, so a TV that never imported a collection (or
 * mirrored a chip) would otherwise wipe the account's list for every other
 * device, which is exactly what "it doesn't sync" looks like from the
 * receiving end. A deliberate removal still propagates: it publishes through
 * the store's own save path, not through this gate.
 */
internal object HomeListBlobRules {

    /** Collections: publish only when this device actually has one imported. */
    fun shouldPublishCollections(urls: List<String>): Boolean = urls.isNotEmpty()

    /** Browse chips: publish only when this device actually has one mirrored. */
    fun shouldPublishBrowseShortcuts(shortcutsJson: String): Boolean =
        shortcutsJson.isNotBlank()

    /**
     * Built catalogs: publish only when this device actually has one built.
     *
     * Same full-replace reasoning as the chips above - and it matters more here,
     * because a catalog is a hand-composed rule set rather than one tap: one TV
     * that never opened the Catalog Builder must not erase the account's list.
     */
    fun shouldPublishCustomCatalogs(catalogsJson: String): Boolean =
        catalogsJson.isNotBlank()

    /**
     * Home order: publish only when this device has actually arranged a rail.
     *
     * Same reasoning as the other two full-replace blobs - the bulk push runs
     * on every device, so a TV that never touched its order would otherwise
     * publish an empty arrangement (stamped with its own push time) and erase
     * every sibling's rail order, which is what "the order never syncs" looks
     * like from the receiving end. A deliberate arrangement publishes through
     * [com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.save], not through here.
     */
    fun shouldPublishHomeOrder(homeOrderJson: String): Boolean =
        homeOrderJson.isNotBlank()

    /**
     * The `updatedAt` a full-replace Home blob publishes. The content's OWN
     * last-change/adoption time wins; only a blob with no stamp at all
     * (written by a build that predates the stamp) falls back to [now], so it
     * still publishes once and self-heals on the next pull.
     *
     * This is the fix for "the order never syncs": these blobs used to publish
     * `updatedAt = now` on every bulk push, so any device that merely opened
     * the app re-stamped its unchanged copy with a fresh time and won the
     * last-write-wins race against a sibling's real edit. Publishing the
     * content's own change time means an untouched device republishes the same
     * old stamp and cannot revert a newer arrangement or chip list.
     */
    fun publishStamp(contentChangedAt: Long, now: Long): Long =
        if (contentChangedAt > 0L) contentChangedAt else now

    /**
     * Whether a pulled blob may replace local state: yes when it carries no
     * stamp (a build that predates the guard), otherwise only when it is not
     * older than the last local edit or adoption.
     */
    fun shouldApply(remoteUpdated: Long?, localSyncedAt: Long): Boolean =
        remoteUpdated == null || remoteUpdated >= localSyncedAt
}

/**
 * Publish/apply rules for the IPTV source config (`sync_prefs` key
 * "iptv_config").
 *
 * The reported failure: "the guest profile's IPTV didn't sync, every other
 * profile does". A guest profile is exactly the profile a second device has
 * nothing set up for — and the IPTV blob was the last full-replace blob still
 * publishing `updatedAt = now` from every bulk push, with no gate.
 *
 * Two things went wrong together, and neither is visible on the device that
 * configured the playlist:
 *  - the unconfigured device re-stamped its EMPTY config with a brand-new time
 *    on every sign-in / Sync now, so its empty blob won last-write-wins in the
 *    cloud and the configured sibling's real row was replaced by it — and it
 *    re-won it on each of that device's pushes, because its own stamp was
 *    always the newest,
 *  - the applier stamped `iptv_synced_at` even when it applied nothing (a blank
 *    playlist_url is skipped by design), so the empty blob also blocked the
 *    real config from landing on any device that pulled it.
 *
 * Hence: the published stamp is the config's OWN last change (never the
 * push's), a device with no edit of its own publishes nothing, and a pulled
 * config has to be strictly newer than this device's own last edit to apply.
 * A deliberate removal — clearing the playlist — IS an edit, so it still
 * propagates; what can no longer propagate is an untouched device's emptiness.
 */
internal object IptvConfigRules {

    /**
     * Whether this device has an IPTV source at all: a playlist, or an extra
     * one. Deliberately not the guide/EPG URL or the favorites — those are
     * refinements of a source, and a device that only ever typed an EPG URL
     * has no source to share.
     */
    fun looksConfigured(playlistUrl: String, extraPlaylistUrls: List<String>): Boolean =
        playlistUrl.isNotBlank() || extraPlaylistUrls.any { it.isNotBlank() }

    /**
     * Whether a config write should claim a new edit timestamp.
     *
     * [previousSignature] is the config this device last OBSERVED (the tracker
     * the applier refreshes on an adoption), and null means "first sight" - the
     * first build after the upgrade, where only a config that looks deliberate
     * may claim one. A later difference is a real change, because the only
     * other writer is the applier, which sets the tracker to what it adopted.
     */
    fun shouldStampEdit(
        previousSignature: String?,
        signature: String,
        configured: Boolean
    ): Boolean {
        if (previousSignature == signature) return false
        if (previousSignature != null) return true
        return configured
    }

    /**
     * The `updatedAt` an IPTV blob publishes: the config's own edit time, and 0
     * when this device has never changed its config. A never-edited device's
     * blob is never published anyway (see [shouldPublish]), and 0 loses every
     * last-write-wins comparison, so it could not blank a sibling's config even
     * if a future caller published it.
     */
    fun publishStamp(editedAt: Long): Long = editedAt

    /**
     * Publish only when this device's config out-runs the cloud copy it last
     * adopted. An untouched device has editedAt 0 and cloudEditedAt >= 0, so it
     * publishes nothing: an empty config can no longer travel.
     */
    fun shouldPublish(localEditedAt: Long, cloudEditedAt: Long): Boolean =
        localEditedAt > cloudEditedAt

    /** A remote config wins only when its edit is strictly newer than ours. */
    fun remoteConfigWins(remoteEditedAt: Long, localEditedAt: Long): Boolean =
        remoteEditedAt > localEditedAt

    /**
     * Whether a pulled blob may be adopted.
     *
     * A blob with no stamp at all comes from a build that predates the stamp and
     * is applied as it always was; otherwise the remote edit must be strictly
     * newer than this profile's own last edit here. Deliberately NOT compared
     * against `iptv_synced_at` the way it used to be: that key was stamped even
     * when the apply wrote nothing, so an empty blob from another device could
     * block the real config from ever landing (see this object's doc).
     */
    fun shouldApply(remoteUpdated: Long?, localEditedAt: Long): Boolean =
        remoteUpdated == null || remoteConfigWins(remoteUpdated, localEditedAt)
}

/**
 * Merge rules for the service-credential blob (`sync_prefs` key "api_keys"):
 * the TorBox, OpenSubtitles and MDBList keys.
 *
 * Each one is an account-level credential the viewer pastes ONCE, and each is
 * PER-KEY state, so the rules are the display-prefs ones lifted to credentials:
 * a key travels with the time it was last edited, a device only publishes keys
 * it has an opinion about, and a pulled key is adopted only when its edit is at
 * least as new as this device's own.
 *
 * Why per key rather than one blob stamp: a viewer pastes the TorBox key on one
 * TV and the MDBList key on the other, and a whole-blob last-write-wins would
 * lose one of them. Why an edit stamp at all: the blanks are the dangerous
 * part — a device that has never had a key pasted must not publish its empties
 * over the account's copy (the failure mode the Simkl session blob had), while
 * a deliberate CLEAR must still propagate, and only a stamp tells those two
 * apart.
 */
internal object ApiKeySyncRules {

    const val KEYS_FIELD = "keys"
    const val TIMESTAMPS_FIELD = "timestamps"

    /** The blob's own stamp: the newest local key edit, or 0 for "none". */
    fun latestStamp(stamps: Collection<Long>): Long = stamps.maxOrNull() ?: 0L

    /** Publish only when this device has an edit newer than the copy it adopted. */
    fun shouldPublish(latestLocalEditAt: Long, cloudEditedAt: Long): Boolean =
        latestLocalEditAt > cloudEditedAt

    /**
     * Whether a remote key may replace the local one. A device that never
     * touched the key accepts anything (stamp 0); otherwise the remote edit must
     * be at least as new, so two devices that paste the same key converge on one
     * instead of trading writes forever.
     */
    fun remoteKeyWins(remoteEditedAt: Long, localEditedAt: Long): Boolean =
        remoteEditedAt >= localEditedAt
}
