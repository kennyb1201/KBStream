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

    /**
     * BingeCat's own rows.
     *
     * Three sources rather than one because the section shows three things -
     * the watchlist, the saved list (BingeCat's "favorites") and each user list
     * by its own name - and a row has to be able to say which it came from.
     *
     * Writes reach two of them since step 3 (`lists:write`): a save adds to the
     * watchlist, and the picker's rows add to and remove from a user list
     * by its own `ref`. The saved list (favorites) stays read-only - step 3's
     * scope is the watchlist and the lists, not BingeCat's own favorites - so a
     * `BINGECAT_SAVED` row is still a row nothing writes back to.
     */
    BINGECAT_WATCHLIST("BingeCat"),
    BINGECAT_SAVED("BingeCat saved"),
    BINGECAT_LIST("BingeCat list"),

    /**
     * The anime trackers' own lists (spec 2026-11-13, F1).
     *
     * An anime list is a Library list the same way a BingeCat or Trakt list is:
     * it is drawn on the lists rail, opens into the grid, and pins to Home from
     * its own row. Three sources rather than one because the row says which
     * service holds it - and because a status list of the same name exists on
     * all three ("Watching" means AniList's group on one row and Kitsu's on the
     * next).
     *
     * READ-ONLY here: the lists belong to the service, so the rail offers no
     * rename and no delete ([canManageList] is false) - the same promise Simkl's
     * list bucket keeps. What the app does offer is the pin, which is this
     * device's arrangement and nobody else's list.
     *
     * A service with no username (AniList, Kitsu) or no connection (MAL) simply
     * contributes no row: there is no dead "connect me" row on the rail.
     */
    ANILIST_LIST("AniList"),
    KITSU_LIST("Kitsu"),
    MAL_LIST("MyAnimeList"),
    LOCAL_LIST("This device · list");

    /** True for a list this device owns and can rename/delete. */
    val isLocalList: Boolean get() = this == LOCAL_LIST

    /**
     * True for a list the Library rail can rename and delete.
     *
     * A local list is both, entirely on this device. A Trakt, MDBList or
     * BingeCat list is the same pair on the service that holds it, through the
     * API each of them documents for its own manageable lists (owned static
     * lists for MDBList, manual lists for BingeCat, the account's own for
     * Trakt). A Simkl row can be neither - Simkl exposes no custom lists at all
     * - so the rail offers no action for one rather than an action that fails.
     */
    val canManageList: Boolean
        get() = this == LOCAL_LIST || this == TRAKT_LIST ||
            this == MDBLIST_LIST || this == BINGECAT_LIST

    /**
     * Where a delete of a list in this source goes, for the confirm dialog.
     *
     * The wording is the point: deleting a local list and deleting a Trakt list
     * are different promises, and a dialog that says only "Delete list?" is how
     * one gets taken for the other.
     */
    val deleteDestinationLabel: String
        get() = when (this) {
            LOCAL_LIST -> "this device"
            TRAKT_LIST -> "Trakt"
            MDBLIST_LIST -> "MDBList"
            BINGECAT_LIST -> "BingeCat"
            else -> label
        }

    /**
     * True for a source whose whole contribution is one account's WATCHLIST.
     *
     * The Library's WATCHLIST filter merges these four into one grid, and that
     * is still the only place the merge itself is drawn - but each of them is
     * also a LIST the viewer collects into, so each gets its own row in the
     * personal-lists rail (see [watchlistShelfId]) and can be pinned to Home
     * like any hand-made list. MY LIST and the BingeCat saved list are not here:
     * My List has its own filter, and BingeCat's favourites are read-only.
     */
    val isWatchlistSource: Boolean
        get() = this == TRAKT_WATCHLIST || this == MDBLIST_WATCHLIST ||
            this == SIMKL_WATCHLIST || this == BINGECAT_WATCHLIST

    /**
     * True for one of the anime trackers' own lists (F1).
     *
     * The rail's actions read this: an anime list is read live from its service
     * and written nowhere from this app, so it has a pin and nothing else - no
     * rename, no delete, and no per-row remove beyond what the service itself
     * does.
     */
    val isAnimeList: Boolean
        get() = this == ANILIST_LIST || this == KITSU_LIST || this == MAL_LIST

    /** The heading a watchlist row carries, so four of them can be told apart. */
    val watchlistTitle: String
        get() = when (this) {
            TRAKT_WATCHLIST -> "Trakt watchlist"
            MDBLIST_WATCHLIST -> "MDBList watchlist"
            SIMKL_WATCHLIST -> "Simkl watchlist"
            BINGECAT_WATCHLIST -> "BingeCat watchlist"
            else -> "Watchlist"
        }
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
 * The rail id for one of an anime tracker's lists (F1).
 *
 * Derived from the (source, ref) pair because that pair IS the list: AniList's
 * group key (`status:CURRENT`, `custom:<name>`), Kitsu's and MAL's status
 * buckets. The row keeps its identity across a refresh, so a Home pin keeps
 * pointing at it, and the ids sit in a band of their own - above MDBList's small
 * positive ids and below the watchlist rows at the top of the Int range - clear
 * of Trakt's bottom range, BingeCat's and the local store's negative ids.
 */
fun animeListShelfId(source: LibrarySource, ref: String): Int {
    val hash = ("${source.name}:$ref").hashCode() and 0x3FFF_FFFF
    return Int.MAX_VALUE / 2 + hash
}

/**
 * The rail id for a saved (liked) community list.
 *
 * Derived from the vendor and the list's own ref, so the row keeps its identity
 * across refreshes and a Home pin keeps pointing at it. The range is a band of
 * its own, clear of Trakt's own lists at the bottom of the Int range, MDBList's
 * positive ids and the watching-screen ids at the top - the same partitioning
 * [traktListShelfId] and [watchlistShelfId] describe.
 */
fun likedListShelfId(vendorWire: String, ref: String): Int {
    val hash = ("$vendorWire:$ref").hashCode() and 0x3FFF_FFFF
    return Int.MIN_VALUE / 2 + hash
}

/**
 * The rail id for one account's WATCHLIST row.
 *
 * A watchlist is not one of the account's named lists, so it has no service id
 * to carry; the row still needs a [LibraryList.id] nothing else can reach (the
 * lists rail is keyed on it) and a stable identity for its Home pin. Both come
 * from the SOURCE rather than from the row's slot, so adding or removing a
 * neighbour cannot renumber it - the same rule [traktListShelfId] follows. The
 * ids sit at the top of the Int range, clear of Trakt's bottom range, MDBList's
 * positive ids and the local store's negative ones.
 */
fun watchlistShelfId(source: LibrarySource): Int = Int.MAX_VALUE - source.ordinal

/**
 * The wire id of a watchlist row, used as the Home pin's identity.
 *
 * One constant is enough because the pin's key is `source + id`: the source is
 * already in it, and the value must never collide with a real list's remoteId.
 */
const val WATCHLIST_REMOTE_ID = "watchlist"

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
    val remoteId: String? = null,
    /**
     * True for a community list the viewer SAVED by liking it (Settings →
     * Trakt/MDBList → Browse public lists), as opposed to a list the account
     * owns.
     *
     * The difference is entirely in what its actions mean: a liked list cannot
     * be renamed, and removing it is an UNLIKE - it takes the row off this
     * rail without deleting the owner's list, which is not the viewer's to
     * delete. Without the flag the rail's delete would call the account's hard
     * list delete on a list that belongs to someone else (spec F4).
     */
    val liked: Boolean = false,
    /**
     * The public list's owner, for a [liked] row only.
     *
     * Attribution is mandatory on a saved community list (spec F5): without the
     * owner, a row in this rail reads as a list the viewer or the app made,
     * which is exactly what a public list is not.
     */
    val owner: String? = null
) {
    /** Where the list lives, for the rail's source line. */
    val locationLabel: String
        get() = when (source) {
            LibrarySource.LOCAL_LIST -> "This device"
            LibrarySource.MDBLIST_LIST -> "MDBList"
            LibrarySource.TRAKT_LIST -> "Trakt"
            LibrarySource.BINGECAT_LIST -> "BingeCat"
            else -> source.label
        }
}

/**
 * True when [other] is this list, across a refresh that renumbered the rail.
 *
 * [LibraryList.id] is not the list. A BingeCat row's id is its INDEX in the
 * snapshot ([bingecatShelfId]), so deleting one list shifts every later one's
 * id down by one: a selection held across that refresh now matches the list
 * that moved into its slot, and the rail highlights one row while the pane
 * shows another. The identity that survives is the service's own:
 *
 *  - [remoteId] when BOTH sides carry one - BingeCat's `ref`, a Trakt
 *    slug/id, an MDBList id. This is the stable identity for every remote
 *    list, and the one a Home pin already prefers;
 *  - [id] otherwise, which is the stable creation-time id of a local list
 *    (and the only identity a list has before a read fills its remote one).
 *
 * [source] always has to match first: the four watchlists share
 * [WATCHLIST_REMOTE_ID] on purpose, and the source is what tells them apart.
 */
fun LibraryList.sameListAs(other: LibraryList?): Boolean {
    if (other == null) return false
    if (source != other.source) return false
    val mine = remoteId?.takeIf { it.isNotBlank() }
    val theirs = other.remoteId?.takeIf { it.isNotBlank() }
    return if (mine != null && theirs != null) mine == theirs else id == other.id
}

/**
 * The list-control dialogs' words, for this row.
 *
 * Every one of them delegates to [ListControlWording], so the TV dialog and the
 * phone sheet cannot disagree about what a saved community list's removal is:
 * one of them saying DELETE while the other says REMOVE is how a like gets
 * explained as a delete of someone else's list.
 */
val LibraryList.controlSummary: String
    get() = ListControlWording.summary(
        itemCount = itemCount,
        sourceLabel = source.deleteDestinationLabel,
        liked = liked,
        owner = owner
    )

/** The action dialog's subtitle: the source, plus the owner for a saved list. */
val LibraryList.controlAttribution: String
    get() = ListControlWording.attribution(
        sourceLabel = source.deleteDestinationLabel,
        liked = liked,
        owner = owner
    )

/** The remove/delete row's label. */
val LibraryList.removeActionLabel: String
    get() = ListControlWording.removeActionLabel(
        sourceLabel = source.deleteDestinationLabel,
        local = source.isLocalList,
        liked = liked
    )

/** The confirm dialog's title. */
val LibraryList.removeConfirmTitle: String
    get() = ListControlWording.removeConfirmTitle(
        sourceLabel = source.deleteDestinationLabel,
        local = source.isLocalList,
        liked = liked
    )

/** The confirm dialog's body. */
val LibraryList.removeConfirmBody: String
    get() = ListControlWording.removeConfirmBody(
        sourceLabel = source.deleteDestinationLabel,
        local = source.isLocalList,
        liked = liked,
        itemCount = itemCount
    )

/** The confirm button's label. */
val LibraryList.removeConfirmAction: String
    get() = ListControlWording.removeConfirmAction(liked)

/** Why a saved community list offers no rename, or null when renaming is ordinary. */
val LibraryList.renameUnavailableNote: String?
    get() = if (liked) {
        ListControlWording.renameUnavailableNote(source.deleteDestinationLabel, owner)
    } else {
        null
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
     * The removal record for [KEY_LISTS]: id -> when it was deleted.
     *
     * A delete cannot be expressed by the lists blob alone - a list that is
     * simply absent is also a list this device never had, and reading those as
     * the same thing is how a sibling's next push put a deleted list back. The
     * record is the only thing that removes a list, and it outranks any older
     * copy that has not heard about the delete yet (see
     * [com.kennyb1201.kbstream.data.sync.LocalListSyncRules]).
     */
    private const val KEY_LISTS_DELETED = "personal_lists_deleted"

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
        val items: MutableList<LibraryItem>,
        /**
         * When this list last CHANGED here: its creation, a rename, or a row
         * added or removed. The merge compares it across devices, so it is the
         * list's own edit time - never the moment a push happened, which would
         * let a device that merely opened the app overwrite a newer copy.
         *
         * Absent in blobs written before this existed, and read as
         * [createdAt]: an older list is still older than a sibling's edit, and
         * any pulled copy of it may then win.
         */
        val updatedAt: Long = createdAt
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
                    items = items,
                    updatedAt = obj.optLong("updatedAt", createdAt)
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
                    .put("updatedAt", list.updatedAt)
                    .put("items", items)
            )
        }
        prefs(context).edit().putString(KEY_LISTS, root.toString()).apply()
        pushListsSync(context)
    }

    /** The removal record of the ambient profile, as id -> deletedAt. */
    private fun deletedLists(context: Context): Map<Int, Long> =
        decodeDeletedLists(prefs(context).getString(KEY_LISTS_DELETED, null))

    /**
     * The ids this profile has a removal record for, whoever deleted them.
     *
     * Read by the pins store: a pin that names a deleted list must not survive
     * an adopted copy from a sibling that has not deleted it yet (see
     * [withoutDeletedLocalPins]).
     */
    internal fun deletedListIds(context: Context, profileId: String?): Set<Int> =
        decodeDeletedLists(
            libraryPrefs(context, profileId).getString(KEY_LISTS_DELETED, null)
        ).keys

    /** The ids a pushed removal record names, without touching any store. */
    internal fun deletedListIdsFromRaw(raw: String?): Set<Int> =
        decodeDeletedLists(raw).keys

    private fun decodeDeletedLists(raw: String?): Map<Int, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<Int, Long>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val id = key.toIntOrNull() ?: continue
            out[id] = root.optLong(key, 0L)
        }
        return out
    }

    private fun encodeDeletedLists(record: Map<Int, Long>): String {
        val root = JSONObject()
        record.forEach { (id, at) -> root.put(id.toString(), at) }
        return root.toString()
    }

    /**
     * Cross-device sync for the personal lists, on their own row.
     *
     * The library row is whole-blob last-write-wins against a stamp every My
     * List write also moves, which is what made a list created on one device
     * disappear on the next one: the other device's My List edit republished
     * its older copy of the lists. This row carries the lists and their removal
     * record and is merged per list (see
     * [com.kennyb1201.kbstream.data.sync.LocalListSyncRules]).
     */
    private fun pushListsSync(context: Context) {
        com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
            context,
            com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_LIBRARY_LISTS,
            com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildLibraryLists(context)
        )
    }

    /** True when this profile has lists or a delete to publish. */
    fun hasListsToPublish(context: Context): Boolean =
        com.kennyb1201.kbstream.data.sync.LocalListSyncRules.shouldPublish(
            listCount = readLists(context).size,
            tombstoneCount = deletedLists(context).size
        )

    /**
     * Merges the account's lists into [profileId]'s store, one list at a time,
     * and answers whether anything changed.
     *
     * Addressed by profile rather than by the ambient store: the applier may be
     * writing a sibling profile's library. Nothing is pushed - a pull is not
     * this device's edit, and the sibling that made it already published it.
     * A local blob that will not parse refuses the merge rather than replacing
     * the other lists with the incoming half (the same rule every read-modify-
     * write here follows).
     */
    @Synchronized
    fun mergePulledLists(
        context: Context,
        profileId: String?,
        incomingListsRaw: String?,
        incomingDeletedRaw: String?
    ): Boolean {
        val prefs = libraryPrefs(context, profileId)
        val localRaw = prefs.getString(KEY_LISTS, null)
        val local = listsFromRaw(localRaw) ?: return false
        val incoming = listsFromRaw(incomingListsRaw) ?: return false
        val localDeleted = decodeDeletedLists(prefs.getString(KEY_LISTS_DELETED, null))
        val incomingDeleted = decodeDeletedLists(incomingDeletedRaw)
        val deleted = com.kennyb1201.kbstream.data.sync.LocalListSyncRules.mergeTombstones(
            local = localDeleted,
            incoming = incomingDeleted
        )

        val byId = linkedMapOf<Int, StoredList>()
        local.forEach { byId[it.id] = it }
        incoming.forEach { list ->
            val localCopy = byId[list.id]
            val keeps = com.kennyb1201.kbstream.data.sync.LocalListSyncRules.takesIncoming(
                incomingUpdatedAt = list.updatedAt,
                localUpdatedAt = localCopy?.updatedAt,
                tombstoned = deleted.containsKey(list.id)
            )
            if (keeps) byId[list.id] = list
        }
        // The removal record is applied last, so it also takes out a local copy
        // the incoming half did not mention.
        deleted.keys.forEach { byId.remove(it) }

        val merged = byId.values.sortedBy { it.createdAt }
        val listsChanged = merged != local
        val deletedChanged = deleted != localDeleted
        if (!listsChanged && !deletedChanged) return false

        val root = JSONObject()
        merged.forEach { list ->
            val items = JSONArray()
            list.items.forEach { items.put(entryJson(it)) }
            root.put(
                list.id.toString(),
                JSONObject()
                    .put("id", list.id)
                    .put("name", list.name)
                    .put("createdAt", list.createdAt)
                    .put("updatedAt", list.updatedAt)
                    .put("items", items)
            )
        }
        prefs.edit()
            .putString(KEY_LISTS, root.toString())
            .putString(KEY_LISTS_DELETED, encodeDeletedLists(deleted))
            .apply()
        // The ambient store's last-good copy belongs to the ambient profile; a
        // sibling's merge must not leave the parsed cache pointing at it.
        if (profileId == null) {
            lastGood[cacheKey(context, KEY_LISTS)] = merged
        }
        return true
    }

    /**
     * Parses one personal-lists blob, or null when it is absent or unreadable.
     *
     * Absent is "no lists here" (an empty list); unreadable is a refusal, so
     * the caller keeps what it has instead of replacing it with an empty
     * library.
     */
    private fun listsFromRaw(raw: String?): List<StoredList>? {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            val out = mutableListOf<StoredList>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = root.optJSONObject(key) ?: continue
                val name = obj.optString("name", "")
                if (name.isBlank()) continue
                val createdAt = obj.optLong("createdAt", 0L)
                val items = mutableListOf<LibraryItem>()
                obj.optJSONArray("items")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let(::entryFromJson)?.let(items::add)
                    }
                }
                val id = obj.optInt("id", localListId(createdAt))
                if (id == 0) continue
                out += StoredList(
                    id = id,
                    name = name,
                    createdAt = createdAt,
                    items = items,
                    updatedAt = obj.optLong("updatedAt", createdAt)
                )
            }
            out.sortedBy { it.createdAt }
        }.getOrNull()
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
        // the id is free - of the lists here AND of the removal record, because
        // re-using a deleted id would make the new list read as the one the
        // viewer deleted and the next merge would drop it.
        val deleted = deletedLists(context)
        var stamp = createdAt
        var idForList = localListId(stamp)
        while (lists.any { it.id == idForList } || deleted.containsKey(idForList)) {
            stamp += 1L
            idForList = localListId(stamp)
        }
        val stored = StoredList(
            id = idForList,
            name = trimmed,
            createdAt = stamp,
            items = mutableListOf(),
            updatedAt = stamp
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
        val renamed = target.copy(name = trimmed, updatedAt = System.currentTimeMillis())
        val updated = lists.map { if (it.id == listId) renamed else it }
        writeLists(context, updated)
        return true
    }

    /**
     * Deletes one local list, and records the removal so it travels.
     *
     * The empty blob alone would not say whether the list was deleted here or
     * never existed on the other device - and reading those the same way is how
     * a sibling's next push put a deleted list back. The record outranks an
     * older copy of the list, forever, because ids are never reused
     * ([createList] steps over them).
     */
    @Synchronized
    fun deleteList(context: Context, listId: Int): Boolean {
        val lists = readListsForWrite(context) ?: return false
        val filtered = lists.filter { it.id != listId }
        if (filtered.size == lists.size) return false
        val record = deletedLists(context).toMutableMap()
        record[listId] = System.currentTimeMillis()
        prefs(context).edit()
            .putString(KEY_LISTS_DELETED, encodeDeletedLists(record))
            .apply()
        writeLists(context, filtered)
        // The Home rail goes with the list, here at the store rather than in a
        // screen: every surface that can delete a list comes through this call,
        // and the drop publishes the pin row so the sibling loses it as well.
        LibraryHomeRails.dropLocalPins(context, setOf(listId))
        return true
    }

    fun listItems(context: Context, listId: Int): List<LibraryItem> {
        val list = readLists(context).firstOrNull { it.id == listId } ?: return emptyList()
        return list.items.map { it.copy(source = LibrarySource.LOCAL_LIST, listId = list.id, listName = list.name) }
    }

    /**
     * Adds a whole list's worth of titles in ONE write, and answers how many
     * were new.
     *
     * The public-lists surface's anime save is a COPY of somebody else's list
     * (spec 2026-11-13 F2): a few hundred titles that would otherwise be a few
     * hundred read-modify-write rounds over one SharedPreferences blob, each
     * enqueueing a sync push. One write is the same result without the storm.
     *
     * Order is the SERVICE's order, appended (not newest-first): a copied list
     * is a snapshot of a list somebody ordered, so re-ordering it here would
     * lose the one thing the copy is for. Duplicates are skipped by the same
     * either-id match every other add uses - a second copy of the same list
     * finds the titles already present and changes nothing.
     */
    @Synchronized
    fun addItemsToLocalList(context: Context, listId: Int, items: List<LibraryItem>): Int {
        if (items.isEmpty()) return 0
        val lists = readListsForWrite(context) ?: run {
            Log.w(TAG, "bulk add refused: personal-lists blob will not parse")
            return 0
        }
        val target = lists.firstOrNull { it.id == listId } ?: return 0
        var added = 0
        items.forEach { item ->
            if (item.imdbId == null && item.tmdbId == null) return@forEach
            if (target.items.any { matches(it, item.mediaType, item.imdbId, item.tmdbId) }) {
                return@forEach
            }
            target.items.add(
                LibraryItem(
                    source = LibrarySource.LOCAL_LIST,
                    mediaType = normalizedType(item.mediaType),
                    title = item.title,
                    year = item.year,
                    posterUrl = cleanLibraryPoster(item.posterUrl),
                    imdbId = item.imdbId,
                    tmdbId = item.tmdbId
                )
            )
            added++
        }
        if (added == 0) return 0
        // One stamp for the whole copy: the sibling takes this copy instead of
        // its older one, exactly as a single add does.
        val stamped = lists.map {
            if (it.id == listId) it.copy(updatedAt = System.currentTimeMillis()) else it
        }
        writeLists(context, stamped)
        return added
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
        val removed = target.items.removeAll { matches(it, mediaType, imdbId, tmdbId) }
        if (!removed) return
        // A row leaving a list is that list's edit: the stamp is what lets the
        // sibling take this copy instead of keeping the row it still shows.
        val stamped = lists.map {
            if (it.id == listId) it.copy(updatedAt = System.currentTimeMillis()) else it
        }
        writeLists(context, stamped)
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
        val stamped = lists.map {
            if (it.id == id) it.copy(updatedAt = System.currentTimeMillis()) else it
        }
        writeLists(context, stamped)
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
