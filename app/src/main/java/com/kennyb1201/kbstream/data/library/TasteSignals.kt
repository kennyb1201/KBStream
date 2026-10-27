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
 * Thumbs up / thumbs down, one signal per title, per profile.
 *
 * The signal is recorded here and nowhere else: v1 is deliberately LOCAL-ONLY,
 * because neither tracker API this app talks to exposes a rating write
 * (Simkl's service has no rating endpoint, Trakt's has no `sync/ratings` POST,
 * and MDBList is read-only) — so a thumb press never claims a tracker action it
 * did not take. Sync is a separate follow-up as well: the sync payload carries
 * named blobs only, and inventing a carrier for this one is not part of the
 * taste work. What the thumb buys today is a real signal the recommendation
 * blend consumes (see [Snapshot]) plus a mark the viewer can see.
 *
 * Identity follows the hidden-titles rule, because it is the same problem: a
 * title arrives as `tmdb:603` from a TMDB rail and as `tt0133093` from an
 * add-on rail, and the bridge between the two needs a network lookup. An entry
 * therefore keeps EVERY spelling the signalling surface knew (see
 * [HiddenTitles.keysFor]) plus a derived name key, and matching asks with every
 * spelling the reading surface knows, falling back to the name (with the year,
 * so "The Office" (US) never answers for "The Office" (UK)). One title, one
 * row: liking a film from a TMDB rail and then finding it in an add-on rail
 * reads back as liked, and never as two signals.
 *
 * Storage is the HiddenTitles shape (a JSON blob under a profile-namespaced
 * SharedPreferences file, a StateFlow plus a version so screens repaint) with
 * the LocalLibraryStore corrupt-blob discipline on top: a blob that will not
 * parse is served from the last copy that did, and every read-modify-write
 * REFUSES to write over it. Without that, one unreadable blob would read as
 * "nothing is rated" and the next thumb press would persist that emptiness.
 */
object TasteSignals {

    /** Thumbs up. */
    const val LIKE = "LIKE"

    /** Thumbs down. */
    const val DISLIKE = "DISLIKE"

    private const val TAG = "TASTE_SIGNALS"

    private const val PREFS_BASE = "kbstream_taste_signals"
    private const val KEY_ENTRIES = "entries_v1"

    /** Prefix of a derived name key (never matched alone, see [keysFor]). */
    private const val TITLE_KEY_PREFIX = "title::"

    /** Stands in for a year the signalling surface did not know. */
    private const val ANY_YEAR = "*"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * One signalled title.
     *
     * [keys] is every identity spelling (see [keysFor]); [title] and [posterUrl]
     * are carried for diagnostics and for a future manager surface only — v1 has
     * no list UI and no Settings row. There is no rating field: a 1-10 number
     * would have no endpoint to submit to and no rule consuming it, and a field
     * nothing reads is a field that quietly disagrees with the signal beside it.
     */
    @Serializable
    data class Entry(
        val keys: List<String> = emptyList(),
        val title: String = "",
        val mediaType: String = "",
        val posterUrl: String? = null,
        /** [LIKE] or [DISLIKE]. */
        val signal: String = LIKE,
        val updatedAt: Long = 0L
    )

    @Serializable
    private data class Store(
        val entries: List<Entry> = emptyList()
    )

    /**
     * The store without Android: what a consumer (the recommendation blend)
     * reads. Both sides are key SETS, because a signal is matched by whichever
     * spelling the reading surface has in hand.
     */
    data class Snapshot(
        val liked: Set<String> = emptySet(),
        val disliked: Set<String> = emptySet(),
        val likedTitles: List<Entry> = emptyList()
    ) {
        val isEmpty: Boolean get() = liked.isEmpty() && disliked.isEmpty()
    }

    /**
     * The last copy of the blob that PARSED, per profile store - the
     * LocalLibraryStore discipline. A blob that will not parse is served from
     * here rather than as "nothing is rated", and [readOrNull] returning null is
     * what makes a read-modify-write refuse.
     */
    private val lastGood = java.util.concurrent.ConcurrentHashMap<String, List<Entry>>()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Bumped on every change so screens re-read [signalFor]. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** The profile whose set is currently published, so a switch re-reads. */
    private var loadedFor: String? = null

    /**
     * The keys one title is STORED under: the id spellings the surface knew (both
     * flavors, [HiddenTitles.keysFor]), plus the derived name key that bridges the
     * two id namespaces without a lookup - the year form when the surface knew the
     * year, the `*` form only when it did not.
     *
     * Write keys and match keys are deliberately two functions (see [matchKeys]),
     * because the same name key must mean opposite things in the two directions:
     * a STORED entry may say "this year, or no year at all", while a READING
     * surface asking with a year must also accept an entry that never recorded
     * one. Writing both forms at once - the obvious shortcut - registers the entry
     * as year-less and loses the guard entirely (a 2005 "Fight Club" answering for
     * the 1999 one), which is exactly the collision the year is there to prevent.
     */
    fun keysFor(
        mediaType: String?,
        ids: List<String?>,
        title: String?,
        year: Int?
    ): Set<String> = LinkedHashSet<String>().apply {
        addAll(HiddenTitles.keysFor(mediaType, ids))
        nameKey(mediaType, title, year)?.let { add(it) }
    }

    /**
     * The keys a READING surface tests with: the same id spellings, plus both name
     * forms - its own year, and the year-less one an entry stored from a surface
     * without a year carries. Nothing here can match a different year, which is
     * what keeps two titles that share a name apart.
     */
    fun matchKeys(
        mediaType: String?,
        ids: List<String?>,
        title: String?,
        year: Int?
    ): Set<String> = LinkedHashSet<String>().apply {
        addAll(HiddenTitles.keysFor(mediaType, ids))
        addAll(nameMatchKeys(mediaType, title, year))
    }

    /**
     * The signal this title carries, or null when it carries none.
     *
     * [ids] are the spellings the reading surface knows, in the caller's
     * preferred order (the resolved IMDB id first, the raw TMDB id second, the
     * watched-key rule). Matching is the hidden-titles rule: any id spelling
     * first, then the name with the year.
     */
    fun signalFor(
        context: Context,
        mediaType: String?,
        title: String?,
        year: Int?,
        ids: List<String?>
    ): String? {
        val app = context.applicationContext
        ensureLoaded(app)
        val keys = matchKeys(mediaType, ids, title, year)
        if (keys.isEmpty()) return null
        return matching(read(app), keys, mediaType, title, year)?.signal
            ?.takeIf { it == LIKE || it == DISLIKE }
    }

    /**
     * Records [signal] for one title, merging every spelling into the one row
     * it already has (a title signalled under two spellings is ONE signal, never
     * two rows). Returns false when the write was refused: no usable key, or a
     * stored blob that will not parse.
     */
    @Synchronized
    fun setSignal(
        context: Context,
        mediaType: String?,
        title: String?,
        posterUrl: String?,
        year: Int?,
        ids: List<String?>,
        signal: String
    ): Boolean {
        if (signal != LIKE && signal != DISLIKE) return false
        val app = context.applicationContext
        ensureLoaded(app)

        val keys = keysFor(mediaType, ids, title, year)
        if (keys.isEmpty()) return false

        // The row this press is FOR is found with the reading rule (it has to
        // catch the entry as it was written, under its own spellings), and the
        // keys it is stored with are the writing rule's.
        val matchOn = matchKeys(mediaType, ids, title, year)

        // A blob this build cannot read is not an empty set to add to.
        val current = readOrNull(app) ?: return false
        val existing = matching(current, matchOn, mediaType, title, year)
        val kept = current.filterNot { entry -> entry.keys.any { it in matchOn } }
        val merged = Entry(
            keys = (keys + existing?.keys.orEmpty()).distinct(),
            title = title?.trim()?.takeIf { it.isNotBlank() } ?: existing?.title.orEmpty(),
            mediaType = HiddenTitles.normalizedType(mediaType),
            posterUrl = posterUrl?.takeIf { it.isNotBlank() } ?: existing?.posterUrl,
            signal = signal,
            updatedAt = System.currentTimeMillis()
        )
        publish(app, Store(kept + merged))
        Log.i(TAG, "signal $signal: ${merged.title} (${merged.keys.size} keys)")
        return true
    }

    /**
     * Clears the title's signal. True when the store now holds no signal for
     * this title - which includes the case where it held none already, because
     * the press asked for exactly that. False only when the write was refused.
     */
    @Synchronized
    fun clearSignal(
        context: Context,
        mediaType: String?,
        title: String?,
        year: Int?,
        ids: List<String?>
    ): Boolean {
        val app = context.applicationContext
        ensureLoaded(app)
        val keys = matchKeys(mediaType, ids, title, year)
        if (keys.isEmpty()) return false

        val current = readOrNull(app) ?: return false
        val kept = current.filterNot { entry ->
            entry.keys.any { it in keys } ||
                matching(listOf(entry), emptySet(), mediaType, title, year) != null
        }
        if (kept.size != current.size) publish(app, Store(kept))
        return true
    }

    /** The active profile's signals, without Android in the way (the blend's view). */
    fun snapshot(context: Context): Snapshot {
        val app = context.applicationContext
        ensureLoaded(app)
        val all = read(app)
        return Snapshot(
            liked = all.filter { it.signal == LIKE }
                .flatMapTo(LinkedHashSet()) { it.keys },
            disliked = all.filter { it.signal == DISLIKE }
                .flatMapTo(LinkedHashSet()) { it.keys },
            likedTitles = all.filter { it.signal == LIKE }.sortedByDescending { it.updatedAt }
        )
    }

    /**
     * Publishes the active profile's set. Cheap and idempotent, so any screen may
     * call it; the prefs name is compared first so a profile switch is picked up
     * without re-reading on every frame (the HiddenTitles rule).
     */
    fun ensureLoaded(context: Context) {
        val app = context.applicationContext
        val name = ProfileStorage.prefsName(app, PREFS_BASE)
        if (loadedFor == name) return
        loadedFor = name
        _entries.value = read(app).sortedByDescending { it.updatedAt }
    }

    /**
     * What a press on [pressed] means, given the signal the button is showing:
     * pressing the active thumb CLEARS it, pressing the other FLIPS it. Never a
     * third state, never both.
     */
    fun nextSignal(current: String?, pressed: String): String? =
        if (current == pressed) null else pressed

    /** The name key a surface STORES: its year, or the `*` form when it had none. */
    private fun nameKey(mediaType: String?, title: String?, year: Int?): String? {
        val name = HiddenTitles.normalizeTitle(title) ?: return null
        val prefix = "$TITLE_KEY_PREFIX${HiddenTitles.normalizedType(mediaType)}::$name::"
        return "$prefix${year ?: ANY_YEAR}"
    }

    /**
     * The name keys a surface asks WITH: its own year, and the year-less form - so
     * an entry recorded by a surface that had no year still answers, while an
     * entry recorded with a different year does not.
     */
    private fun nameMatchKeys(mediaType: String?, title: String?, year: Int?): List<String> {
        val name = HiddenTitles.normalizeTitle(title) ?: return emptyList()
        val prefix = "$TITLE_KEY_PREFIX${HiddenTitles.normalizedType(mediaType)}::$name::"
        return if (year != null) listOf("$prefix$year", "$prefix$ANY_YEAR") else emptyList()
    }

    /**
     * The one entry [keys] names, or null.
     *
     * Id spellings first (precise), then the name keys, then - only when the
     * surface had no year to give - any entry with this name and type.
     */
    private fun matching(
        entries: List<Entry>,
        keys: Set<String>,
        mediaType: String?,
        title: String?,
        year: Int?
    ): Entry? {
        if (keys.isNotEmpty()) {
            entries.firstOrNull { entry -> entry.keys.any { it in keys } }?.let { return it }
        }
        val name = HiddenTitles.normalizeTitle(title) ?: return null
        val prefix = "$TITLE_KEY_PREFIX${HiddenTitles.normalizedType(mediaType)}::$name::"
        val exact = nameMatchKeys(mediaType, title, year)
        entries.firstOrNull { entry -> entry.keys.any { it in exact } }?.let { return it }
        // No year to compare with: any entry with this name and type matches
        // (permissive on purpose - the viewer explicitly pressed the thumb).
        return if (year == null) {
            entries.firstOrNull { entry -> entry.keys.any { it.startsWith(prefix) } }
        } else {
            null
        }
    }

    /** The parsed blob, or the last copy that parsed - never an empty set for a corrupt one. */
    private fun read(context: Context): List<Entry> =
        readOrNull(context) ?: lastGood[cacheKey(context)].orEmpty()

    /**
     * The blob for a READ-MODIFY-WRITE: null when it exists and will not parse,
     * which is a refusal to write rather than an empty set to add to.
     */
    private fun readOrNull(context: Context): List<Entry>? {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        val parsed = runCatching {
            json.decodeFromString(Store.serializer(), raw).entries
        }.getOrNull()
        if (parsed == null) {
            Log.w(
                TAG,
                "taste blob will not parse; serving the last good copy and refusing to " +
                    "write over it"
            )
            return null
        }
        lastGood[cacheKey(context)] = parsed
        return parsed
    }

    private fun publish(context: Context, store: Store) {
        prefs(context).edit()
            .putString(KEY_ENTRIES, json.encodeToString(Store.serializer(), store))
            .apply()
        loadedFor = ProfileStorage.prefsName(context, PREFS_BASE)
        _entries.value = store.entries.sortedByDescending { it.updatedAt }
        _version.value = _version.value + 1
    }

    private fun cacheKey(context: Context): String =
        ProfileStorage.prefsName(context, PREFS_BASE)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            ProfileStorage.prefsName(context.applicationContext, PREFS_BASE),
            Context.MODE_PRIVATE
        )
}

/**
 * True when this snapshot has a DISLIKE for the title [tmdbId] / [imdbId] /
 * [title] describe.
 *
 * The blend's one taste drop: a title the viewer thumbed down is not
 * recommended back to them. Asked through the SAME key rule the store writes
 * with ([TasteSignals.keysFor]), so a title disliked from an add-on rail - which
 * only knew `tt...` - still drops the TMDB candidate that shares its name and
 * year, and a dislike recorded under the TMDB flavor drops the add-on-flavored
 * copy. Both id flavors plus the name keys, no lookup.
 */
internal fun TasteSignals.Snapshot.isDisliked(
    mediaType: String?,
    title: String?,
    year: Int?,
    tmdbId: Int?,
    imdbId: String? = null
): Boolean {
    if (disliked.isEmpty()) return false
    val keys = TasteSignals.matchKeys(
        mediaType = mediaType,
        ids = listOfNotNull(tmdbId?.takeIf { it > 0 }?.let { "tmdb:$it" }, imdbId),
        title = title,
        year = year
    )
    return keys.any { it in disliked }
}
