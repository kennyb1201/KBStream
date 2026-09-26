package com.kennyb1201.kbstream.ui.detail

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.addon.VideoEntry
import com.kennyb1201.kbstream.data.airdates.AirDateCorrection
import com.kennyb1201.kbstream.data.airdates.AirDateCorrections
import com.kennyb1201.kbstream.data.airdates.TvmazeAirDateRepository
import com.kennyb1201.kbstream.data.history.WatchHistoryDao
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryRepository
import com.kennyb1201.kbstream.data.reporting.PerfTrace
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.sync.KidsMode
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbCollectionDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbReview
import com.kennyb1201.kbstream.data.tmdb.UNSCRIPTED_TV_GENRES
import com.kennyb1201.kbstream.data.library.LibraryMirror
import com.kennyb1201.kbstream.data.library.LocalLibraryStore
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.mdblist.MdbListRatings
import com.kennyb1201.kbstream.data.tmdb.TmdbSeasonSummary
import com.kennyb1201.kbstream.data.tmdb.bestReleaseDate
import com.kennyb1201.kbstream.data.tmdb.certification
import com.kennyb1201.kbstream.data.watched.WatchedEpisodeState
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import com.kennyb1201.kbstream.data.tmdb.displayRuntimeMinutes
import com.kennyb1201.kbstream.data.tmdb.keepRecommendedGenre
import com.kennyb1201.kbstream.data.tmdb.list
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ceiling on the add-on meta probe, which stands between the Detail screen and
 * its first paint (see the probe loop in `DetailViewModel.load`).
 *
 * A meta document is one cached JSON lookup and a healthy add-on answers in
 * well under a second, so both of these are far above any honest response. They
 * exist because the candidates are walked IN ORDER and each one rides the
 * shared add-on client's 25s call timeout: without a ceiling, a single add-on
 * that accepted the connection and then went quiet held the whole screen for
 * its timeout, and several of them for the sum of them.
 *
 * Running out of either lands on the same soft fallback — the add-on meta only
 * refines TMDB's own detail (clearlogo, IMDb rating, website), so the screen
 * still paints.
 */
private const val META_PROBE_CALL_TIMEOUT_MS = 10_000L

/**
 * How long a COMPLETED load keeps ownership of this screen's state.
 *
 * Re-entering a detail page - back from the stream picker or the player, back
 * from an actor/studio/tag page, or simply re-opening the same card - used to
 * tear the whole screen down and fetch everything again, which put the spinner
 * back in front of a title the user had just been looking at. Inside this
 * window the remote phases are skipped and the retained state is reused
 * instead (see [DetailViewModel.load]).
 *
 * The LOCAL watch state is deliberately not skipped: the fast path still
 * re-reads the Room rows (resume, per-episode progress, completed ids). Those
 * are the one thing playback can change while this screen is away, and they
 * are what makes the checkmarks and the RESUME row correct on the way back
 * from the player. Remote documents - the TMDB detail and the add-on meta -
 * do not move in a minute, so gating them costs nothing.
 */
private const val DETAIL_FRESH_WINDOW_MS = 60_000L

fun computeEpisodeWatched(
    parentId: String,
    season: Int?,
    episode: Int?,
    episodeStreamId: String?,
    completedIds: Set<String>,
    watchedKeys: Set<String>
): Boolean {
    val streamMatch = !episodeStreamId.isNullOrBlank() && episodeStreamId in completedIds
    if (streamMatch) return true

    if (parentId.isBlank() || season == null || episode == null) return false
    val key = WatchedEpisodeState.buildEpisodeKey(parentId, season, episode) ?: return false
    return key in watchedKeys
}

class DetailViewModel(private val app: Application) : AndroidViewModel(app) {
    private val repository = AddonRepository.getInstance()
    private val addonManager = AddonManager.getInstance(app)
    internal val tmdbRepository = TmdbRepository.getInstance(app)
    internal val simklRepository = SimklRepository.getInstance(app)
    // Resolved per access: the scoped DB instance is bound to the ACTIVE
    // profile. Capturing the DAO once meant a Detail page opened before a
    // profile switch kept writing resume/watch progress into the previous
    // profile's (closed) database after the switch.
    private val historyDao: WatchHistoryDao
        get() = WatchHistoryDatabase.getInstanceScoped(app).watchHistoryDao()
    private val watchHistoryRepository = WatchHistoryRepository(app)
    private val watchedStatusRepository = WatchedStatusRepository(app)
    // Second-source air dates (see [AirDateCorrection]). Display-only: it
    // never writes back to history, watch state or the metadata caches.
    private val airDateRepository = TvmazeAirDateRepository.getInstance(app)

    private val _meta = MutableStateFlow<Meta?>(null)
    val meta: StateFlow<Meta?> = _meta.asStateFlow()

    private val _tmdbDetail = MutableStateFlow<TmdbDetail?>(null)

    // Full review list = the page bundled with the detail payload plus every
    // additional page from the standalone paginated reviews endpoint. Kept
    // separate so the UI can render page 1 immediately while the rest loads.
    private val _allReviews = MutableStateFlow<List<TmdbReview>>(emptyList())
    val allReviews: StateFlow<List<TmdbReview>> = _allReviews.asStateFlow()

    /**
     * True when [_tmdbDetail] is a synthetic stand-in built from the addon's
     * Meta.videos list (TVDB/anime titles with no TMDB match) rather than a
     * real TMDB record. Episodes then come from the addon data instead of
     * TMDB season endpoints.
     */
    private val _isSyntheticDetail = MutableStateFlow(false)
    val isSyntheticDetail: StateFlow<Boolean> = _isSyntheticDetail
    val tmdbDetail: StateFlow<TmdbDetail?> = _tmdbDetail.asStateFlow()

    /**
     * Air dates a second metadata source has that disagree with TMDB's - see
     * [AirDateCorrection]. Empty until (and unless) that lookup answers, and
     * empty is always a no-op: the screen renders exactly as it did before.
     */
    private val _airDateCorrections = MutableStateFlow(AirDateCorrections.NONE)
    val airDateCorrections: StateFlow<AirDateCorrections> =
        _airDateCorrections.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _episodes = MutableStateFlow<List<ResolvedEpisode>>(emptyList())
    val episodes: StateFlow<List<ResolvedEpisode>> = _episodes.asStateFlow()

    private val _episodesLoading = MutableStateFlow(false)
    val episodesLoading: StateFlow<Boolean> = _episodesLoading.asStateFlow()

    private val _episodeError = MutableStateFlow<String?>(null)
    val episodeError: StateFlow<String?> = _episodeError.asStateFlow()

    private val _resumeInfo = MutableStateFlow<WatchHistoryEntity?>(null)
    val resumeInfo: StateFlow<WatchHistoryEntity?> = _resumeInfo.asStateFlow()

    /** In-progress rows for the loaded parent, keyed by episodeStreamId. */
    private val _inProgressByStreamId =
        MutableStateFlow<Map<String, WatchHistoryEntity>>(emptyMap())
    val inProgressByStreamId: StateFlow<Map<String, WatchHistoryEntity>> =
        _inProgressByStreamId.asStateFlow()

    private val _completedEpisodeIds = MutableStateFlow<Set<String>>(emptySet())
    val completedEpisodeIds: StateFlow<Set<String>> = _completedEpisodeIds.asStateFlow()

    private val _simklWatchedEpisodes = MutableStateFlow<Set<Pair<Int, Int>>>(emptySet())
    val simklWatchedEpisodes: StateFlow<Set<Pair<Int, Int>>> = _simklWatchedEpisodes.asStateFlow()

    private val _simklSeriesWatched = MutableStateFlow(false)
    val simklSeriesWatched: StateFlow<Boolean> = _simklSeriesWatched.asStateFlow()

    private val _watchedEpisodeKeys = MutableStateFlow<Set<String>>(emptySet())
    val watchedEpisodeKeys: StateFlow<Set<String>> = _watchedEpisodeKeys.asStateFlow()

    private val _watchedKeys = MutableStateFlow<Set<String>>(emptySet())
    val watchedKeys: StateFlow<Set<String>> = _watchedKeys.asStateFlow()

    // Keys of shows started-but-not-finished (the eye badge) for the poster
    // rails. Filled by the same refresh as watchedKeys; the completed
    // checkmark wins when a key is in both sets.
    private val _partialWatchedKeys = MutableStateFlow<Set<String>>(emptySet())
    val partialWatchedKeys: StateFlow<Set<String>> = _partialWatchedKeys.asStateFlow()

    private val _resolvedPosterIds = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolvedPosterIds: StateFlow<Map<String, String>> = _resolvedPosterIds.asStateFlow()

    private val _collection = MutableStateFlow<TmdbCollectionDetail?>(null)
    val collection: StateFlow<TmdbCollectionDetail?> = _collection.asStateFlow()

    private val _mdbListRatings = MutableStateFlow<MdbListRatings?>(null)
    val mdbListRatings: StateFlow<MdbListRatings?> = _mdbListRatings.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _selectedPersonDetail = MutableStateFlow<TmdbPersonDetail?>(null)
    val selectedPersonDetail: StateFlow<TmdbPersonDetail?> = _selectedPersonDetail.asStateFlow()

    private val _selectedPersonLoading = MutableStateFlow(false)
    val selectedPersonLoading: StateFlow<Boolean> = _selectedPersonLoading.asStateFlow()

    private val _selectedPersonError = MutableStateFlow<String?>(null)
    val selectedPersonError: StateFlow<String?> = _selectedPersonError.asStateFlow()

    private val _targetEpisode = MutableStateFlow<ResolvedEpisode?>(null)
    val targetEpisode: StateFlow<ResolvedEpisode?> = _targetEpisode.asStateFlow()

    private val _loadedSeason = MutableStateFlow<Int?>(null)
    val loadedSeason: StateFlow<Int?> = _loadedSeason.asStateFlow()

    private val _playButtonText = MutableStateFlow("Play")
    val playButtonText: StateFlow<String> = _playButtonText.asStateFlow()

    internal var imdbId: String = ""
    private var latestEpisodeSeasonRequest: Int? = null

    /** Normalized media type of the loaded detail ("movie"/"series"), used
     *  to resolve the title's IMDB twin for local-history lookups. */
    private var mediaType: String = "movie"

    /**
     * The (normalized type, id) whose completed load put the state currently
     * in the flows there, and when that load finished (elapsedRealtime).
     * Together with [_error] and [_isLoading] these are the freshness gate's
     * inputs - see [isFreshDetailLoad] and [DETAIL_FRESH_WINDOW_MS].
     */
    private var loadedDetailKey: String? = null
    private var loadedDetailAtMs: Long = 0L

    /**
     * True when [_resumeInfo] was read from local history rather than from the
     * Simkl/MDBList cloud sessions. The freshness gate's fast path re-reads
     * local rows only, so it has to know whether a row that is now missing
     * means "finished or cleared elsewhere" (drop the RESUME row) or "was
     * never local" (leave the cloud-derived row alone).
     */
    private var resumeFromLocalHistory = false

    /**
     * MDBList's watched-episode set for the loaded title (the snapshot read in
     * [load]). Kept as a field so a local-only refresh can rebuild the merged
     * episode keys without paying for the cloud snapshot again.
     */
    private var mdbListWatchedEpisodes: Set<Pair<Int, Int>> = emptySet()

    // Hot reactive StateFlow checking if stream addons are configured and present globally
    val hasStreamAddons: StateFlow<Boolean> = addonManager.streamAddons
        .map { addons -> addons.isNotEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = addonManager.streamAddons.value.isNotEmpty()
        )

    fun watchedKey(id: String, type: String): String = "${type.lowercase()}::$id"

    fun posterLookupKey(tmdbId: Int, mediaType: String): String = "${mediaType.lowercase()}::$tmdbId"

    /**
     * Local-history parent ids that describe THIS title: the id the screen was
     * opened with plus the twin named by the resolved TMDB record.
     *
     * The same title arrives under more than one id flavor - TMDB search
     * results and the hardcoded kids rails carry "tmdb:<n>", add-on catalogs
     * and Continue Watching carry "tt..." - and playback history is written
     * under whichever flavor started it. Reading (and clearing) only the
     * route's own flavor is what made a half-watched title look untouched
     * when opened from its other flavor, so every local history lookup goes
     * through this list.
     */
    private suspend fun localHistoryParentIds(parentId: String): List<String> {
        if (parentId.isBlank()) return emptyList()

        val ids = linkedSetOf(parentId)

        // Synthetic addon-videos details carry a -1 sentinel instead of a
        // real TMDB id and must not be turned into a fake "tmdb:-1" key.
        val tmdbId = _tmdbDetail.value?.id?.takeIf { it > 0 }
        if (tmdbId != null) {
            ids += "tmdb:$tmdbId"

            // The IMDB twin. The TMDB detail payload carries no external ids,
            // so this resolves them (memory + disk cached after the first
            // lookup) - without it a "tmdb:<n>" route could never see history
            // written under the title's "tt..." id.
            runCatching {
                tmdbRepository.resolveImdbId(tmdbId, mediaType)
            }.getOrNull()
                ?.trim()
                ?.takeIf { it.startsWith("tt") }
                ?.let { ids += it }
        }

        return ids.toList()
    }

    private fun normalizeMediaType(type: String): String = when (type.lowercase()) {
        "tv", "show" ->
            "series"
        // Anime catalogs emit type "anime" (and movies "anime.movie"); TMDB
        // and most meta addons serve them under series/movie, so normalize
        // before building meta/episode endpoints. Ids are unaffected.
        "anime", "anime.series" ->
            "series"
        "anime.movie" ->
            "movie"
        else ->
            type.lowercase()
    }

    // Hot reactive check to observe if the current item is marked watched in real-time
    fun observeIsWatched(id: String, type: String): StateFlow<Boolean> {
        return watchedStatusRepository.observeIsWatched(id, type)
    }

    /**
     * Re-resolves this screen's poster rows (More Like This / collection)
     * after a watched-state write.
     *
     * Those rows read a ONE-SHOT snapshot of the watched state taken when the
     * page loaded, so a season unmark left every one of them showing the
     * completed checkmark it had resolved earlier - the eye never appeared
     * until the page was reloaded. Re-running the (cached, no extra network)
     * resolution against the repository - which the write has already
     * updated - repaints them: checkmark for a fully watched title, eye for
     * one that is now only partly watched.
     */
    private fun refreshPostersAfterWatchedChange() {
        val type =
            mediaType.ifBlank {
                normalizeMediaType(
                    _meta.value?.type.orEmpty()
                )
            }

        if (type.isBlank()) return

        refreshPosterWatchedStatus(type)
    }

    private fun refreshPosterWatchedStatus(type: String) {
        viewModelScope.launch {
            try {
                val normalizedType = type.lowercase()

                val rawItems = buildList {
                    _collection.value?.parts.orEmpty().forEach { part ->
                        add(part.id to "movie")
                    }
                    _tmdbDetail.value?.recommendations?.results.orEmpty().forEach { rec ->
                        add(rec.id to normalizedType)
                    }
                }.distinct()

                if (rawItems.isEmpty()) {
                    _watchedKeys.value = emptySet()
                    _resolvedPosterIds.value = emptyMap()
                    Log.i("KBStream", "poster watched refresh: no items")
                    return@launch
                }

                val resolvedItems = rawItems.mapNotNull { (tmdbId, mediaType) ->
                    val imdb = runCatching {
                        resolveImdbId(tmdbId, mediaType)
                    }.getOrNull()

                    if (imdb.isNullOrBlank()) {
                        null
                    } else {
                        Triple(tmdbId, mediaType, imdb)
                    }
                }.distinct()

                if (resolvedItems.isEmpty()) {
                    _watchedKeys.value = emptySet()
                    _resolvedPosterIds.value = emptyMap()
                    Log.i("KBStream", "poster watched refresh: no resolvable imdb ids")
                    return@launch
                }

                _resolvedPosterIds.value = resolvedItems.associate { (tmdbId, mediaType, imdbId) ->
                    posterLookupKey(tmdbId, mediaType) to imdbId
                }

                val preloadItems = resolvedItems
                    .map { (_, mediaType, imdbId) -> imdbId to mediaType }
                    .distinct()

                watchedStatusRepository.preload(preloadItems)

                _watchedKeys.value = resolvedItems
                    .filter { (_, mediaType, imdbId) ->
                        watchedStatusRepository.isWatchedCached(imdbId, mediaType)
                    }
                    .map { (_, mediaType, imdbId) ->
                        watchedKey(imdbId, mediaType)
                    }
                    .toSet()

                _partialWatchedKeys.value = resolvedItems
                    .filter { (_, mediaType, imdbId) ->
                        watchedStatusRepository.isPartiallyWatchedCached(imdbId, mediaType)
                    }
                    .map { (_, mediaType, imdbId) ->
                        watchedKey(imdbId, mediaType)
                    }
                    .toSet() -
                    _watchedKeys.value

                Log.i(
                    "KBStream",
                    "poster watched refresh resolved=${resolvedItems.size} watched=${_watchedKeys.value.size}"
                )
            } catch (e: Exception) {
                _watchedKeys.value = emptySet()
                _resolvedPosterIds.value = emptyMap()
                Log.e("KBStream", "poster watched refresh failed", e)
            }
        }
    }

    /**
     * Whether the state already in the flows was produced by a completed load
     * of this exact (type, id) and is recent enough to reuse.
     *
     * Everything here is a reason NOT to reuse: a different title, a load
     * still in flight (a second entry must not read half-written state), a
     * failed one (the error screen has to be able to retry), a load that never
     * produced a TMDB detail (the kids-mode block clears it deliberately), and
     * the window itself, which re-anchors on every real load so a page that
     * stays open still revalidates once a minute.
     */
    private fun isFreshDetailLoad(detailKey: String): Boolean {
        if (loadedDetailKey != detailKey) return false
        if (_isLoading.value || _error.value != null) return false
        if (_tmdbDetail.value == null) return false

        return SystemClock.elapsedRealtime() - loadedDetailAtMs < DETAIL_FRESH_WINDOW_MS
    }

    /**
     * Reads this title's LOCAL watch state and publishes it: the resume row,
     * the per-episode in-progress map, the completed-episode ids.
     *
     * Extracted from the load body because there are two callers now - the
     * full load and the freshness gate's fast path - and they must not drift:
     * the fast path's whole claim to correctness is that it refreshes exactly
     * the half of the screen playback can change. Everything it touches is a
     * Room read, which is what makes that claim affordable.
     *
     * @param cloudResumeFallback when local history has no in-progress row,
     *   derive a display-only resume row from the paused Simkl/MDBList cloud
     *   session (a network read the fast path deliberately skips).
     * @return the completed rows, for rebuilding the merged episode keys.
     */
    private suspend fun refreshLocalWatchState(
        parentId: String,
        normalizedType: String,
        tmdbId: Int?,
        cloudResumeFallback: Boolean
    ): List<WatchHistoryEntity> {
        /*
         * Ids this title is reachable under: the route's own flavor plus the
         * twin the TMDB detail just resolved ("tmdb:<n>" for an imdb route,
         * the imdb id for a tmdb route). Playback history is written under
         * whichever flavor started it, so reading only the route's flavor hid
         * progress, resume rows and episode checkmarks made from the other one
         * - the same show opened from Search ("tmdb:<n>") looked untouched
         * next to the copy opened from an add-on catalog ("tt...").
         */
        val historyParentIds = localHistoryParentIds(parentId)

        val localResume = runCatching {
            historyDao.getResumeForParents(historyParentIds)
        }.getOrNull()

        if (localResume != null && localResume.positionMs > 0L) {
            _resumeInfo.value = localResume
            resumeFromLocalHistory = true
        } else if (resumeFromLocalHistory) {
            // The row this screen was showing is gone - the title was finished
            // or cleared while the screen was away - so drop it rather than
            // leave a RESUME row pointing at a position the device no longer
            // has.
            _resumeInfo.value = null
            resumeFromLocalHistory = false
        }

        // Simkl cloud-session fallback: when local history has no in-progress
        // position for this title, derive a display-only resume row from the
        // paused Simkl playback session so Detail shows RESUME + progress for
        // cloud-tracked progress too.
        if (_resumeInfo.value == null && cloudResumeFallback) {
            _resumeInfo.value = simklPlaybackResumeFor(
                id = parentId,
                type = normalizedType,
                tmdbId = tmdbId
            ) ?: mdbListPlaybackResumeFor(
                id = parentId,
                type = normalizedType,
                tmdbId = tmdbId
            )
        }

        // Per-episode in-progress map for the episode cards: every card derives
        // its own progress bar / time left from its episodeStreamId instead of
        // only the single latest row.
        _inProgressByStreamId.value = runCatching {
            historyDao.getInProgressForParents(historyParentIds)
        }.getOrDefault(emptyList())
            // Rows arrive newest-first and toMap keeps the LAST entry per key
            // - reverse so the newest row wins per streamId.
            .reversed()
            .flatMap { row ->
                // Index each row by its own streamId AND by the route-flavored
                // episode id: a row written under the title's other id flavor
                // carries "tt123:2:5" while this screen's cards compare
                // "tmdb:456:2:5".
                buildList {
                    row.episodeStreamId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { add(it to row) }

                    val season = row.season
                    val episode = row.episode
                    if (season != null && episode != null) {
                        add("$parentId:$season:$episode" to row)
                    }
                }
            }
            .toMap()

        val localCompletedEntries = runCatching {
            historyDao.getCompletedForParents(historyParentIds)
        }.getOrDefault(emptyList())
        _completedEpisodeIds.value = localCompletedEntries.map { it.id }.toSet()

        return localCompletedEntries
    }

    /**
     * The merged watched-episode key set (local history + Simkl + MDBList) for
     * [parentId]. Shared by the full load and the freshness gate's fast path
     * so both publish the same keys from the same inputs.
     */
    private fun publishWatchedEpisodeKeys(
        parentId: String,
        localCompletedEntries: List<WatchHistoryEntity>
    ) {
        _watchedEpisodeKeys.value =
            WatchedEpisodeState.buildMergedWatchedKeys(
                parentId = parentId,
                localCompletedEntries = localCompletedEntries,
                simklCompletedEpisodes = _simklWatchedEpisodes.value
            ) + mdbListWatchedEpisodes.map { (season, episode) ->
                "$parentId:$season:$episode"
            }
    }

    fun load(
        type: String,
        id: String,
        initialSeason: Int? = null,
        initialMeta: Meta? = null,
        // True for a Continue Watching / Up Next deep link, which auto-plays
        // the moment metadata lands: its loading splash has to stay up
        // instead of the page painting early underneath it.
        isDeepLinkAutoPlay: Boolean = false
    ) {
        val normalizedType = normalizeMediaType(type)
        // The active profile is part of the key: the state being reused is
        // profile-scoped (watched badges, and the Kids Mode gate that decides
        // whether the title may be open at all), so a profile switch has to
        // invalidate it instead of letting a stricter profile reuse a looser
        // one's page. Legacy/global stores (- no profile yet) share a bucket.
        val profileKey = ProfileStorage.activeProfileId(app) ?: "default"
        val detailKey = "$profileKey|$normalizedType|$id"

        /*
         * Freshness gate: a page reopened inside the window repaints from the
         * state this ViewModel already holds. The ViewModel is keyed per
         * (type, id) and outlives the composable, so that state is this
         * title's own - the wipe below is what made a revisit as slow as a
         * first visit, and it is what is skipped here.
         *
         * The local watch state is re-read (Room, no network) because
         * playback is the one thing that plausibly changed while the screen
         * was away; everything remote keeps until the window expires.
         */
        if (isFreshDetailLoad(detailKey)) {
            Log.e(
                "KBStream",
                "detail load skipped (fresh " +
                    "${SystemClock.elapsedRealtime() - loadedDetailAtMs}ms) " +
                    "type=$normalizedType id=$id"
            )
            viewModelScope.launch {
                val localCompletedEntries = refreshLocalWatchState(
                    parentId = id,
                    normalizedType = normalizedType,
                    tmdbId = _tmdbDetail.value?.id,
                    cloudResumeFallback = false
                )
                publishWatchedEpisodeKeys(id, localCompletedEntries)
                refreshPosterWatchedStatus(normalizedType)
            }
            return
        }

        imdbId = id
        _meta.value = initialMeta
        _tmdbDetail.value = null
        _isSyntheticDetail.value = false
        _airDateCorrections.value = AirDateCorrections.NONE
        _episodes.value = emptyList()
        _episodeError.value = null
        _episodesLoading.value = false
        _inProgressByStreamId.value = emptyMap()
        latestEpisodeSeasonRequest = initialSeason
        _collection.value = null
        _mdbListRatings.value = null
        _allReviews.value = emptyList()
        _simklSeriesWatched.value = false
        _resolvedPosterIds.value = emptyMap()
        _targetEpisode.value = null
        _loadedSeason.value = null
        _playButtonText.value = "Play"
        _selectedPersonDetail.value = null
        _selectedPersonLoading.value = false
        _selectedPersonError.value = null

        viewModelScope.launch {
            // Timed for the diagnostics perf block. "The detail screen
            // sometimes takes a while" is otherwise only a feeling, and the
            // numbers are what turn it into a phase worth fixing.
            val loadStartedAt = SystemClock.elapsedRealtime()
            _isLoading.value = true
            _error.value = null

            try {
                fun buildMergedMeta(
                    tmdbDetail: TmdbDetail?,
                    addonMeta: Meta?,
                    initialMeta: Meta?
                ): Meta {
                    val tmdbMeta = tmdbDetail?.let { detail ->
                        Meta(
                            id = id,
                            type = normalizedType,
                            name = detail.name ?: detail.title ?: id,
                            poster = detail.posterPath?.let { TmdbRepository.POSTER_BASE + it },
                            background = detail.backdropPath?.let { TmdbRepository.BACKDROP_BASE + it },
                            logo = null,
                            description = detail.overview,
                            releaseInfo = if (normalizedType == "series") {
                                detail.firstAirDate?.takeIf { it.isNotBlank() }?.take(4)
                            } else {
                                detail.releaseDate?.takeIf { it.isNotBlank() }?.take(4)
                            },
                            // Deliberately no imdbRating here. TMDB's vote_average is
                            // not an IMDb score, and the meta line renders this field as
                            // "IMDb x.x" — which then disagreed with the MDBList IMDb
                            // chip on the same screen. A real IMDb rating only ever comes
                            // from the add-on meta (see the merge below); TMDB's own
                            // score is already shown as its own chip in the RATINGS
                            // strip.
                            runtime = if (normalizedType == "series") {
                                detail.episodeRunTime.firstOrNull()?.toString()
                            } else {
                                detail.runtime?.toString()
                            },
                            language = detail.originalLanguage,
                            country = detail.originCountries.firstOrNull(),
                            awards = detail.awards,
                            website = null,
                            genres = detail.genres.map { it.name },
                            cast = detail.credits?.cast?.map { it.name },
                            director = detail.credits?.crew
                                ?.filter { it.job.equals("Director", ignoreCase = true) }
                                ?.map { it.name },
                            videos = detail.videos?.results?.map { video ->
                                VideoEntry(
                                    id = video.key,
                                    title = video.name,
                                    description = video.site,
                                    thumbnail = video.thumbnail
                                )
                            }
                        )
                    }

                    val releaseFromAddon = addonMeta?.releaseInfo?.takeIf { it.isNotBlank() }
                    val releaseFromTmdb = tmdbMeta?.releaseInfo?.takeIf { it.isNotBlank() }

                    return Meta(
                        id = id,
                        type = normalizedType,
                        name = tmdbMeta?.name ?: addonMeta?.name ?: initialMeta?.name ?: id,
                        poster = tmdbMeta?.poster ?: addonMeta?.poster ?: initialMeta?.poster,
                        background = tmdbMeta?.background ?: addonMeta?.background ?: initialMeta?.background,
                        logo = addonMeta?.logo ?: initialMeta?.logo,
                        description = tmdbMeta?.description ?: addonMeta?.description ?: initialMeta?.description,
                        releaseInfo = releaseFromAddon ?: releaseFromTmdb,
                        // The meta add-on's rating is the only genuine IMDb figure in
                        // this merge; initialMeta is the poster/blurb the caller passed
                        // in and carries no rating.
                        imdbRating = addonMeta?.imdbRating ?: initialMeta?.imdbRating,
                        runtime = tmdbMeta?.runtime ?: addonMeta?.runtime,
                        language = tmdbMeta?.language ?: addonMeta?.language,
                        country = tmdbMeta?.country ?: addonMeta?.country,
                        awards = tmdbMeta?.awards ?: addonMeta?.awards,
                        website = addonMeta?.website ?: initialMeta?.website,
                        genres = tmdbMeta?.genres?.takeIf { it.isNotEmpty() }
                            ?: addonMeta?.genres?.takeIf { it.isNotEmpty() }
                            ?: initialMeta?.genres,
                        cast = tmdbMeta?.cast?.takeIf { it.isNotEmpty() }
                            ?: addonMeta?.cast?.takeIf { it.isNotEmpty() }
                            ?: initialMeta?.cast,
                        director = tmdbMeta?.director?.takeIf { it.isNotEmpty() }
                            ?: addonMeta?.director?.takeIf { it.isNotEmpty() }
                            ?: initialMeta?.director,
                        videos = tmdbMeta?.videos?.takeIf { it.isNotEmpty() }
                            ?: addonMeta?.videos?.takeIf { it.isNotEmpty() }
                            ?: initialMeta?.videos
                    )
                }

                mediaType = normalizedType
                Log.e("KBStream", "detail load start type=$normalizedType id=$id initialSeason=$initialSeason")

                val addonsDeferred = async { addonManager.getEnabledAddons() }
                // Cached (12h memory / 30d disk), like every other screen's
                // TMDB fetch. This call was UNCACHED, so a revisit after the
                // 60s freshness window redid the whole TMDB round-trip - and
                // for an "tt..." id that is the extra find-then-detail hop -
                // before the spinner could come down. The freshness gate only
                // ever spared a reopen inside its own minute; the cache is what
                // makes later revisits instant too.
                val tmdbDeferred = async {
                    runCatching {
                        tmdbRepository.fetchEnrichedMetaCached(id, normalizedType)
                    }
                }

                // 1. Await structural and history components first so watched data is guaranteed ready
                val tmdbDetailResult = tmdbDeferred.await()

                // Paint first, refine second: publish the TMDB detail the
                // moment it lands so the hero/poster/overview render
                // immediately, instead of waiting for the Simkl round-trip
                // and the serial addon-meta probes below. Watched badges and
                // the resume row merge in afterwards when those resolve —
                // they write to their own state flows, so nothing is lost.
                tmdbDetailResult.onSuccess { earlyDetail ->
                    if (earlyDetail != null && _tmdbDetail.value == null) {
                        _tmdbDetail.value = earlyDetail
                    }
                }

                // Second-source air dates, fired right behind the early
                // publish so the lookup can never sit in front of first
                // paint. It lands whenever it lands: episodes already on
                // screen are re-dated when it arrives.
                if (normalizedType == "series") {
                    val airDateParentId = id
                    viewModelScope.launch {
                        loadAirDateCorrections(airDateParentId)
                    }
                }

                // Kids Mode gate: once the TMDB detail (with US
                // certifications) is in hand, a kids profile may not open a
                // title rated above its ceiling. Fails OPEN like every
                // other surface: an unresolvable rating keeps the title
                // unless the ceiling is strict (PG/G hides unknowns).
                val ceiling = tmdbRepository.kidsMaxAge()
                val gatedDetail = tmdbDetailResult.getOrNull()
                if (ceiling != null && gatedDetail != null) {
                    val isSeriesType = normalizedType == "series"
                    if (!KidsMode.allowed(
                            ceiling,
                            gatedDetail.certification(isMovie = !isSeriesType)
                        )
                    ) {
                        _meta.value = null
                        _tmdbDetail.value = null
                        _error.value =
                            "This title isn't available on this profile."
                        Log.i(
                            "KBStream",
                            "detail blocked by kids mode type=$normalizedType id=$id"
                        )
                        return@launch
                    }
                }

                // Paint first, refine second: TMDB has already answered, so
                // publish a meta built from it and bring the spinner down NOW,
                // without waiting for the Simkl/MDBList round-trips or the
                // add-on meta probes below. The add-on meta still refines the
                // page when it lands (logo, IMDb rating, extra art). Skipped
                // for a synthetic addon-videos detail (TMDB had no record) and
                // for a deep-link auto-play open, whose loading splash the
                // player replaces the moment metadata lands.
                val earlyTmdbDetail = tmdbDetailResult.getOrNull()
                if (earlyTmdbDetail != null && !_isSyntheticDetail.value) {
                    if (_meta.value == null) {
                        _meta.value = buildMergedMeta(
                            tmdbDetail = earlyTmdbDetail,
                            addonMeta = null,
                            initialMeta = initialMeta
                        )
                    }
                    if (!isDeepLinkAutoPlay) {
                        _isLoading.value = false
                    }
                }

                // MDBList ratings: fire EARLY, not buried behind the Simkl
                // round-trip and the add-on meta probes below. The row
                // renders as soon as its own fetch answers; a blank IMDb id
                // (TMDB-only titles) retries via the enrich pass once the
                // external-ids lookup resolves.
                fetchMdbListRatings(normalizedType)

                // Local watch state for this title (resume row, per-episode
                // in-progress map, completed ids) plus the Simkl/MDBList cloud
                // fallbacks for the resume row. Read through the shared reader
                // because the freshness gate's fast path re-runs exactly this
                // half of the load and nothing else.
                val localCompletedEntries = refreshLocalWatchState(
                    parentId = id,
                    normalizedType = normalizedType,
                    tmdbId = tmdbDetailResult.getOrNull()?.id,
                    cloudResumeFallback = true
                )

                // 2. Fetch Simkl watch states utilizing the resolved TMDB show ID safely
                val simklCompleted = if (
                    normalizedType == "series" &&
                    simklRepository.isConfigured() &&
                    simklRepository.hasToken()
                ) {
                    val tmdbShowId = tmdbDetailResult.getOrNull()?.id
                    runCatching {
                        simklRepository.getWatchedEpisodesForShowByImdb(imdbId = id, tmdbId = tmdbShowId)
                    }.getOrDefault(emptySet())
                } else {
                    emptySet()
                }

                _simklWatchedEpisodes.value = simklCompleted
                _simklSeriesWatched.value = simklCompleted.isNotEmpty()

                // MDBList watched episodes for this show, merged alongside
                // the Simkl set so episode badges reflect both trackers.
                val mdbListCompleted = if (
                    normalizedType == "series" &&
                    MdbListClient.isConfigured(getApplication())
                ) {
                    val tmdbShowId = tmdbDetailResult.getOrNull()?.id
                    runCatching {
                        MdbListClient.getWatchedSnapshot(getApplication())
                    }.getOrNull()
                        ?.episodeKeys
                        .orEmpty()
                        .mapNotNull { key ->
                            // Key shapes from MdbListClient.addKey():
                            // "tt123:S:E" and "tmdb:456:S:E".
                            val parts = key.split(":")
                            if (parts.size < 3) return@mapNotNull null

                            val isImdbKey = key.startsWith("tt")
                            val isTmdbKey = key.startsWith("tmdb:")
                            if (!isImdbKey && !isTmdbKey) {
                                return@mapNotNull null
                            }

                            val seasonIdx = if (isImdbKey) 1 else 2
                            val season = parts.getOrNull(seasonIdx)
                                ?.toIntOrNull() ?: return@mapNotNull null
                            val episode = parts.getOrNull(seasonIdx + 1)
                                ?.toIntOrNull() ?: return@mapNotNull null

                            // Only keep entries anchored to THIS show.
                            if (isImdbKey) {
                                val showId = parts[0]
                                if (!showId.equals(id, ignoreCase = true)) {
                                    return@mapNotNull null
                                }
                            } else {
                                val keyTmdbId = parts.getOrNull(1)
                                    ?.toIntOrNull()
                                if (tmdbShowId == null ||
                                    keyTmdbId != tmdbShowId
                                ) {
                                    return@mapNotNull null
                                }
                            }
                            season to episode
                        }
                        .toSet()
                } else {
                    emptySet()
                }

                // Kept so the freshness gate's local-only refresh can rebuild
                // the merged keys without re-reading the cloud snapshot.
                mdbListWatchedEpisodes = mdbListCompleted

                // 3. Build merged keys *before* evaluating target episodes or seasons
                publishWatchedEpisodeKeys(id, localCompletedEntries)

                // 4. Handle Meta addon loading asynchronously in background
                val addons = addonsDeferred.await()

// Probe order: addons whose manifest idPrefixes match this id first
// (a TVDB addon for "tvdb:...", a Cinemeta-style IMDB addon for "tt..."),
// then legacy manifests that declare no prefixes, then declared-but-
// non-matching addons as a last resort. Prevents an IMDB-only addon from
// winning the probe race for TVDB-sourced titles and vice versa.
val metaAddons = addonManager.orderMetaAddonsForId(addons, id, normalizedType)

Log.e(
    "KBStream",
    "detail meta: type=$normalizedType id=$id candidates=${metaAddons.map { it.name }}"
)

var resolvedMeta: Meta? = null
var lastMetaError: Throwable? = null

// Every candidate is probed CONCURRENTLY and the results are then considered
// in preference order, so the winner is still the best-ranked add-on that
// answered - but the wait is the slowest single probe instead of the sum of
// every probe (one add-on that never answers no longer delays everything
// behind it). Each call keeps its own timeout, and withTimeoutOrNull sits
// INSIDE runCatching so a timeout's own cancellation turns into a null
// response here instead of escaping this scope as a cancellation of the whole
// load.
val probeResults = metaAddons.map { metaAddon ->
    async {
        val baseUrl = metaAddon.manifestUrl.substringBeforeLast("/manifest.json")
        val result = runCatching {
            withTimeoutOrNull(META_PROBE_CALL_TIMEOUT_MS) {
                repository.getMeta(baseUrl, normalizedType, id)
            }
        }
        Triple(metaAddon, result.getOrNull(), result.exceptionOrNull())
    }
}.awaitAll()

for ((metaAddon, response, error) in probeResults) {
    if (response != null) {
        resolvedMeta = response
        Log.e(
            "KBStream",
            "detail meta resolved addon=${metaAddon.name} id=$id"
        )
        break
    } else if (error != null) {
        lastMetaError = error
        Log.e(
            "KBStream",
            "detail meta failed addon=${metaAddon.name} id=$id",
            error
        )
    } else {
        Log.e(
            "KBStream",
            "detail meta empty or timed out addon=${metaAddon.name} id=$id"
        )
    }
}

    if (resolvedMeta != null) {
        val savedMeta = initialMeta
        _meta.value = buildMergedMeta(
            tmdbDetail = tmdbDetailResult.getOrNull(),
            addonMeta = resolvedMeta,
            initialMeta = savedMeta
        )
    } else {
        val fallbackDetail = tmdbDetailResult.getOrNull()

        if (fallbackDetail != null) {
            _meta.value = buildMergedMeta(
                tmdbDetail = fallbackDetail,
                addonMeta = null,
                initialMeta = initialMeta
            )
        } else if (initialMeta != null) {
            _meta.value = initialMeta
        }

        Log.e(
            "KBStream",
            "detail meta unresolved type=$normalizedType id=$id",
            lastMetaError
        )

        if (_meta.value == null) {
            _error.value =
                "Couldn't load details for this title. " +
                    "Check that your add-ons are reachable, then try again."
        }
    }

    // TVDB / anime fallback: when TMDB has no record for this id (find
    // returned nothing) but the addon meta carries the Stremio-standard
    // Meta.videos episode list, synthesize a minimal TmdbDetail stand-in
    // from it. The existing season/episode pipeline (autoLoadRelevantSeason,
    // season picker, episode browser, watch markers) then works unchanged
    // for titles TMDB does not know; loadEpisodesForSeason checks the
    // synthetic flag and serves addon videos instead of calling TMDB.
    if (tmdbDetailResult.getOrNull() == null && normalizedType == "series") {
        val addonVideos = _meta.value?.videos.orEmpty()
            .filter { it.season != null && it.episode != null }
        if (addonVideos.isNotEmpty()) {
            val seasons = addonVideos
                .mapNotNull { it.season }
                .distinct()
                .sorted()
            _tmdbDetail.value = TmdbDetail(
                id = -1,
                name = _meta.value?.name,
                overview = _meta.value?.description,
                seasons = seasons.map { seasonNum ->
                    TmdbSeasonSummary(
                        seasonNumber = seasonNum,
                        name = "Season $seasonNum",
                        episodeCount = addonVideos.count { it.season == seasonNum }
                    )
                }
            )
            _isSyntheticDetail.value = true
            Log.e(
                "KBStream",
                "detail meta: synthesized seasons from addon videos " +
                    "id=$id seasons=${seasons.size}"
            )
        }
    }

                tmdbDetailResult.onSuccess { detail ->
                    if (_isSyntheticDetail.value) {
                        // A synthetic addon-videos detail is already in place
                        // (TMDB had no match); never clobber it with a null.
                        return@onSuccess
                    }

                    // "More Like This" trimming, three filters composed.
                    // Unscripted formats (talk / news / reality / soap) are
                    // always dropped — a nightly talk show is not "more like
                    // this" for a scripted series — unless this title is
                    // itself unscripted. Then Kids Mode drops recs rated
                    // above the active profile's ceiling (no-op otherwise),
                    // then the app-wide digital-release filter trims movies
                    // not yet available at home when the Settings toggle is
                    // on. Recs inherit this screen's media type.
                    val shownDetail =
                        if (
                            detail != null &&
                            detail.recommendations?.results.orEmpty().isNotEmpty()
                        ) {
                            val parentIsUnscripted =
                                detail.genres.any { it.id in UNSCRIPTED_TV_GENRES }

                            var recs =
                                detail.recommendations?.results.orEmpty()
                                    .filter {
                                        keepRecommendedGenre(
                                            it.genreIds,
                                            parentIsUnscripted
                                        )
                                    }

                            // Kids Mode first (cached certification
                            // lookups); kidsFilter no-ops when the active
                            // profile has no ceiling.
                            if (tmdbRepository.kidsMaxAge() != null) {
                                recs =
                                    tmdbRepository.kidsFilter(
                                        recs
                                    ) { it.id to normalizedType }
                            }

                            if (tmdbRepository.isDigitalFilterEnabled()) {
                                recs =
                                    tmdbRepository.filterByHomeAvailability(
                                        recs
                                    ) { it.id to normalizedType }
                            }

                            detail.copy(
                                recommendations = detail.recommendations?.copy(
                                    results = recs
                                )
                            )
                        } else {
                            detail
                        }

                    _tmdbDetail.value = shownDetail
                    Log.e(
                        "KBStream",
                        "tmdbDetail populated type=$normalizedType id=${detail?.id} " +
                            "genres=${detail?.genres?.size} keywords=${detail?.keywords?.list()?.size} " +
                            "cast=${detail?.credits?.cast?.size} crew=${detail?.credits?.crew?.size} " +
                            "companies=${detail?.productionCompanies?.size} " +
                            "reviews=${detail?.reviews?.results?.size} " +
                            "recs=${detail?.recommendations?.results?.size} " +
                            "recsShown=${shownDetail?.recommendations?.results?.size} " +
                            // Whether TMDB returned any video at all decides
                            // whether the trailer button can exist; logging it
                            // separates "no video on TMDB" from "we hid it".
                            "videos=${detail?.videos?.results?.size}"
                    )
                    val collectionId = detail?.belongsToCollection?.id
                    // The collection row is the only thing this call feeds,
                    // and nothing else on the load path reads it - so it runs
                    // in the background instead of holding the spinner for
                    // another round trip. The poster-watched refresh DOES read
                    // the collection's parts, so it moves in here behind it
                    // rather than being left behind to race it.
                    viewModelScope.launch {
                        if (collectionId != null) {
                            runCatching { tmdbRepository.getCollection(collectionId) }
                                .onSuccess { collection -> _collection.value = collection }
                        }
                        refreshPosterWatchedStatus(normalizedType)
                    }
                    fetchExtraReviews(normalizedType)
                }

                // 5. Trigger season selection strictly after history states are fully loaded
                if (normalizedType == "series") {
                    autoLoadRelevantSeason(
                        detail = tmdbDetailResult.getOrNull() ?: _tmdbDetail.value,
                        localCompletedEntries = localCompletedEntries,
                        initialSeason = initialSeason
                    )
                }
                
            } catch (e: Exception) {
                _error.value = "Failed to load: ${e.message}"
                Log.e("KBStream", "detail load failed", e)
            } finally {
                _isLoading.value = false
                // Hand the state to the freshness gate - but only if this load
                // produced a usable page. A failed one has to retry, and the
                // kids-mode block deliberately empties the detail.
                loadedDetailKey = if (_error.value == null) detailKey else null
                loadedDetailAtMs = SystemClock.elapsedRealtime()
                PerfTrace.record(
                    label = "detail.load",
                    ms = SystemClock.elapsedRealtime() - loadStartedAt,
                    ok = _error.value == null
                )
                Log.e("KBStream", "detail load finished type=$type id=$id")
            }
        }
    }

    /**
     * MDBList ratings (IMDb / TMDB / Rotten Tomatoes / Metacritic / Trakt /
     * Letterboxd / MyAnimeList). Key comes from the Settings screen (stored
     * in prefs) first, falling back to the MDBLIST_API_KEY BuildConfig
     * field; blank in both = feature silently off. IMDb id: addon meta ids
     * are already tt-id based for movies/series; for TMDB-only ids fall back
     * to the external_ids lookup.
     */
    /** MDBList ratings + the full review list (see [DetailRatingEnrichment]). */
    private fun fetchMdbListRatings(normalizedType: String, retryOnResolve: Boolean = true) =
        DetailRatingEnrichment.ratings(this, normalizedType, retryOnResolve)

    private fun fetchExtraReviews(normalizedType: String) =
        DetailRatingEnrichment.extraReviews(this, normalizedType)

    internal fun setMdbListRatings(value: MdbListRatings?) {
        _mdbListRatings.value = value
    }

    internal fun setAllReviews(value: List<TmdbReview>) {
        _allReviews.value = value
    }

    private fun autoLoadRelevantSeason(
        detail: TmdbDetail?,
        localCompletedEntries: List<WatchHistoryEntity>,
        initialSeason: Int?
    ) {
        if (detail == null) return

        val seasons = detail.seasons
            ?.mapNotNull { it.seasonNumber }
            ?.filter { it > 0 }
            .orEmpty()

        if (seasons.isEmpty()) return

        val resumeSeason = _resumeInfo.value?.season?.takeIf { it > 0 && it in seasons }
        val targetSeason = when {
            initialSeason != null && initialSeason in seasons -> initialSeason
            resumeSeason != null -> resumeSeason
            else -> {
                val latestLocal = localCompletedEntries
                    .mapNotNull { entry ->
                        val s = entry.season
                        val ep = entry.episode
                        if (s != null && ep != null && s in seasons) s to ep else null
                    }
                    .maxWithOrNull(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
                    ?.first

                val latestSimkl = _simklWatchedEpisodes.value
                    .filter { it.first in seasons }
                    .maxWithOrNull(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
                    ?.first

                // No history at all: the show's FIRST season, so the screen
                // opens on S1E1 rather than on whatever TMDB happened to list
                // first (its seasons array is not sorted by contract).
                val latestWatched =
                    listOfNotNull(latestLocal, latestSimkl).maxOrNull() ?: seasons.min()

                advancePastWatchedSeasons(
                    startSeason = latestWatched,
                    seasons = seasons
                )
            }
        }

        // This was previously computed and discarded - nothing ever told the
        // UI or the episodes list which season to actually load. Without this
        // call, episodes only ever loaded once something else (e.g. the
        // screen's own season-selection effect) independently decided on a
        // season, which is why episodes could show empty until the user
        // manually switched seasons.
        loadEpisodesForSeason(targetSeason)
    }

    /**
     * Picks the season to open, from the season the history points at. The rule
     * itself — leave a season only once it is FINISHED — lives in
     * [DetailSeasonTarget], where it can be tested without a repository or a
     * running coroutine; this supplies it with the live watched state.
     */
    private fun advancePastWatchedSeasons(
        startSeason: Int,
        seasons: List<Int>
    ): Int {
        val completedIds = _completedEpisodeIds.value
        val watchedKeys = _watchedEpisodeKeys.value

        fun isWatched(season: Int, episode: Int): Boolean =
            computeEpisodeWatched(
                parentId = imdbId,
                season = season,
                episode = episode,
                episodeStreamId = null,
                completedIds = completedIds,
                watchedKeys = watchedKeys
            )

        fun isSeasonReleased(season: Int): Boolean {
            val detail = _tmdbDetail.value
            val premiere = detail?.seasons
                ?.firstOrNull { it.seasonNumber == season }
                ?.airDate
                ?: return true
            val seasonDate = runCatching { java.time.LocalDate.parse(premiere) }
                .getOrNull()
                ?: return true

            // TMDB's per-season air_date can lag behind the show's own
            // next_episode_to_air (American Horror Story: 13 carried Oct 1
            // while the pointer said Sep 25 - the date the Home Upcoming rail
            // showed). Believe whichever is earlier, which can only make a
            // season count as released, never the reverse.
            val pointerDate = detail.nextEpisodeToAir
                ?.takeIf { it.seasonNumber == season }
                ?.airDate
                ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            val effective = when {
                pointerDate != null && pointerDate.isBefore(seasonDate) -> pointerDate
                else -> seasonDate
            }

            return !effective.isAfter(java.time.LocalDate.now())
        }

        fun seasonEpisodeNumbers(season: Int): List<Int> {
            val summary = _tmdbDetail.value?.seasons
                ?.firstOrNull { it.seasonNumber == season }
                ?: return emptyList()
            val count = summary.episodeCount ?: return emptyList()
            return if (count > 0) (1..count).toList() else emptyList()
        }

        return DetailSeasonTarget.pick(
            startSeason = startSeason,
            seasons = seasons,
            episodeNumbers = { season -> seasonEpisodeNumbers(season) },
            isWatched = { season, episode -> isWatched(season, episode) },
            isReleased = { season -> isSeasonReleased(season) }
        )
    }

    /**
     * Simkl playback fallback for the Detail screen.
     *
     * The resume row above only reflects LOCAL watch history. When progress
     * lives in a Simkl cloud playback session instead (paused mid-episode on
     * this or another device), synthesize a display-only history row from it
     * so the UI lights up RESUME + progress bar + position. Estimated
     * position = progress% x episode runtime (Simkl exposes only a
     * percentage). Never written back to the local database.
     */
    /** Tracker playback-resume rows (see [DetailPlaybackResume]). */
    private suspend fun simklPlaybackResumeFor(
        id: String,
        type: String,
        tmdbId: Int?
    ): WatchHistoryEntity? = DetailPlaybackResume.simkl(this, id, type, tmdbId)

    private suspend fun mdbListPlaybackResumeFor(
        id: String,
        type: String,
        tmdbId: Int?
    ): WatchHistoryEntity? = DetailPlaybackResume.mdbList(this, id, type, tmdbId)

    fun loadEpisodesForSeason(season: Int) {
        // Synthetic (addon-videos) detail: build the episode list from the
        // Stremio Meta.videos entries instead of a TMDB season endpoint so
        // TVDB/anime titles without a TMDB match still get a full browser.
        // streamId uses the same "<parentId>:<s>:<e>" shape TMDB episodes
        // use, so stream addons and watch-history keys stay compatible.
        if (_isSyntheticDetail.value) {
            latestEpisodeSeasonRequest = season
            _episodes.value = emptyList()
            _episodeError.value = null

            viewModelScope.launch {
                _episodesLoading.value = true
                try {
                    val videos = _meta.value?.videos.orEmpty()
                        .filter { it.season == season && it.episode != null }
                        .sortedBy { it.episode ?: 0 }
                    val parentId = imdbId
                    val syntheticEpisodes = videos.mapNotNull { v ->
                        val episodeNumber = v.episode ?: return@mapNotNull null
                        ResolvedEpisode(
                            streamId = "$parentId:${season}:$episodeNumber",
                            episodeNumber = episodeNumber,
                            name = v.title,
                            overview = v.overview ?: v.description,
                            thumbnail = v.thumbnail,
                            runtimeMinutes = null,
                            airDate = v.released,
                            voteAverage = null
                        )
                    }
                    _episodes.value = applyAirDateCorrections(syntheticEpisodes, season)
                    _loadedSeason.value = season
                    val targetEp = _episodes.value.firstOrNull { ep ->
                        val isWatched = computeEpisodeWatched(
                            parentId = imdbId,
                            season = season,
                            episode = ep.episodeNumber,
                            episodeStreamId = ep.streamId,
                            completedIds = _completedEpisodeIds.value,
                            watchedKeys = _watchedEpisodeKeys.value
                        )
                        !isWatched
                    } ?: _episodes.value.firstOrNull()
                    _targetEpisode.value = targetEp
                    _playButtonText.value = targetEp?.let {
                        "Play S${season}E${it.episodeNumber}"
                    } ?: "Play"
                } finally {
                    if (latestEpisodeSeasonRequest == season) {
                        _episodesLoading.value = false
                    }
                }
            }
            return
        }

        val tvId = _tmdbDetail.value?.id
        if (tvId == null) {
            Log.e("KBStream", "episodes skipped: tmdbDetail id is null for imdbId=$imdbId season=$season")
            return
        }

        latestEpisodeSeasonRequest = season
        _episodes.value = emptyList()
        _episodeError.value = null

        viewModelScope.launch {
            _episodesLoading.value = true
            try {
                val seasonEpisodes = tmdbRepository.getSeasonEpisodes(tvId, season, imdbId)
                if (latestEpisodeSeasonRequest == season) {
                    _episodes.value = applyAirDateCorrections(seasonEpisodes, season)
                    _episodeError.value = null
                    _loadedSeason.value = season

                    val resume = _resumeInfo.value
                    val targetEp = if (resume != null && resume.season == season) {
                        seasonEpisodes.firstOrNull { it.episodeNumber == resume.episode }
                    } else {
                        seasonEpisodes.firstOrNull { ep ->
                            val eNum = ep.episodeNumber
                            val isWatched = computeEpisodeWatched(
                                parentId = imdbId,
                                season = season,
                                episode = eNum,
                                episodeStreamId = ep.streamId,
                                completedIds = _completedEpisodeIds.value,
                                watchedKeys = _watchedEpisodeKeys.value
                            )
                            !isWatched
                        } ?: seasonEpisodes.firstOrNull()
                    }

                    _targetEpisode.value = targetEp

                    if (resume != null && resume.season == season && resume.positionMs > 0L) {
                        _playButtonText.value = "Resume S${resume.season}E${resume.episode}"
                    } else if (targetEp != null) {
                        val e = targetEp.episodeNumber
                        _playButtonText.value = "Play S${season}E${e}"
                    } else {
                        _playButtonText.value = "Play"
                    }

                } else {
                    Log.e("KBStream", "episodes ignored stale result for season=$season imdbId=$imdbId")
                }
            } catch (e: Exception) {
                Log.e("KBStream", "episodes load failed for season=$season imdbId=$imdbId", e)
                if (latestEpisodeSeasonRequest == season) {
                    _episodes.value = emptyList()
                    _episodeError.value = e.message ?: "Couldn't load episodes"
                }
            } finally {
                if (latestEpisodeSeasonRequest == season) {
                    _episodesLoading.value = false
                }
            }
        }
    }

    /**
     * Fetches the second source's air dates for this show and re-dates the
     * episode list already on screen.
     *
     * Silent about failure by design: the repository answers an empty map for
     * an unknown show, a blocked network or an outage, and an empty map leaves
     * TMDB's dates in place (see [AirDateCorrection]).
     */
    private suspend fun loadAirDateCorrections(parentId: String) {
        if (imdbId != parentId) return

        val detail = _tmdbDetail.value ?: return
        if (detail.id <= 0) return

        // This source is looked up by IMDB id, while the screen's parent id is
        // usually the "tmdb:<n>" form; resolveImdbId caches its answer in the
        // resolution table, so this is free after the first open.
        val imdbIdForSource = imdbId
            .takeIf { it.startsWith("tt", ignoreCase = true) }
            ?: runCatching { tmdbRepository.resolveImdbId(detail.id, "series") }
                .getOrNull()
        if (imdbIdForSource.isNullOrBlank()) return

        val dates = airDateRepository.episodeAirDates(imdbIdForSource)
        // The load may have moved on to another title while this was in
        // flight; publishing then would re-date the wrong screen.
        if (dates.isEmpty() || imdbId != parentId) return

        _airDateCorrections.value = AirDateCorrections(
            episodeDates = dates,
            seasonPremieres = AirDateCorrection.seasonPremieresFrom(dates)
        )

        val season = _loadedSeason.value
        if (season != null && _episodes.value.isNotEmpty()) {
            _episodes.value = applyAirDateCorrections(_episodes.value, season)
        }
    }

    /**
     * TMDB's dates for [episodes] with the second source's corrections applied.
     *
     * Idempotent, so it is safe to run again once corrections arrive after the
     * episodes do: a date that has already been corrected is no longer in the
     * future, so the second pass leaves it alone.
     */
    private fun applyAirDateCorrections(
        episodes: List<ResolvedEpisode>,
        season: Int
    ): List<ResolvedEpisode> {
        val corrections = _airDateCorrections.value
        if (corrections.episodeDates.isEmpty()) return episodes

        val today = java.time.LocalDate.now()
        return episodes.map { episode ->
            val corrected = AirDateCorrection.correctAirDate(
                primary = episode.airDate,
                secondary = corrections.episodeDates[
                    AirDateCorrection.episodeKey(season, episode.episodeNumber)
                ],
                today = today
            )
            if (corrected == episode.airDate) {
                episode
            } else {
                episode.copy(airDate = corrected)
            }
        }
    }

    fun loadPerson(personId: Int) {
        _selectedPersonLoading.value = false
        _selectedPersonError.value = null
        _selectedPersonDetail.value = null
    }

    /**
     * Long-press "Mark as Watched" on a More Like This / collection rail
     * poster: resolves the TMDB id to an IMDB id, records the persistent
     * local watched override (mirrored to SIMKL when connected) and updates
     * the reactive poster keys so the checkmark appears immediately.
     */
    fun markPosterWatched(
        tmdbId: Int,
        mediaType: String
    ) {
        viewModelScope.launch {
            val normalizedType = mediaType.lowercase().takeIf {
                it == "movie" || it == "series" || it == "tv"
            } ?: return@launch

            val lookup = posterLookupKey(tmdbId, normalizedType)
            val imdbId = _resolvedPosterIds.value[lookup]
                ?: runCatching {
                    resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()
                    ?: return@launch

            if (_resolvedPosterIds.value[lookup] == null) {
                _resolvedPosterIds.value = _resolvedPosterIds.value + (lookup to imdbId)
            }

            runCatching {
                watchedStatusRepository.markWatchedLocal(imdbId, normalizedType)
            }.onFailure { e ->
                Log.e("KBStream", "markPosterWatched failed tmdb=$tmdbId type=$normalizedType", e)
            }

            _watchedKeys.value = _watchedKeys.value + watchedKey(imdbId, normalizedType)
            // A manual mark resolves the tile to fully-watched: drop the eye.
            _partialWatchedKeys.value =
                _partialWatchedKeys.value - watchedKey(imdbId, normalizedType)
        }
    }

    /**
     * Long-press "Mark as Unwatched" on a More Like This / collection rail
     * poster: resolves the TMDB id to an IMDB id, removes the persistent
     * local watched override (mirrored as a Simkl history delete when
     * connected) and drops the poster key so the checkmark clears.
     */
    fun markPosterUnwatched(
        tmdbId: Int,
        mediaType: String
    ) {
        viewModelScope.launch {
            val normalizedType = mediaType.lowercase().takeIf {
                it == "movie" || it == "series" || it == "tv"
            } ?: return@launch

            val lookup = posterLookupKey(tmdbId, normalizedType)
            val imdbId = _resolvedPosterIds.value[lookup]
                ?: runCatching {
                    resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()
                    ?: return@launch

            if (_resolvedPosterIds.value[lookup] == null) {
                _resolvedPosterIds.value = _resolvedPosterIds.value + (lookup to imdbId)
            }

            runCatching {
                watchedStatusRepository.markUnwatchedLocal(imdbId, normalizedType)
            }.onFailure { e ->
                Log.e("KBStream", "markPosterUnwatched failed tmdb=$tmdbId type=$normalizedType", e)
            }

            _watchedKeys.value = _watchedKeys.value - watchedKey(imdbId, normalizedType)
            // Reset state entirely: the tile goes back to unwatched.
            _partialWatchedKeys.value =
                _partialWatchedKeys.value - watchedKey(imdbId, normalizedType)
        }
    }

    /**
     * Long-press "Mark as Watched" on a season chip: writes one local
     * completed history row per episode (keyed by the derived
     * "parent:season:episode" key so the watched keys / episode badges
     * update immediately and survive restarts; positionMs=0 keeps them out
     * of Continue Watching / Recent) and mirrors the season to SIMKL
     * episode-by-episode when connected.
     */
    fun markSeasonWatched(
        season: Int,
        episodeNumbers: List<Int>,
        /**
         * [WatchHistoryEntity.episodeStreamId] values for the marked
         * episodes, when the caller knows them (an episode card does; a
         * season chip only has numbers). See
         * [WatchHistoryDao.deleteResumeRowsForParentsStreamIds] for why they
         * are the stronger identity for the progress-row cleanup below.
         */
        episodeStreamIds: List<String> = emptyList()
    ) {
        val parentId = imdbId
        if (parentId.isBlank() || season < 0) return

        val validEpisodes =
            episodeNumbers.filter { it > 0 }.distinct()
        if (validEpisodes.isEmpty()) return

        viewModelScope.launch {
            val showName =
                _meta.value?.name?.ifBlank {
                    _tmdbDetail.value?.name
                        ?: _tmdbDetail.value?.title
                        ?: ""
                } ?: _tmdbDetail.value?.name
                ?: _tmdbDetail.value?.title
                ?: ""
            val posterUrl =
                _meta.value?.poster?.takeIf {
                    it.isNotBlank()
                } ?: _tmdbDetail.value?.posterPath
                ?.let {
                    TmdbRepository.POSTER_BASE + it
                }
            // Synthetic addon-videos detail carries a -1 sentinel id; it is
            // not a real TMDB id and must never reach Simkl.
            val showTmdbId =
                _tmdbDetail.value?.id?.takeIf { it > 0 }

            val now =
                System.currentTimeMillis()

            // 1. Local: one completed row per episode so the next load()
            // re-derives the watched keys from Room.
            validEpisodes.forEach { episode ->
                val key =
                    WatchedEpisodeState.buildEpisodeKey(
                        parentId = parentId,
                        season = season,
                        episode = episode
                    )

                if (key == null) {
                    return@forEach
                }

                val row =
                    WatchHistoryEntity(
                        id = key,
                        parentId = parentId,
                        type = "series",
                        name = showName,
                        poster = posterUrl,
                        streamUrl = null,
                        positionMs = 0L,
                        durationMs = 1L,
                        season = season,
                        episode = episode,
                        updatedAt = now,
                        isCompleted = true,
                        completedAt = now
                    )

                runCatching {
                    historyDao.upsert(row)
                }.onSuccess {
                    // Mirror the marker itself. The player keys its resume row
                    // for an episode the same way, so when the cloud still
                    // holds that resume write the marker has to reach it too,
                    // or a pull applies the resume row back OVER this marker.
                    runCatching { SupabaseSync.enqueueHistory(row) }
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markSeasonWatched row failed s=$season e=$episode",
                        e
                    )
                }
            }

            // 1b. A watched episode has nothing left to resume, so drop the
            // progress row too - the completed marker written above is what
            // keeps the badge. Marking a season (or one episode) watched used
            // to leave the resume bar sitting there, because the progress row
            // is a different row from the marker.
            runCatching {
                val parents = localHistoryParentIds(parentId)

                // Captured BEFORE the deletes: these are the rows whose CLOUD
                // copies have to end too (see [endCloudResumeRows]).
                val resumeRowsBefore =
                    historyDao.getInProgressForParents(parents)

                var removedByNumber = 0
                validEpisodes.forEach { episode ->
                    removedByNumber +=
                        historyDao.deleteResumeRowsForParentsSeasonEpisode(
                            parentIds = parents,
                            season = season,
                            episode = episode
                        )
                }

                // Second pass, by the identity the UI actually keys progress
                // on (inProgressByStreamId[ep.streamId]). A row whose
                // season/episode numbers disagree with the card's never
                // matched the loop above, so the bar outlived the mark.
                val streamIds =
                    episodeStreamIds
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                val removedByStreamId =
                    if (streamIds.isEmpty()) {
                        0
                    } else {
                        historyDao.deleteResumeRowsForParentsStreamIds(
                            parentIds = parents,
                            streamIds = streamIds
                        )
                    }

                Log.i(
                    "KBStream",
                    "markSeasonWatched resume cleanup s=$season " +
                        "eps=$validEpisodes streams=${streamIds.size} " +
                        "byNumber=$removedByNumber byStreamId=$removedByStreamId"
                )

                endCloudResumeRows(resumeRowsBefore)
            }.onFailure { e ->
                Log.e(
                    "KBStream",
                    "markSeasonWatched resume cleanup failed s=$season",
                    e
                )
            }

            // The hero's RESUME bar and the per-card progress bars read these
            // in-memory copies, so they have to lose the marked episodes too.
            _inProgressByStreamId.value =
                _inProgressByStreamId.value.filterValues { row ->
                    !(row.season == season && (row.episode ?: -1) in validEpisodes)
                }
            _resumeInfo.value =
                _resumeInfo.value?.takeUnless { row ->
                    row.season == season && (row.episode ?: -1) in validEpisodes
                }

            // 2. Optimistic in-memory state so badges light up instantly.
            val newKeys =
                _watchedEpisodeKeys.value +
                    validEpisodes.mapNotNull { episode ->
                        WatchedEpisodeState.buildEpisodeKey(
                            parentId = parentId,
                            season = season,
                            episode = episode
                        )
                    }
            _watchedEpisodeKeys.value = newKeys

            val newSimklPairs =
                _simklWatchedEpisodes.value +
                    validEpisodes.map { episode ->
                        season to episode
                    }
            _simklWatchedEpisodes.value = newSimklPairs
            _simklSeriesWatched.value =
                newSimklPairs.isNotEmpty()

            // 3. Mirror to SIMKL when connected.
            if (
                simklRepository.isConfigured() &&
                simklRepository.hasToken()
            ) {
                runCatching {
                    simklRepository.pushWatchedSeason(
                        showImdbId = parentId,
                        season = season,
                        episodes = validEpisodes,
                        title = showName.takeIf {
                            it.isNotBlank()
                        },
                        tmdbId = showTmdbId
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markSeasonWatched simkl failed s=$season",
                        e
                    )
                }
            }

            // 3b. Close the paused SIMKL sessions for the marked episodes.
            // pushWatchedSeason's own cleanup only closes a session already
            // past 95% - it is shared with the player's completion path,
            // where a mostly-unwatched session must survive - but an EXPLICIT
            // mark makes that episode's session stale whatever its progress.
            // A session paused short of that threshold was therefore never
            // closed, and it kept feeding the rail a Continue Watching card
            // at its old position. Scoped to these episodes: a paused session
            // on another episode of the same show is still in progress and
            // belongs on the rail.
            if (
                simklRepository.isConfigured() &&
                simklRepository.hasToken()
            ) {
                runCatching {
                    simklRepository.deletePlaybackSessionsForParent(
                        parentId = parentId,
                        title = showName.takeIf { it.isNotBlank() },
                        seasonsEpisodes =
                            validEpisodes
                                .map { episode -> season to episode }
                                .toSet()
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markSeasonWatched simkl session cleanup failed s=$season",
                        e
                    )
                }
            }

            // 4. Mirror to MDBList when a key is set: one bulk
            // /sync/watched call per batch, episode-level via the nested
            // shows payload.
            if (MdbListClient.isConfigured(getApplication())) {
                runCatching {
                    MdbListClient.pushWatchedEpisodes(
                        getApplication(),
                        imdbId = parentId.takeIf { it.startsWith("tt") },
                        tmdbId = showTmdbId,
                        season = season,
                        episodes = validEpisodes
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markSeasonWatched mdblist failed s=$season",
                        e
                    )
                }
            }

            refreshPostersAfterWatchedChange()
        }
    }

    /**
     * Long-press "Mark as Unwatched" on a season chip: deletes every local
     * completed row for that season, drops the derived watched keys from
     * memory so the badges clear instantly, and mirrors the removal to
     * SIMKL episode-by-episode when connected.
     */
    fun markSeasonUnwatched(
        season: Int,
        episodeNumbers: List<Int>
    ) {
        val parentId = imdbId
        if (parentId.isBlank() || season < 0) return

        val validEpisodes =
            episodeNumbers.filter { it > 0 }.distinct()
        if (validEpisodes.isEmpty()) return

        viewModelScope.launch {
            val showName =
                _meta.value?.name?.ifBlank {
                    _tmdbDetail.value?.name
                        ?: _tmdbDetail.value?.title
                        ?: ""
                } ?: _tmdbDetail.value?.name
                ?: _tmdbDetail.value?.title
                ?: ""
            // Synthetic addon-videos detail carries a -1 sentinel id; it is
            // not a real TMDB id and must never reach Simkl.
            val showTmdbId =
                _tmdbDetail.value?.id?.takeIf { it > 0 }

            // 1. Local: drop every completed row for this season, under every
            // id flavor this title is reachable by - a season marked watched
            // from the other flavor would otherwise survive the unmark.
            runCatching {
                historyDao.deleteCompletedForParentsSeason(
                    parentIds = localHistoryParentIds(parentId),
                    season = season
                )
            }.onFailure { e ->
                Log.e(
                    "KBStream",
                    "markSeasonUnwatched delete failed s=$season",
                    e
                )
            }

            // 2. Optimistic in-memory state so badges clear instantly.
            val removeKeys =
                validEpisodes.mapNotNull { episode ->
                    WatchedEpisodeState.buildEpisodeKey(
                        parentId = parentId,
                        season = season,
                        episode = episode
                    )
                }.toSet()
            _watchedEpisodeKeys.value =
                _watchedEpisodeKeys.value - removeKeys

            val removePairs =
                validEpisodes.map { episode ->
                    season to episode
                }.toSet()
            _simklWatchedEpisodes.value =
                _simklWatchedEpisodes.value - removePairs
            _simklSeriesWatched.value =
                _simklWatchedEpisodes.value.isNotEmpty()

            // 3. A manual whole-show mark cannot be cleared episode by
            // episode, so a series carrying that override gets its tracker
            // record rewritten instead. Clearing the override is also what
            // stops the app's own poster from keeping its checkmark.
            //
            // This MUST run before the "started but unfinished" write below:
            // clearing the override rewrites the cached row as neither
            // watched NOR partial, so running it afterwards wiped the partial
            // marker this path had just set - the poster lost its eye and the
            // badge went bare.
            val hadWholeShowMark =
                runCatching {
                    watchedStatusRepository.clearWatchedOverride(
                        parentId,
                        "series"
                    )
                }.getOrDefault(false)

            // The show is no longer completed but still has watched
            // episodes, so record started-but-unfinished: the badge becomes
            // the eye at once - on this screen and on every poster - instead
            // of keeping the completed checkmark from the cached Simkl
            // snapshot / the old on-disk row.
            if (_simklWatchedEpisodes.value.isNotEmpty() ||
                _watchedEpisodeKeys.value.isNotEmpty()
            ) {
                runCatching {
                    watchedStatusRepository.markPartiallyWatchedLocal(
                        parentId,
                        "series"
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markPartiallyWatchedLocal failed after unmark",
                        e
                    )
                }
            }

            if (hadWholeShowMark) {
                rewriteTrackersAfterPartialUnmark(
                    parentId = parentId,
                    showTmdbId = showTmdbId,
                    title = showName,
                    removed = validEpisodes.map { episode ->
                        season to episode
                    }.toSet()
                )
                return@launch
            }

            // 4. Mirror to SIMKL when connected.
            if (
                simklRepository.isConfigured() &&
                simklRepository.hasToken()
            ) {
                runCatching {
                    simklRepository.removeWatchedSeason(
                        showImdbId = parentId,
                        season = season,
                        episodes = validEpisodes,
                        title = showName.takeIf {
                            it.isNotBlank()
                        },
                        tmdbId = showTmdbId
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markSeasonUnwatched simkl failed s=$season",
                        e
                    )
                }
            }

            // 5. Mirror the removal to MDBList when a key is set: one
            // bulk /sync/watched/remove call per batch.
            if (MdbListClient.isConfigured(getApplication())) {
                runCatching {
                    MdbListClient.removeWatchedEpisodes(
                        getApplication(),
                        imdbId = parentId.takeIf { it.startsWith("tt") },
                        tmdbId = showTmdbId,
                        season = season,
                        episodes = validEpisodes
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markSeasonUnwatched mdblist failed s=$season",
                        e
                    )
                }
            }
        }
    }

    /**
     * Re-writes the trackers after part of a manually whole-show-marked
     * series is unmarked.
     *
     * A "Mark as Watched" / "Mark Entire Series as Watched" on a series
     * stores the show on Simkl and MDBList as ONE show-level record (ids
     * only; the trackers expand it across every episode themselves). Neither
     * tracker can clear that record with an episode-level removal - which is
     * why unmarking a single season cleared the app while Simkl and MDBList
     * kept showing the whole show as watched. The record has to be rewritten
     * the way it was written: drop the whole show, then re-add exactly the
     * episodes that are still marked watched ([removed] excluded).
     */
    private suspend fun rewriteTrackersAfterPartialUnmark(
        parentId: String,
        showTmdbId: Int?,
        title: String,
        removed: Set<Pair<Int, Int>>
    ) {
        // What is still watched: this device's completed rows merged with
        // Simkl's per-episode snapshot (a cached read, no extra round trip),
        // minus the episodes just unmarked.
        val localPairs =
            runCatching {
                historyDao.getCompletedForParents(
                    localHistoryParentIds(parentId)
                )
            }.getOrDefault(
                emptyList()
            ).mapNotNull { row ->
                val season =
                    row.season
                val episode =
                    row.episode

                if (season != null && episode != null) {
                    season to episode
                } else {
                    null
                }
            }

        val simklPairs =
            runCatching {
                simklRepository.getWatchedEpisodesForShowByImdb(
                    imdbId = parentId,
                    tmdbId = showTmdbId
                )
            }.getOrDefault(
                emptySet()
            )

        val remainingBySeason =
            (localPairs + simklPairs)
                .filterNot { pair -> pair in removed }
                .groupBy(
                    keySelector = { pair -> pair.first },
                    valueTransform = { pair -> pair.second }
                )
                .mapValues { (_, episodes) ->
                    episodes.distinct().sorted()
                }

        val imdbId =
            parentId.takeIf { it.startsWith("tt") }
        val titleOrNull =
            title.takeIf { it.isNotBlank() }
        val appContext =
            getApplication<Application>()

        if (
            simklRepository.isConfigured() &&
            simklRepository.hasToken()
        ) {
            runCatching {
                simklRepository.removeWatchedShow(
                    showImdbId = parentId,
                    title = titleOrNull,
                    tmdbId = showTmdbId
                )
            }.onFailure { e ->
                Log.e(
                    "KBStream",
                    "rewriteTrackers unmark simkl failed",
                    e
                )
            }

            remainingBySeason.forEach { (season, episodes) ->
                runCatching {
                    simklRepository.pushWatchedSeason(
                        showImdbId = parentId,
                        season = season,
                        episodes = episodes,
                        title = titleOrNull,
                        tmdbId = showTmdbId
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "rewriteTrackers re-mark simkl failed s=$season",
                        e
                    )
                }
            }
        }

        if (MdbListClient.isConfigured(appContext)) {
            runCatching {
                MdbListClient.removeWatchedShow(
                    appContext,
                    imdbId = imdbId,
                    tmdbId = showTmdbId
                )
            }.onFailure { e ->
                Log.e(
                    "KBStream",
                    "rewriteTrackers unmark mdblist failed",
                    e
                )
            }

            remainingBySeason.forEach { (season, episodes) ->
                runCatching {
                    MdbListClient.pushWatchedEpisodes(
                        appContext,
                        imdbId = imdbId,
                        tmdbId = showTmdbId,
                        season = season,
                        episodes = episodes
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "rewriteTrackers re-mark mdblist failed s=$season",
                        e
                    )
                }
            }
        }

        Log.i(
            "KBStream",
            "rewriteTrackers finished parent=$parentId seasons=${remainingBySeason.size} removed=${removed.size}"
        )

        // This path returns early from the unmark flows, so it has to repaint
        // the poster rows itself.
        refreshPostersAfterWatchedChange()
    }

    /**
     * Cleans the (season -> episode numbers) map the episode/season chip
     * menus hand in: drops invalid entries and sorts/dedupes the numbers so
     * a whole-series mark cannot write the same row twice.
     */
    private fun normalizeSeasonEpisodes(
        seasonEpisodes: List<Pair<Int, List<Int>>>
    ): List<Pair<Int, List<Int>>> =
        seasonEpisodes
            .map { (season, episodes) ->
                season to episodes
                    .filter { it > 0 }
                    .distinct()
                    .sorted()
            }
            .filter { (season, episodes) ->
                season >= 0 && episodes.isNotEmpty()
            }
            .distinctBy { it.first }

    /**
     * Long-press "Mark Entire Series as Watched" (episode / season chip
     * menus): the one-shot answer for shows with a lot of seasons. Writes a
     * completed local row for every episode the caller could enumerate,
     * drops the title's resume rows so nothing is left half-watched in
     * Continue Watching, and records the whole-show watched override — which
     * is also what mirrors the mark to SIMKL and MDBList (one call each),
     * syncs it to the other devices, and paints the poster checkmark.
     *
     * An empty [seasonEpisodes] still marks the show on the trackers and the
     * poster; it just cannot paint per-episode state locally.
     */
    fun markSeriesWatched(
        seasonEpisodes: List<Pair<Int, List<Int>>>
    ) {
        val parentId = imdbId
        if (parentId.isBlank()) return

        val seasonsToMark =
            normalizeSeasonEpisodes(seasonEpisodes)

        viewModelScope.launch {
            val showName =
                _meta.value?.name?.ifBlank {
                    _tmdbDetail.value?.name
                        ?: _tmdbDetail.value?.title
                        ?: ""
                } ?: _tmdbDetail.value?.name
                ?: _tmdbDetail.value?.title
                ?: ""
            val posterUrl =
                _meta.value?.poster?.takeIf {
                    it.isNotBlank()
                } ?: _tmdbDetail.value?.posterPath
                ?.let {
                    TmdbRepository.POSTER_BASE + it
                }
            // Synthetic addon-videos detail carries a -1 sentinel id; it is
            // not a real TMDB id and must never reach Simkl.
            val showTmdbId =
                _tmdbDetail.value?.id?.takeIf { it > 0 }

            val now =
                System.currentTimeMillis()

            // 1. Local: one completed row per episode, so the season and
            // episode badges survive the next load()/restart. One bulk write,
            // not one transaction per episode.
            val rows =
                seasonsToMark.flatMap { (season, episodes) ->
                    episodes.mapNotNull { episode ->
                        WatchedEpisodeState
                            .buildEpisodeKey(
                                parentId = parentId,
                                season = season,
                                episode = episode
                            )
                            ?.let { key ->
                                WatchHistoryEntity(
                                    id = key,
                                    parentId = parentId,
                                    type = "series",
                                    name = showName,
                                    poster = posterUrl,
                                    streamUrl = null,
                                    positionMs = 0L,
                                    durationMs = 1L,
                                    season = season,
                                    episode = episode,
                                    updatedAt = now,
                                    isCompleted = true,
                                    completedAt = now
                                )
                            }
                    }
                }

            if (rows.isNotEmpty()) {
                runCatching {
                    historyDao.upsertAll(
                        rows
                    )
                }.onSuccess {
                    // Same mirror as the per-episode mark: the markers have to
                    // reach the cloud, or a stale resume write for the same
                    // episode key outranks them on the next pull.
                    rows.forEach { row ->
                        runCatching { SupabaseSync.enqueueHistory(row) }
                    }
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markSeriesWatched rows failed count=${rows.size}",
                        e
                    )
                }
            }

            // 2. The show has nothing left to resume: drop the resume rows and
            // the in-memory copies the hero / episode chips read, so the page
            // stops offering "Resume S5E3" on an episode that is now watched.
            runCatching {
                val parents = localHistoryParentIds(parentId)

                // Every in-progress row for the show, captured before the
                // delete so their cloud copies can end with them.
                val resumeRowsBefore =
                    historyDao.getInProgressForParents(parents)

                historyDao.deleteResumeRowsForParents(parents)

                endCloudResumeRows(resumeRowsBefore)
            }.onFailure { e ->
                Log.e(
                    "KBStream",
                    "markSeriesWatched resume cleanup failed",
                    e
                )
            }

            _inProgressByStreamId.value =
                emptyMap()
            _resumeInfo.value =
                null

            // 3. Optimistic in-memory state so the badges light up instantly.
            val pairs =
                seasonsToMark
                    .flatMap { (season, episodes) ->
                        episodes.map { episode ->
                            season to episode
                        }
                    }
                    .toSet()

            _watchedEpisodeKeys.value =
                _watchedEpisodeKeys.value +
                    pairs.mapNotNull { (season, episode) ->
                        WatchedEpisodeState.buildEpisodeKey(
                            parentId = parentId,
                            season = season,
                            episode = episode
                        )
                    }
            _simklWatchedEpisodes.value =
                _simklWatchedEpisodes.value + pairs
            _simklSeriesWatched.value = true

            // 4. Whole-show mark: local override (poster checkmark) + SIMKL
            // whole-show push + MDBList whole-show push + cross-device sync.
            runCatching {
                watchedStatusRepository.markWatchedLocal(
                    parentId,
                    "series"
                )
            }.onFailure { e ->
                Log.e(
                    "KBStream",
                    "markSeriesWatched override failed",
                    e
                )
            }

            // 5. Drop any paused SIMKL session for the show, or the remote
            // feed re-adds it to Continue Watching on the next refresh.
            runCatching {
                simklRepository.deletePlaybackSessionsForParent(
                    parentId = parentId,
                    title = showName.takeIf { it.isNotBlank() }
                )
            }.onFailure { e ->
                Log.e(
                    "KBStream",
                    "markSeriesWatched simkl session cleanup failed",
                    e
                )
            }

            // 6. Whole-show push to the trackers. Closing the paused session
            // above is NOT enough: Simkl's Continue Watching feed is built
            // from two tables, and for a series the card normally comes from
            // the "watching" library (getContinueWatching's
            // source = "watching", carrying a next-episode pointer) rather
            // than from the playback table this function used to be the only
            // one to touch. So a fully-marked show stayed in "watching" and
            // came straight back on the next rail refresh. The whole-show
            // write moves it to Simkl's completed list, and its impl closes
            // any open playback session too - leaving step 5 as the
            // belt-and-braces for a push that fails.
            if (
                simklRepository.isConfigured() &&
                simklRepository.hasToken()
            ) {
                runCatching {
                    simklRepository.pushWatchedShow(
                        showImdbId = parentId,
                        title = showName.takeIf { it.isNotBlank() },
                        tmdbId = showTmdbId
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markSeriesWatched simkl show push failed",
                        e
                    )
                }
            }

            if (MdbListClient.isConfigured(getApplication())) {
                runCatching {
                    MdbListClient.pushWatchedShow(
                        getApplication(),
                        imdbId = parentId.takeIf { it.startsWith("tt") },
                        tmdbId = showTmdbId
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markSeriesWatched mdblist show push failed",
                        e
                    )
                }
            }

            refreshPostersAfterWatchedChange()
        }
    }

    /**
     * Ends the CLOUD copies of resume rows a mark-watched just deleted
     * locally.
     *
     * A local delete used to be invisible to the sync layer, and the pull
     * merge rules the other way: `applyHistoryRow` lets the remote row win
     * whenever it is newer than the local one, and a DELETED local row has no
     * timestamp at all - so any surviving cloud copy came straight back on the
     * next pull or realtime event. That is how an episode the user had just
     * marked watched came back with its progress bar (and with its Continue
     * Watching card), while the completed marker stayed put: the marker is a
     * different row, so the checkmark and the bar showed at once.
     *
     * Only ids that are GONE locally are sent: when the completed marker
     * reused the resume row's id, the row still exists and its cloud copy must
     * stay - the caller has just republished it as the completed version.
     */
    private suspend fun endCloudResumeRows(candidates: List<WatchHistoryEntity>) {
        if (candidates.isEmpty()) return

        val removedIds =
            candidates
                .map { it.id }
                .distinct()
                .filter { id ->
                    runCatching { historyDao.getById(id) }
                        .getOrNull() == null
                }

        if (removedIds.isEmpty()) return

        SupabaseSync.deleteHistoryRows(removedIds)
        Log.i(
            "KBStream",
            "resume cleanup: ended ${removedIds.size} cloud row(s)"
        )
    }

    /**
     * Long-press "Mark Entire Series as Unwatched" (episode / season chip
     * menus): deletes every local completed row for the show, clears the
     * in-memory episode state so the badges clear instantly, and removes the
     * whole show from SIMKL history / MDBList via the same whole-show
     * unmark the poster menu uses.
     */
    fun markSeriesUnwatched() {
        val parentId = imdbId
        if (parentId.isBlank()) return

        viewModelScope.launch {
            // 1. Local: every completed row for this show, whatever season or
            // episode numbers they were written with.
            runCatching {
                historyDao.deleteCompletedForParents(
                    localHistoryParentIds(parentId)
                )
            }.onFailure { e ->
                Log.e(
                    "KBStream",
                    "markSeriesUnwatched delete failed",
                    e
                )
            }

            // 2. Optimistic in-memory state so badges clear instantly.
            _watchedEpisodeKeys.value =
                _watchedEpisodeKeys.value
                    .filterNot { key -> key.startsWith("$parentId:") }
                    .toSet()
            _completedEpisodeIds.value =
                emptySet()
            _simklWatchedEpisodes.value =
                emptySet()
            _simklSeriesWatched.value = false

            // 3. Whole-show unmark: override removed + SIMKL history delete +
            // MDBList removal.
            runCatching {
                watchedStatusRepository.markUnwatchedLocal(
                    parentId,
                    "series"
                )
            }.onFailure { e ->
                Log.e(
                    "KBStream",
                    "markSeriesUnwatched override failed",
                    e
                )
            }

            refreshPostersAfterWatchedChange()
        }
    }

    /**
     * Long-press "Mark as Watched" on an episode card: marks exactly one
     * episode locally and on SIMKL (reuses the season machinery with a
     * single-episode list).
     */
    fun markEpisodeWatched(
        season: Int,
        episode: Int,
        episodeStreamId: String? = null
    ) {
        markSeasonWatched(
            season = season,
            episodeNumbers = listOf(episode),
            episodeStreamIds =
                episodeStreamId?.let { listOf(it) }.orEmpty()
        )
    }

    /**
     * Long-press "Mark as Unwatched" on an episode card: clears exactly one
     * episode locally and on SIMKL, leaving the rest of the season alone.
     */
    fun markEpisodeUnwatched(
        season: Int,
        episode: Int
    ) {
        markSpecificEpisodesUnwatched(
            season = season,
            episodeNumbers = listOf(episode)
        )
    }

    /**
     * Long-press "Mark Previous as Watched" on an episode card: marks every
     * episode before the pressed one in the same season (1..episode-1).
     */
    fun markPreviousWatched(
        season: Int,
        episode: Int
    ) {
        if (episode <= 1) return
        markSeasonWatched(
            season = season,
            episodeNumbers = (1 until episode).toList()
        )
    }

    /**
     * Long-press "Mark Previous as Unwatched" on an episode card: clears
     * every episode before the pressed one in the same season.
     */
    fun markPreviousUnwatched(
        season: Int,
        episode: Int
    ) {
        if (episode <= 1) return
        markSpecificEpisodesUnwatched(
            season = season,
            episodeNumbers = (1 until episode).toList()
        )
    }

    /**
     * Shared core for un-marking an arbitrary set of episodes: deletes the
     * matching local completed rows, drops the derived watched keys / SIMKL
     * pairs from memory so the badges clear instantly, and mirrors the
     * removal to SIMKL when connected.
     */
    private fun markSpecificEpisodesUnwatched(
        season: Int,
        episodeNumbers: List<Int>
    ) {
        val parentId = imdbId
        if (parentId.isBlank() || season < 0) return

        val validEpisodes =
            episodeNumbers.filter { it > 0 }.distinct()
        if (validEpisodes.isEmpty()) return

        viewModelScope.launch {
            val showName =
                _meta.value?.name?.ifBlank {
                    _tmdbDetail.value?.name
                        ?: _tmdbDetail.value?.title
                        ?: ""
                } ?: _tmdbDetail.value?.name
                ?: _tmdbDetail.value?.title
                ?: ""
            // Synthetic addon-videos detail carries a -1 sentinel id; it is
            // not a real TMDB id and must never reach Simkl.
            val showTmdbId =
                _tmdbDetail.value?.id?.takeIf { it > 0 }

            // 1. Local: drop the completed row(s) for each targeted episode.
            validEpisodes.forEach { episode ->
                runCatching {
                    historyDao.deleteCompletedForParentsSeasonEpisode(
                        parentIds = localHistoryParentIds(parentId),
                        season = season,
                        episode = episode
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markSpecificEpisodesUnwatched delete failed s=$season e=$episode",
                        e
                    )
                }
            }

            // 2. Optimistic in-memory state so badges clear instantly.
            val removeKeys =
                validEpisodes.mapNotNull { episode ->
                    WatchedEpisodeState.buildEpisodeKey(
                        parentId = parentId,
                        season = season,
                        episode = episode
                    )
                }.toSet()
            _watchedEpisodeKeys.value =
                _watchedEpisodeKeys.value - removeKeys

            val removePairs =
                validEpisodes.map { episode ->
                    season to episode
                }.toSet()
            _simklWatchedEpisodes.value =
                _simklWatchedEpisodes.value - removePairs
            _simklSeriesWatched.value =
                _simklWatchedEpisodes.value.isNotEmpty()

            // 3. Same whole-show repair as the season path: a manual
            // whole-show mark on the trackers cannot be cleared episode by
            // episode, and its local override must go too. Runs BEFORE the
            // partial write below, which the override clear would
            // otherwise wipe (see the season path).
            val hadWholeShowMark =
                runCatching {
                    watchedStatusRepository.clearWatchedOverride(
                        parentId,
                        "series"
                    )
                }.getOrDefault(false)

            // The show is no longer completed but still has watched
            // episodes, so record started-but-unfinished: the badge becomes
            // the eye at once - on this screen and on every poster - instead
            // of keeping the completed checkmark from the cached Simkl
            // snapshot / the old on-disk row.
            if (_simklWatchedEpisodes.value.isNotEmpty() ||
                _watchedEpisodeKeys.value.isNotEmpty()
            ) {
                runCatching {
                    watchedStatusRepository.markPartiallyWatchedLocal(
                        parentId,
                        "series"
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markPartiallyWatchedLocal failed after unmark",
                        e
                    )
                }
            }

            if (hadWholeShowMark) {
                rewriteTrackersAfterPartialUnmark(
                    parentId = parentId,
                    showTmdbId = showTmdbId,
                    title = showName,
                    removed = validEpisodes.map { episode ->
                        season to episode
                    }.toSet()
                )
                return@launch
            }

            // 4. Mirror to SIMKL when connected.
            if (
                simklRepository.isConfigured() &&
                simklRepository.hasToken()
            ) {
                runCatching {
                    simklRepository.removeWatchedSeason(
                        showImdbId = parentId,
                        season = season,
                        episodes = validEpisodes,
                        title = showName.takeIf {
                            it.isNotBlank()
                        },
                        tmdbId = showTmdbId
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markSpecificEpisodesUnwatched simkl failed s=$season",
                        e
                    )
                }
            }

            // 5. Mirror the removal to MDBList when a key is set: one
            // bulk /sync/watched/remove call per batch.
            if (MdbListClient.isConfigured(getApplication())) {
                runCatching {
                    MdbListClient.removeWatchedEpisodes(
                        getApplication(),
                        imdbId = parentId.takeIf { it.startsWith("tt") },
                        tmdbId = showTmdbId,
                        season = season,
                        episodes = validEpisodes
                    )
                }.onFailure { e ->
                    Log.e(
                        "KBStream",
                        "markSpecificEpisodesUnwatched mdblist failed s=$season",
                        e
                    )
                }
            }

            refreshPostersAfterWatchedChange()
        }
    }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? =
        tmdbRepository.resolveImdbId(tmdbId, type.lowercase())

    /** Full poster URL for the loaded title (null when unavailable). */
    fun currentPosterUrl(): String? =
        _tmdbDetail.value?.posterPath
            ?.takeIf { it.isNotBlank() }
            ?.let { TmdbRepository.POSTER_BASE + it }

    /** Simkl is signed in, so long-press adds will mirror there too. */
    fun simklConnectedForLibrary(): Boolean =
        simklRepository.isConfigured() && simklRepository.hasToken()

    /** MDBList API key is set, so long-press adds will mirror there too. */
    fun mdbListConnectedForLibrary(): Boolean =
        MdbListClient.isConfigured(getApplication<Application>())

    /**
     * True when the title is already on this profile's local My List.
     * Checks the tmdb id form directly and, when not yet resolved, the
     * imdb form via the poster-lookup map.
     */
    fun isInLocalLibrary(mediaType: String, tmdbId: Int): Boolean {
        val appContext = getApplication<Application>()
        if (LocalLibraryStore.isInMyList(appContext, mediaType, null, tmdbId)) {
            return true
        }
        val imdbId = _resolvedPosterIds.value[posterLookupKey(tmdbId, mediaType)]
        return imdbId != null &&
            LocalLibraryStore.isInMyList(appContext, mediaType, imdbId, null)
    }

    /**
     * "Add to Library" from a long-press menu: saves to this profile's
     * local My List, then mirrors the add to the Simkl watchlist and/or
     * MDBList watchlist when those accounts are connected. Remote adds
     * are best-effort; the local write always wins.
     */
    fun addToLibrary(mediaType: String, tmdbId: Int, title: String) {
        val appContext = getApplication<Application>()
        val normalizedType = mediaType.lowercase()
        val imdbId = _resolvedPosterIds.value[posterLookupKey(tmdbId, mediaType)]

        val year = _tmdbDetail.value?.bestReleaseDate()?.take(4)?.toIntOrNull()

        LibraryMirror.addToLibrary(
            context = appContext,
            scope = viewModelScope,
            mediaType = normalizedType,
            imdbId = imdbId,
            tmdbId = tmdbId,
            title = title,
            year = year,
            posterUrl = _tmdbDetail.value?.posterPath
                ?.takeIf { it.isNotBlank() }
                ?.let { TmdbRepository.POSTER_BASE + it }
        )
    }
}
