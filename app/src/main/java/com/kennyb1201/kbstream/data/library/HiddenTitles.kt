package com.kennyb1201.kbstream.data.library

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Titles the user hid from the app with the long-press "Hide" row.
 *
 * A hidden title is gone from every browsing surface — rails, search results,
 * Continue Watching, the catalog grid, the TMDB rails (studio / keyword /
 * decade / collection / actor), My List and the detail rails — and comes back
 * only from Settings → Hidden titles, which is why the entry keeps the title
 * and poster it was hidden under: the manager has to be able to draw the row
 * without the add-on that produced it.
 *
 * Identity is a SET of keys, not one id, because the same title arrives under
 * different spellings depending on where it came from ("tmdb:603" from a TMDB
 * rail, "tt0133093" from an add-on catalog, a bare "603" from the library).
 * Hiding records every spelling the caller knew about, and matching asks with
 * every spelling the surface knows about, so hiding once from a TMDB rail also
 * hides the add-on copy of the same film.
 *
 * Profile-scoped and device-local, like the hidden browse chips: which titles
 * are hidden is a property of the profile that hid them, so a kids profile can
 * be kept clean without hiding anything from the adult one.
 */
object HiddenTitles {

    private const val TAG = "HIDDEN_TITLES"

    private const val PREFS_BASE = "kbstream_hidden_titles"
    private const val KEY_ENTRIES = "entries_v1"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Entry(
        /** Every identity spelling of this title, e.g. "movie::tmdb:603". */
        val keys: List<String> = emptyList(),
        val title: String = "",
        val mediaType: String = "",
        val posterUrl: String? = null,
        val at: Long = 0L
    )

    @Serializable
    private data class Store(
        val entries: List<Entry> = emptyList()
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Newest first — the order the manager lists them in. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Bumped on every change so screens re-read [keys]. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** The profile whose set is currently published, so a switch re-reads. */
    private var loadedFor: String? = null

    /**
     * The two media types the app filters on. Everything a catalog calls a
     * series — "tv", "show", "anime" — is one type here, so a show hidden from
     * an anime rail is hidden from the TMDB series rails too.
     */
    fun normalizedType(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "tv", "series", "show", "anime", "anime.series" -> "series"
        else -> "movie"
    }

    /**
     * One canonical spelling of an id: "tmdb:603" for anything numeric (the
     * app writes TMDB ids as bare ints, "tmdb:603" and "tmdb_603"), and the id
     * itself otherwise — an add-on's "tt0133093" is already canonical.
     */
    fun normalizeId(raw: String?): String? {
        val id = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val bare = id.removePrefix("tmdb:").removePrefix("tmdb_")
        return if (bare.isNotEmpty() && bare.all { it.isDigit() }) "tmdb:$bare" else id
    }

    /**
     * The identity key for one id on one surface.
     *
     * A TMDB id is a NUMBER that movies and shows share (movie 1399 and series
     * 1399 are different titles), so it keeps its media type. Everything else
     * — an add-on's "tt0133093", a KB collection id — is already unique, so it
     * is keyed WITHOUT the type: the same film arrives as "movie" from one
     * catalog and "series" from a badly-typed one, and hiding it must not
     * depend on which spelling the catalog happened to use.
     */
    fun keyFor(mediaType: String?, id: String?): String? {
        val normalized = normalizeId(id) ?: return null
        return if (normalized.startsWith("tmdb:")) {
            "${normalizedType(mediaType)}::$normalized"
        } else {
            "id::$normalized"
        }
    }

    /** Every identity spelling for one title, given the ids a surface knows. */
    fun keysFor(mediaType: String?, ids: List<String?>): Set<String> =
        ids.mapNotNull { keyFor(mediaType, it) }.toCollection(LinkedHashSet())

    /** True when any spelling of this title is hidden. */
    fun hides(hidden: Set<String>, mediaType: String?, vararg ids: String?): Boolean =
        hidden.isNotEmpty() &&
            ids.any { keyFor(mediaType, it)?.let { key -> key in hidden } == true }

    /**
     * The active profile's hidden keys. Read on demand (a prefs string set is
     * cheap) and re-read whenever [version] changes, so a screen that hides a
     * title from its own long-press menu filters it out on the next frame.
     */
    fun keys(context: Context): Set<String> {
        val app = context.applicationContext
        ensureLoaded(app)
        return _entries.value.flatMapTo(LinkedHashSet()) { it.keys }
    }

    /**
     * Publishes the active profile's set. Cheap and idempotent, so any screen
     * may call it; the profile name is compared first so a profile switch (a
     * different prefs file) is picked up without re-reading on every frame.
     */
    fun ensureLoaded(context: Context) {
        val app = context.applicationContext
        val name = ProfileStorage.prefsName(app, PREFS_BASE)
        if (loadedFor == name) return
        loadedFor = name
        _entries.value = read(app).entries.sortedByDescending { it.at }
    }

    /** Hides one title; returns true when it was stored. */
    fun hide(
        context: Context,
        title: String,
        mediaType: String?,
        posterUrl: String?,
        ids: List<String?>
    ): Boolean {
        val app = context.applicationContext
        ensureLoaded(app)

        val keys = keysFor(mediaType, ids).toList()
        if (keys.isEmpty()) return false

        // One entry per title: hiding an already-hidden film again (from a
        // surface that knows a spelling the first press did not) merges the
        // spellings instead of leaving two rows in the manager.
        val current = read(app).entries
        val kept = current.filterNot { entry -> entry.keys.any { it in keys } }
        val merged = Entry(
            keys = (keys + kept.firstOrNull { entry ->
                entry.keys.any { it in keys }
            }?.keys.orEmpty()).distinct(),
            title = title.takeIf { it.isNotBlank() }
                ?: current.firstOrNull { entry -> entry.keys.any { it in keys } }
                    ?.title
                ?: "",
            mediaType = normalizedType(mediaType),
            posterUrl = posterUrl ?: current.firstOrNull { entry ->
                entry.keys.any { it in keys }
            }?.posterUrl,
            at = System.currentTimeMillis()
        )

        write(app, Store(kept + merged))
        Log.i(TAG, "hidden: ${merged.title} (${merged.keys.size} keys)")
        return true
    }

    /** Brings one hidden title back; returns true when something changed. */
    fun unhide(context: Context, keys: Collection<String>): Boolean {
        val app = context.applicationContext
        val current = read(app).entries
        val target = keys.toSet()
        val kept = current.filterNot { entry -> entry.keys.any { it in target } }
        if (kept.size == current.size) return false
        write(app, Store(kept))
        return true
    }

    fun unhideAll(context: Context) {
        val app = context.applicationContext
        if (read(app).entries.isEmpty()) return
        write(app, Store(emptyList()))
    }

    private fun read(context: Context): Store {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return Store()
        return runCatching { json.decodeFromString(Store.serializer(), raw) }
            .getOrElse {
                // A corrupt blob must never hide everything: reset to none.
                Log.w(TAG, "hidden titles blob unreadable; resetting (${it.message})")
                Store()
            }
    }

    private fun write(context: Context, store: Store) {
        prefs(context).edit()
            .putString(KEY_ENTRIES, json.encodeToString(Store.serializer(), store))
            .apply()
        loadedFor = ProfileStorage.prefsName(context, PREFS_BASE)
        _entries.value = store.entries.sortedByDescending { it.at }
        _version.value = _version.value + 1
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            ProfileStorage.prefsName(context.applicationContext, PREFS_BASE),
            Context.MODE_PRIVATE
        )
}
