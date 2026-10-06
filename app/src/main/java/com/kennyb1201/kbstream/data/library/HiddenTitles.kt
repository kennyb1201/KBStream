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

    /** Prefix of a derived name key (never stored, only matched). */
    private const val TITLE_KEY_PREFIX = "title::"

    /** Stands in for a year the hiding surface did not know. */
    private const val ANY_YEAR = "*"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Entry(
        /** Every identity spelling of this title, e.g. "movie::tmdb:603". */
        val keys: List<String> = emptyList(),
        val title: String = "",
        val mediaType: String = "",
        val posterUrl: String? = null,
        val at: Long = 0L,
        /**
         * The release/first-air year, when the hiding surface knew it.
         *
         * [keys] alone cannot catch every surface: the app runs two id
         * namespaces (TMDB's "tmdb:603" and an add-on's "tt0133093") and the
         * bridge between them needs a network lookup, so a title hidden from
         * a TMDB rail used to come back in an add-on search rail keyed by its
         * IMDB id. The title is matched too (see [hides]), and the year keeps
         * that from also hiding an unrelated title that shares the name -
         * "The Office" (US) must not hide "The Office" (UK).
         */
        val year: Int? = null
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

    /**
     * True when this title is hidden - by an id spelling it carries, or by its
     * name.
     *
     * The id check is the precise one and covers the common case. The name
     * check exists because the two id namespaces are not bridged offline: a
     * title hidden from a TMDB rail ("movie::tmdb:603") and the same film in an
     * add-on rail ("id::tt0133093") cannot be matched through ids without a
     * TMDB lookup, which is exactly how a hidden title came back in add-on
     * search. [title] and [year] close that gap; [year] keeps the match from
     * catching a different title that shares the name (it is only skipped when
     * a surface truly has no year to give).
     */
    fun hides(
        hidden: Set<String>,
        mediaType: String?,
        title: String?,
        year: Int?,
        vararg ids: String?
    ): Boolean {
        if (hidden.isEmpty()) return false

        if (ids.any { keyFor(mediaType, it)?.let { key -> key in hidden } == true }) {
            return true
        }

        val name = normalizeTitle(title) ?: return false
        val prefix = "$TITLE_KEY_PREFIX${normalizedType(mediaType)}::$name::"
        return when {
            // A year on the surface: match that year, or a hidden entry that
            // never recorded one (hidden from a surface that had no year).
            year != null ->
                ("$prefix$year") in hidden || ("$prefix$ANY_YEAR") in hidden

            // No year to compare: any entry with this name and type matches.
            // Permissive on purpose - the user explicitly removed the title.
            else -> hidden.any { it.startsWith(prefix) }
        }
    }

    /**
     * The active profile's hidden keys. Read on demand (a prefs string set is
     * cheap) and re-read whenever [version] changes, so a screen that hides a
     * title from its own long-press menu filters it out on the next frame.
     */
    fun keys(context: Context): Set<String> {
        val app = context.applicationContext
        ensureLoaded(app)
        return _entries.value.flatMapTo(LinkedHashSet()) { entry ->
            // Both index forms: the id spellings, and the derived name key the
            // fallback in [hides] looks up.
            entry.keys + listOfNotNull(titleKey(entry.mediaType, entry.title, entry.year))
        }
    }

    /**
     * The name key one entry is stored and matched under. Alphanumerics only,
     * lowercased, so "The  Office" and "The Office" agree. The year is "*"
     * when the hiding surface had none.
     */
    internal fun titleKey(mediaType: String?, title: String?, year: Int?): String? {
        val name = normalizeTitle(title) ?: return null
        return "$TITLE_KEY_PREFIX${normalizedType(mediaType)}::$name::${year ?: ANY_YEAR}"
    }

    /** Lowercased, alphanumerics only; null when nothing usable is left. */
    internal fun normalizeTitle(raw: String?): String? =
        raw?.lowercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }

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
        ids: List<String?>,
        year: Int? = null
    ): Boolean {
        val app = context.applicationContext
        ensureLoaded(app)

        val keys = keysFor(mediaType, ids).toList()
        if (keys.isEmpty()) return false

        // One entry per title: hiding an already-hidden film again (from a
        // surface that knows a spelling the first press did not) merges the
        // spellings instead of leaving two rows in the manager.
        val current = read(app).entries
        // The entry being merged is read BEFORE it is filtered out of `current`:
        // the merge used to search the already-filtered `kept`, which can never
        // contain a match, so a second hide under another id spelling created a
        // DUPLICATE row instead of folding the spellings together (see LS-P2-9).
        val existing = current.firstOrNull { entry -> entry.keys.any { it in keys } }
        val kept = current.filterNot { entry -> entry.keys.any { it in keys } }
        val merged = Entry(
            keys = (keys + existing?.keys.orEmpty()).distinct(),
            title = title.takeIf { it.isNotBlank() } ?: existing?.title ?: "",
            mediaType = normalizedType(mediaType),
            posterUrl = posterUrl ?: existing?.posterUrl,
            at = System.currentTimeMillis(),
            // Keep an earlier year when this hide does not carry one, so a
            // second hide from a year-less surface does not widen the match.
            year = year ?: existing?.year
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
