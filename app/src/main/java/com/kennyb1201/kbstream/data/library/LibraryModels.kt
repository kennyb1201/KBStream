package com.kennyb1201.kbstream.data.library

import android.content.Context
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import org.json.JSONArray
import org.json.JSONObject

/** Which tracker a Library row came from. */
enum class LibrarySource(val label: String) {
    LOCAL("This device"),
    SIMKL_WATCHLIST("Simkl"),
    SIMKL_LIST("Simkl list"),
    MDBLIST_WATCHLIST("MDBList"),
    MDBLIST_LIST("MDBList list"),
    LOCAL_LIST("This device · list")
}

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
    val listId: Int? = null,
    val listName: String? = null
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
}

/**
 * A user-created personal list on MDBList (or a local list stored on
 * this profile). Personal lists group titles the user chose to keep.
 */
data class LibraryList(
    val id: Int,
    val name: String,
    val itemCount: Int,
    val source: LibrarySource
)

/**
 * Profile-scoped local library: the on-device "My List" (works with no
 * account connected) plus local personal lists. Stored as JSON in a
 * SharedPreferences store namespaced to the active profile via
 * [ProfileStorage.prefsName], matching every other
 * profile-scoped store.
 */
object LocalLibraryStore {

    private const val BASE_NAME = "kbstream_library"
    private const val KEY_MY_LIST = "my_list"
    private const val KEY_LISTS = "personal_lists"

    private fun prefs(context: Context) =
        context.getSharedPreferences(
            ProfileStorage.prefsName(context, BASE_NAME),
            Context.MODE_PRIVATE
        )

    private fun entryJson(item: LibraryItem): JSONObject = JSONObject()
        .put("mediaType", item.mediaType)
        .put("title", item.title)
        .put("year", item.year ?: -1)
        .put("posterUrl", item.posterUrl ?: "")
        .put("imdbId", item.imdbId ?: "")
        .put("tmdbId", item.tmdbId ?: -1)

    private fun entryFromJson(obj: JSONObject): LibraryItem? {
        val mediaType = obj.optString("mediaType", "movie")
        val title = obj.optString("title", "").ifBlank { return null }
        val imdbId = obj.optString("imdbId", "").ifBlank { null }
        val tmdbId = obj.optInt("tmdbId", -1).takeIf { it > 0 }
        if (imdbId == null && tmdbId == null) return null
        return LibraryItem(
            source = LibrarySource.LOCAL,
            mediaType = mediaType,
            title = title,
            year = obj.optInt("year", -1).takeIf { it > 0 },
            posterUrl = obj.optString("posterUrl", "").ifBlank { null },
            imdbId = imdbId,
            tmdbId = tmdbId
        )
    }

    private fun readList(context: Context, key: String): MutableList<LibraryItem> {
        val raw = prefs(context).getString(key, null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(::entryFromJson)
            }
        }.getOrDefault(emptyList()).toMutableList()
    }

    private fun writeList(context: Context, key: String, items: List<LibraryItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(entryJson(it)) }
        prefs(context).edit().putString(key, arr.toString()).apply()
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

    fun isInMyList(context: Context, mediaType: String, imdbId: String?, tmdbId: Int?): Boolean {
        val key = dedupeKeyOf(mediaType, imdbId, tmdbId)
        return readList(context, KEY_MY_LIST).any { dedupeKey(it) == key }
    }

    fun addToMyList(
        context: Context,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?,
        title: String,
        year: Int?,
        posterUrl: String?
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
            source = LibrarySource.LOCAL
        )
    }

    fun removeFromMyList(context: Context, mediaType: String, imdbId: String?, tmdbId: Int?) {
        removeFromListInternal(context, KEY_MY_LIST, mediaType, imdbId, tmdbId)
    }

    fun myList(context: Context): List<LibraryItem> = readList(context, KEY_MY_LIST)

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

    private fun readLists(context: Context): MutableList<StoredList> {
        val raw = prefs(context).getString(KEY_LISTS, null) ?: return mutableListOf()
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
        }.getOrDefault(mutableListOf())
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

    fun createList(context: Context, name: String): LibraryList? {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return null
        val lists = readLists(context)
        if (lists.any { it.name.equals(trimmed, ignoreCase = true) }) {
            return userLists(context).firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        }
        val createdAt = System.currentTimeMillis()
        val stored = StoredList(
            id = localListId(createdAt),
            name = trimmed,
            createdAt = createdAt,
            items = mutableListOf()
        )
        lists += stored
        writeLists(context, lists)
        return LibraryList(stored.id, stored.name, 0, LibrarySource.LOCAL_LIST)
    }

    fun renameList(context: Context, listId: Int, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        val lists = readLists(context)
        val target = lists.firstOrNull { it.id == listId } ?: return false
        val renamed = target.copy(name = trimmed)
        val updated = lists.map { if (it.id == listId) renamed else it }
        writeLists(context, updated)
        return true
    }

    fun deleteList(context: Context, listId: Int): Boolean {
        val lists = readLists(context)
        val filtered = lists.filter { it.id != listId }
        if (filtered.size == lists.size) return false
        writeLists(context, filtered)
        return true
    }

    fun listItems(context: Context, listId: Int): List<LibraryItem> {
        val list = readLists(context).firstOrNull { it.id == listId } ?: return emptyList()
        return list.items.map { it.copy(source = LibrarySource.LOCAL_LIST, listId = list.id, listName = list.name) }
    }

    fun isListEmpty(context: Context, listId: Int): Boolean =
        readLists(context).firstOrNull { it.id == listId }?.items.isNullOrEmpty()

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

    fun removeFromLocalList(context: Context, listId: Int, mediaType: String, imdbId: String?, tmdbId: Int?) {
        val lists = readLists(context)
        val target = lists.firstOrNull { it.id == listId } ?: return
        val key = dedupeKeyOf(mediaType, imdbId, tmdbId)
        target.items.removeAll { dedupeKey(it) == key }
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
        source: LibrarySource
    ): Boolean {
        val entry = LibraryItem(
            source = source,
            mediaType = mediaType.lowercase(),
            title = title,
            year = year,
            posterUrl = posterUrl,
            imdbId = imdbId,
            tmdbId = tmdbId
        )
        if (entry.imdbId == null && entry.tmdbId == null) return false
        val key = dedupeKey(entry)

        if (storageKey != null) {
            val items = readList(context, storageKey)
            if (items.any { dedupeKey(it) == key }) return true
            items.add(0, entry)
            writeList(context, storageKey, items)
            return true
        }

        val id = listId ?: return false
        val lists = readLists(context)
        val target = lists.firstOrNull { it.id == id } ?: return false
        if (target.items.any { dedupeKey(it) == key }) return true
        target.items.add(0, entry)
        writeLists(context, lists)
        return true
    }

    private fun removeFromListInternal(context: Context, storageKey: String, mediaType: String, imdbId: String?, tmdbId: Int?) {
        val key = dedupeKeyOf(mediaType, imdbId, tmdbId)
        val items = readList(context, storageKey)
        val filtered = items.filter { dedupeKey(it) != key }
        writeList(context, storageKey, filtered)
    }
}
