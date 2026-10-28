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
 * did not take - and the same holds for the numeric rating, which is local-only
 * until a submission spec lands (see [submissionValue] for the shape that spec
 * would use). Sync is a separate follow-up as well: the sync payload carries
 * named blobs only, and inventing a carrier for this one is not part of the
 * taste work. What the two surfaces buy today is a real signal the
 * recommendation blend consumes (see [Snapshot]) plus a mark the viewer can see.
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

    /** Neither: a mid-range numeric rating (4-6), or an entry with no signal. */
    const val NONE = "NONE"

    /** The numeric scale is 1-10, the scale the trackers a submission would write to use. */
    const val RATING_MIN = 1
    const val RATING_MAX = 10

    /**
     * Where a numeric rating stops being neutral and becomes a verdict.
     *
     * Pinned in exactly this one place: a rating of [RATING_LIKE_FLOOR] and above
     * IS a like, a rating of [RATING_DISLIKE_CEIL] and below IS a dislike, and
     * 4-6 is neither - a genuine mid-range rating that is stored, displayed and
     * submitted like any other, but steers nothing.
     */
    const val RATING_LIKE_FLOOR = 7
    const val RATING_DISLIKE_CEIL = 3

    /**
     * [clearAll]'s answer when the stored blob will not parse: NOTHING changed.
     *
     * A real return value rather than throwing, because the manager's only
     * possible reactions are "report what went" and "report the refusal" - and
     * a cleared-everything answer would be indistinguishable from success.
     */
    const val CLEAR_ALL_REFUSED = -1

    private const val TAG = "TASTE_SIGNALS"

    private const val PREFS_BASE = "kbstream_taste_signals"

    /** The blob this build writes. */
    private const val KEY_ENTRIES = "entries_v2"

    /** The blob the thumbs-only build wrote; read once, then migrated on write. */
    private const val KEY_ENTRIES_V1 = "entries_v1"

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
 * no list UI and no Settings row. The numeric 1-10 rating lives here too, as a
 * second field of the same entry and never as a rival opinion: when a number is
 * present the signal is DERIVED from it (see [deriveSignal]), and a thumb
 * pressed over a number edits that one entry through the truth table in
 * [setSignal]. A stored rating always means the viewer picked a number - no
 * thumb press writes one.
     */
    @Serializable
    data class Entry(
        val keys: List<String> = emptyList(),
        val title: String = "",
        val mediaType: String = "",
        val posterUrl: String? = null,
        /**
         * [LIKE], [DISLIKE] or [NONE] - and when [rating] is present it is
         * DERIVED from it ([deriveSignal]), never independent. There is no
         * state in which a title holds a thumb and a rating that disagree: the
         * thumb controls and the rating dialog are two editors of this one
         * entry, and every writer keeps the invariant.
         */
        val signal: String = LIKE,
        /**
         * The viewer's own 1-10 rating, or null when they never picked a
         * number. A thumb press never writes one (no "LIKE means 8"): a stored
         * rating always means the viewer chose it.
         */
        val rating: Int? = null,
        val updatedAt: Long = 0L
    )

    /**
     * The signal a rating implies. The whole of the thumbs/rating link.
     *
     * A rating of 4-6 derives [NONE]: it is a real rating, and for every
     * consumer it behaves exactly like no signal at all - it neither seeds, nor
     * boosts, nor drops. Nothing else in the app re-derives this.
     */
    fun deriveSignal(rating: Int?): String = when {
        rating == null -> NONE
        rating >= RATING_LIKE_FLOOR -> LIKE
        rating <= RATING_DISLIKE_CEIL -> DISLIKE
        else -> NONE
    }

    /**
     * What this entry MEANS, which is what every consumer reads: the rating's
     * own verdict when there is one, the thumb otherwise.
     */
    fun effectiveSignal(entry: Entry): String =
        if (entry.rating != null) deriveSignal(entry.rating) else entry.signal

    /**
     * The value a tracker submission would write, when the submission spec
     * lands: the viewer's own number when they picked one - 4-6 included, it is
     * a real rating - and the thumb fallback otherwise. Null means there is
     * nothing to submit. Local-only until then; nothing in the app calls this
     * against a tracker today.
     */
    fun submissionValue(signal: String, rating: Int?): Int? = rating
        ?: when (signal) {
            LIKE -> 8
            DISLIKE -> 2
            else -> null
        }

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
        val likedTitles: List<Entry> = emptyList(),
        /**
         * Both-flavor keys to 1-10 ratings, for the entries that carry one - the
         * strength the blend's seed order and the affinity profile read. A
         * title absent here has no numeric rating, however it is signalled.
         */
        val ratingsByKey: Map<String, Int> = emptyMap()
    ) {
        val isEmpty: Boolean get() = liked.isEmpty() && disliked.isEmpty()

        /** The rating for one title, asked with the keys a reading surface knows. */
        fun ratingFor(keys: Set<String>): Int? = keys.firstNotNullOfOrNull { ratingsByKey[it] }
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
        return matching(read(app), keys, mediaType, title, year)
            ?.let(::effectiveSignal)
            ?.takeIf { it == LIKE || it == DISLIKE }
    }

    /**
     * The numeric rating this title carries, or null when it carries none.
     *
     * Asked with the same key rule as [signalFor], so a rating set from a TMDB
     * rail reads back from an add-on rail's `tt...` copy - the rating is a
     * property of the title, not of the surface that recorded it.
     */
    fun ratingFor(
        context: Context,
        mediaType: String?,
        title: String?,
        year: Int?,
        ids: List<String?>
    ): Int? {
        val app = context.applicationContext
        ensureLoaded(app)
        val keys = matchKeys(mediaType, ids, title, year)
        if (keys.isEmpty()) return null
        return matching(read(app), keys, mediaType, title, year)?.rating
    }

    /**
     * Records [rating] for one title, as a numeric edit of the SAME entry the
     * thumbs write.
     *
     * Out of range is a refusal, not a clamp: 0, 11 and anything else outside
     * 1-10 never persist and report failure through the caller's feedback (the
     * dialog's own rule - a rating is never claimed that did not persist).
     */
    @Synchronized
    fun setRating(
        context: Context,
        mediaType: String?,
        title: String?,
        posterUrl: String?,
        year: Int?,
        ids: List<String?>,
        rating: Int
    ): Boolean {
        if (rating !in RATING_MIN..RATING_MAX) return false
        val app = context.applicationContext
        ensureLoaded(app)

        val keys = keysFor(mediaType, ids, title, year)
        if (keys.isEmpty()) return false
        val matchOn = matchKeys(mediaType, ids, title, year)
        val current = readOrNull(app) ?: return false
        val existing = matching(current, matchOn, mediaType, title, year)
        val kept = current.filterNot { entry -> entry.keys.any { it in matchOn } }
        val merged = Entry(
            keys = (keys + existing?.keys.orEmpty()).distinct(),
            title = title?.trim()?.takeIf { it.isNotBlank() } ?: existing?.title.orEmpty(),
            mediaType = HiddenTitles.normalizedType(mediaType),
            posterUrl = posterUrl?.takeIf { it.isNotBlank() } ?: existing?.posterUrl,
            signal = deriveSignal(rating),
            rating = rating,
            updatedAt = System.currentTimeMillis()
        )
        publish(app, Store(kept + merged))
        TasteProfileStore.markDirty(app)
        Log.i(TAG, "rating $rating (${merged.signal}): ${merged.title}")
        // The tracker write rides along, local-first as always: the number the
        // viewer picked IS the submitted value (4-6 included, it is a real
        // rating), and the local write is already done whatever happens next.
        enqueueSubmission(
            app = app,
            keys = merged.keys,
            mediaType = merged.mediaType,
            title = merged.title,
            rating = rating,
            lastRating = null,
            signal = merged.signal
        )
        return true
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

        // A rating and a thumb are one signal (the entry's own truth table).
        // With a number present the press edits it:
        //  - pressing the thumb the number already earned clears the whole
        //    signal, number included (the active-thumb rule, applied to the
        //    derived state);
        //  - pressing the other thumb drops the number and sets the thumb, so
        //    "liked AND rated 2" is not a state this store can hold.
        val existingRating = existing?.rating
        if (existingRating != null && existing != null) {
            if (deriveSignal(existingRating) == signal) {
                publish(app, Store(kept))
                TasteProfileStore.markDirty(app)
                Log.i(TAG, "signal cleared with its rating: ${existing.title}")
                // A clear is the viewer taking their rating back: the tracker
                // hears a REMOVE, not a low rating.
                enqueueSubmission(
                    app = app,
                    keys = existing.keys,
                    mediaType = existing.mediaType,
                    title = existing.title,
                    rating = null,
                    lastRating = existingRating,
                    signal = effectiveSignal(existing)
                )
                return true
            }
            val merged = Entry(
                keys = (keys + existing.keys).distinct(),
                title = title?.trim()?.takeIf { it.isNotBlank() } ?: existing.title,
                mediaType = HiddenTitles.normalizedType(mediaType),
                posterUrl = posterUrl?.takeIf { it.isNotBlank() } ?: existing.posterUrl,
                signal = signal,
                rating = null,
                updatedAt = System.currentTimeMillis()
            )
            publish(app, Store(kept + merged))
            TasteProfileStore.markDirty(app)
            Log.i(TAG, "signal $signal drops the rating: ${merged.title}")
            enqueueSubmission(
                app = app,
                keys = merged.keys,
                mediaType = merged.mediaType,
                title = merged.title,
                rating = TasteRatingMapping.thumbToRating(signal),
                lastRating = existingRating,
                signal = signal
            )
            return true
        }

        // Thumb-only: the v1 rule - the active thumb clears, the other flips.
        val next = nextSignal(effectiveSignalOrNull(existing), signal)
        if (next == null) {
            publish(app, Store(kept))
            TasteProfileStore.markDirty(app)
            Log.i(TAG, "signal cleared: ${existing?.title}")
            existing?.let { cleared ->
                enqueueSubmission(
                    app = app,
                    keys = cleared.keys,
                    mediaType = cleared.mediaType,
                    title = cleared.title,
                    rating = null,
                    lastRating = cleared.rating,
                    signal = effectiveSignal(cleared)
                )
            }
            return true
        }
        val merged = Entry(
            keys = (keys + existing?.keys.orEmpty()).distinct(),
            title = title?.trim()?.takeIf { it.isNotBlank() } ?: existing?.title.orEmpty(),
            mediaType = HiddenTitles.normalizedType(mediaType),
            posterUrl = posterUrl?.takeIf { it.isNotBlank() } ?: existing?.posterUrl,
            signal = next,
            updatedAt = System.currentTimeMillis()
        )
        publish(app, Store(kept + merged))
        // The affinity profile is built from these signals, so a press makes it
        // stale. Marked here rather than detected later: a flip keeps the count
        // the same, and only the stamp proves it changed.
        TasteProfileStore.markDirty(app)
        Log.i(TAG, "signal $signal: ${merged.title} (${merged.keys.size} keys)")
        // Thumb-only: the mapping is the submitted number (LIKE 8 / DISLIKE 2),
        // and it is applied HERE rather than stored - no thumb press ever writes
        // a rating into the entry.
        enqueueSubmission(
            app = app,
            keys = merged.keys,
            mediaType = merged.mediaType,
            title = merged.title,
            rating = TasteRatingMapping.thumbToRating(next),
            lastRating = null,
            signal = next
        )
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
        if (kept.size != current.size) {
            publish(app, Store(kept))
            TasteProfileStore.markDirty(app)
            // Whatever was cleared is taken back from the trackers too, with the
            // entry's own keys so the removal supersedes any pending submit for
            // this title (see TasteRatingQueueRules.coalesce).
            current.firstOrNull { entry -> kept.none { it === entry } }?.let { cleared ->
                enqueueSubmission(
                    app = app,
                    keys = cleared.keys,
                    mediaType = cleared.mediaType,
                    title = cleared.title,
                    rating = null,
                    lastRating = cleared.rating,
                    signal = effectiveSignal(cleared)
                )
            }
        }
        return true
    }

    /**
     * Removes exactly the entry [keys] name - the Rated Titles pane's per-row
     * press, where the pressed row IS the entry and its published key set is its
     * identity.
     *
     * NO name fallback, deliberately, and that is the whole reason this is not
     * the five-argument [clearSignal]: a press on one row must never take a
     * same-named title beside it, and here it cannot - the entry's key set
     * already carries every spelling it was written under (both id flavors, plus
     * its derived name key), so an overlap IS this title. The other overload
     * exists for the surfaces that hold an identity rather than an entry, and
     * matches permissively on purpose because the viewer explicitly pressed a
     * thumb there.
     *
     * True when the store no longer holds that entry - including the case where
     * it held none, because the press asked for exactly that. False only when the
     * stored blob will not parse, so nothing changed.
     */
    @Synchronized
    fun clearSignal(context: Context, keys: List<String>): Boolean {
        val app = context.applicationContext
        ensureLoaded(app)
        val on = keys.filter { key -> key.isNotBlank() }.toSet()
        if (on.isEmpty()) return false
        val current = readOrNull(app) ?: return false
        val removed = current.filter { entry -> entry.keys.any { it in on } }
        if (removed.isEmpty()) return true
        publish(app, Store(current.filterNot { entry -> entry.keys.any { it in on } }))
        TasteProfileStore.markDirty(app)
        removed.forEach { cleared ->
            enqueueSubmission(
                app = app,
                keys = cleared.keys,
                mediaType = cleared.mediaType,
                title = cleared.title,
                rating = null,
                lastRating = cleared.rating,
                signal = effectiveSignal(cleared)
            )
        }
        return true
    }

    /**
     * Clears EVERY signal this profile holds, and reports how many rows went.
     *
     * The Rated Titles pane's clear-all: one blob write and one version bump,
     * through the same [publish] path every other write takes, so the manager's
     * own list and every other taste surface repaint from the same bump.
     *
     * ONE PROFILE, ONE DEVICE. Another profile's signals are another file and
     * are untouched, and nothing here reaches the network directly - each
     * removed entry is handed to the tracker queue exactly as a single clear is
     * (see [enqueueSubmission], a no-op while no tracker leg is open).
     *
     * A blob this build cannot read is a REFUSAL ([CLEAR_ALL_REFUSED]), never a
     * reset to empty. This is where it deliberately does NOT copy
     * [HiddenTitles.unhideAll]: for hiding, resetting an unreadable blob fails
     * OPEN (nothing stays hidden), but for signals the same reset would silently
     * delete the viewer's whole taste - and the manager, the one surface built
     * to show it, would read as "nothing was ever rated".
     */
    @Synchronized
    fun clearAll(context: Context): Int {
        val app = context.applicationContext
        ensureLoaded(app)
        val current = readOrNull(app) ?: return CLEAR_ALL_REFUSED
        if (current.isEmpty()) return 0
        publish(app, Store(emptyList()))
        TasteProfileStore.markDirty(app)
        // Each removed entry is a clear of its own title, so the trackers are
        // told the same thing they are told by a per-title clear - with the
        // entry's OWN keys, so a bulk clear supersedes any pending submit for
        // each title (TasteRatingQueueRules.coalesce).
        current.forEach { cleared ->
            enqueueSubmission(
                app = app,
                keys = cleared.keys,
                mediaType = cleared.mediaType,
                title = cleared.title,
                rating = null,
                lastRating = cleared.rating,
                signal = effectiveSignal(cleared)
            )
        }
        return current.size
    }

    /** The active profile's signals, without Android in the way (the blend's view). */
    fun snapshot(context: Context): Snapshot {
        val app = context.applicationContext
        ensureLoaded(app)
        val all = read(app)
        val ratings = LinkedHashMap<String, Int>()
        all.forEach { entry ->
            entry.rating?.let { rating -> entry.keys.forEach { ratings[it] = rating } }
        }
        return Snapshot(
            // The EFFECTIVE signal everywhere: a 9-rating is a like and a
            // 4-rating is nothing, whatever the stored thumb says (and the
            // writer keeps the stored thumb in step with the number anyway).
            liked = all.filter { effectiveSignal(it) == LIKE }
                .flatMapTo(LinkedHashSet()) { it.keys },
            disliked = all.filter { effectiveSignal(it) == DISLIKE }
                .flatMapTo(LinkedHashSet()) { it.keys },
            likedTitles = all.filter { effectiveSignal(it) == LIKE }
                .sortedByDescending { it.updatedAt },
            ratingsByKey = ratings
        )
    }

    /** The effective signal of an entry, or null when it carries none. */
    private fun effectiveSignalOrNull(entry: Entry?): String? =
        entry?.let(::effectiveSignal)?.takeIf { it == LIKE || it == DISLIKE }

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

    /**
     * Hands a just-written signal to the tracker-submission queue.
     *
     * This store is the ONLY thing that enqueues (see [TasteRatingSubmission]):
     * no thumbs row, menu, dialog or ViewModel talks to a tracker about taste, so
     * a surface added later gets submission for free by writing through here, and
     * the local write has already happened whatever the tracker does next.
     *
     * The ENTRY's own key set travels, never the calling surface's: it holds
     * every spelling the store knows for this title, which is what lets a clear
     * from an add-on rail supersede a submit from a TMDB rail for the same title
     * (see TasteRatingQueueRules.coalesce). A blank set is refused by the queue
     * itself, and the whole call is a no-op while no leg is open.
     */
    private fun enqueueSubmission(
        app: Context,
        keys: List<String>,
        mediaType: String?,
        title: String?,
        rating: Int?,
        lastRating: Int?,
        signal: String
    ) {
        TasteRatingSubmission.onSignalWritten(
            context = app,
            keys = keys,
            mediaType = HiddenTitles.normalizedType(mediaType),
            title = title.orEmpty(),
            rating = rating,
            lastRating = lastRating,
            signal = signal
        )
    }

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
        val store = prefs(context)
        val raw = store.getString(KEY_ENTRIES, null)
        if (raw != null) {
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

        // Migration from the thumbs-only blob: read once, serve it with no
        // ratings, and persist v2 lazily on the first successful write (which
        // removes v1 only after the v2 blob is written). A v1 blob that will
        // not parse is NOT "migrated" into an empty v2 blob - it refuses, so the
        // last readable copy survives for a build that can read it.
        val legacy = store.getString(KEY_ENTRIES_V1, null) ?: return emptyList()
        val migrated = runCatching {
            json.decodeFromString(Store.serializer(), legacy).entries
        }.getOrNull()
        if (migrated == null) {
            Log.w(
                TAG,
                "thumbs-only taste blob will not parse; serving the last good copy and " +
                    "refusing to write over it"
            )
            return null
        }
        lastGood[cacheKey(context)] = migrated
        return migrated
    }

    private fun publish(context: Context, store: Store) {
        prefs(context).edit()
            .putString(KEY_ENTRIES, json.encodeToString(Store.serializer(), store))
            // The migration's second half: the thumbs-only blob goes only after
            // a readable v2 blob is on disk, so no crash between the two can
            // leave the store with neither.
            .remove(KEY_ENTRIES_V1)
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

/**
 * Where the viewer's taste puts one title, for the consumers OUTSIDE the blend
 * - search's merged order and the Library's LIKED sort: liked first, everything
 * un-signalled in the middle, disliked last.
 *
 * Declared as an enum in sort order rather than as a 0/1/2 rank, so a consumer
 * says what it means ([TastePlacement.LIKED]) instead of carrying magic numbers
 * around, and `compareBy` / `sortedBy` order a group without a lookup table.
 */
enum class TastePlacement { LIKED, NONE, DISLIKED }

/**
 * The [TastePlacement] of one title, asked with the ID spellings the reading
 * surface knows - and NOTHING else. No name fallback: a surface holding no id
 * reads as [TastePlacement.NONE] rather than borrowing another title's signal
 * off a shared name.
 *
 * The spellings are the store's own key rule ([HiddenTitles.keysFor]), which is
 * NOT the watched-badge rule: a TMDB id is stored typed (`"movie::tmdb:603"`,
 * because movie 603 and series 603 are different titles) while an add-on's
 * `"tt0133093"` is stored untyped (`"id::tt0133093"`). Asking with
 * `type::tt...` would miss every signal recorded from an add-on rail, which is
 * why the raw id spellings are handed over here and the key rule is applied in
 * one place instead of at each consumer.
 */
fun TasteSignals.Snapshot.placementOf(
    mediaType: String?,
    ids: List<String?>
): TastePlacement {
    val keys = HiddenTitles.keysFor(mediaType, ids)
    return when {
        keys.any { it in liked } -> TastePlacement.LIKED
        keys.any { it in disliked } -> TastePlacement.DISLIKED
        else -> TastePlacement.NONE
    }
}
