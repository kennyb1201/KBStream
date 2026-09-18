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
    MDBLIST_LIST("MDBList list")
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

    fun isInMyList(context: Context, mediaType: String, imdbId: String?, tmdbId: Int?): Boolean {
        val probe = LibraryItem(
            source = LibrarySource.LOCAL,
            mediaType = mediaType.lowercase(),
            title = "",
            imdbId = imdbId,
            tmdbId = tmdbId
        )
        val key = dedupeKey(probe)
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
        val entry = LibraryItem(
            source = LibrarySource.LOCAL,
            mediaType = mediaType.lowercase(),
            title = title,
            year = year,
            posterUrl = posterUrl,
            imdbId = imdbId,
            tmdbId = tmdbId
        )
        if (entry.imdbId == null && entry.tmdbId == null) return false

        val items = readList(context, KEY_MY_LIST)
        val key = dedupeKey(entry)
        if (items.any { dedupeKey(it) == key }) return true
        items.add(0, entry)
        writeList(context, KEY_MY_LIST, items)
        return true
    }

    fun removeFromMyList(context: Context, mediaType: String, imdbId: String?, tmdbId: Int?) {
        val probe = LibraryItem(
            source = LibrarySource.LOCAL,
            mediaType = mediaType.lowercase(),
            title = "",
            imdbId = imdbId,
            tmdbId = tmdbId
        )
        val key = dedupeKey(probe)
        val items = readList(context, KEY_MY_LIST)
        val filtered = items.filter { dedupeKey(it) != key }
        writeList(context, KEY_MY_LIST, filtered)
    }

    fun myList(context: Context): List<LibraryItem> = readList(context, KEY_MY_LIST)
}
