package com.kennyb1201.kbstream.data.library

import org.json.JSONObject

/**
 * One title My List has been told to forget, and when it was told.
 *
 * [key] is the store's own de-dupe key ([LocalLibraryStore.dedupeKey]), which is
 * what makes a tombstone address exactly the row that left. [removed] is false
 * for a CLEARANCE: a deliberate re-add has to travel the same way the removal
 * did, or the other device's tombstone would simply delete the title again on
 * the next pull.
 */
data class LibraryTombstone(
    val key: String,
    val at: Long,
    val removed: Boolean
)

/**
 * The My List removal record: the half of the library the cross-device sync was
 * missing.
 *
 * The library travels as ONE blob per profile and the applier replaces it
 * wholesale, guarded by a timestamp - so a device whose list is older (or whose
 * TorBox marks were reset, letting a round re-add the whole cloud) can push a
 * removed title back onto the device that removed it. Each device is locally
 * correct and together they undo each other: the field replayed exactly that,
 * 81 stamped titles removed, 81 offered again, then 413 added in one round.
 *
 * A tombstone is the fact that was never written down: this title was removed
 * HERE, on purpose. It travels beside the list rather than inside it, merges as
 * a UNION (never a whole-blob replacement), and is consulted by the pull and by
 * every sync round - so a re-processed cloud cannot re-impose a removal, on any
 * device, whatever its processed marks say.
 *
 * Identity is the store's de-dupe key, but matching a title against the record
 * compares the ids the key carries rather than the key string: one device's row
 * can name a title by both ids while a round's candidate knows only TMDB's, and
 * those are the same title. Type plus one shared id is the same rule the library
 * already uses for "this is already in My List" ([LocalLibraryStore.matches]).
 */
internal object LibraryTombstones {

    /** Where the record lives, beside the lists, in the library's own prefs. */
    const val PREFS_KEY = "my_list_tombstones"

    /** The payload field the record travels in (see `SyncPrefsPayload`). */
    const val PAYLOAD_KEY = PREFS_KEY

    /**
     * How many keys the record keeps, newest first.
     *
     * The field's whole contamination is a few hundred titles, and a removal is
     * a small record. The cap is here for the same reason every other store has
     * one: nothing that grows per removal may grow without end.
     */
    const val MAX_ENTRIES = 2_000

    private const val AT = "at"
    private const val REMOVED = "removed"

    /** The record as the JSON string the library prefs hold. */
    fun encode(record: Map<String, LibraryTombstone>): String {
        val root = JSONObject()
        record.values.forEach { entry ->
            root.put(
                entry.key,
                JSONObject()
                    .put(AT, entry.at)
                    .put(REMOVED, entry.removed)
            )
        }
        return root.toString()
    }

    /**
     * The record a stored string holds.
     *
     * Defensive by construction: a missing, torn or foreign blob reads as an
     * EMPTY record rather than throwing, and a record that will not parse must
     * never take a pull down with it.
     */
    fun decode(raw: String?): Map<String, LibraryTombstone> {
        if (raw.isNullOrBlank()) return emptyMap()
        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, LibraryTombstone>()
        parsed.keys().forEach { key ->
            if (key.isBlank()) return@forEach
            val obj = parsed.optJSONObject(key) ?: return@forEach
            out[key] = LibraryTombstone(
                key = key,
                at = obj.optLong(AT, 0L),
                removed = obj.optBoolean(REMOVED, true)
            )
        }
        return cap(out)
    }

    /**
     * The union of two records: newest timestamp per key wins, then a clearance
     * outranks a removal of the SAME title filed under another key.
     *
     * The second rule is what keeps a deliberate re-add honest across devices. A
     * title's row on one device can carry both ids while the other device's row
     * (or the round that re-adds it) knows only TMDB's - two keys, one title - so
     * clearing only the exact key it was added under would leave the sibling
     * tombstone free to delete it again. A clearance is the viewer's word, and it
     * speaks for the title rather than for one spelling of its key.
     */
    fun merge(
        local: Map<String, LibraryTombstone>,
        incoming: Map<String, LibraryTombstone>
    ): Map<String, LibraryTombstone> {
        if (incoming.isEmpty()) return cap(local)
        val byKey = LinkedHashMap<String, LibraryTombstone>(local)
        incoming.values.forEach { entry ->
            val current = byKey[entry.key]
            if (current == null || entry.at >= current.at) byKey[entry.key] = entry
        }

        val clearances = byKey.values.filter { !it.removed }
        if (clearances.isNotEmpty()) {
            byKey.entries.removeAll { (key, entry) ->
                entry.removed && clearances.any { clearance ->
                    clearance.at > entry.at && keysShareTitle(clearance.key, key)
                }
            }
        }
        return cap(byKey)
    }

    /** [record] with only its newest [MAX_ENTRIES] keys. */
    fun cap(record: Map<String, LibraryTombstone>): Map<String, LibraryTombstone> {
        if (record.size <= MAX_ENTRIES) return record
        return record.values
            .sortedByDescending { it.at }
            .take(MAX_ENTRIES)
            .associateByTo(LinkedHashMap()) { it.key }
    }

    /**
     * Whether [record] says the title named by these ids was removed on purpose.
     *
     * Only removals count: a clearance is the title being welcome again.
     */
    fun isRemoved(
        record: Map<String, LibraryTombstone>,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?
    ): Boolean {
        if (record.isEmpty()) return false
        return record.values.any { entry ->
            entry.removed && matches(entry.key, mediaType, imdbId, tmdbId)
        }
    }

    /**
     * Whether the de-dupe key [key] names the title these ids name: same
     * normalized media type, plus at least one shared id.
     */
    fun matches(
        key: String,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?
    ): Boolean {
        val parsed = parse(key) ?: return false
        if (parsed.first != LocalLibraryStore.normalizedType(mediaType)) return false

        val wantedImdb = imdbId
            ?.trim()
            ?.removePrefix("tmdb:")
            ?.takeIf { it.isNotBlank() }
        if (parsed.second != null && wantedImdb != null &&
            parsed.second.equals(wantedImdb, ignoreCase = true)
        ) {
            return true
        }

        val wantedTmdb = tmdbId?.takeIf { it > 0 }
        return parsed.third != null && wantedTmdb != null && parsed.third == wantedTmdb
    }

    /** [items] without the titles [record] says were removed on purpose. */
    fun removedFrom(
        items: List<LibraryItem>,
        record: Map<String, LibraryTombstone>
    ): List<LibraryItem> {
        if (record.isEmpty() || items.isEmpty()) return items
        return items.filterNot { item ->
            isRemoved(record, item.mediaType, item.imdbId, item.tmdbId)
        }
    }

    /**
     * Whether two keys name the same title (see [matches]): one parsed key
     * against another, for the merge's clearance rule.
     */
    private fun keysShareTitle(a: String, b: String): Boolean {
        val left = parse(a) ?: return false
        val right = parse(b) ?: return false
        if (left.first != right.first) return false
        val leftImdb = left.second
        val rightImdb = right.second
        if (leftImdb != null && rightImdb != null && leftImdb.equals(rightImdb, ignoreCase = true)) {
            return true
        }
        return left.third != null && right.third != null && left.third == right.third
    }

    /**
     * A de-dupe key as (type, imdb-or-null, tmdb-or-null).
     *
     * The key's own spelling, produced by [LocalLibraryStore.dedupeKey]: the
     * normalized type, the IMDb id (or `-`), and the TMDB number (or `-`). A key
     * from a future/older shape that does not split into three parts is not
     * matched at all, which is the safe direction - an unreadable tombstone must
     * not delete anything.
     */
    private fun parse(key: String): Triple<String, String?, Int?>? {
        val bits = key.split(':', limit = 3)
        if (bits.size != 3) return null
        val type = bits[0].trim()
        if (type.isEmpty()) return null
        val imdb = bits[1]
            .trim()
            .removePrefix("tmdb:")
            .takeIf { it.isNotBlank() && it != "-" }
        val tmdb = bits[2].trim().toIntOrNull()?.takeIf { it > 0 }
        return Triple(type, imdb, tmdb)
    }
}
