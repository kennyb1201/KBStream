package com.kennyb1201.kbstream.data.library

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.sync.KidsMode
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.data.tmdb.AFFINITY_EVIDENCE_CAP
import com.kennyb1201.kbstream.data.tmdb.AffinityEntry
import com.kennyb1201.kbstream.data.tmdb.EvidenceFingerprint
import com.kennyb1201.kbstream.data.tmdb.TasteEvidenceTitle
import com.kennyb1201.kbstream.data.tmdb.TasteProfile
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.affinityEvidenceKey
import com.kennyb1201.kbstream.data.tmdb.buildTasteProfile
import com.kennyb1201.kbstream.data.tmdb.certification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The affinity profile's store: one JSON blob per profile, rebuilt lazily at
 * blend-build time.
 *
 * The store's job is evidence GATHERING - the profile's arithmetic lives in
 * [buildTasteProfile] and is pure. Gathering is deliberately quiet:
 *
 *  - **Zero new calls.** Evidence detail payloads are read through the
 *    repository's cache-only read ([TmdbRepository.cachedDetailByTmdbId]),
 *    never a network fetch. A cache miss contributes no dimensions and still
 *    holds its evidence slot, so a cold cache cannot rewrite the profile from
 *    older history.
 *  - **Completions come from the query Home already pays for**
 *    ([com.kennyb1201.kbstream.data.history.WatchHistoryDao.getRecentCompletedSeeds]),
 *    one row per parent, newest first. No second query, no new index.
 *  - **No worker.** A periodic rebuild would recompute a profile nobody may read
 *    before the next signal changes it, on devices where the guide and import
 *    workers already share the disk. The profile rebuilds when a blend build
 *    asks for it and the evidence has moved; otherwise it is a map read.
 *
 * Storage is the HiddenTitles shape (a JSON blob under a profile-namespaced
 * SharedPreferences file, a StateFlow plus a version so screens repaint) with
 * the LocalLibraryStore corrupt-blob discipline on top, NOT HiddenTitles' own:
 * a blob that will not parse is served from the last copy that did and every
 * write REFUSES to run over it. Copying the nearer file would mean one bad read
 * reads as "no taste" and the next rebuild persists that emptiness over a
 * lifetime of evidence.
 */
object TasteProfileStore {

    private const val TAG = "TASTE_PROFILE"

    private const val PREFS_BASE = "kbstream_taste_profile"
    private const val KEY_PROFILE = "profile_v1"

    /**
     * How many recent completion rows are read when the evidence is gathered.
     *
     * Twice the evidence cap, so titles that turn out to duplicate an explicit
     * signal, or to resolve to nothing, cannot starve the completions side of
     * the profile.
     */
    private const val EVIDENCE_SCAN = AFFINITY_EVIDENCE_CAP * 2

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * The stored profile: computed entries plus what they were built from.
     *
     * [StoredBlob.evidenceFingerprint] is the staleness detector - enough to
     * notice a new signal, a flipped one or a new completion without re-reading
     * the evidence to find out, and cheap enough to compute on every blend build
     * (the signal store's own list plus one capped history read).
     * [StoredBlob.dirty] is the fast path the signal writers set directly, so a
     * write the fingerprint cannot distinguish (two presses in the same
     * millisecond) still rebuilds.
     */
    @Serializable
    private data class StoredBlob(
        val entries: List<AffinityEntry> = emptyList(),
        val evidenceFingerprint: EvidenceFingerprint = EvidenceFingerprint(),
        val computedAt: Long = 0L,
        val dirty: Boolean = true
    )

    /**
     * The last copy of the blob that PARSED, per profile store. A blob that will
     * not parse is served from here rather than as "no taste", and [readOrNull]
     * returning null is what makes a write refuse.
     */
    private val lastGood = java.util.concurrent.ConcurrentHashMap<String, StoredBlob>()

    /** Serializes rebuilds: two blend builds racing must compute one profile. */
    private val rebuildMutex = Mutex()

    private val _profile = MutableStateFlow<TasteProfile?>(null)

    /**
     * The profile a diagnostics surface or a future viewer reads.
     *
     * `internal` like the profile type itself: [TasteProfile] is the taste
     * subsystem's own shape (`TasteProfileBuilder`), not a published one, and a
     * public `StateFlow` of it would be the leak that declares it.
     */
    internal val profile: StateFlow<TasteProfile?> = _profile.asStateFlow()

    /** Bumped on every change, the HiddenTitles/TasteSignals pattern. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** The profile whose blob is currently loaded, so a switch re-reads. */
    private var loadedFor: String? = null

    /** The loaded blob (dirty flag + fingerprint), kept beside its snapshot. */
    private var loadedBlob: StoredBlob? = null

    /** The loaded profile's runtime snapshot (persisted entries, no rebuild). */
    private var loaded: TasteProfile? = null

    /**
     * The profile a blend build should score with, rebuilding only when the
     * evidence has moved.
     *
     * Called from the blend surfaces, which are already off the main thread and
     * already async; this adds one capped history read and, when something
     * changed, the evidence reads. A failed rebuild never empties a persisted
     * profile and never fails a build: the last stored profile is used instead,
     * and with none stored the caller blends with an empty profile - which is
     * byte-identical to blending with no profile at all.
     */
    internal suspend fun current(
        context: Context,
        repo: TmdbRepository
    ): TasteProfile? =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val name = ProfileStorage.prefsName(app, PREFS_BASE)
            rebuildMutex.withLock {
                ensureLoaded(app, name)
                val rows = completionRows(app)
                val fingerprint = fingerprint(app, rows)
                val stored = loadedBlob
                if (stored != null && !stored.dirty &&
                    stored.evidenceFingerprint == fingerprint && loaded != null
                ) {
                    return@withLock loaded
                }
                runCatching { rebuild(app, repo, rows, fingerprint, name) }
                    .onFailure { error ->
                        Log.w(TAG, "profile rebuild failed; keeping the stored one", error)
                    }
                    .getOrNull() ?: loaded
            }
        }

    /**
     * Marks the stored profile stale. Called by the signal writers, so a thumb
     * press is reflected by the next blend build without waiting for a
     * fingerprint the press might not change (a flip inside one millisecond).
     *
     * A write over an unreadable blob is refused, exactly as every other write
     * here is: marking an unparseable store dirty would rewrite it on the next
     * rebuild.
     */
    @Synchronized
    fun markDirty(context: Context) {
        val app = context.applicationContext
        val name = ProfileStorage.prefsName(app, PREFS_BASE)
        val blob = readOrNull(app, name) ?: return
        // A profile that was never written is EMPTY, not unreadable: there is
        // nothing stored to protect, so the mark is MATERIALIZED rather than
        // skipped because the blank blob already reads as dirty (the default a
        // fresh blob carries for the rebuild's benefit). Only a blob that exists
        // and will not parse refuses to be written over - readOrNull answered
        // null for that case above, and never for this one.
        if (blob.dirty && prefs(app, name).contains(KEY_PROFILE)) return
        publish(app, name, blob.copy(dirty = true))
    }

    /** Loads the named profile's blob. Cheap and idempotent (the HiddenTitles rule). */
    private fun ensureLoaded(context: Context, name: String) {
        if (loadedFor == name) return
        loadedFor = name
        loadedBlob = readForDisplay(context, name)
        loaded = loadedBlob?.toProfile()
        _profile.value = loaded
    }

    /** The evidence read, the pure build, and the persist. */
    private suspend fun rebuild(
        context: Context,
        repo: TmdbRepository,
        rows: List<WatchHistoryEntity>,
        fingerprint: EvidenceFingerprint,
        name: String
    ): TasteProfile? {
        val evidence = gatherEvidence(context, repo, rows)
        val profile = buildTasteProfile(evidence)
        val blob = StoredBlob(
            entries = profile.entries.values
                .flatMap { it.values }
                .sortedWith(compareBy({ it.dimension }, { it.key })),
            evidenceFingerprint = fingerprint,
            computedAt = System.currentTimeMillis(),
            dirty = false
        )
        // A profile switch while the evidence was being read must not write this
        // profile's taste into the new one's store: the load belongs to the name
        // it was asked for, and the new profile pays for its own build.
        if (ProfileStorage.prefsName(context, PREFS_BASE) == name) {
            publish(context, name, blob)
            loadedBlob = blob
            loaded = profile
            _profile.value = profile
        }
        Log.i(TAG, "profile rebuilt: ${profile.entries.values.sumOf { it.size }} entries")
        return profile
    }

    /**
     * The evidence titles, newest first, signals before completions, capped.
     *
     * A signal title's TMDB id comes from the id spelling the signalling surface
     * stored (the `tmdb:` one, or an `tt...` resolved through the resolution
     * table - a memory/disk read, never a network call). A title that resolves
     * to neither is skipped entirely: it cannot be keyed, so it can neither
     * contribute nor be left out.
     *
     * Completions fill the remaining slots, and they are recognised even for a
     * title that is ALSO signalled - the signal is the evidence title, and the
     * completion is its second contribution.
     */
    private suspend fun gatherEvidence(
        context: Context,
        repo: TmdbRepository,
        rows: List<WatchHistoryEntity>
    ): List<TasteEvidenceTitle> {
        val ceiling = repo.kidsMaxAge()
        val completions = LinkedHashMap<String, WatchHistoryEntity>()
        rows.forEach { row ->
            val key = rowEvidenceKey(row, repo) ?: return@forEach
            completions.putIfAbsent(key, row)
        }

        val evidence = ArrayList<TasteEvidenceTitle>(AFFINITY_EVIDENCE_CAP)
        val seen = HashSet<String>()

        TasteSignals.ensureLoaded(context)
        TasteSignals.entries.value
            .filter { it.signal == TasteSignals.LIKE || it.signal == TasteSignals.DISLIKE }
            .forEach { entry ->
                if (evidence.size >= AFFINITY_EVIDENCE_CAP) return@forEach
                val target = signalTarget(entry, repo) ?: return@forEach
                val key = affinityEvidenceKey(target.first, target.second)
                if (!seen.add(key)) return@forEach
                val completion = completions[key]
                evidence += TasteEvidenceTitle(
                    tmdbId = target.second,
                    mediaType = target.first,
                    signal = entry.signal,
                    completed = completion != null,
                    markedWatched = completion?.markedWatched == true,
                    stamp = maxOf(entry.updatedAt, completion?.completedAt ?: 0L)
                )
            }

        completions.forEach { (key, row) ->
            if (evidence.size >= AFFINITY_EVIDENCE_CAP) return@forEach
            if (!seen.add(key)) return@forEach
            val tmdbId = key.substringAfterLast(':').toIntOrNull() ?: return@forEach
            evidence += TasteEvidenceTitle(
                tmdbId = tmdbId,
                mediaType = if (key.contains(":series:")) "series" else "movie",
                completed = true,
                markedWatched = row.markedWatched,
                stamp = row.completedAt ?: row.updatedAt
            )
        }

        // Details: cache-only, so the profile adds no request. A miss keeps its
        // slot and contributes nothing.
        return coroutineScope {
            evidence.map { title ->
                async(Dispatchers.IO) {
                    val detail = runCatchingCancellable {
                        repo.cachedDetailByTmdbId(title.tmdbId, title.mediaType)
                    }.getOrNull()
                    // The kids ceiling, on the payload already in hand: an
                    // evidence title above this profile's ceiling contributes
                    // nothing, and KidsMode.allowed owns the known-unknown rule.
                    val allowed = detail == null || KidsMode.allowed(
                        ceiling,
                        detail.certification(isMovie = title.mediaType != "series")
                    )
                    if (allowed) title.copy(detail = detail) else title
                }
            }.awaitAll()
        }
    }

    /** The signal store's own fingerprint half plus the completions half. */
    private fun fingerprint(
        context: Context,
        rows: List<WatchHistoryEntity>
    ): EvidenceFingerprint {
        TasteSignals.ensureLoaded(context)
        val signals = TasteSignals.entries.value.filter {
            it.signal == TasteSignals.LIKE || it.signal == TasteSignals.DISLIKE
        }
        return EvidenceFingerprint(
            signalCount = signals.size,
            signalMaxUpdatedAt = signals.maxOfOrNull { it.updatedAt } ?: 0L,
            completionCount = rows.size,
            completionMaxAt = rows.maxOfOrNull { it.completedAt ?: it.updatedAt } ?: 0L
        )
    }

    private suspend fun completionRows(context: Context): List<WatchHistoryEntity> =
        runCatchingCancellable {
            WatchHistoryDatabase.getInstanceScoped(context)
                .watchHistoryDao()
                .getRecentCompletedSeeds(EVIDENCE_SCAN)
        }.getOrDefault(emptyList())

    /** The TMDB id + type a signalled title's stored keys name, or null. */
    private suspend fun signalTarget(
        entry: TasteSignals.Entry,
        repo: TmdbRepository
    ): Pair<String, Int>? {
        val type = HiddenTitles.normalizedType(entry.mediaType)
        entry.keys.forEach { key ->
            val id = key.substringAfterLast("::")
            if (id.startsWith("tmdb:")) {
                val tmdbId = id.removePrefix("tmdb:").toIntOrNull()?.takeIf { it > 0 }
                if (tmdbId != null) return type to tmdbId
            }
        }
        entry.keys.forEach { key ->
            val id = key.substringAfterLast("::")
            if (id.startsWith("tt")) {
                val tmdbId = runCatchingCancellable { repo.resolveTmdbId(id, type) }
                    .getOrNull()
                    ?.takeIf { it > 0 }
                if (tmdbId != null) return type to tmdbId
            }
        }
        return null
    }

    /** The canonical evidence key a history row names, or null. */
    private suspend fun rowEvidenceKey(
        row: WatchHistoryEntity,
        repo: TmdbRepository
    ): String? {
        val type = when (row.type.lowercase()) {
            "series", "show", "tv" -> "series"
            "channel" -> return null
            else -> "movie"
        }
        val id = row.parentId.trim()
        if (id.isEmpty()) return null
        val tmdbId = if (id.startsWith("tt")) {
            runCatchingCancellable { repo.resolveTmdbId(id, type) }.getOrNull()
        } else {
            id.removePrefix("tmdb:").removePrefix("tmdb_").toIntOrNull()
        }?.takeIf { it > 0 } ?: return null
        return affinityEvidenceKey(type, tmdbId)
    }

    private fun StoredBlob.toProfile(): TasteProfile? {
        if (entries.isEmpty()) return TasteProfile.EMPTY
        return TasteProfile(
            entries = entries
                .groupBy { it.dimension }
                .mapValues { (_, list) -> list.associateBy { it.key } },
            evidence = entries
                .flatMap { entry -> entry.contributions.map { it.evidenceKey } }
                .distinct()
        )
    }

    /** The parsed blob, or the last copy that parsed - never "no taste" for a corrupt one. */
    private fun readForDisplay(context: Context, name: String): StoredBlob? =
        readOrNull(context, name) ?: lastGood[name]

    /**
     * The blob for a WRITE: null when it exists and will not parse, which is a
     * refusal to write rather than an empty profile to replace it with.
     */
    private fun readOrNull(context: Context, name: String): StoredBlob? {
        val raw = prefs(context, name).getString(KEY_PROFILE, null) ?: return StoredBlob()
        val parsed = runCatching {
            json.decodeFromString(StoredBlob.serializer(), raw)
        }.getOrNull()
        if (parsed == null) {
            Log.w(
                TAG,
                "taste profile blob will not parse; serving the last good copy and " +
                    "refusing to write over it"
            )
            return null
        }
        lastGood[name] = parsed
        return parsed
    }

    private fun publish(context: Context, name: String, blob: StoredBlob) {
        prefs(context, name).edit()
            .putString(KEY_PROFILE, json.encodeToString(StoredBlob.serializer(), blob))
            .apply()
        loadedFor = name
        lastGood[name] = blob
        _version.value = _version.value + 1
    }

    private fun prefs(context: Context, name: String) =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
}
