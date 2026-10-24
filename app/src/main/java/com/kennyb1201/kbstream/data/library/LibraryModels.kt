package com.kennyb1201.kbstream.data.library

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.data.watched.WatchedStatusKeys
import org.json.JSONArray
import org.json.JSONObject

/**
 * Who put a row in this profile's LOCAL My List.
 *
 * Every local row used to be anonymous, which made the TorBox cloud→Library
 * sync's writes indistinguishable from the viewer's own: turning the sync off
 * could not offer to take them back, and the one-time cleanup of an older
 * build's contamination had no way to name what it was cleaning. The marker is
 * additive on the stored JSON and stored only when it is NOT manual, so every
 * library written before this existed parses exactly as before and means
 * "manual".
 *
 * It is deliberately not part of the de-dupe identity: a title that is already
 * on the list by hand keeps its manual mark when a sync round matches it, which
 * is what makes "manual adds are never touched" true.
 */
enum class LibraryOrigin(val wire: String) {
    /** The viewer added it (or it came from a pre-marker build). */
    MANUAL("manual"),

    /** The TorBox cloud library sync added it (see TorBoxLibrarySync). */
    TORBOX_SYNC("torbox-sync")
}

/** Which tracker a Library row came from. */
enum class LibrarySource(val label: String) {
    LOCAL("This device"),
    SIMKL_WATCHLIST("Simkl"),
    SIMKL_LIST("Simkl list"),
    MDBLIST_WATCHLIST("MDBList"),
    MDBLIST_LIST("MDBList list"),
    TRAKT_WATCHLIST("Trakt"),
    TRAKT_LIST("Trakt list"),
    LOCAL_LIST("This device · list");

    /** True for a list this device owns and can rename/delete. */
    val isLocalList: Boolean get() = this == LOCAL_LIST
}

/**
 * The rail id for a Trakt personal list.
 *
 * [LibraryList.id] is an Int (MDBList ids are positive, local ids negative
 * from their creation time), and Trakt's own id is an Int too — but a Trakt id
 * and an MDBList id can be the same number. Trakt rows therefore live in the
 * one range no local id can reach (`Int.MIN_VALUE` … `Int.MIN_VALUE + n`), and
 * callers test [LibraryList.remoteId] / the source rather than the sign.
 */
fun traktListShelfId(traktId: Int): Int = Int.MIN_VALUE + traktId.coerceAtLeast(1)

/**
 * One row in the Library tab. A row is either a whole title (movie or
 * show) or — for personal lists — an entry inside one of those lists.
 */
data class LibraryItem(
    val source: LibrarySource,
    val mediaType: String,
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val simklId: Int? = null,
    /**
     * Simkl's own slug for the title, when the read carried one. Only used to
     * make the per-item attribution link read (see [simklItemUrl]); the id is
     * what identifies the page.
     */
    val simklSlug: String? = null,
    val listId: Int? = null,
    val listName: String? = null,
    /**
     * The remote list's own id, for the lists that are not addressed by
     * [listId] (Trakt's slugs/ids are strings). Null on every other row.
     */
    val listRemoteId: String? = null,
    /**
     * Who put this row on the profile's local list. [LibraryOrigin.MANUAL] for
     * everything the viewer did and for every row written before the marker
     * existed (see [LibraryOrigin]); the TorBox sync's own adds are the only
     * other value.
     */
    val origin: LibraryOrigin = LibraryOrigin.MANUAL
) {
    val navigationId: String?
        get() = imdbId ?: tmdbId?.let { "tmdb:$it" }

    /**
     * Stable identity used for badge lookups and remote mirroring: the
     * TMDB id when known, otherwise the IMDB id. Row dedupe across
     * trackers still goes through [LocalLibraryStore.dedupeKey].
     */
    val badgeId: Int?
        get() = tmdbId

    /** Key in the shared watched-badge sets ("type::imdb"). */
    fun watchedKey(): String? {
        val type = when (mediaType.lowercase()) {
            "tv", "series" -> "series"
            else -> "movie"
        }
        return imdbId?.takeIf { it.isNotBlank() }?.let { "$type::$it" }
    }

    /**
     * Every key this row can be watched under, in badge order: the IMDB
     * flavor first, then the raw `tmdb:<n>` one.
     *
     * A row a tracker sent with only a TMDB id — or whose TMDB→IMDB
     * resolution never answered — is watched under the second form, and the
     * Library's enrichment adds exactly that key to the badge sets. The grid
     * and the UNWATCHED filter therefore have to test the row against BOTH, or
     * a title watched under the flavor that needs no resolution stays bare
     * here while it badges on every other screen (see [WatchedStatusKeys]).
     */
    fun watchedKeyCandidates(): List<String> {
        val type = when (mediaType.lowercase()) {
            "tv", "series" -> "series"
            else -> "movie"
        }
        return listOfNotNull(
            imdbId?.takeIf { it.isNotBlank() }?.let { "$type::$it" },
            tmdbId?.takeIf { it > 0 }?.let { "$type::${WatchedStatusKeys.tmdbKeyId(it)}" }
        )
    }
}

/**
 * A user-created personal list on MDBList (or a local list stored on
 * this profile). Personal lists group titles the user chose to keep.
 */
data class LibraryList(
    val id: Int,
    val name: String,
    val itemCount: Int,
    val source: LibrarySource,
    /** Trakt's own list id/slug; null for local and MDBList lists. */
    val remoteId: String? = null
) {
    /** Where the list lives, for the rail's source line. */
    val locationLabel: String
        get() = when (source) {
            LibrarySource.LOCAL_LIST -> "This device"
            LibrarySource.MDBLIST_LIST -> "MDBList"
            LibrarySource.TRAKT_LIST -> "Trakt"
            else -> source.label
        }
}

/**
 * Profile-scoped local library: the on-device "My List" (works with no
 * account connected) plus local personal lists. Stored as JSON in a
 * SharedPreferences store namespaced to the active profile via
 * [ProfileStorage.prefsName], matching every other
 * profile-scoped store.
 */
object LocalLibraryStore {

    private const val TAG = "LIBRARY_STORE"
    private const val BASE_NAME = "kbstream_library"
    private const val KEY_MY_LIST = "my_list"
    private const val KEY_LISTS = "personal_lists"

    /**
     * The last copy of each blob that PARSED, per profile store.
     *
     * A blob that will not parse (a schema drift from a sideloaded newer/older
     * build, a torn write) used to read as an EMPTY library, and the next
     * add/remove persisted that emptiness and pushed it to the other devices - 
     * the AddonManager blob-wipe shape, one store over. Reads now fall back to
     * this instead of to nothing, and every read-modify-write REFUSES to write
     * when it could not read (see [readListForWrite]). The map only ever holds
     * data that really parsed, so a fallback can never invent a title.
     */
    private val lastGood = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun cacheKey(context: Context, key: String): String =
        "${ProfileStorage.prefsName(context, BASE_NAME)}::$key"

    private fun prefs(context: Context) =
        context.getSharedPreferences(
            ProfileStorage.prefsName(context, BASE_NAME),
            Context.MODE_PRIVATE
        )

    private fun entryJson(item: LibraryItem): JSONObject = JSONObject()
        .put("mediaType", item.mediaType)
        .put("title", item.title)
        .put("year", item.year ?: -1)
        .put("posterUrl", cleanLibraryPoster(item.posterUrl) ?: "")
        .put("imdbId", item.imdbId ?: "")
        .put("tmdbId", item.tmdbId ?: -1)
        // ADDITIVE: only the non-default marker is written. An absent key is
        // what every library written before this existed holds, and it means
        // manual - so an older build reading a newer blob still parses it, and
        // this build reads an older one unchanged.
        .apply {
            if (item.origin != LibraryOrigin.MANUAL) put("origin", item.origin.wire)
        }

    private fun entryFromJson(obj: JSONObject): LibraryItem? {
        val mediaType = obj.optString("mediaType", "movie")
        val title = obj.optString("title", "").ifBlank { return null }
        val imdbId = obj.optString("imdbId", "").ifBlank { null }
        val tmdbId = obj.optInt("tmdbId", -1).takeIf { it > 0 }
        if (imdbId == null && tmdbId == null) return null
        // An unknown marker (a newer build's origin, or a half-written blob) is
        // read as MANUAL: the safe direction, because the only thing a marker
        // unlocks is removal.
        val origin = when (obj.optString("origin", "")) {
            LibraryOrigin.TORBOX_SYNC.wire -> LibraryOrigin.TORBOX_SYNC
            else -> LibraryOrigin.MANUAL
        }
        return LibraryItem(
            source = LibrarySource.LOCAL,
            mediaType = mediaType,
            title = title,
            year = obj.optInt("year", -1).takeIf { it > 0 },
            posterUrl = cleanLibraryPoster(obj.optString("posterUrl", "")),
            imdbId = imdbId,
            tmdbId = tmdbId,
            origin = origin
        )
    }

    /**
     * The list for display: the parsed blob, or - when the blob will not parse -
     * the last copy that did, so one bad read cannot blank the Library on screen.
     */
    @Suppress("UNCHECKED_CAST")
    private fun readList(context: Context, key: String): MutableList<LibraryItem> =
        (readListOrNull(context, key)
            ?: lastGood[cacheKey(context, key)] as? List<LibraryItem>
            ?: emptyList()).toMutableList()

    /**
     * The list a READ-MODIFY-WRITE may start from: null when the stored blob
     * exists and cannot be parsed, which is a refusal to write rather than an
     * empty library to add to.
     */
    private fun readListForWrite(context: Context, key: String): MutableList<LibraryItem>? =
        readListOrNull(context, key)

    private fun readListOrNull(context: Context, key: String): MutableList<LibraryItem>? {
        val raw = prefs(context).getString(key, null) ?: return mutableListOf()
        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(::entryFromJson)
            }
        }.getOrNull()
        if (parsed == null) {
            Log.w(
                TAG,
                "library blob '$key' will not parse; serving the last good copy and " +
                    "refusing to write over it"
            )
            return null
        }
        lastGood[cacheKey(context, key)] = parsed
        return parsed.toMutableList()
    }

    private fun writeList(context: Context, key: String, items: List<LibraryItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(entryJson(it)) }
        prefs(context).edit().putString(key, arr.toString()).apply()
        pushLibrarySync(context)
    }

    /**
     * Cross-device sync: every local library write stamps the write time
     * (so a pull with an older remote blob is rejected — offline edits
     * survive) and enqueues a push (a no-op outbox entry when signed out).
     */
    private fun pushLibrarySync(context: Context) {
        prefs(context).edit()
            .putLong("library_synced_at", System.currentTimeMillis())
            .apply()
        com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
            context,
            com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_LIBRARY,
            com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildLibrary(context)
        )
    }

    /**
     * De-dup key: type + whichever id pair the entry carries. Type aliases
     * collapse (tv/series; tmdb: prefixed imdb keys) so entries saved from
     * one screen still match lookups from another that normalized the
     * media type differently.
     */
    fun dedupeKey(item: LibraryItem): String {
        val type = when (item.mediaType.lowercase()) {
            "tv", "series" -> "series"
            else -> "movie"
        }
        val imdb = item.imdbId?.trim()
            ?.removePrefix("tmdb:")
            ?.takeIf { it.isNotBlank() }
            ?: "-"
        return "$type:$imdb:${item.tmdbId ?: "-"}"
    }

    /**
     * De-dup key for any (mediaType, imdb, tmdb) triple — same rule as
     * [dedupeKey] without needing a full [LibraryItem], so screens can
     * test membership and remote mirrors can remove by id.
     */
    fun dedupeKeyOf(mediaType: String, imdbId: String?, tmdbId: Int?): String {
        val type = when (mediaType.lowercase()) {
            "tv", "series" -> "series"
            else -> "movie"
        }
        val imdb = imdbId?.trim()
            ?.removePrefix("tmdb:")
            ?.takeIf { it.isNotBlank() }
            ?: "-"
        return "$type:$imdb:${tmdbId ?: "-"}"
    }

    /** Normalized media type every library comparison uses. */
    fun normalizedType(mediaType: String): String = when (mediaType.lowercase()) {
        "tv", "series" -> "series"
        else -> "movie"
    }

    private fun normalizedImdb(imdbId: String?): String? =
        imdbId?.trim()
            ?.removePrefix("tmdb:")
            ?.takeIf { it.isNotBlank() }

    /**
     * True when [item] and the (mediaType, imdbId, tmdbId) triple name the
     * same title: same normalized media type plus at least one shared id.
     *
     * The exact-triple compare ([dedupeKey]) only matches when BOTH sides
     * carry the same ids. A title saved with both ids — the resolved IMDB id
     * plus its TMDB id — therefore never matched a long-press lookup that
     * only knows the TMDB id: the menu kept offering "Add to Library" for a
     * title already on the list, the picker showed no ✓, and re-adding a
     * title could not be told apart from adding it for the first time.
     * Matching on either id is what those callers mean by "this title".
     */
    fun matches(
        item: LibraryItem,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?
    ): Boolean {
        if (normalizedType(item.mediaType) != normalizedType(mediaType)) return false
        val aImdb = normalizedImdb(item.imdbId)
        val bImdb = normalizedImdb(imdbId)
        if (aImdb != null && bImdb != null && aImdb.equals(bImdb, ignoreCase = true)) {
            return true
        }
        val aTmdb = item.tmdbId?.takeIf { it > 0 }
        val bTmdb = tmdbId?.takeIf { it > 0 }
        return aTmdb != null && bTmdb != null && aTmdb == bTmdb
    }

    fun isInMyList(context: Context, mediaType: String, imdbId: String?, tmdbId: Int?): Boolean =
        readList(context, KEY_MY_LIST).any { matches(it, mediaType, imdbId, tmdbId) }

    // Every mutator below is a read-modify-write over the same SharedPreferences
    // blob (or the id-keyed lists map). Without a lock, two near-simultaneous
    // adds/removes from different entry points each read the same base and the
    // last apply() wins, silently dropping one entry (LS-P2-7). @Synchronized on
    // the singleton serialises them; it is reentrant, so a public entry point
    // calling a private helper is fine.
    @Synchronized
    fun addToMyList(
        context: Context,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?,
        title: String,
        year: Int?,
        posterUrl: String?,
        origin: LibraryOrigin = LibraryOrigin.MANUAL
    ): Boolean {
        return addToListInternal(
            context = context,
            storageKey = KEY_MY_LIST,
            mediaType = mediaType,
            imdbId = imdbId,
            tmdbId = tmdbId,
            title = title,
            year = year,
            posterUrl = posterUrl,
            source = LibrarySource.LOCAL,
            origin = origin
        )
    }

    /**
     * How many rows on THIS profile's My List the TorBox sync put there.
     *
     * What the untoggle question counts. Manual adds are never included, and
     * neither is anything a pre-marker build wrote - which is the honest answer
     * (the app cannot attribute those), and the reason the one-time remediation
     * exists instead of making this number guess.
     */
    fun torboxSyncedCount(context: Context): Int =
        myList(context).count { it.origin == LibraryOrigin.TORBOX_SYNC }

    /**
     * Removes every torbox-sync row from this profile's My List in ONE write,
     * and reports how many that was. The untoggle question's "yes".
     *
     * Every row that leaves is TOMBSTONED first: the cross-device sync replaces
     * the whole list, so a removal that is not written down as a removal is a
     * removal another device can undo with an older copy of the list (see
     * [LibraryTombstones]).
     */
    @Synchronized
    fun removeTorboxSynced(context: Context): Int {
        val items = readListForWrite(context, KEY_MY_LIST) ?: return 0
        val leaving = items.filter { it.origin == LibraryOrigin.TORBOX_SYNC }
        val kept = items.filterNot { it.origin == LibraryOrigin.TORBOX_SYNC }
        val removed = items.size - kept.size
        if (removed > 0) {
            markTombstoned(context, leaving)
            writeList(context, KEY_MY_LIST, kept)
        }
        return removed
    }

    /**
     * Removes exactly [entries] from this profile's My List, matching on the
     * store's own de-dupe key so the caller can name rows it read (the one-time
     * remediation) without guessing indices.
     *
     * The removed rows are tombstoned, for the reason [removeTorboxSynced]
     * gives: the cleanup's half of the field's leak is only half the fix if the
     * next device re-adds what it just removed.
     */
    @Synchronized
    fun removeMyListEntries(context: Context, entries: List<LibraryItem>): Int {
        if (entries.isEmpty()) return 0
        val doomed = entries.map { dedupeKey(it) }.toSet()
        val items = readListForWrite(context, KEY_MY_LIST) ?: return 0
        val leaving = items.filter { dedupeKey(it) in doomed }
        val kept = items.filterNot { dedupeKey(it) in doomed }
        val removed = items.size - kept.size
        if (removed > 0) {
            markTombstoned(context, leaving)
            writeList(context, KEY_MY_LIST, kept)
        }
        return removed
    }

    // ------------------------------------------------------------------
    // My List tombstones — the removals that have to survive the sync.
    // ------------------------------------------------------------------

    /**
     * This profile's removal record (or another profile's, addressed directly
     * for a pull - see [mergePulledTombstones]).
     */
    fun tombstones(
        context: Context,
        profileId: String? = null
    ): Map<String, LibraryTombstone> =
        LibraryTombstones.decode(
            libraryPrefs(context, profileId).getString(LibraryTombstones.PREFS_KEY, null)
        )

    /**
     * Records [entries] as removed on purpose, and pushes the record.
     *
     * Called by the two removal paths that answer "the sync put this here and it
     * does not belong" - the cleanup and the untoggle question - so what they
     * remove stays removed on every device rather than until the next pull.
     */
    @Synchronized
    fun markTombstoned(
        context: Context,
        entries: List<LibraryItem>,
        at: Long = System.currentTimeMillis()
    ): Int {
        if (entries.isEmpty()) return 0
        val record = tombstones(context).toMutableMap()
        entries.forEach { entry ->
            val key = dedupeKey(entry)
            record[key] = LibraryTombstone(key = key, at = at, removed = true)
        }
        writeTombstones(context, profileId = null, record = LibraryTombstones.cap(record))
        return entries.size
    }

    /**
     * Records a DELIBERATE re-add: this title is welcome again.
     *
     * Written as a clearance entry rather than by deleting the removal, because
     * the record travels by the newest timestamp per key - a deletion would be
     * undone by the other device's older tombstone on the very next merge, and
     * the title would vanish again. Clears every key that names this title, for
     * the reason [LibraryTombstones.merge] gives.
     */
    @Synchronized
    fun clearTombstonesFor(
        context: Context,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?,
        at: Long = System.currentTimeMillis()
    ) {
        val record = tombstones(context)
        if (record.isEmpty()) return
        val hits = record.keys.filter { key ->
            LibraryTombstones.matches(key, mediaType, imdbId, tmdbId)
        }
        if (hits.isEmpty()) return
        val updated = record.toMutableMap()
        hits.forEach { key -> updated[key] = LibraryTombstone(key, at, removed = false) }
        writeTombstones(context, profileId = null, record = LibraryTombstones.cap(updated))
    }

    /**
     * Merges a PULLED record into [profileId]'s own, and answers the merged one.
     *
     * Addressed by profile rather than by the ambient store: the applier may be
     * writing a sibling profile's library, and the tombstone merge has to land in
     * that profile's file. No push - a pull is not the local device's edit, and
     * the applier already owns the timestamp that decides the next one.
     */
    @Synchronized
    fun mergePulledTombstones(
        context: Context,
        profileId: String?,
        incoming: Map<String, LibraryTombstone>
    ): Map<String, LibraryTombstone> {
        val local = tombstones(context, profileId)
        val merged = LibraryTombstones.merge(local, incoming)
        if (merged != local) {
            writeTombstones(context, profileId = profileId, record = merged, push = false)
        }
        return merged
    }

    /** [profileId]'s raw My List blob, as stored, or null when there is none. */
    fun myListBlob(context: Context, profileId: String? = null): String? =
        libraryPrefs(context, profileId).getString(KEY_MY_LIST, null)

    /**
     * [raw] with every identity-tombstoned row removed, in the store's own
     * encoding.
     *
     * A blob that does not parse is handed back UNCHANGED: a pull must never
     * turn an unreadable library into an empty one (the shape [lastGood] exists
     * for), and an empty answer here would do exactly that.
     */
    fun withoutTombstonedRows(
        raw: String,
        record: Map<String, LibraryTombstone>
    ): String {
        if (record.isEmpty()) return raw
        val rows = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::entryFromJson) }
        }.getOrNull() ?: return raw
        val kept = LibraryTombstones.removedFrom(rows, record)
        if (kept.size == rows.size) return raw
        val arr = JSONArray()
        kept.forEach { arr.put(entryJson(it)) }
        return arr.toString()
    }

    private fun libraryPrefs(context: Context, profileId: String?): android.content.SharedPreferences {
        val id = profileId?.trim().orEmpty()
        if (id.isEmpty()) return prefs(context)
        return context.applicationContext.getSharedPreferences(
            ProfileStorage.prefsName(id, BASE_NAME),
            Context.MODE_PRIVATE
        )
    }

    private fun writeTombstones(
        context: Context,
        profileId: String?,
        record: Map<String, LibraryTombstone>,
        push: Boolean = true
    ) {
        libraryPrefs(context, profileId).edit()
            .putString(LibraryTombstones.PREFS_KEY, LibraryTombstones.encode(record))
            .apply()
        // Only this device's own edit is news to the other devices. A pull - and
        // a sibling profile's file, which only a pull writes - must not enqueue a
        // push of the ambient profile's library.
        if (push && profileId == null) pushLibrarySync(context)
    }

    @Synchronized
    fun removeFromMyList(context: Context, mediaType: String, imdbId: String?, tmdbId: Int?) {
        removeFromListInternal(context, KEY_MY_LIST, mediaType, imdbId, tmdbId)
    }

    fun myList(context: Context): List<LibraryItem> = readList(context, KEY_MY_LIST)

    /**
     * Somebody else's My List, read-only, without switching profiles.
     *
     * The one caller is the TorBox cloud→Library cleanup
     * ([com.kennyb1201.kbstream.data.debrid.TorBoxLibrarySync]): the pre-fix sync
     * mirrored one shared cloud into every profile it ran under, so a leaked row
     * sits in the sibling's list too - and that copy is the evidence the cleanup
     * has for a row no profile has ever watched.
     *
     * Addresses that profile's file directly, the same way the poison sweep does,
     * because the ambient store would otherwise resolve the ACTIVE profile and
     * answer about the wrong library. Nothing here writes, and the [lastGood]
     * fallback is deliberately not used: a blob that will not parse answers "no
     * copy here", which leaves the row alone. The other direction - serving a
     * remembered copy of someone else's list as evidence to delete with - is the
     * one a read-only witness must not take.
     */
    fun myListForProfile(context: Context, profileId: String): List<LibraryItem> {
        val id = profileId.trim()
        if (id.isEmpty()) return emptyList()

        val appContext = context.applicationContext
        val raw = appContext.getSharedPreferences(
            ProfileStorage.prefsName(id, BASE_NAME),
            Context.MODE_PRIVATE
        ).getString(KEY_MY_LIST, null) ?: return emptyList()

        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(::entryFromJson)
            }
        }.getOrNull()
        if (parsed == null) {
            Log.w(TAG, "profile '$id' library blob will not parse; read as no copy")
            return emptyList()
        }
        return parsed
    }

    // ------------------------------------------------------------------
    // Local personal lists — profile-scoped, work with no MDBList key.
    // Stored as a JSON map of listId -> { name, items[] } so ids stay
    // stable for re-entry after a delete (fresh ids are time-based) and
    // items reuse the same [LibraryItem] encoding as My List.
    // ------------------------------------------------------------------

    /**
     * Local lists use string ids namespaced "local:<n>", but
     * [LibraryList.id] is an Int (MDBList ids). To keep one list-id
     * space, local lists get deterministic negative ids derived from
     * their creation order — stable per profile, never colliding with
     * positive MDBList ids.
     */
    private fun localListId(createdAt: Long): Int =
        -(((createdAt % Int.MAX_VALUE.toLong()) + 1L).toInt())

    private data class StoredList(
        val id: Int,
        val name: String,
        val createdAt: Long,
        val items: MutableList<LibraryItem>
    )

    /** The personal lists for display: the parsed blob or the last good copy. */
    @Suppress("UNCHECKED_CAST")
    private fun readLists(context: Context): MutableList<StoredList> =
        (readListsOrNull(context)
            ?: lastGood[cacheKey(context, KEY_LISTS)] as? List<StoredList>
            ?: emptyList()).toMutableList()

    /** The lists a read-modify-write may start from: null when the blob is bad. */
    private fun readListsForWrite(context: Context): MutableList<StoredList>? =
        readListsOrNull(context)

    private fun readListsOrNull(context: Context): MutableList<StoredList>? {
        val raw = prefs(context).getString(KEY_LISTS, null) ?: return mutableListOf()
        val parsed = runCatching {
            val root = JSONObject(raw)
            val out = mutableListOf<StoredList>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = root.optJSONObject(key) ?: continue
                val name = obj.optString("name", "")
                if (name.isBlank()) continue
                val createdAt = obj.optLong("createdAt", 0L)
                val itemsArr = obj.optJSONArray("items")
                val items = mutableListOf<LibraryItem>()
                if (itemsArr != null) {
                    for (i in 0 until itemsArr.length()) {
                        itemsArr.optJSONObject(i)?.let(::entryFromJson)?.let(items::add)
                    }
                }
                val id = obj.optInt("id", localListId(createdAt))
                if (id == 0) continue
                out += StoredList(
                    id = id,
                    name = name,
                    createdAt = createdAt,
                    items = items
                )
            }
            out.sortBy { it.createdAt }
            out
        }.getOrNull()
        if (parsed == null) {
            Log.w(
                TAG,
                "personal-lists blob will not parse; serving the last good copy and " +
                    "refusing to write over it"
            )
            return null
        }
        lastGood[cacheKey(context, KEY_LISTS)] = parsed
        return parsed.toMutableList()
    }

    private fun writeLists(context: Context, lists: List<StoredList>) {
        val root = JSONObject()
        lists.forEach { list ->
            val items = JSONArray()
            list.items.forEach { items.put(entryJson(it)) }
            root.put(
                list.id.toString(),
                JSONObject()
                    .put("id", list.id)
                    .put("name", list.name)
                    .put("createdAt", list.createdAt)
                    .put("items", items)
            )
        }
        prefs(context).edit().putString(KEY_LISTS, root.toString()).apply()
        pushLibrarySync(context)
    }

    /** All local personal lists, oldest first (stable rail order). */
    fun userLists(context: Context): List<LibraryList> =
        readLists(context).map { list ->
            LibraryList(
                id = list.id,
                name = list.name,
                itemCount = list.items.size,
                source = LibrarySource.LOCAL_LIST
            )
        }

    @Synchronized
    fun createList(context: Context, name: String): LibraryList? {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return null
        // A blob that will not parse must not be replaced by the one list this
        // call is creating: that is how the other lists would be lost.
        val lists = readListsForWrite(context) ?: return null
        if (lists.any { it.name.equals(trimmed, ignoreCase = true) }) {
            return userLists(context).firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        }
        val createdAt = System.currentTimeMillis()
        // Two lists created in the same millisecond derive the SAME id, and the
        // store is keyed by it - the second silently replaced the first, a
        // double-tap on create losing a list (LS-P2-8). Bump the stamp until
        // the id is free.
        var stamp = createdAt
        var idForList = localListId(stamp)
        while (lists.any { it.id == idForList }) {
            stamp += 1L
            idForList = localListId(stamp)
        }
        val stored = StoredList(
            id = idForList,
            name = trimmed,
            createdAt = stamp,
            items = mutableListOf()
        )
        lists += stored
        writeLists(context, lists)
        return LibraryList(stored.id, stored.name, 0, LibrarySource.LOCAL_LIST)
    }

    @Synchronized
    fun renameList(context: Context, listId: Int, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        val lists = readListsForWrite(context) ?: return false
        val target = lists.firstOrNull { it.id == listId } ?: return false
        val renamed = target.copy(name = trimmed)
        val updated = lists.map { if (it.id == listId) renamed else it }
        writeLists(context, updated)
        return true
    }

    @Synchronized
    fun deleteList(context: Context, listId: Int): Boolean {
        val lists = readListsForWrite(context) ?: return false
        val filtered = lists.filter { it.id != listId }
        if (filtered.size == lists.size) return false
        writeLists(context, filtered)
        return true
    }

    fun listItems(context: Context, listId: Int): List<LibraryItem> {
        val list = readLists(context).firstOrNull { it.id == listId } ?: return emptyList()
        return list.items.map { it.copy(source = LibrarySource.LOCAL_LIST, listId = list.id, listName = list.name) }
    }

    @Synchronized
    fun addToLocalList(
        context: Context,
        listId: Int,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?,
        title: String,
        year: Int?,
        posterUrl: String?
    ): Boolean = addToListInternal(
        context = context,
        storageKey = null,
        listId = listId,
        mediaType = mediaType,
        imdbId = imdbId,
        tmdbId = tmdbId,
        title = title,
        year = year,
        posterUrl = posterUrl,
        source = LibrarySource.LOCAL_LIST
    )

    @Synchronized
    fun removeFromLocalList(context: Context, listId: Int, mediaType: String, imdbId: String?, tmdbId: Int?) {
        val lists = readListsForWrite(context) ?: return
        val target = lists.firstOrNull { it.id == listId } ?: return
        // Match on either id: a removal passes whatever id the row it came
        // from knows, which is often only one side of the stored pair.
        target.items.removeAll { matches(it, mediaType, imdbId, tmdbId) }
        writeLists(context, lists)
    }

    // Shared add used by My List and local lists: de-duped, newest first.
    private fun addToListInternal(
        context: Context,
        storageKey: String? = null,
        listId: Int? = null,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?,
        title: String,
        year: Int?,
        posterUrl: String?,
        source: LibrarySource,
        origin: LibraryOrigin = LibraryOrigin.MANUAL
    ): Boolean {
        val entry = LibraryItem(
            source = source,
            mediaType = mediaType.lowercase(),
            title = title,
            year = year,
            posterUrl = posterUrl,
            imdbId = imdbId,
            tmdbId = tmdbId,
            origin = origin
        )
        if (entry.imdbId == null && entry.tmdbId == null) return false

        if (storageKey != null) {
            // Refuse to add over a blob that will not parse: the read-modify-write
            // would persist a one-item library and push that erasure to the other
            // devices (see [lastGood]).
            val items = readListForWrite(context, storageKey) ?: run {
                Log.w(TAG, "add refused: library blob will not parse")
                return false
            }
            if (items.none { matches(it, mediaType, imdbId, tmdbId) }) {
                items.add(0, entry)
                writeList(context, storageKey, items)
            }
            // A MANUAL add is the viewer's word against a removal the sync made:
            // it clears the title's tombstone, and the clearance travels the same
            // way the tombstone did. Asked even when the row is already here - a
            // manual add of a title the sync put back is the same statement - and
            // never for the sync's own adds, which is what keeps a re-processed
            // cloud from re-imposing a removal by adding the title again.
            if (origin == LibraryOrigin.MANUAL) {
                clearTombstonesFor(context, mediaType, imdbId, tmdbId)
            }
            return true
        }

        val id = listId ?: return false
        val lists = readListsForWrite(context) ?: run {
            Log.w(TAG, "add refused: personal-lists blob will not parse")
            return false
        }
        val target = lists.firstOrNull { it.id == id } ?: return false
        if (target.items.any { matches(it, mediaType, imdbId, tmdbId) }) return true
        target.items.add(0, entry)
        writeLists(context, lists)
        return true
    }

    private fun removeFromListInternal(context: Context, storageKey: String, mediaType: String, imdbId: String?, tmdbId: Int?) {
        val items = readListForWrite(context, storageKey) ?: run {
            Log.w(TAG, "remove refused: library blob will not parse")
            return
        }
        val filtered = items.filterNot { matches(it, mediaType, imdbId, tmdbId) }
        writeList(context, storageKey, filtered)
    }
}
