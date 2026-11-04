package com.kennyb1201.kbstream.data.library

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kennyb1201.kbstream.data.sync.HiddenTitlesRules
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
 * Profile-scoped and synced device to device, like the hidden browse chips:
 * which titles are hidden is a property of the profile that hid them, so a kids
 * profile can be kept clean without hiding anything from the adult one, and a
 * hide made on the television reaches the phone's copy of that same profile.
 * The set travels as a MERGE, never as a replacement (see [mergeFromSync]): per
 * title the newest event wins, an unhide travels as a [Removed] record so a
 * sibling's older hide cannot put the title back, a device with nothing to say
 * publishes nothing, and a corrupt local blob refuses merges instead of
 * adopting a sibling's set over its own.
 */
object HiddenTitles {

    private const val TAG = "HIDDEN_TITLES"

    private const val PREFS_BASE = "kbstream_hidden_titles"
    private const val KEY_ENTRIES = "entries_v1"

    /**
     * The removal records (see [Removed]). Same prefs file as the entries, so
     * profile scoping comes free and there is no second namespace to keep in
     * step.
     */
    private const val KEY_REMOVED = "removed_v1"

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

    /**
     * One unhide, as it travels to the other devices.
     *
     * A hide leaves nothing behind in [entries] once it is gone, so without a
     * record of the removal a sibling's older hide of the same title would
     * simply be merged back in - and the title would reappear on the device that
     * just unhid it. This is the watched-overrides unmark, per title. No poster:
     * a removal is never drawn.
     */
    @Serializable
    private data class Removed(
        val keys: List<String> = emptyList(),
        val title: String = "",
        val mediaType: String = "",
        val year: Int? = null,
        val at: Long = 0L
    )

    @Serializable
    private data class Store(
        val entries: List<Entry> = emptyList()
    )

    @Serializable
    private data class RemovedStore(
        val removed: List<Removed> = emptyList()
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

    /**
     * Hides one title; returns true when it was stored.
     *
     * @Synchronized on the mutators (hide/unhide/unhideAll/clear): each is
     * read-blob -> transform -> write-blob over one JSON store, so two
     * interleaved calls lose one of the two updates. LocalLibraryStore, the
     * same shape over the same kind of blob, is synchronized for this reason.
     */
    @Synchronized
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
        // A re-hide is newer than the removal record for the same title, so that
        // record is superseded and goes: leaving it would suppress a later
        // merge of this very hide.
        pruneSupersededRemoved(app, merged)
        Log.i(TAG, "hidden: ${merged.title} (${merged.keys.size} keys)")
        publishToAccount(app)
        return true
    }

    /**
     * Brings one hidden title back; returns true when something changed.
     *
     * Every removal is recorded before the store is written (see [Removed]):
     * the unhide is what has to reach the other devices, and it is invisible in
     * the entries themselves once it has happened.
     */
    @Synchronized
    fun unhide(context: Context, keys: Collection<String>): Boolean {
        val app = context.applicationContext
        val current = read(app).entries
        val target = keys.toSet()
        val gone = current.filter { entry -> entry.keys.any { it in target } }
        if (gone.isEmpty()) return false
        val kept = current.filterNot { entry -> entry.keys.any { it in target } }
        recordRemoved(app, gone)
        write(app, Store(kept))
        publishToAccount(app)
        return true
    }

    /**
     * Clears the set, one removal record per entry rather than a "cleared"
     * stamp: a stamp would need a second precedence rule against the per-title
     * hides that come later, while per-entry records keep exactly one rule.
     */
    @Synchronized
    fun unhideAll(context: Context) {
        val app = context.applicationContext
        val current = read(app).entries
        if (current.isEmpty()) return
        recordRemoved(app, current)
        write(app, Store(emptyList()))
        publishToAccount(app)
    }

    // ── Cross-device sync ───────────────────────────────────────────
    //
    // The set was device-local until this section existed: a title hidden on the
    // television stayed on the phone's rails, because nothing in the sync
    // payload carried it (the IPTV blob does carry hidden CHANNEL GROUPS, which
    // is why expecting hidden titles to sync was reasonable). The store travels
    // as its own encoded text - the taste-signals rule - so this file stays the
    // single owner of the schema and a future field on [Entry] syncs without a
    // second serialization to keep in step.
    //
    // ONE rule decides every conflict, per TITLE:
    //
    //   For one title the newest event wins. A hide at H and an unhide at U:
    //   hidden when H > U, unhidden when U >= H. A tie goes to the unhide, so
    //   hide-then-unhide inside one millisecond cannot resurrect on a sibling.
    //
    // Titles that never conflict union - two devices that hid different titles
    // while offline both end up hiding both - and nothing is ever replaced
    // wholesale, so neither side's existing hides can be wiped by the other's
    // set.

    /**
     * ONE profile's hides, encoded, for the sync blob - or null when there is
     * nothing to say.
     *
     * A blob this build cannot parse answers null: publishing a repair of a
     * damaged blob would push that repair over a good copy on every other
     * device. [read] masks damage as an empty store for the UI, and that masked
     * "nothing is hidden" must never become publishable.
     */
    fun encodedForSync(context: Context, profileId: String? = null): String? {
        val raw = prefsFor(context.applicationContext, profileId).getString(KEY_ENTRIES, null)
        if (raw.isNullOrBlank()) return null
        return if (parseEntriesOrNull(raw) != null) raw else null
    }

    /**
     * ONE profile's removal records, encoded, for the sync blob - or null when
     * it has none. Refuses a damaged blob exactly as [encodedForSync] does.
     */
    internal fun removedEncodedForSync(context: Context, profileId: String? = null): String? {
        val raw = prefsFor(context.applicationContext, profileId).getString(KEY_REMOVED, null)
        if (raw.isNullOrBlank()) return null
        return if (parseRemovedOrNull(raw) != null) raw else null
    }

    /**
     * True when this profile has something to say: hides OR unhides.
     *
     * The watched-overrides gate, not the taste one: "a device that only ever
     * unhid still has something to say", and gating on entries alone would
     * strand that unhide. It is also what stops a fresh device from publishing
     * an empty set over the account's hides before its first pull.
     */
    fun hasHiddenTitlesToPublish(context: Context, profileId: String? = null): Boolean {
        val prefs = prefsFor(context.applicationContext, profileId)
        val hides = parseEntriesOrNull(prefs.getString(KEY_ENTRIES, null)).orEmpty().size
        val removals = parseRemovedOrNull(prefs.getString(KEY_REMOVED, null)).orEmpty().size
        return HiddenTitlesRules.shouldPublish(hides, removals)
    }

    /**
     * Merges another device's blob into ONE profile's store, and returns how
     * many rows it changed (0 means the account had nothing new).
     *
     * The merge is per title, newest event wins, keys united - see the block
     * comment above for the rule and [HiddenTitlesRules] for the comparison. A
     * corrupt LOCAL store refuses the whole merge: the incoming blob is
     * recoverable from the account, the local one is not. A corrupt remote
     * contributes nothing, because "I cannot read this" is never "erase
     * yours".
     *
     * This never goes through [hide]: `hide` refreshes `at` to now, which would
     * rewrite the very timestamps the merge decides by.
     */
    @Synchronized
    fun mergeFromSync(
        context: Context,
        entriesRaw: String,
        removedRaw: String,
        profileId: String? = null
    ): Int {
        val app = context.applicationContext
        val remoteEntries = parseEntriesOrNull(entriesRaw).orEmpty()
        val remoteRemoved = parseRemovedOrNull(removedRaw).orEmpty()
        if (remoteEntries.isEmpty() && remoteRemoved.isEmpty()) {
            Log.w(TAG, "sync blob carries no hides and no unhides; nothing merged")
            return 0
        }
        val prefs = prefsFor(app, profileId)
        val local = readOrNull(prefs) ?: return 0
        val localRemoved = readRemovedOrNull(prefs) ?: return 0

        var changed = 0

        // 1. Hides, united per title. The newer entry keeps its fields - a
        //    poster the newer device knew beats the older one - and both sides'
        //    spellings survive, so the merged row answers any surface's ids.
        val merged = local.entries.toMutableList()
        remoteEntries.forEach { incoming ->
            val index = merged.indexOfFirst { sameTitle(it, incoming) }
            if (index < 0) {
                merged.add(incoming)
                changed += 1
                return@forEach
            }
            val existing = merged[index]
            val winner = if (incoming.at > existing.at) incoming else existing
            val next = winner.copy(keys = (existing.keys + incoming.keys).distinct())
            if (next != existing) {
                merged[index] = next
                changed += 1
            }
        }

        // 2. Unhides, united the same way, so both devices hold the same records
        //    and suppress the same stale hides.
        val removed = localRemoved.toMutableList()
        remoteRemoved.forEach { incoming ->
            val index = removed.indexOfFirst { sameTitle(it, incoming) }
            if (index < 0) {
                removed.add(incoming)
                changed += 1
                return@forEach
            }
            val existing = removed[index]
            val winner = if (incoming.at > existing.at) incoming else existing
            val next = winner.copy(keys = (existing.keys + incoming.keys).distinct())
            if (next != existing) {
                removed[index] = next
                changed += 1
            }
        }

        // 3. The rule: a removal at or after the hide takes the title out.
        val kept = merged.filterNot { entry ->
            removed.any { tomb ->
                sameTitle(entry, tomb) && HiddenTitlesRules.tombstoneWins(entry.at, tomb.at)
            }
        }
        changed += merged.size - kept.size

        // ...and a hide newer than its removal supersedes it, so a re-hide does
        // not leave a record behind that would suppress the next merge.
        val liveRemoved = removed.filterNot { tomb ->
            kept.any { entry ->
                sameTitle(entry, tomb) && HiddenTitlesRules.hideSupersedesTombstone(entry.at, tomb.at)
            }
        }
        val finalRemoved = pruneRemoved(liveRemoved)

        val encodedEntries =
            json.encodeToString(Store.serializer(), Store(kept))
        val encodedRemoved =
            json.encodeToString(RemovedStore.serializer(), RemovedStore(finalRemoved))
        if (encodedEntries == prefs.getString(KEY_ENTRIES, null) &&
            encodedRemoved == prefs.getString(KEY_REMOVED, null)
        ) {
            return 0
        }

        prefs.edit()
            .putString(KEY_ENTRIES, encodedEntries)
            .putString(KEY_REMOVED, encodedRemoved)
            .apply()

        // The flows serve the ACTIVE profile. A merge into a sibling's file is
        // written and left there for that profile's next switch.
        if (storeName(app, profileId) == ProfileStorage.prefsName(app, PREFS_BASE)) {
            loadedFor = storeName(app, profileId)
            _entries.value = kept.sortedByDescending { it.at }
            _version.value = _version.value + 1
        }
        return changed
    }

    /**
     * Records one removal per entry that just went, newest first.
     *
     * Only called when something was actually removed: an unhide that matched
     * nothing writes nothing, here as before.
     */
    private fun recordRemoved(context: Context, gone: List<Entry>) {
        if (gone.isEmpty()) return
        val prefs = prefs(context)
        val current = readRemovedOrNull(prefs) ?: return
        val now = System.currentTimeMillis()
        val next = pruneRemoved(
            current + gone.map { entry ->
                Removed(
                    keys = entry.keys,
                    title = entry.title,
                    mediaType = entry.mediaType,
                    year = entry.year,
                    at = now
                )
            }
        )
        prefs.edit()
            .putString(KEY_REMOVED, json.encodeToString(RemovedStore.serializer(), RemovedStore(next)))
            .apply()
    }

    /** Drops the removal records a newer hide of the same title has superseded. */
    private fun pruneSupersededRemoved(context: Context, entry: Entry) {
        val prefs = prefs(context)
        val current = readRemovedOrNull(prefs) ?: return
        val kept = current.filterNot { tomb ->
            sameTitle(entry, tomb) &&
                HiddenTitlesRules.hideSupersedesTombstone(entry.at, tomb.at)
        }
        if (kept.size == current.size) return
        prefs.edit()
            .putString(KEY_REMOVED, json.encodeToString(RemovedStore.serializer(), RemovedStore(kept)))
            .apply()
    }

    /**
     * The removal records this device keeps: newest first, capped.
     *
     * The cap is the library record's number, for the same reason - nothing that
     * grows per removal may grow without end. Pruning can in principle let a
     * device that has been offline for a very long time re-propagate a stale
     * hide; that is the limitation the library's tombstone record already
     * accepts, and a re-hide is one tap to undo.
     */
    private fun pruneRemoved(removed: List<Removed>): List<Removed> =
        removed.sortedByDescending { it.at }.take(HiddenTitlesRules.MAX_REMOVED)

    /**
     * Hands a local edit to the account, so a hide or unhide made here reaches
     * the other devices without waiting for the next full sync.
     *
     * The write has already happened and is what the viewer pressed; a failed
     * enqueue is carried by the next full sync (sign-in / Sync now / resync),
     * exactly as the taste signals' is. No new transport: the outbox, its 400 ms
     * debounce, the flush loop and OutboxFlushWorker are reused as they are.
     */
    private fun publishToAccount(context: Context) {
        runCatching {
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                context,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_HIDDEN_TITLES,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildHiddenTitles(context)
            )
        }.onFailure { Log.w(TAG, "could not queue the hidden titles for sync: ${it.message}") }
    }

    /**
     * Two rows are the same title when they share a spelling, or when their
     * normalized names and types agree - the taste rule, which is what lets an
     * unhide recorded under `id::tt0133093` suppress a hide merged in under
     * `movie::tmdb:603`.
     */
    private fun sameTitle(
        keysA: List<String>,
        titleA: String,
        typeA: String,
        keysB: List<String>,
        titleB: String,
        typeB: String
    ): Boolean {
        if (keysA.any { it in keysB }) return true
        val nameA = normalizeTitle(titleA) ?: return false
        val nameB = normalizeTitle(titleB) ?: return false
        return nameA == nameB && normalizedType(typeA) == normalizedType(typeB)
    }

    private fun sameTitle(a: Entry, b: Entry) =
        sameTitle(a.keys, a.title, a.mediaType, b.keys, b.title, b.mediaType)

    private fun sameTitle(a: Entry, b: Removed) =
        sameTitle(a.keys, a.title, a.mediaType, b.keys, b.title, b.mediaType)

    private fun sameTitle(a: Removed, b: Removed) =
        sameTitle(a.keys, a.title, a.mediaType, b.keys, b.title, b.mediaType)

    /** The local store, or null when the blob will not parse (a merge refuses it). */
    private fun readOrNull(prefs: SharedPreferences): Store? {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return Store()
        return runCatching { json.decodeFromString(Store.serializer(), raw) }.getOrNull()
    }

    private fun readRemovedOrNull(prefs: SharedPreferences): List<Removed>? =
        parseRemovedOrNull(prefs.getString(KEY_REMOVED, null))

    /** Remote hides; unparseable reads as nothing to say, never as "erase yours". */
    private fun parseEntriesOrNull(raw: String?): List<Entry>? {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(Store.serializer(), raw).entries }.getOrNull()
    }

    private fun parseRemovedOrNull(raw: String?): List<Removed>? {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(RemovedStore.serializer(), raw).removed
        }.getOrNull()
    }

    private fun storeName(context: Context, profileId: String?): String =
        if (profileId == null) {
            ProfileStorage.prefsName(context, PREFS_BASE)
        } else {
            ProfileStorage.prefsName(profileId, PREFS_BASE)
        }

    private fun prefsFor(context: Context, profileId: String? = null) =
        context.applicationContext.getSharedPreferences(
            storeName(context.applicationContext, profileId),
            Context.MODE_PRIVATE
        )

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

    private fun prefs(context: Context) = prefsFor(context, null)
}
