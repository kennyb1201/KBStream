package com.kennyb1201.kbstream.ui.home

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.airdates.AirDateCorrection
import com.kennyb1201.kbstream.data.airdates.TvmazeAirDateRepository
import com.kennyb1201.kbstream.data.history.WatchHistoryDao
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryRepository
import com.kennyb1201.kbstream.data.iptv.EpgWriteGate
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.mdblist.MdbListPlaybackItem
import com.kennyb1201.kbstream.data.reporting.PerfTrace
import com.kennyb1201.kbstream.data.simkl.SimklContinueWatchingItem
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.simkl.UPCOMING_DIAGNOSTICS
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbEpisodeAirInfo
import com.kennyb1201.kbstream.data.tmdb.TmdbHeroArtworkRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tv.TvLauncherPublisher
import com.kennyb1201.kbstream.data.watched.ContinueWatchingRefreshBus
import com.kennyb1201.kbstream.data.watched.LocalSeriesProgress
import com.kennyb1201.kbstream.data.format.DateFormats
import com.kennyb1201.kbstream.data.watched.WatchStateBus
import com.kennyb1201.kbstream.data.watched.WatchedEpisodeState
import com.kennyb1201.kbstream.ui.components.LandscapeArtRequest
import com.kennyb1201.kbstream.ui.components.landscapeArtFor
import com.kennyb1201.kbstream.ui.components.landscapeArtKey
import com.kennyb1201.kbstream.ui.components.landscapeArtUrls
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.tmdb.alternatePosterPath
import com.kennyb1201.kbstream.data.tmdb.tmdbImage
import com.kennyb1201.kbstream.data.tmdb.director
import com.kennyb1201.kbstream.data.tmdb.displayCountry
import com.kennyb1201.kbstream.data.tmdb.displayDescription
import com.kennyb1201.kbstream.data.tmdb.displayLanguage
import com.kennyb1201.kbstream.data.tmdb.displayRating
import com.kennyb1201.kbstream.data.tmdb.displayRuntime
import com.kennyb1201.kbstream.data.tmdb.displayRuntimeMinutes
import com.kennyb1201.kbstream.data.tmdb.episodeCountForSeason
import com.kennyb1201.kbstream.data.tmdb.releaseYear
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlin.math.round
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import com.kennyb1201.kbstream.data.namedEpisodeNumber
import com.kennyb1201.kbstream.data.runCatchingCancellable

@OptIn(FlowPreview::class)
class HomeViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository =
        AddonRepository.getInstance()

    // FIXED: was AddonManager(application) — bypassed the singleton so
    // Home held a stale copy of installed addons/catalogs whenever the
    // Addons screen changed something. Now shares the same instance.
    private val addonManager =
        AddonManager.getInstance(application)

    private val watchHistoryRepository =
        WatchHistoryRepository(application)

    // Resolved per access, not captured at construction: getInstanceScoped
    // binds the Room instance to the ACTIVE profile's database file. Holding
    // one DAO across a profile switch (or a first-profile creation, which
    // closes the scoped DB) left Home reading the previous profile's closed
    // history DB - continue watching went stale/local-only until Home was
    // fully rebuilt. Every read below re-resolves, so a switch is picked up
    // on the very next query/subscription.
    private val historyDao: WatchHistoryDao
        get() = WatchHistoryDatabase
            .getInstanceScoped(getApplication())
            .watchHistoryDao()

    private val simklRepository =
        SimklRepository.getInstance(application)

    private val tmdbRepository =
        TmdbRepository.getInstance(application)

    // Second-source air dates (see [AirDateCorrection]). Display-only, and
    // empty whenever that source has nothing - which leaves TMDB's own dates
    // in place.
    private val airDateRepository =
        TvmazeAirDateRepository.getInstance(application)

    private val tmdbHeroArtworkRepository =
        TmdbHeroArtworkRepository(application)

    private val watchedStatusRepository =
        WatchedStatusRepository(application)

    private val tmdbLookupSemaphore =
        Semaphore(
            TMDB_MAX_CONCURRENT_LOOKUPS
        )

    private val watchedStateMutex =
        Mutex()

    private val upNextRequestMutex =
        Mutex()

    private val railsRefreshMutex =
        Mutex()

    /**
     * True while a rail build holds [railsRefreshMutex].
     *
     * [onHomeResumed] rebuilds whenever Home looks empty, and on a cold start it
     * always does: init's own build has not published a rail yet when the screen
     * first composes, so the resume guard started a SECOND full build. That one
     * queued behind the first and then refetched, refiltered and republished
     * every catalog, competing for the same TMDB lookups and the same add-on host
     * as the build it was waiting on - so it made the first build slower for no
     * gain. An empty rail list with a build already running is not an empty Home;
     * the publish this call is waiting for belongs to that build.
     *
     * Same class of duplicate the add-on observer already skips its first
     * emission to avoid (see observeAddonChanges).
     */
    @Volatile
    private var railBuildInFlight = false

    /** Set when a background caller wants a rebuild while one is already running. */
    @Volatile
    private var railRebuildQueued = false

    /** Whether the rebuild those callers asked for has to clear the catalog cache. */
    @Volatile
    private var railRebuildClearCache = false

    /**
     * Bumped on every profile switch. A rail build (or a pagination page)
     * captures it at the start and refuses to publish once it changed, so a
     * load started for the profile the user just left cannot repaint that
     * profile's rows over the new profile's Home.
     */
    @Volatile
    private var railBuildEpoch = 0L

    private val catalogRequestSemaphore =
        Semaphore(
            MAX_CONCURRENT_CATALOG_REQUESTS
        )

    // Caps parallel TMDB artwork lookups for landscape cards.
    private val landscapeArtSemaphore = Semaphore(permits = 6)

    /**
     * The landscape art the rails on screen already have, keyed by rail
     * identity.
     *
     * Artwork is a per-item fact, but it lived only on the [Rail] object, and
     * every rebuild replaces those objects wholesale: so a refresh (or an add-on
     * edit, or a resume) threw away every resolved backdrop and asked TMDB for
     * all of them again, one lookup per item, for rows whose art was already on
     * screen a moment earlier. Captured when a build starts and handed to the
     * rails that take those same keys, a rebuild paints with the art it already
     * had and resolves only what it has never seen.
     *
     * Deliberately not a cache with a lifetime of its own: it is a snapshot of
     * what is on screen. A profile switch empties the rails before rebuilding,
     * so nothing is carried across profiles, and nothing here can outlive the
     * rows it was taken from.
     */
    private var previousLandscapeArt:
        Map<String, Map<String, Pair<String?, String?>>> =
        emptyMap()

    /**
     * The TMDB artwork answer for this build's lookups, per art key - including
     * the answers that were "there is no artwork".
     *
     * The repository will not remember a miss (see
     * [TmdbRepository.fetchEnrichedMetaCached]: a pinned null there would lock
     * the Detail screen out of ever retrying for the rest of the session), so a
     * title TMDB has nothing for was re-asked over the network once per rail it
     * appears in - and a title that is trending, in a top-ten row and in its own
     * catalog rail appears in three. This remembers the answer for the length of
     * one build instead: long enough to make the repeats free, short enough that
     * the next build asks again.
     *
     * Keyed by art key and holding the TMDB art rather than the [TmdbDetail]
     * behind it, because the merge that follows differs per rail (a pinned rail
     * takes TMDB art or nothing) and a detail is large.
     */
    private val landscapeLookupMemo =
        java.util.concurrent.ConcurrentHashMap<String, Pair<String?, String?>>()

    /**
     * [landscapeLookupMemo] is cleared between builds but has to be bounded
     * within one: a profile with very large catalogs can page a lot of items
     * through here, and this box's heap is 192MB. Dropping it costs at most one
     * repeated lookup - the cost it had before the memo existed.
     */
    private fun rememberLandscapeLookup(
        key: String,
        art: Pair<String?, String?>
    ) {
        if (landscapeLookupMemo.size >= LANDSCAPE_MEMO_MAX_KEYS) {
            landscapeLookupMemo.clear()
        }
        landscapeLookupMemo[key] = art
    }

    private val watchedRefreshMutex =
        Mutex()

    // Watched episodes per show from the TRACKERS - Simkl and MDBList merged,
    // not Simkl alone. It used to be Simkl's set, which meant a viewer who ran
    // MDBList but no Simkl had every tracker-side signal read as "nothing
    // watched": their shows ignored the tracker in Continue Watching progress,
    // the episode counts and the caught-up rules, even though the very same
    // episodes were ticked on Detail (which already merges both). Folding both
    // sources into one map here is what gives the two trackers parity across
    // the whole Home surface rather than only the MDBList rail card.
    private val trackerWatchedEpisodesByShow =
        mutableMapOf<String, Set<Pair<Int, Int>>>()

    private val watchedEpisodeKeysByShow =
        mutableMapOf<String, Set<String>>()

    private val watchedStatePreloadInFlight =
        mutableSetOf<String>()

    private var upNextRequestVersion = 0L

    private var watchedRefreshVersion = 0L

    private var periodicRefreshJob: Job? = null

    // The bounded re-merge window a completion starts. Held so a second
    // completion supersedes the first window instead of stacking a second
    // one; cancelled with the ViewModel like every other Home job.
    private var completionRefreshRetryJob: Job? = null

    // Title-level removals from the Continue Watching rail: dedupe key ->
    // wall-clock ms of the dismissal. The local delete plus the Simkl calls
    // in removeFromContinueWatching normally remove the title everywhere,
    // but a paused session or a "watching" status can survive on Simkl's
    // side and resurrect the card from the remote feed. This persistent
    // layer guarantees the card stays hidden until there is NEWER watch
    // activity for the same title (a fresh local resume row or a new Simkl
    // pause), which then un-hides it automatically.
    private val dismissalPrefs: SharedPreferences
        get() = getApplication<Application>()
            .getSharedPreferences(
                com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(
                    getApplication(), PREFS_DISMISSED_UPNEXT
                ),
                Context.MODE_PRIVATE
            )

    private val dismissedContinueWatching: MutableMap<String, Long> =
        loadDismissedContinueWatching()

    private val _rails =
        MutableStateFlow<List<Rail>>(
            emptyList()
        )

    val rails: StateFlow<List<Rail>> =
        _rails.asStateFlow()

    /**
     * Profile id the current [rails] content was built for. Home renders the
     * list only while this matches the active profile: clearing the rails on
     * a switch is dispatched, so without this gate the previous profile's
     * rows could paint for a frame (or until the clear landed) after the
     * user picked a different profile. Null = no profile yet (legacy scope).
     */
    private val _railsProfileId =
        MutableStateFlow<String?>(
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id
        )

    val railsProfileId: StateFlow<String?> =
        _railsProfileId.asStateFlow()

    private val _watchedKeys =
        MutableStateFlow<Set<String>>(
            emptySet()
        )

    val watchedKeys: StateFlow<Set<String>> =
        _watchedKeys.asStateFlow()

    /*
     * Keys of shows started-but-not-finished (the eye badge). Filled by the
     * same refresh that fills watchedKeys; the completed checkmark wins
     * when a key is in both sets.
     */
    private val _partialWatchedKeys =
        MutableStateFlow<Set<String>>(
            emptySet()
        )

    val partialWatchedKeys: StateFlow<Set<String>> =
        _partialWatchedKeys.asStateFlow()

    private val _upNext =
        MutableStateFlow<List<UpNextItem>>(
            emptyList()
        )

    val upNext: StateFlow<List<UpNextItem>> =
        _upNext.asStateFlow()

    /**
     * Upcoming episodes: one entry per CAUGHT-UP show whose TMDB detail carries
     * a future "next episode to air", plus the next UNAIRED episode of every
     * show this profile is caught up on but which is not on Continue Watching.
     *
     * A caught-up show has nothing to resume, so it never reaches Continue
     * Watching - and this is the only rail that can surface what it has coming,
     * whether that is a new season or the next episode of one already airing.
     * The caught-up cards come from all three sources the account can have:
     * Simkl's followed library ([loadCaughtUpUpcomingItems]), this profile's own
     * watch history, and MDBList's watched snapshot
     * ([loadLocalCaughtUpUpcomingItems]), so the rail is populated for a
     * local-only or MDBList-only viewer too. Sorted by air date.
     *
     * Re-published whenever [upNext] changes, which is also when the watch
     * state behind those sources is freshest.
     */
    val upcomingSchedule: StateFlow<List<UpcomingEpisode>> =
        _upNext
            .asStateFlow()
            .map { items ->
                // Only a show the viewer is caught up on advertises its next
                // unaired episode; a show with aired episodes still waiting is
                // announcing something the viewer cannot use yet (see
                // isCaughtUpForUpcoming). Continue Watching keeps the show
                // either way - the NEW SEASON / NEW EPISODE badge there is the
                // alert that there is something to catch up on.
                val caughtUp = items.filter(::isCaughtUpForUpcoming)

                if (UPCOMING_DIAGNOSTICS && caughtUp.size != items.size) {
                    Log.d(
                        "UPCOMING_DIAG",
                        "kept off Upcoming (not caught up): " +
                            items.filterNot(::isCaughtUpForUpcoming)
                                .joinToString { "'${it.title}'" }
                    )
                }

                buildUpcomingSchedule(
                    caughtUp +
                        loadCaughtUpUpcomingItems() +
                        loadLocalCaughtUpUpcomingItems()
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )

    private val _isLoading =
        MutableStateFlow(true)

    val isLoading: StateFlow<Boolean> =
        _isLoading.asStateFlow()

    private val _error =
        MutableStateFlow<String?>(null)

    val error: StateFlow<String?> =
        _error.asStateFlow()

    private val _heroMeta =
        MutableStateFlow<Meta?>(null)

    val heroMeta: StateFlow<Meta?> =
        _heroMeta.asStateFlow()

    private val _heroBackdropUrl =
        MutableStateFlow<String?>(null)

    val heroBackdropUrl: StateFlow<String?> =
        _heroBackdropUrl.asStateFlow()

    private val _heroLogoUrl =
        MutableStateFlow<String?>(null)

    val heroLogoUrl: StateFlow<String?> =
        _heroLogoUrl.asStateFlow()

    private val _heroTrailerKey =
        MutableStateFlow<String?>(null)

    val heroTrailerKey: StateFlow<String?> =
        _heroTrailerKey.asStateFlow()

    // Exposes the resolved TMDB detail for the current hero item so the
    // UI can source year/certification from TMDB first (mirroring
    // DetailScreen's tmdbDetail?.releaseYear / tmdbDetail?.certification
    // pattern) and only fall back to the addon Meta's raw fields when
    // TMDB has nothing -- instead of reading year/rating off heroMeta
    // alone, which silently disappears whenever the addon's own Meta
    // resource doesn't supply them.
    private val _heroTmdbDetail =
        MutableStateFlow<TmdbDetail?>(null)

    val heroTmdbDetail: StateFlow<TmdbDetail?> =
        _heroTmdbDetail.asStateFlow()

    private var heroResolveJob: Job? = null

    /**
     * Like runCatching, but for suspend calls: runCatching swallows
     * CancellationException along with real failures, which lets a
     * canceled coroutine keep running instead of stopping -- it then
     * surfaces later as a fake "failure" further down. This rethrows
     * cancellation and only treats genuine exceptions as null.
     */
    private suspend inline fun <T> safeSuspend(block: () -> T): T? =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    fun resolveHeroMeta(
        item: MetaPreview?,
        baseUrl: String? = null
    ) {
        heroResolveJob?.cancel()

        if (item == null) {
            _heroMeta.value = null
            _heroBackdropUrl.value = null
            _heroLogoUrl.value = null
            _heroTrailerKey.value = null
            _heroTmdbDetail.value = null
            return
        }

        val requestedId = item.id
        val requestedType = item.type

        // Keep the PREVIOUS item's resolved art (backdrop + clearlogo)
        // visible while the new item resolves. Nulling these here made the
        // hero fall back to the raw addon backdrop/poster (a zoomed-in
        // mess) plus a plain-text title for the 250ms dwell + network time
        // on EVERY focus change — the "ugly backdrop flashes first"
        // report. Only ART is held over: meta/detail (name, year, rating,
        // synopsis) are cleared so the new item's title shows immediately
        // and no wrong year/description ever appears under it. The
        // resolution below publishes the new full set together.
        // (The very first hero after Home opens has no previous art and
        // resolves from cold — the rail-level prefetch warms that.)
        _heroMeta.value = _heroMeta.value?.takeIf {
            it.id == requestedId &&
                it.type.equals(requestedType, ignoreCase = true)
        }
        _heroTmdbDetail.value = null
        _heroTrailerKey.value = null

        heroResolveJob = viewModelScope.launch {
            try {
                // Dwell before any network work: scrolling a rail with the
                // D-pad fires one focus event per card. Without this pause
                // every transitively-focused title launched a full meta +
                // detail + artwork chain before being canceled, wasting
                // requests and starving the ones that mattered. Focus that
                // survives 250ms is a deliberate stop — resolve it fully.
                delay(HERO_RESOLVE_DWELL_MS)

                coroutineScope {
                    val addonMetaDeferred = async {
    val resolvedBaseUrl =
        baseUrl?.takeIf { it.isNotBlank() }
            ?: findBaseUrlForMeta(item)

    if (resolvedBaseUrl.isNullOrBlank()) {
        probeInstalledAddonsForMeta(requestedId, requestedType)
    } else {
        safeSuspend {
            repository.getMeta(
                baseUrl = resolvedBaseUrl,
                type = requestedType,
                id = requestedId
            )
        } ?: probeInstalledAddonsForMeta(requestedId, requestedType)
    }
}

                    val tmdbDetailDeferred = async {
                        safeSuspend {
                            when {
                                requestedId.trim().startsWith("tmdb:", ignoreCase = true) -> {
                                    requestedId.trim()
                                        .substringAfter(":")
                                        .toIntOrNull()
                                        ?.let {
                                            tmdbRepository.getDetailByTmdbId(
                                                it,
                                                requestedType
                                            )
                                        }
                                }

                                requestedId.trim().startsWith("tt", ignoreCase = true) -> {
                                    // full = true: the hero strip below draws
                                    // the cast line (tmdb.credits) and the
                                    // trailer (videos) off this exact object.
                                    tmdbRepository.fetchEnrichedMetaCached(
                                        requestedId.trim(),
                                        requestedType,
                                        full = true
                                    )
                                }

                                requestedId.trim().toIntOrNull() != null -> {
                                    // Bare numeric id, no "tmdb:"/"tt" prefix — some
                                    // catalog-only addons (e.g. Top Today) emit these.
                                    requestedId.trim().toIntOrNull()?.let {
                                        tmdbRepository.getDetailByTmdbId(
                                            it,
                                            requestedType
                                        )
                                    }
                                }

                                else -> null
                            }
                        }
                    }

                    // Resolve the TMDB id up front when the request id already
                    // carries one, so the hero-artwork call runs in PARALLEL with
                    // the meta/detail lookups instead of waiting for them. That
                    // makes the clearlogo land before the user scrolls away on a
                    // cold start (tt ids still fall back to the detail's id).
                    val quickTmdbId = when {
                        requestedId.trim().startsWith("tmdb:", ignoreCase = true) ->
                            requestedId.trim().substringAfter(":").toIntOrNull()

                        requestedId.trim().toIntOrNull() != null ->
                            requestedId.trim().toIntOrNull()

                        else -> null
                    }

                    val artworkDeferred = async {
                        val artworkTmdbId = quickTmdbId
                            ?: tmdbDetailDeferred.await()?.id

                        if (artworkTmdbId == null || artworkTmdbId <= 0) {
                            return@async null
                        }

                        try {
                            tmdbHeroArtworkRepository.resolve(
                                id = "tmdb:$artworkTmdbId",
                                type = requestedType,
                                tmdbId = artworkTmdbId
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(
                                "HOME_HERO",
                                "Artwork lookup failed: title=${item.name}, tmdbId=$artworkTmdbId",
                                e
                            )
                            null
                        }
                    }

                    // Early publish: backdrop + clearlogo go up the moment
                    // the artwork fetch answers, NOT when the whole meta
                    // chain finishes — the addon probe / detail lookup are
                    // the slow legs, and holding the art hostage behind
                    // them is what made the hero show a plain title for a
                    // beat before the backdrop/logo appeared. Metadata
                    // (year, synopsis, cast) still lands with finalMeta.
                    launch {
                        val earlyArt = artworkDeferred.await()
                        earlyArt?.backdropUrl
                            ?.takeIf { it.isNotBlank() }
                            ?.let { _heroBackdropUrl.value = it }
                        earlyArt?.logoUrl
                            ?.takeIf { it.isNotBlank() }
                            ?.let { _heroLogoUrl.value = it }
                    }

                    val resolvedAddonMeta = addonMetaDeferred.await()
                    val resolvedTmdbDetail = tmdbDetailDeferred.await()

                    val resolvedTmdbId =
                        when {
                            requestedId.trim().startsWith("tmdb:", ignoreCase = true) -> {
                                requestedId.trim()
                                    .substringAfter(":")
                                    .toIntOrNull()
                            }

                            requestedId.trim().toIntOrNull() != null -> {
                                // Bare numeric id (e.g. Top Today) -- use it
                                // directly as the tmdb id instead of relying
                                // on resolvedTmdbDetail?.id, which wasn't
                                // reliably echoing it back and was silently
                                // skipping heroArtwork (and therefore the
                                // clearlogo) for every item on this path.
                                requestedId.trim().toIntOrNull()
                            }

                            else -> resolvedTmdbDetail?.id
                        }

                    val heroArtwork = artworkDeferred.await()

val resolvedLogo = heroArtwork?.logoUrl
    ?.takeIf { it.isNotBlank() }
    ?: resolvedAddonMeta?.logo?.takeIf { it.isNotBlank() }
    ?: item.logo?.takeIf { it.isNotBlank() }

Log.d(
    "HOME_HERO",
    "Hero artwork: title=${item.name}, rawId=$requestedId, " +
        "tmdbId=$resolvedTmdbId, logo=${resolvedLogo != null}, " +
        "artworkLogo=${heroArtwork?.logoUrl}"
)

                    val resolvedBackdrop =
                        heroArtwork?.backdropUrl?.takeIf { it.isNotBlank() }
                            ?: resolvedTmdbDetail?.backdropPath
                                ?.takeIf { it.isNotBlank() }
                                ?.let { TmdbRepository.BACKDROP_BASE + it }
                            ?: resolvedAddonMeta?.background?.takeIf { it.isNotBlank() }
                            ?: item.background?.takeIf { it.isNotBlank() }
                            // Poster fallback for backdrop-less titles:
                            // prefer an ALTERNATE TMDB poster so the hero
                            // doesn't display the exact image the focused
                            // rail card shows.
                            ?: resolvedTmdbDetail?.alternatePosterPath()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { tmdbImage(it, "w780") }
                            ?: resolvedAddonMeta?.poster?.takeIf { it.isNotBlank() }
                            ?: item.poster?.takeIf { it.isNotBlank() }

                    val finalMeta = resolvedAddonMeta?.copy(
    logo = resolvedLogo ?: resolvedAddonMeta.logo,
    background = resolvedBackdrop ?: resolvedAddonMeta.background,
    description =
        resolvedAddonMeta.description
    ?.trim()
    ?.takeIf { it.isNotBlank() }
            ?: resolvedTmdbDetail
                ?.displayDescription()
            ?: item.description,

    // Year and score, from TMDB when the add-on sent neither. An add-on is
    // authoritative about its own catalog entry, but plenty of them ship a
    // preview with a name and artwork and nothing else - the pinned "Top
    // Today" rails are the extreme case, and a catalog-only add-on resolves
    // no meta at all (which is why the `?:` below already fills both from
    // TMDB). Filling them here too means every reader of this Meta gets the
    // same answer instead of each one re-deriving a fallback, and it costs
    // nothing: resolvedTmdbDetail is resolved for this hero either way.
    releaseInfo =
        resolvedAddonMeta.releaseInfo
    ?.trim()
    ?.takeIf { it.isNotBlank() }
            ?: resolvedTmdbDetail?.releaseYear(),
    imdbRating =
        resolvedAddonMeta.imdbRating
    ?.trim()
    ?.takeIf { it.isNotBlank() }
            ?: resolvedTmdbDetail?.displayRating()
) ?: resolvedTmdbDetail?.let { tmdb ->
    Meta(
        id = requestedId,
        type = requestedType,

        name = tmdb.name
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: tmdb.title
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: item.name,

        poster = tmdb.posterPath
            ?.takeIf { it.isNotBlank() }
            ?.let(TmdbRepository.POSTER_BASE::plus)
            ?: item.poster,

        background = resolvedBackdrop,
        logo = resolvedLogo,

        description = tmdb.displayDescription(),
        releaseInfo = tmdb.releaseYear(),
        imdbRating = tmdb.displayRating(),
        runtime = tmdb.displayRuntime(),
        language = tmdb.displayLanguage(),
        country = tmdb.displayCountry(),

        genres = tmdb.genres
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() },

        cast = tmdb.credits?.cast
            ?.map { it.name.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.take(12)
            ?.takeIf { it.isNotEmpty() },

        director = tmdb.credits?.director()
            ?.name
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::listOf)
    )
} ?: Meta(
    id = requestedId,
    type = requestedType,
    name = item.name,
    poster = item.poster,
    background = resolvedBackdrop ?: item.background,
    logo = resolvedLogo,
    description = item.description
)

                    _heroMeta.value = finalMeta
                    // NOTE: finalMeta is null whenever resolvedAddonMeta is null (addon has
                    // no "meta" resource — true for catalog-only addons like Top Today).
                    // resolvedLogo/resolvedBackdrop are resolved independently above (TMDB
                    // + heroArtwork), so publish them on their own state regardless of
                    // whether finalMeta exists, instead of losing them via the ?.copy() above.
                    _heroBackdropUrl.value = resolvedBackdrop
                    _heroLogoUrl.value = resolvedLogo
                    _heroTmdbDetail.value = resolvedTmdbDetail
                    _heroTrailerKey.value = resolvedTmdbDetail?.videos?.results
                        ?.asSequence()
                        ?.filter { video ->
                            video.site.equals("YouTube", ignoreCase = true) &&
                                video.type.equals("Trailer", ignoreCase = true) &&
                                video.key.isNotBlank()
                        }
                        ?.firstOrNull()
                        ?.key

                    Log.d(
                        "HOME_HERO",
                        "Hero resolved: title=${item.name}, id=$requestedId, type=$requestedType, tmdbId=$resolvedTmdbId, logo=${resolvedLogo != null}, backdrop=${resolvedBackdrop != null}, trailer=${_heroTrailerKey.value != null}"
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Expected when the user moves focus before this item's
                // enrichment finishes (resolveHeroMeta cancels the previous
                // heroResolveJob). Not a real failure -- rethrow so the
                // coroutine machinery can clean up normally, and don't touch
                // hero state here; the newly-focused item's own job will set
                // it. Logging/nulling this out was causing the hero to flash
                // blank (including the clearlogo) on every fast rail scroll.
                throw e
            } catch (e: Exception) {
                Log.w(
                    "HOME_HERO",
                    "Hero enrichment failed for ${item.name}: ${e.message}",
                    e
                )
                _heroMeta.value = null
                _heroBackdropUrl.value = null
                _heroLogoUrl.value = null
                _heroTrailerKey.value = null
                _heroTmdbDetail.value = null
            }
        }
    }

    // Prefetches hero art (TMDB detail + hero artwork: backdrop + clearlogo)
    // for rail items BEFORE the user focuses them, so the caches are hot and
    // focusing a card swaps the hero art in one frame instead of showing the
    // raw addon art + plain title for a second. The resolver flow is exactly
    // the hero's: fetchEnrichedMetaCached / getDetailByTmdbId by id shape,
    // then TmdbHeroArtworkRepository — both disk+memory cached, so a
    // prefetched item's focus resolution becomes a cache hit that returns
    // instantly. Throttled below the hero's own priority; failures are
    // silent (the focus path retries over the network as before).
    private val heroArtPrefetchInFlight =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    // Deliberately low (2): this runs while the user is already interacting
    // with Home and about to open a Detail page, so the prefetch must never
    // hold the shared TMDB/OkHttp capacity the foreground needs. Two at a time
    // still warms a rail's worth of cards well ahead of focus.
    private val heroArtPrefetchSemaphore = Semaphore(permits = 2)

    // Upper bound on cards warmed per rails build. The previous 120 covered
    // more rails than a viewer reaches before the rest has resolved anyway,
    // while roughly doubling the background TMDB traffic. The focus path still
    // resolves any card on demand; this only decides how many are pre-warmed.
    private val heroArtPrefetchLimit = 60

    fun prefetchHeroArt(items: List<MetaPreview>) {
        val toWarm = items
            .filter { it.id.isNotBlank() }
            .distinctBy { "${it.type}:${it.id}" }
            .filter { heroArtPrefetchInFlight.add("${it.type}:${it.id}") }
            .take(heroArtPrefetchLimit)
        if (toWarm.isEmpty()) return

        viewModelScope.launch {
            try {
                coroutineScope {
                    toWarm.map { item ->
                        async {
                            heroArtPrefetchSemaphore.withPermit {
                                try {
                                    val detail = when {
                                        item.id.startsWith("tmdb:", ignoreCase = true) ->
                                            item.id.substringAfter(":").toIntOrNull()
                                                ?.let { tmdbRepository.getDetailByTmdbId(it, item.type) }

                                        item.id.startsWith("tt", ignoreCase = true) ->
                                            tmdbRepository.fetchEnrichedMetaCached(item.id, item.type)

                                        item.id.toIntOrNull() != null ->
                                            item.id.toIntOrNull()
                                                ?.let { tmdbRepository.getDetailByTmdbId(it, item.type) }

                                        else -> null
                                    }

                                    val tmdbId = when {
                                        item.id.startsWith("tmdb:", ignoreCase = true) ->
                                            item.id.substringAfter(":").toIntOrNull()
                                        item.id.toIntOrNull() != null -> item.id.toIntOrNull()
                                        else -> detail?.id
                                    }

                                    if (tmdbId != null && tmdbId > 0) {
                                        tmdbHeroArtworkRepository.resolve(
                                            id = "tmdb:$tmdbId",
                                            type = item.type,
                                            tmdbId = tmdbId
                                        )
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    // Silent: prefetch is best-effort; the
                                    // focus path handles failures properly.
                                } finally {
                                    heroArtPrefetchInFlight.remove("${item.type}:${item.id}")
                                }
                            }
                        }
                    }.awaitAll()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Scope-level failure (viewmodel clearing): nothing to do.
            } finally {
                toWarm.forEach { heroArtPrefetchInFlight.remove("${it.type}:${it.id}") }
            }
        }
    }

    private fun findBaseUrlForMeta(item: MetaPreview): String {
        return _rails.value.firstOrNull { rail ->
            rail.type.equals(item.type, ignoreCase = true) &&
                rail.items.any { it.id == item.id }
        }?.baseUrl
            ?: _rails.value.firstOrNull { rail ->
                rail.items.any { it.id == item.id }
            }?.baseUrl
            ?: ""
    }

    private val _refreshTrigger =
        MutableStateFlow(0)


    /**
     * Profile-switch cleanup for the ViewModel's profile-bound in-memory
     * state. The singleton layers (watch history DB handle, watched-status
     * caches, addon list, Simkl cache) are reset by ProfileManager itself;
     * this clears what lives HERE: dismissal map, watched-key set, hero
     * state, up-next list, and the rails (rebuilt from the incoming
     * profile's own addon/catalog configuration).
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeProfileSwitches() {
        viewModelScope.launch {
            var first = true
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile
                .collect { profile ->
                    if (first) {
                        // Skip the initial emission: init already loads
                        // against whatever profile was active at creation.
                        first = false
                        return@collect
                    }
                    // Note: profile may be null transiently during a switch
                    // (all profiles deleted); still refresh - the stores then
                    // resolve to the legacy namespace, which is the correct
                    // post-switch target.
                    Log.d("HOME_VM", "profile switched -> ${profile?.id ?: "legacy"}")

                    // Bump the rail-build epoch FIRST: any rail build still
                    // streaming from the profile we just left is now stale
                    // and must not republish its rows (loadRailsInternal).
                    railBuildEpoch += 1

                    // A previous profile's error message must not sit under
                    // the new profile's rails while its build is in flight.
                    _error.value = null

                    dismissedContinueWatching.clear()
                    dismissedContinueWatching.putAll(loadDismissedContinueWatching())

                    _watchedKeys.value = emptySet()
                    _partialWatchedKeys.value = emptySet()

                    // Empty the rail UP FRONT: without this, the previous
                    // profile's cards stayed on screen until the new
                    // profile's enriched pass finished (collectLatest cancels
                    // the stale pass, but cancellation alone doesn't repaint
                    // an already-published list). The snapshot seed below
                    // refills it from the NEW profile's raw rows instantly;
                    // the enriched pipeline replaces it when it lands.
                    _upNext.value = emptyList()
                    publishInstantUpNextSnapshot()

                    // Same for the caught-up Upcoming cards: they come from
                    // the account-wide Simkl feed and are only kids-filtered
                    // for the profile that built them, so nothing built for
                    // the profile we just left may survive the switch.
                    lastCaughtUpUpcomingItems = emptyList()
                    caughtUpUpcomingProfileId = null
                    lastLocalCaughtUpUpcomingItems = emptyList()
                    localCaughtUpUpcomingProfileId = null
                    caughtUpUpcomingLoadedAt = 0L

                    // Same for the addon rails: catalog rails for a NON-kids
                    // profile (adult content) visibly lingered for seconds
                    // after switching to a kids profile, until the kids
                    // profile's own catalogs finished fetching. Drop them
                    // now so Home shows the loading state instead of the
                    // previous profile's content.
                    _rails.value = emptyList()
                    railInfo.clear()
                    loadingRails.clear()
                    exhaustedRails.clear()
                    railSourceOffset.clear()

                    _heroMeta.value = null
                    _heroTmdbDetail.value = null
                    _heroBackdropUrl.value = null
                    _heroLogoUrl.value = null
                    _heroTrailerKey.value = null
                    heroResolveJob?.cancel()

                    refreshAllHomeData()
                    refreshUpNext()
                }
        }
    }

    private fun observeAddonChanges() {
        viewModelScope.launch {
            // Skip the initial emission (init's loadRails() already covers
            // it — without this, Home loaded every catalog TWICE on cold
            // start) and debounce bursts so rapid addon edits (reorder,
            // toggle several catalogs) coalesce into one rebuild instead of
            // serially refetching the whole home per change.
            var first = true
            addonManager.installedAddons
                .debounce(300)
                .collectLatest {
                    if (first) {
                        first = false
                        return@collectLatest
                    }
                    // Rebuild rails immediately when addon/catalog settings
                    // change (reorder, show/hide, add/remove) without
                    // clearing the catalog cache, so the new order/visibility
                    // shows up right away from the in-memory cache instead of
                    // a slow full network refetch. The manual REFRESH paths
                    // still pass clearCatalogCache = true.
                    loadRailsInternal(
                        forceRefresh = true,
                        clearCatalogCache = false,
                        coalesce = true
                    )
                }
        }
    }

    fun refreshUpNext() {

        viewModelScope.launch {

            clearWatchedStateCaches()

            _refreshTrigger.value += 1
        }
    }

    /**
     * Long-press "Remove" on a Continue Watching card: deletes every
     * in-progress resume row for the parent so the show/movie leaves the
     * rail. Completed-episode history is kept so watched badges survive.
     */
    fun removeFromContinueWatching(item: UpNextItem) {

        val parentId = item.parentId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: item.historyRowId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return

        viewModelScope.launch {

            try {

                // Local guarantee: remember the dismissal up front so the
                // card cannot resurface from a stale Simkl feed snapshot
                // while the remote delete calls settle (or if one of them
                // fails). Watching the title again later clears this.
                dismissedContinueWatching[
                    showDedupeKey(item)
                ] = System.currentTimeMillis()

                persistDismissedContinueWatching()

                val removedResumeIds = watchHistoryRepository.deleteResumeRowsForParent(parentId)

                // Fallback path removed a single row by its raw id; also
                // drop that exact row so the item always leaves the rail.
                item.historyRowId?.let { rowId ->
                    watchHistoryRepository.deleteById(rowId)
                }

                // The cloud copy has to go with the local one, or the row is
                // back on the next sync: the pull re-inserts any remote row
                // that is newer than a local row which no longer exists, so
                // "Remove" looked like it had done nothing. That is also how
                // a row another profile's session filed here (see
                // PlaybackHistoryWriter) kept reappearing.
                SupabaseSync.deleteHistoryRows(
                    removedResumeIds + listOfNotNull(item.historyRowId)
                )

                // Simkl-backed cards come back from the remote Continue
                // Watching feed on every load (and after a restart) unless
                // the title is also removed from Simkl history. Mirror the
                // local delete with POST /sync/history/remove; the
                // repository clears its Continue Watching snapshot so the
                // refresh below sees the updated remote state.
                if (
                    simklRepository.isConfigured() &&
                    simklRepository.hasToken()
                ) {
                    val removedFromSimkl =
                        when (item.parentType?.lowercase()) {
                            "movie" -> parentId.let {
                                simklRepository.removeWatchedMovie(
                                    imdbId = it,
                                    title = item.title
                                )
                            }
                            "series", "tv" -> parentId.let {
                                simklRepository.removeWatchedShow(
                                    showImdbId = it,
                                    title = item.title
                                )
                            }
                            else -> false
                        }

                    Log.i(
                        "HOME_UPNEXT",
                        "Simkl continue watching removal " +
                            "title=${item.title} " +
                            "result=$removedFromSimkl"
                    )

                }

                clearTrackerPlaybackSessions(item, parentId)

                // Keep the TV launcher Continue Watching rail in sync with
                // the in-app removal (full reconcile is cheap and self-healing).
                TvLauncherPublisher.sync(
                    getApplication(),
                    watchHistoryRepository.getAll()
                )

                _refreshTrigger.value += 1

                Log.i(
                    "HOME_UPNEXT",
                    "Removed continue watching parent=$parentId"
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancellation is not a failure: rethrow so a superseded
                // build is not logged as a failed removal.
                throw e
            } catch (e: Exception) {

                Log.e(
                    "HOME_UPNEXT",
                    "Failed to remove continue watching parent=$parentId",
                    e
                )
            }
        }
    }

    /**
     * Long-press "Mark as Watched" on a Continue Watching card.
     *
     * A movie is marked watched through the shared whole-title path; a card
     * that names an episode marks exactly that episode, so the rail advances
     * to the next one instead of the whole show disappearing. Either way the
     * card has to leave the rail for good, so the mark is followed by the
     * same tracker-session cleanup and dismissal a Remove uses - otherwise
     * the paused remote session keeps feeding the card back on the next
     * refresh even though the local history was cleaned up.
     */
    fun markContinueWatchingAsWatched(item: UpNextItem) {

        val target =
            upNextWatchedTarget(item)
                ?: return

        viewModelScope.launch {

            try {

                // Local guarantee: a mark made while a stale feed snapshot is
                // in flight must not be re-added by it. Watching the title
                // again later clears this.
                dismissedContinueWatching[
                    showDedupeKey(item)
                ] = System.currentTimeMillis()

                persistDismissedContinueWatching()

                when (target) {
                    is UpNextWatchedTarget.WholeTitle ->
                        watchedStatusRepository.markWatchedLocal(
                            target.parentId,
                            target.type
                        )

                    is UpNextWatchedTarget.Episode ->
                        watchedStatusRepository.markEpisodeWatchedLocal(
                            id = target.parentId,
                            type = "series",
                            season = target.season,
                            episode = target.episode,
                            episodeStreamId = target.episodeStreamId,
                            showName = target.title,
                            posterUrl = target.poster,
                            tmdbId = target.tmdbId
                        )
                }

                // Scoped to the marked episode when the card named one, so a
                // paused session on ANOTHER episode of the same show stays
                // on the rail.
                clearTrackerPlaybackSessions(
                    item = item,
                    parentId = target.parentId,
                    seasonsEpisodes =
                        (target as? UpNextWatchedTarget.Episode)
                            ?.let { setOf(it.season to it.episode) }
                )

                // Keep the TV launcher rail in sync with the in-app change
                // (full reconcile is cheap and self-healing).
                TvLauncherPublisher.sync(
                    getApplication(),
                    watchHistoryRepository.getAll()
                )

                _refreshTrigger.value += 1

                Log.i(
                    "HOME_UPNEXT",
                    "Marked continue watching watched parent=${target.parentId}"
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(
                    "HOME_UPNEXT",
                    "Failed to mark continue watching watched ${target.parentId}",
                    e
                )
            }
        }
    }

    /**
     * Mirrors a Continue Watching removal/mark to the trackers' open playback
     * sessions. Playback-sourced cards (paused mid-title) live in a separate
     * Simkl playback table and an MDBList paused session, so cleaning up
     * local history alone leaves the card resurfacing from the remote feed. A
     * paused title is usually backed by BOTH a local resume row and a remote
     * session, and the rail dedupe can surface the local twin (which carries
     * no playbackId), so this sweeps every open session for the parent
     * instead of only the one the card happened to carry. When
     * [seasonsEpisodes] is given, only those episodes are swept.
     */
    private suspend fun clearTrackerPlaybackSessions(
        item: UpNextItem,
        parentId: String,
        seasonsEpisodes: Set<Pair<Int, Int>>? = null
    ) {
        if (
            simklRepository.isConfigured() &&
            simklRepository.hasToken()
        ) {
            simklRepository.deletePlaybackSessionsForParent(
                parentId = parentId,
                title = item.title,
                seasonsEpisodes = seasonsEpisodes
            )
            item.playbackId?.let { playbackId ->
                simklRepository.deletePlaybackSession(
                    playbackId
                )
            }
        }

        if (MdbListClient.isConfigured(getApplication())) {
            runCatchingCancellable {
                MdbListClient.scrobbleClear(
                    getApplication(),
                    isMovie = item.parentType?.lowercase() == "movie",
                    imdbId = item.parentId
                        ?.takeIf { it.startsWith("tt") },
                    tmdbId = item.tmdbId,
                    season = item.season,
                    episode = item.episode
                )
            }.onFailure {
                Log.w(
                    "HOME_UPNEXT",
                    "MDBList scrobble/clear failed: ${it.message}"
                )
            }
        }
    }

    private fun loadDismissedContinueWatching(): MutableMap<String, Long> {

        val raw =
            dismissalPrefs.getString(
                PREFS_DISMISSED_UPNEXT,
                null
            )
                ?: return mutableMapOf()

        return runCatching {

            val json =
                JSONObject(raw)

            val keys =
                json.keys()

            buildMap {
                while (keys.hasNext()) {
                    val key =
                        keys.next()

                    put(
                        key,
                        json.optLong(key)
                    )
                }
            }
                .toMutableMap()
        }
            .getOrDefault(
                mutableMapOf()
            )
    }

    private fun persistDismissedContinueWatching() {

        runCatching {

            val json =
                JSONObject()

            dismissedContinueWatching.forEach { (key, dismissedAt) ->
                json.put(key, dismissedAt)
            }

            dismissalPrefs
                .edit()
                .putString(
                    PREFS_DISMISSED_UPNEXT,
                    json.toString()
                )
                .putLong(
                    DISMISSALS_SYNCED_AT,
                    System.currentTimeMillis()
                )
                .apply()

            // Cross-device sync: dismissals follow the user between TVs.
            // Timestamped like the library blob so an offline dismissal on
            // one device isn't clobbered by an older cloud row.
            val appCtx = getApplication<Application>()
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appCtx,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_DISMISSALS,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildDismissals(appCtx)
            )
        }
    }

    /**
     * Filters the merged Up Next list against locally dismissed titles (see
     * [removeFromContinueWatching]). A dismissal stays in effect until there
     * is watch activity for the title NEWER than the dismissal time - a
     * fresh local resume row or a new Simkl pause/session - at which point
     * the marker is cleared and the card is allowed back onto the rail.
     */
    private suspend fun applyContinueWatchingDismissals(
        items: List<UpNextItem>
    ): List<UpNextItem> {

        // Kids Mode gate runs before dismissal filtering: the Simkl feed
        // merged into this list is account-wide, so a kids profile must not
        // inherit adult titles from a shared Simkl account (local history is
        // already profile-scoped; Simkl is not).
        val kidsSafe = kidsFilterUpNext(items)

        // Profile gate, the same account-wide-feed problem one step further:
        // a title THIS device has watched under one profile must not come back
        // on another profile through the tracker feed (see
        // [applyTrackerTitleOwnership]).
        val profileSafe = applyTrackerTitleOwnership(kidsSafe)

        if (
            dismissedContinueWatching.isEmpty() ||
            profileSafe.isEmpty()
        ) {
            return profileSafe
        }

        var changed =
            false

        val filtered =
            profileSafe.filter { item ->

                val key =
                    showDedupeKey(item)

                val dismissedAt =
                    dismissedContinueWatching[key]
                        ?: return@filter true

                val reAdded =
                    item.recencyTimestamp >
                        dismissedAt

                if (reAdded) {

                    dismissedContinueWatching.remove(key)

                    changed = true
                }

                reAdded
            }

        if (changed) {

            persistDismissedContinueWatching()
        }

        return filtered
    }

    /**
     * Keeps the tracker feeds out of profiles they do not belong to.
     *
     * Reported: "there's a kids show in my continue watching on profile 1
     * that's supposed to be in profile 3". Profile 1 had no history for it -
     * local history is per profile - but the card came from Simkl, whose
     * library is one per ACCOUNT, so a show watched on any profile came back
     * on every one of them.
     *
     * The device knows the missing half: a local card only exists for a title
     * the ACTIVE profile watched, so every local card in this list is recorded
     * as that profile's (TitleProfileOwnership), and a tracker card whose
     * title is owned by a different profile is dropped. Nothing else is: a
     * title with no owner was watched on another device (or before this TV had
     * profiles) and hiding it would break the cross-device Continue Watching
     * the tracker feeds exist for, and a local card is never in question.
     */
    private fun applyTrackerTitleOwnership(
        items: List<UpNextItem>
    ): List<UpNextItem> {

        if (items.isEmpty()) return items

        val activeProfileId =
            com.kennyb1201.kbstream.data.sync.ProfileManager
                .activeProfile.value?.id
                ?.takeIf { it.isNotBlank() }

        // Every local card claims its title for the active profile, on every
        // pass: this is the only place that knows both the profile and the
        // titles its history holds, and a later watch on another profile has
        // to be able to take the title over.
        items
            .filterNot { item -> isTrackerSourcedCard(item) }
            .forEach { item ->
                com.kennyb1201.kbstream.data.history.TitleProfileOwnership
                    .record(
                        context = getApplication(),
                        type = item.parentType ?: item.showTitle,
                        title = item.title,
                        profileId = activeProfileId
                    )
            }

        if (activeProfileId == null) return items

        val owners =
            com.kennyb1201.kbstream.data.history.TitleProfileOwnership
                .snapshot(getApplication())

        if (owners.isEmpty()) return items

        return items.filterNot { item ->
            trackerCardOwnedByAnotherProfile(
                item = item,
                ownerByTitleKey = owners,
                activeProfileId = activeProfileId
            )
        }
    }

    /**
     * Last successfully loaded set of caught-up Upcoming cards. Served when
     * the next load fails, so a flaky Simkl call cannot blink the cards off
     * the rail.
     */
    private var lastCaughtUpUpcomingItems:
        List<UpNextItem> =
        emptyList()

    /** When [lastCaughtUpUpcomingItems] was built, for [CAUGHT_UP_UPCOMING_TTL_MS]. */
    private var caughtUpUpcomingLoadedAt =
        0L

    /**
     * The LOCAL half of the caught-up cards: shows this profile - or the
     * MDBList account - has watched every aired episode of
     * ([loadLocalCaughtUpUpcomingItems]). Held separately from the Simkl list
     * above because the two answer different sources and fail independently: a
     * Simkl outage must not take the locally-provable cards with it, and the
     * cards built here are never handed to the Simkl path's "last good answer"
     * fallback. Same profile stamp and TTL for the same reasons.
     */
    private var lastLocalCaughtUpUpcomingItems:
        List<UpNextItem> =
        emptyList()

    /** When [lastLocalCaughtUpUpcomingItems] was built. */
    private var localCaughtUpUpcomingLoadedAt =
        0L

    /** Profile [lastLocalCaughtUpUpcomingItems] belongs to. */
    private var localCaughtUpUpcomingProfileId:
        String? =
        null

    /**
     * Profile [lastCaughtUpUpcomingItems] belongs to. Simkl auth is
     * per-profile while the feed is account-wide, so the cached list must
     * never outlive a switch: without the stamp, a switch inside the TTL
     * painted the profile the user just left's cards onto the incoming one
     * (including a kids profile on a shared account).
     */
    private var caughtUpUpcomingProfileId:
        String? =
        null

    /**
     * [lastCaughtUpUpcomingItems] only when it is THIS profile's, empty
     * otherwise.
     *
     * The stamp makes the cache a hit only for the profile that built it, but
     * the failure fallbacks below used to hand the list back unconditionally -
     * so one flaky Simkl call right after a switch painted the previous
     * profile's cards onto the incoming one, which is how an adult show showed
     * up on a kids profile's Upcoming rail. Every read of the cache goes
     * through here now; an unknown profile id is never a match.
     */
    private fun caughtUpUpcomingCache(
        profileId: String
    ): List<UpNextItem> =
        if (
            profileId.isNotBlank() &&
            profileId == caughtUpUpcomingProfileId
        ) {
            lastCaughtUpUpcomingItems
        } else {
            emptyList()
        }

    /**
     * The Upcoming rail's caught-up cards: the next UNAIRED episode of every
     * show this profile is caught up on - a season premiere when a new season
     * is what is coming, and a mid-season episode when the show is still
     * airing and the user is simply waiting on the next one.
     *
     * Caught-up shows are deliberately kept off Continue Watching (there is
     * nothing to resume) and because the Upcoming rail is derived from that
     * rail they used to be absent from it too - a returning show's next
     * episode surfaced nowhere. These cards close that gap, so "everything
     * upcoming of what I'm watching" actually reaches the rail; the rule is
     * any next unaired episode, and buildUpcomingSchedule keeps just the
     * future air dates (the NEW SEASON chip is still driven by a genuine
     * S01E01, so a mid-season entry reads as a plain dated card).
     *
     * The returned cards are Upcoming-only - they are handed straight to the
     * schedule builder below and never published to [upNext], which is what
     * keeps a caught-up show off the Continue Watching rail.
     *
     * Kids Mode and the rail's dismissals apply to these cards exactly as
     * they do to the Continue Watching ones (one shared gate).
     */
    private suspend fun loadCaughtUpUpcomingItems(): List<UpNextItem> {

        if (
            !simklRepository.isConfigured() ||
            !simklRepository.hasToken()
        ) {
            lastCaughtUpUpcomingItems = emptyList()
            return emptyList()
        }

        val profileId =
            com.kennyb1201.kbstream.data.sync.ProfileManager
                .activeProfile.value?.id
                ?: ""

        if (
            profileId.isNotBlank() &&
            profileId == caughtUpUpcomingProfileId &&
            System.currentTimeMillis() -
            caughtUpUpcomingLoadedAt <
            CAUGHT_UP_UPCOMING_TTL_MS
        ) {
            return caughtUpUpcomingCache(
                profileId
            )
        }

        val candidates =
            runCatchingCancellable {
                simklRepository.getCaughtUpUnreleasedShows()
            }.getOrElse { e ->
                Log.w(
                    "UPCOMING_DIAG",
                    "caught-up candidates failed: ${e.message}",
                    e
                )
                // The last good answer stands for a flaky call - but
                // only this profile's. See [caughtUpUpcomingCache].
                return caughtUpUpcomingCache(
                    profileId
                )
            }

        if (
            UPCOMING_DIAGNOSTICS
        ) {
            val capped =
                candidates.take(
                    MAX_CAUGHT_UP_UPCOMING_ITEMS
                )

            Log.d(
                "UPCOMING_DIAG",
                "caught-up candidates=${candidates.size} " +
                    capped.joinToString {
                        "'${it.title}'"
                    }
            )
        }

        // Reuse the Continue Watching builder: it is what resolves the TMDB
        // detail (artwork, next episode to air) for a Simkl item, so these
        // cards get the same look and the same cached lookups.
        val built =
            coroutineScope {
                candidates
                    .take(MAX_CAUGHT_UP_UPCOMING_ITEMS)
                    .map { candidate ->
                        async {
                            runCatchingCancellable {
                                buildSimklUpNextItem(candidate)
                            }.getOrNull()
                        }
                    }
                    .awaitAll()
                    .filterNotNull()
            }

        // The decisive step for a show the user expects to see: Simkl says an
        // episode is still to air, but only TMDB knows WHEN - and an entry it
        // has no dated next episode for is dropped by
        // buildUpcomingSchedule, not here.
        if (
            UPCOMING_DIAGNOSTICS
        ) {
            val startOfToday =
                LocalDate.now(ZoneId.systemDefault())
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()

            val airing =
                built.filter { item ->

                    val air =
                        item.nextEpisodeAir
                        ?: return@filter false

                    (
                        parseTmdbAirDate(
                            air.airDate
                        ) ?: 0L
                        ) >= startOfToday
                }

            Log.d(
                "UPCOMING_DIAG",
                "caught-up enriched=${built.size} " +
                    "future-dated=${airing.size} " +
                    "no-dated-tmdb-episode=" + built.filterNot {
                        airing.contains(
                            it
                        )
                    }.joinToString {
                        "'${it.title}'"
                    }
            )
        }

        val upcomingCards =
            applyContinueWatchingDismissals(built)

        if (
            UPCOMING_DIAGNOSTICS
        ) {
            Log.d(
                "UPCOMING_DIAG",
                "caught-up cards=${upcomingCards.size} " +
                    "(after kids mode + dismissals)"
            )
        }

        lastCaughtUpUpcomingItems = upcomingCards
        caughtUpUpcomingProfileId = profileId
        caughtUpUpcomingLoadedAt =
            System.currentTimeMillis()

        return upcomingCards
    }

    /**
     * The Upcoming rail's caught-up cards for the sources that do NOT need
     * Simkl: this profile's own watch history, and MDBList's account-wide
     * record of what has been watched.
     *
     * The rail lets a show advertise its next unaired episode only when the
     * viewer is caught up on it (see [isCaughtUpForUpcoming]), and a caught-up
     * show is on no other rail: it has nothing to resume, so Continue Watching
     * drops it. The Simkl half above was the only thing that could put its next
     * episode back, so a viewer with local history alone - or with MDBList as
     * their tracker - had an empty Upcoming rail. These cards close that, using
     * the app's own caught-up rule ([LocalSeriesProgress.isCaughtUp], the rule
     * behind the eye marker and the detail page's caught-up state) over the
     * episodes each source records watched, so both halves agree about who is
     * caught up.
     *
     * The returned cards are Upcoming-only, exactly like the Simkl half's: they
     * are handed straight to the schedule builder and never published to
     * [upNext], which is what keeps a caught-up show off Continue Watching.
     *
     * Kids Mode and the rail's dismissals apply here too (one shared gate), and
     * an MDBList card whose title another profile owns is dropped: that
     * tracker's library is one per ACCOUNT, the same leak the tracker cards
     * have always been subject to (see [trackerCardOwnedByAnotherProfile]).
     */
    private suspend fun loadLocalCaughtUpUpcomingItems(): List<UpNextItem> {

        val profileId =
            com.kennyb1201.kbstream.data.sync.ProfileManager
                .activeProfile.value?.id
                ?: ""

        if (
            profileId.isNotBlank() &&
            profileId == localCaughtUpUpcomingProfileId &&
            System.currentTimeMillis() -
            localCaughtUpUpcomingLoadedAt <
            CAUGHT_UP_UPCOMING_TTL_MS
        ) {
            return localCaughtUpUpcomingCache(
                profileId
            )
        }

        val candidates =
            loadLocalCaughtUpCandidates()

        if (
            UPCOMING_DIAGNOSTICS
        ) {
            Log.d(
                "UPCOMING_DIAG",
                "caught-up candidates (local+mdb)=" +
                    candidates.size + " " +
                    candidates.joinToString {
                        "'${it.parentId}'"
                    }
            )
        }

        val semaphore =
            Semaphore(CAUGHT_UP_UPCOMING_CONCURRENCY)

        val built =
            coroutineScope {
                candidates
                    .map { candidate ->
                        async {
                            semaphore.withPermit {
                                runCatchingCancellable {
                                    buildCaughtUpUpcomingCard(candidate)
                                }.getOrNull()
                            }
                        }
                    }
                    .toList()
                    .awaitAll()
                    .filterNotNull()
            }

        // ONE row per show: MDBList knows both id forms of a show, so the same
        // one can reach here twice. The schedule builder collapses the rows it
        // is given, but doing it first keeps the dismissal and profile gates
        // from being spent on a duplicate.
        val cards =
            applyContinueWatchingDismissals(
                built.distinctBy {
                    upNextShowParentKeys(
                        it.parentId,
                        it.parentType,
                        it.tmdbId
                    )
                }
            )

        if (
            UPCOMING_DIAGNOSTICS
        ) {
            Log.d(
                "UPCOMING_DIAG",
                "caught-up cards (local+mdb)=" +
                    cards.size + " " +
                    cards.joinToString {
                        "'${it.title}'"
                    }
            )
        }

        lastLocalCaughtUpUpcomingItems = cards
        localCaughtUpUpcomingProfileId = profileId
        localCaughtUpUpcomingLoadedAt =
            System.currentTimeMillis()

        return cards
    }

    /**
     * [lastLocalCaughtUpUpcomingItems] only when it is THIS profile's, empty
     * otherwise - the local half's twin of [caughtUpUpcomingCache], and
     * separate because the two are built from different sources and are only
     * coincidentally replaced together.
     */
    private fun localCaughtUpUpcomingCache(
        profileId: String
    ): List<UpNextItem> =
        if (
            profileId.isNotBlank() &&
            profileId == localCaughtUpUpcomingProfileId
        ) {
            lastLocalCaughtUpUpcomingItems
        } else {
            emptyList()
        }

    /**
     * Candidate shows from this profile's history and from MDBList's watched
     * snapshot (the selection rules are pure and unit tested: see
     * [localCaughtUpCandidates] and [mdbListCaughtUpCandidates]).
     *
     * Both halves fail soft - no rows, no snapshot, no key set, no budget left
     * - and the rail simply has fewer cards rather than an error.
     */
    private suspend fun loadLocalCaughtUpCandidates():
        List<CaughtUpShowCandidate> {

        val rows =
            try {
                historyDao.getCompletedSeriesRows()
            } catch (e: Exception) {
                Log.w(
                    "HOME_UPNEXT",
                    "local caught-up rows failed: ${e.message}",
                    e
                )
                emptyList()
            }

        val local =
            localCaughtUpCandidates(
                rows = rows,
                max = MAX_LOCAL_CAUGHT_UP_UPCOMING_ITEMS
            )

        // The snapshot is the same account-wide blob the watched badges read,
        // so this is usually already warm in memory (see WatchedStatusRepository
        // .refreshMdbListSetsIfNeeded).
        val mdbList =
            if (
                MdbListClient.isConfigured(getApplication())
            ) {
                runCatchingCancellable {
                    MdbListClient.getWatchedSnapshot(getApplication())
                }.getOrNull()
                    ?.let { snapshot ->
                        mdbListCaughtUpCandidates(
                            startedShowKeys = snapshot.startedShowKeys,
                            episodeKeys = snapshot.episodeKeys,
                            max = MAX_MDBLIST_CAUGHT_UP_UPCOMING_ITEMS
                        )
                    }
                    .orEmpty()
            } else {
                emptyList()
            }

        if (mdbList.isEmpty()) return local

        // A show both halves know is one card, and the local one carries this
        // profile's own name and artwork, so it wins.
        val known =
            local
                .map { upNextIdentifier(it.parentId) ?: it.parentId }
                .toMutableSet()

        return local + mdbList.filter { candidate ->
            known.add(
                upNextIdentifier(candidate.parentId) ?: candidate.parentId
            )
        }
    }

    /**
     * The Upcoming card for one caught-up candidate, or null when the show
     * turns out not to be caught up, has no next episode to air, or cannot be
     * resolved.
     *
     * The detail is the same cached lookup the Continue Watching cards make,
     * and "caught up" is the app's own rule over the episodes the candidate's
     * source recorded watched. Whether the next episode's date is in the future
     * is deliberately left to the schedule builder: that is where the
     * second-source air-date correction lives, and a date it cannot place is
     * dropped there (the rail keeps one authority for "is this upcoming").
     */
    private suspend fun buildCaughtUpUpcomingCard(
        candidate: CaughtUpShowCandidate
    ): UpNextItem? {

        val detail =
            seriesDetailFor(candidate.parentId)
                ?: return null

        val air =
            detail.nextEpisodeToAir
                ?: return null

        val caughtUp =
            LocalSeriesProgress.isCaughtUp(
                completedEpisodes =
                    candidate.watchedEpisodes,

                seasonEpisodeCounts =
                    detail.seasons
                        .mapNotNull { season ->
                            val number = season.seasonNumber
                            val count = season.episodeCount
                            if (number > 0 && count != null && count > 0) {
                                number to count
                            } else {
                                null
                            }
                        }
                        .toMap(),

                lastAiredSeason =
                    detail.lastEpisodeToAir?.seasonNumber,

                lastAiredEpisode =
                    detail.lastEpisodeToAir?.episodeNumber
            )

        if (!caughtUp) return null

        val title =
            upNextDisplayTitleOrNull(
                candidate.title,
                !candidate.poster.isNullOrBlank()
            )
                ?: upNextDisplayTitleOrNull(detail.name, true)
                ?: upNextDisplayTitleOrNull(detail.title, true)
                ?: return null

        val poster =
            candidate.poster
                ?: detail.posterPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let { TmdbRepository.POSTER_BASE + it }

        val backdrop =
            candidate.backdrop
                ?: detail.backdropPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "https://image.tmdb.org/t/p/w780$it" }

        return UpNextItem(
            // An MDBList card is built as a TRACKER card on purpose: it comes
            // from an account-wide library, so the gates that keep one
            // profile's titles off another profile's rails have to apply to
            // it. The local card is this profile's own and is never dropped
            // that way.
            id =
                if (candidate.fromTracker) {
                    "mdblist:caughtup:${candidate.parentId}"
                } else {
                    "caughtup:${candidate.parentId}"
                },

            title = title,
            poster = poster,
            badge = UpNextBadge.NEXT_UP,
            showTitle = detail.name ?: title,
            parentId = candidate.parentId,
            parentType = "series",
            season = air.seasonNumber,
            episode = air.episodeNumber,
            backdrop = backdrop,
            clearLogo = candidate.clearLogo,
            nextEpisodeAir = air,
            tmdbId = detail.id
        )
    }

    /**
     * The TMDB series detail behind a parent id, in whichever id flavor the
     * card carries - the same cached lookup (and the same flavor handling) the
     * local next-up cards use.
     */
    private suspend fun seriesDetailFor(
        parentId: String
    ): TmdbDetail? =
        try {
            tmdbLookupSemaphore.withPermit {
                when {
                    parentId.startsWith("tmdb:", ignoreCase = true) ->
                        parentId
                            .substringAfter(":")
                            .toIntOrNull()
                            ?.let { id ->
                                tmdbRepository.getDetailByTmdbId(
                                    id,
                                    "series"
                                )
                            }

                    parentId.startsWith("tt", ignoreCase = true) ->
                        tmdbRepository.fetchEnrichedMetaCached(
                            parentId,
                            "series"
                        )

                    parentId.toIntOrNull() != null ->
                        tmdbRepository.getDetailByTmdbId(
                            parentId.toInt(),
                            "series"
                        )

                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }

    /**
     * Local "next up" cards for shows that have watched episodes but nothing
     * in progress.
     *
     * Continue Watching is built from the unfinished resume rows alone, so
     * marking a show's episodes watched - or finishing its last in-progress
     * episode - removes the rows the rail is made of and the show disappeared,
     * even with most of it still unwatched. These cards restore it, exactly as
     * the Simkl NEXT_UP cards do for tracked shows: one card per show, pointing
     * at its next unwatched aired episode.
     *
     * A show with no unwatched AIRED episode left (finished, or merely waiting
     * on the next season) resolves to no target and is left off, so caught-up
     * shows stay off Continue Watching as before.
     */
    private suspend fun loadLocalNextUpItems(
        existing: List<UpNextItem>,
        /**
         * Collects the identity keys of shows this pass proves are caught up
         * locally. The caller drops any tracker card for one of them, so a
         * finished title leaves Continue Watching without waiting on the
         * tracker feed (see trackerCardLocallyFinished).
         */
        caughtUpShowKeys: MutableSet<String>
    ): List<UpNextItem> {

        val completedRows =
            try {
                historyDao.getCompletedSeriesRows()
            } catch (e: Exception) {
                Log.w(
                    "HOME_UPNEXT",
                    "local next-up rows failed: ${e.message}",
                    e
                )
                return emptyList()
            }

        if (completedRows.isEmpty()) return emptyList()

        // Shows already on the rail (an episode paused part-way) keep their
        // resume card; a next-up twin for them is wasted work.
        val representedIds =
            existing
                .mapNotNull { item -> upNextIdentifier(item.parentId) }
                .toSet()

        // Candidate selection is pure and unit tested (see
        // selectLocalNextUpCandidates): one card per show, newest completion
        // first, minus any show already on the rail, capped.
        val candidates =
            selectLocalNextUpCandidates(
                completedRows = completedRows,
                representedIdentifiers = representedIds,
                max = MAX_LOCAL_NEXT_UP_ITEMS
            )

        val semaphore =
            Semaphore(LOCAL_NEXT_UP_CONCURRENCY)

        return coroutineScope {
            candidates
                .map { (parentId, row) ->
                    async {
                        semaphore.withPermit {
                            runCatchingCancellable {
                                buildLocalNextUpItem(
                                    parentId,
                                    row,
                                    caughtUpShowKeys
                                )
                            }.getOrNull()
                        }
                    }
                }
                .toList()
                .awaitAll()
                .filterNotNull()
        }
    }

    /**
     * The next unwatched aired episode of one locally-watched show as an
     * [UpNextItem] with no progress (the resume path owns cards that have one).
     * Null when the show cannot be resolved or has nothing left to watch.
     */
    private suspend fun buildLocalNextUpItem(
        parentId: String,
        row: WatchHistoryEntity,
        caughtUpShowKeys: MutableSet<String>
    ): UpNextItem? {        val detail =
            seriesDetailFor(parentId)

        val tmdbId = detail?.id ?: return null
        if (tmdbId <= 0) return null

        // The resolver reads this show's watched state from the shared maps,
        // which only carry what has been preloaded for it.
        preloadWatchedEpisodeStateForShow(
            parentId = parentId,
            tmdbShowId = tmdbId
        )

        val target =
            resolveSeriesTargetFromSharedWatchedState(
                parentId = parentId,
                tmdbId = tmdbId,
                simklSeason = null,
                simklEpisode = null
            )

        if (target == null) {
            // The show resolved, but there is nothing left to watch: the
            // profile finished it locally. Record it so its tracker twin -
            // which the tracker keeps listing until its own feed catches up -
            // is dropped from the rail immediately (see
            // trackerCardLocallyFinished).
            caughtUpShowKeys += upNextShowParentKeys(
                parentId,
                "series",
                tmdbId
            )

            return null
        }

        // A resume target here means the show already has an in-progress row
        // (and therefore its own card); don't duplicate it.
        if (target.isResume) return null

        val poster =
            row.poster?.takeIf { it.isNotBlank() }
                ?: detail.posterPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let { TmdbRepository.POSTER_BASE + it }
                ?: return null

        val title =
            upNextDisplayTitleOrNull(
                row.name,
                !row.poster.isNullOrBlank()
            )
                ?: upNextDisplayTitleOrNull(detail.name, true)
                ?: upNextDisplayTitleOrNull(detail.title, true)
                ?: return null

        val backdrop =
            row.backdropUrl?.takeIf { it.isNotBlank() }
                ?: detail.backdropPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "https://image.tmdb.org/t/p/w780$it" }

        // A recently aired episode is news - a premiere reads NEW SEASON (the
        // show is coming back, not one more episode), anything else NEW
        // EPISODE. This card has no progress to resume (a resume target returns
        // above), so the arrival decides it; the rule is shared with the
        // tracker builders so a viewer without Simkl sees the same chips.
        val nextUpBadge =
            upNextArrivalBadge(
                isResume = false,
                airDate = target.airDate,
                episode = target.episode
            )

        return UpNextItem(
            id = "nextup:$parentId",
            title = title,
            poster = poster,
            badge = nextUpBadge,
            showTitle = title,
            episodeTitle = target.episodeTitle,
            episodeDescription = target.episodeDescription,
            episodesWatched = target.episodesWatched,
            episodesTotal = target.episodesTotal,
            episodesRemaining = target.episodesRemaining,
            episodeThumbnail = target.episodeThumbnail,
            backdrop = backdrop,
            clearLogo = row.clearLogo,
            imdbRating = target.episodeRating,
            runtimeMinutes = target.runtimeMinutes,
            subtitle =
                "${
                    upNextBadgePrefix(
                        nextUpBadge
                    )
                } - ${
                    formatSeasonEpisode(
                        target.season,
                        target.episode
                    )
                }",
            parentId = parentId,
            parentType = "series",
            season = target.season,
            episode = target.episode,
            episodeStreamId = target.streamId,
            startPositionMs = 0L,
            recencyTimestamp = row.completedAt ?: row.updatedAt,
            isSeasonFinale = target.isSeasonFinale,
            isSeriesFinale = target.isSeriesFinale,
            nextEpisodeAir = detail.nextEpisodeToAir,
            tmdbId = tmdbId
        )
    }

    /**
     * Kids Mode gate for the Continue Watching / Upcoming rails. Both rails
     * derive from [_upNext], which merges the account-wide Simkl feed, so
     * each item's parent title is checked against the active profile's
     * rating ceiling through the repository's cached-detail lookup (no-op
     * when kids mode is off). Titles resolve via the same MetaPreview path
     * the catalog rails use; episodes of one show collapse onto a single
     * lookup because they share a parent id.
     */
    private suspend fun kidsFilterUpNext(
        items: List<UpNextItem>
    ): List<UpNextItem> {
        if (items.isEmpty() || tmdbRepository.kidsMaxAge() == null) return items
        val surviving = tmdbRepository.kidsFilterMetas(
            items.map { item ->
                MetaPreview(
                    id = item.parentId?.takeIf { it.isNotBlank() } ?: item.id,
                    type = item.parentType?.takeIf { it.isNotBlank() } ?: "movie",
                    name = item.title,
                    poster = item.poster,
                    background = item.backdrop,
                    logo = item.clearLogo
                )
            }
        ).mapTo(HashSet()) { it.id.trim() }
        return items.filter { item ->
            surviving.contains(
                (item.parentId?.takeIf { it.isNotBlank() } ?: item.id).trim()
            )
        }
    }

    /**
     * Long-press "Mark as Watched" on a catalog poster: writes a persistent
     * local watched override so the poster badge shows immediately and stays
     * marked even when remote (SIMKL) state doesn't know about it.
     */
    fun markAsWatched(meta: MetaPreview) {

        val id = meta.id.trim()

        if (id.isBlank()) {
            return
        }

        viewModelScope.launch {

            runCatchingCancellable {

                watchedStatusRepository.markWatchedLocal(
                    id,
                    meta.type
                )

            }.onFailure { e ->

                Log.e(
                    "HOME_WATCHED",
                    "Failed to mark watched: " +
                        "${meta.name} ($id)",
                    e
                )
            }
        }
    }

    /**
     * Long-press "Mark as Unwatched" on a catalog poster: removes the local
     * watched override so the badge clears immediately (and deletes the title
     * from Simkl history when connected).
     */
    fun markUnwatched(meta: MetaPreview) {

        val id = meta.id.trim()

        if (id.isBlank()) {
            return
        }

        viewModelScope.launch {

            runCatchingCancellable {

                watchedStatusRepository.markUnwatchedLocal(
                    id,
                    meta.type
                )

            }.onFailure { e ->

                Log.e(
                    "HOME_WATCHED",
                    "Failed to mark unwatched: " +
                        "${meta.name} ($id)",
                    e
                )
            }
        }
    }

    fun refreshAllHomeData() {

        viewModelScope.launch {

            // Timed end-to-end: this is the "pull to refresh" the user waits
            // on, and the number the diagnostics perf block reports.
            val startedAt = android.os.SystemClock.elapsedRealtime()

            clearWatchedStateCaches()

            _refreshTrigger.value += 1

            loadRailsInternal(
                forceRefresh = true
            )

            PerfTrace.record(
                "home.refreshAll",
                android.os.SystemClock.elapsedRealtime() - startedAt
            )
        }
    }

    fun refreshRailsOnly() {

        loadRails(
            forceRefresh = true
        )
    }

    /**
     * Called every time the Home screen is (re)entered. If the digital-release
     * filter toggle changed since the rails were last built, rebuilds them
     * from the warm catalog cache — no network refetch.
     */
    fun onHomeResumed() {

        // Rebuild the rails when either display toggle changed in Settings:
        // hide-upcoming needs a refilter, and landscape cards need
        // landscapeArt resolved — which only happens at rail-build time.
        // Rails loaded while landscape was OFF carry an empty landscapeArt
        // map, so flipping the toggle on used to leave every card on the
        // addon's primary backdrop (the same image the hero shows) until a
        // full app restart.
        val currentHideUpcoming =
            AppPreferences.getHomeRailHideUpcoming(
                getApplication()
            )

        val currentLandscape =
            AppPreferences.getHomeLandscapeCards(
                getApplication()
            )

        val needsRebuild =
            (lastAppliedHideUpcoming != null &&
                currentHideUpcoming != lastAppliedHideUpcoming) ||
                (lastAppliedLandscape != null &&
                    currentLandscape != lastAppliedLandscape)

        // Coming back to an empty Home (cold start failed, user backed out
        // of the empty state, process was restored) must always retry the
        // build - otherwise the user is stuck staring at "No catalogs
        // available" until they find some setting to poke.
        // A build already running is not an empty Home: its own publish is
        // exactly what this call would be waiting for, and a second build would
        // queue behind it and repeat every fetch (see railBuildInFlight).
        val railsEmpty =
            _rails.value.isEmpty() && !railBuildInFlight

        // Stale-rails guard: after the device sat on the launcher / another
        // screen for a long while, the addon catalogs (dynamic ones like
        // BingeCat "Because you watched" especially) may have new content
        // server-side. Rebuild with a network refetch instead of serving
        // rails that could be hours old. railsBuiltAtMs updates on every
        // build, so normal quick back-and-forth navigation still uses the
        // warm cache path above.
        val railsStale =
            railsBuiltAtMs > 0L &&
                System.currentTimeMillis() - railsBuiltAtMs >
                RAILS_STALE_RESUME_MS

        if (needsRebuild || railsEmpty || railsStale) {

            viewModelScope.launch {

                loadRailsInternal(
                    forceRefresh = true,
                    clearCatalogCache = railsStale,
                    coalesce = true
                )
            }
        }
    }

    /** Timestamp of the last successful rail build; 0 until first build. */
    @Volatile
    private var railsBuiltAtMs = 0L

    private var lastAppliedHideUpcoming: Boolean? = null
    private var lastAppliedLandscape: Boolean? = null

    // Per-rail pagination bookkeeping, keyed by rail identity
    // ("addonName::catalogId::type"). Volatile: loadMoreForRail can be called
    // from the UI thread (HomeScreen scroll sentinel) and mutates the maps
    // before launching the coroutine that fetches the page.
    //  - loadingRails: in-flight page fetches (prevents duplicate requests)
    //  - exhaustedRails: catalogs that returned an empty page (no more items)
    //  - railInfo: identity needed to build the next page URL (baseUrl, type,
    //    filter toggles active when the rail was built)
    private val loadingRails = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val exhaustedRails = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val railInfo = java.util.concurrent.ConcurrentHashMap<String, RailInfo>()

    // Next raw source offset per rail: how many catalog items have already
    // been consumed, i.e. the `skip` for the following page.
    //
    // Addon catalogs page in whatever batch size they choose — Stremio has no
    // `limit` parameter, so the app can only ask for a `skip` and read
    // whatever comes back (20 for some addons, 50/100 for others, and some
    // dump their whole catalog at once). Advancing the offset by the number of
    // items the addon ACTUALLY returned — rather than rounding up to a fixed
    // 100 — is what lets a 20-per-page addon page all the way through a
    // 96-item catalog, and what stops a short final page from being mistaken
    // for "end of catalog" (which left Home rails stuck at the first 20).
    private val railSourceOffset = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun refreshWatchedStatusForCurrentRails() {

        refreshWatchedStatus(
            _rails.value
        )
    }

    // ---- Catalog grid ("Open in Grid" from a rail's long-press menu) ----

    /** Header for the open catalog grid: the page title row. */
    data class CatalogGridHeader(
        val title: String,
        val addonName: String
    )

    private val _catalogGridHeader =
        MutableStateFlow<CatalogGridHeader?>(null)

    val catalogGridHeader: StateFlow<CatalogGridHeader?> =
        _catalogGridHeader.asStateFlow()

    // The open grid's pages, or null when no grid is open. Paging 3 owns the
    // offset bookkeeping, de-dupe and end-of-list detection that this class
    // used to spell out as items/isLoadingMore/hasMore/error, and the screen
    // reads one LazyPagingItems whose LoadState IS that loading/error/retry
    // UI. The flow is dropped on close, which cancels any in-flight page.
    private val _catalogGridPaging =
        MutableStateFlow<Flow<PagingData<MetaPreview>>?>(null)

    val catalogGridPaging: StateFlow<Flow<PagingData<MetaPreview>>?> =
        _catalogGridPaging.asStateFlow()

    /**
     * Route-based variant: reopens the grid for a catalog identified by its
     * Home rail title + addon name (survives process restoration). No-op if
     * that catalog is already open.
     */
    fun openCatalogInGrid(title: String, addonName: String) {

        val current =
            _catalogGridHeader.value

        if (
            current != null &&
            current.title == title &&
            current.addonName == addonName
        ) {
            return
        }

        val rail =
            _rails.value.firstOrNull { rail ->
                rail.catalogName == title &&
                    rail.addonName == addonName
            }
                ?: return

        openCatalogInGrid(rail)
    }

    /**
     * Opens the catalog behind a Home rail as a full-screen poster grid.
     *
     * The rail has already fetched its first batch, so that batch seeds the
     * grid for an instant first paint and the PagingSource resumes from
     * wherever the rail stopped — no re-fetch of items the rail already holds.
     */
    fun openCatalogInGrid(rail: Rail) {

        val railKey =
            railKeyOf(rail)

        val info =
            railInfo[railKey]

        // Resume exactly where the rail left off in the source catalog. Falls
        // back to the displayed count only if the offset was never recorded
        // (e.g. a rail restored from cache), which is correct for the common
        // contiguous case.
        val startOffset =
            railSourceOffset[railKey] ?: rail.items.size

        _catalogGridHeader.value =
            CatalogGridHeader(
                title = rail.catalogName,
                addonName = rail.addonName
            )

        _catalogGridPaging.value =
            if (info == null || railKey in exhaustedRails) {
                // Rail came from a code path without pagination info
                // (Continue Watching), or the source already reported the end:
                // show what the rail had and page no further.
                flowOf(PagingData.from(rail.items))
            } else {
                Pager(
                    config = PagingConfig(
                        pageSize = CATALOG_GRID_PAGE_SIZE,
                        initialLoadSize = CATALOG_GRID_PAGE_SIZE,
                        // Placeholders would make the grid's item count jump as
                        // pages land, which is exactly what steals D-pad focus
                        // mid-scroll on TV.
                        enablePlaceholders = false,
                        // The same runway the rails use for a grid: two
                        // screenfuls capped at PREFETCH_MAX_ITEMS.
                        prefetchDistance = CATALOG_GRID_PREFETCH_DISTANCE
                    )
                ) {
                    CatalogGridPagingSource(
                        seed = rail.items,
                        startOffset = startOffset,
                        maxItems = MAX_RAIL_ITEMS,
                        loadPage = { skip ->
                            fetchGridPage(info, skip)
                        }
                    )
                }.flow
            }
    }

    fun closeCatalogGrid() {

        _catalogGridPaging.value = null
        _catalogGridHeader.value = null
    }

    /**
     * Fetches one page of the open grid, filtered the same way the rails are:
     * hidden upcoming titles (when the catalog asks for it) and the kids
     * profile's allowances.
     *
     * [skip] addresses the *source* catalog, so [CatalogGridPage.fetchedCount]
     * reports the raw page size — a filter that drops entries must not also
     * move the next offset backwards onto ground it already discarded.
     */
    private suspend fun fetchGridPage(
        info: RailInfo,
        skip: Int
    ): CatalogGridPage {

        val metas =
            fetchCatalogThrottled(
                baseUrl = info.baseUrl,
                type = info.catalogType,
                catalogId = info.catalogId,
                skip = skip
            )

        val filtered =
            if (
                info.hideUpcoming
            ) {
                tmdbRepository.kidsFilterMetas(
                    applyDigitalAvailabilityFilter(
                        filterUpcoming(metas)
                    )
                )
            } else {
                tmdbRepository.kidsFilterMetas(metas)
            }

        return CatalogGridPage(
            items = filtered,
            fetchedCount = metas.size
        )
    }

    /**
     * Infinite scroll: fetches the next page for the rail identified by
     * [railKey] (built via [railKeyOf] on the UI) and appends the de-duped,
     * filtered results to the existing rail in place. No-ops when that rail
     * is already loading a page or the catalog reported it has no more
     * items. Safe to call repeatedly from a scroll sentinel.
     */
    fun loadMoreForRail(railKey: String) {

        val info =
            railInfo[railKey]
                ?: return

        // Profile guard for this page: it belongs to the profile that was
        // active when the scroll asked for it, so a switch cancels it.
        val buildEpochAtStart = railBuildEpoch

        if (
            !loadingRails.add(railKey)
        ) {
            return
        }

        if (
            railKey in exhaustedRails
        ) {
            loadingRails.remove(railKey)
            return
        }

        viewModelScope.launch {

            try {

                val currentRail =
                    _rails.value.firstOrNull { rail ->
                        railKeyOf(rail) == railKey
                    }
                        ?: return@launch

                if (
                    currentRail.items.isEmpty()
                ) {
                    return@launch
                }

                // Resume from the exact source offset the addon last left
                // off at, not a rounded-up multiple of 100.
                val skip =
                    railSourceOffset[railKey] ?: currentRail.items.size

                val metas =
                    fetchCatalogThrottled(
                        baseUrl = info.baseUrl,
                        type = info.catalogType,
                        catalogId = info.catalogId,
                        skip = skip
                    )

                if (
                    metas.isEmpty()
                ) {
                    // Empty page = the addon has no more items.
                    exhaustedRails.add(railKey)
                    return@launch
                }

                // Advance by what the addon actually returned, so the next
                // skip lands exactly where this page ended regardless of the
                // addon's own page size.
                railSourceOffset[railKey] = skip + metas.size

                val filtered =
                    if (
                        info.hideUpcoming
                    ) {
                        tmdbRepository.kidsFilterMetas(
                            applyDigitalAvailabilityFilter(
                                filterUpcoming(metas)
                            )
                        )
                    } else {
                        tmdbRepository.kidsFilterMetas(metas)
                    }

                if (
                    filtered.isEmpty()
                ) {
                    return@launch
                }

                val existingIds =
                    _rails.value
                        .firstOrNull { rail ->
                            railKeyOf(rail) == railKey
                        }
                        ?.items
                        ?.mapTo(mutableSetOf()) { it.id }
                        ?: mutableSetOf()

                val deduped =
                    filtered.filter { it.id !in existingIds }

                if (
                    deduped.isEmpty()
                ) {
                    // Page brought nothing new (dupes / filtered out):
                    // treat as exhausted so the scroll trigger stops
                    // re-requesting the same skip offset.
                    exhaustedRails.add(railKey)
                    return@launch
                }

                // Stale-profile guard: the profile changed while this page
                // was in flight — appending it would mix the old profile's
                // rows into the new profile's rail of the same key.
                if (buildEpochAtStart != railBuildEpoch) {
                    return@launch
                }

                _rails.value =
                    _rails.value.map { rail ->

                        if (
                            railKeyOf(rail) == railKey
                        ) {
                            rail.copy(
                                items = rail.items + deduped
                            )
                        } else {
                            rail
                        }
                    }

                // The page's cards now paint straight away and their artwork
                // lands behind them, exactly as a whole rail's does (see
                // applyLandscapeArt). Resolving it first meant the viewer's
                // scroll sat waiting on TMDB before the new items appeared at
                // all - the wrong way round, because the items are what the
                // scroll asked for. Launched, not awaited, so the page's
                // bookkeeping below still happens on this pass.
                if (
                    info.landscapeCards
                ) {
                    viewModelScope.launch {
                        applyLandscapeArt(
                            railKey = railKey,
                            items = deduped,
                            buildEpoch = buildEpochAtStart
                        )
                    }
                }

                // Runaway guard: an addon that ignores `skip` is already
                // stopped by the identical-page check above (its repeat page
                // dedupes to nothing). This is the second net - an addon that
                // keeps emitting brand-new items forever must not grow a rail
                // without bound either. Past the cap the rail stops paging;
                // the catalog grid stays openable from what loaded.
                if (
                    currentRail.items.size + deduped.size >= MAX_RAIL_ITEMS
                ) {
                    exhaustedRails.add(railKey)
                }

                refreshWatchedStatus(_rails.value)
            } catch (
                e: kotlinx.coroutines.CancellationException
            ) {
                throw e
            } catch (e: Exception) {

                Log.e(
                    "HOME_RAILS",
                    "page load failed rail=$railKey: ${e.message}",
                    e
                )
            } finally {

                loadingRails.remove(railKey)
            }
        }
    }

    fun watchedKey(
        id: String,
        type: String
    ): String {

        val normalizedType =
            when (type.lowercase()) {

                "movie" ->
                    "movie"

                "series",
                "show",
                "tv" ->
                    "series"

                else ->
                    type.lowercase()
            }

        return "$normalizedType::$id"
    }

    private fun startPeriodicSimklRefresh() {

        if (
            periodicRefreshJob?.isActive == true
        ) {
            return
        }

        periodicRefreshJob =
            viewModelScope.launch {

                while (true) {

                    delay(
                        PERIODIC_SIMKL_REFRESH_MS
                    )

                    try {

                        // Skipped, not held, while a fullscreen player holds
                        // the screen. This tick is a full Simkl pull plus a
                        // watched-status rebuild over every rail and the TMDB
                        // work that resolves it - a burst of network the
                        // video's loader shares the link with. Holding it would
                        // not help either: the gate's hold budget is per
                        // viewing session and a film is longer, so it would
                        // land mid-playback anyway. Nothing is lost by letting
                        // this one go - the next tick is fifteen minutes away,
                        // and Home's ON_RESUME path refreshes watched status
                        // the moment the viewer comes back.
                        if (
                            simklRepository.isConfigured() &&
                            simklRepository.hasToken() &&
                            !EpgWriteGate.isPlayerActive
                        ) {

                            Log.e(
                                "HOME_REFRESH",
                                "periodic Simkl refresh tick"
                            )

                            clearWatchedStateCaches()

                            _refreshTrigger.value += 1

                            refreshWatchedStatus(
                                _rails.value
                            )
                        }

                    } catch (e: Exception) {

                        Log.e(
                            "HOME_REFRESH",
                            "periodic refresh failed: ${e.message}",
                            e
                        )
                    }
                }
            }
    }

    private fun calculateRemainingMinutes(
    positionMs: Long,
    durationMs: Long
): Int? {
    if (durationMs <= 0L || positionMs < 0L) {
        return null
    }

    val remainingMs =
        (durationMs - positionMs).coerceAtLeast(0L)

    if (remainingMs <= 0L) {
        return null
    }

    return ((remainingMs + 30_000L) / 60_000L)
        .toInt()
        .coerceAtLeast(1)
}

    private fun calculateRemainingMinutesFromProgress(
    runtimeMinutes: Int?,
    progress: Float?
): Int? {
    val runtime = runtimeMinutes
        ?.takeIf { it > 0 }
        ?: return null

    val progressPercent =
        progress
            ?.coerceIn(0f, 100f)
            ?: return null

    if (progressPercent >= 100f) {
        return null
    }

    val remaining =
        runtime * (1f - (progressPercent / 100f))

    return round(remaining)
        .toInt()
        .coerceAtLeast(1)
    }

    // Per-show "watched of total aired" cache for Continue Watching rows.
    // resolveSeriesTargetFromSharedWatchedState walks every TMDB season
    // listing of a show (up to 50 season pages) - without this cache a rail
    // holding several rows of the SAME show repeated that whole walk per row.
    // Keyed by the numeric TMDB id so the local-history path and the Simkl
    // path (which often use different parent-id flavors for the same show)
    // share one entry; cleared by clearWatchedStateCaches() on refresh.
    // Concurrent access: the local pipeline enriches rows in parallel, so
    // this must be a concurrent map.
    private val showEpisodeTotalsCache =
        java.util.concurrent.ConcurrentHashMap<Int, ShowEpisodeTotals>()

    // Full season-episode map (season -> episodes) per TMDB show, produced by
    // resolveSeriesTargetFromSharedWatchedState's season walk. The Simkl path
    // reuses it so several Simkl rows of the SAME show don't repeat the walk:
    // the resolver builds its next-episode target, finale flags and "X of Y"
    // totals entirely from this map. Keyed by numeric TMDB id (both paths
    // resolve to the same id for one show); cleared with the totals cache.
    private val showSeasonEpisodesCache =
        java.util.concurrent.ConcurrentHashMap<Int, Map<Int, List<ResolvedEpisode>>>()

    private suspend fun clearWatchedStateCaches() {

        watchedStateMutex.withLock {

            trackerWatchedEpisodesByShow.clear()

            watchedEpisodeKeysByShow.clear()

            watchedStatePreloadInFlight.clear()

            showEpisodeTotalsCache.clear()

            showSeasonEpisodesCache.clear()
        }
    }

    /**
     * Builds the Upcoming rail from the current Continue Watching items:
     * one entry per show with a FUTURE "next episode to air", sorted by
     * air date. Air dates at/past the current moment are excluded (the
     * episode has aired -> it belongs in Continue Watching, not here).
     */
    private suspend fun buildUpcomingSchedule(
        items: List<UpNextItem>
    ): List<UpcomingEpisode> {
        val now = System.currentTimeMillis()
        val today = LocalDate.now(ZoneId.systemDefault())
        val startOfToday = today
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        // (source card, derived row) pairs. The winner per show is chosen
        // only after every row is built, because one show can reach the rail
        // twice under different id flavors (see selectUpcomingPerShow).
        val rows = ArrayList<Pair<UpNextItem, UpcomingEpisode>>()

        // Second-source air dates for the shows on the rail, fetched up front
        // (concurrently, and failing open per show) so the loop below never
        // waits on the network once per card.
        val sourceDatesByParent = loadSourceAirDates(items)

        for (item in items) {
            val air = item.nextEpisodeAir ?: continue
            var season = air.seasonNumber ?: continue
            var episode = air.episodeNumber ?: continue
            val parentId = item.parentId?.takeIf { it.isNotBlank() } ?: continue
            val parentType = item.parentType?.takeIf { it.isNotBlank() } ?: continue

            val sourceDates = sourceDatesByParent[parentId].orEmpty()
            var airDateText = AirDateCorrection.correctAirDate(
                primary = air.airDate,
                secondary = sourceDates[
                    AirDateCorrection.episodeKey(season, episode)
                ],
                today = today
            )
            var epochMs = parseTmdbAirDate(airDateText) ?: continue

            if (epochMs < startOfToday && sourceDates.isNotEmpty()) {
                // The episode TMDB still calls "next" has already aired, so its
                // date was stale. The show's real next episode is the earliest
                // one the second source has ahead of today; when it has none,
                // the show has nothing upcoming and belongs in Continue
                // Watching instead.
                val replacement =
                    AirDateCorrection.nextAiring(sourceDates, today) ?: continue
                season = replacement.season
                episode = replacement.episode
                airDateText = replacement.airDate
                epochMs = parseTmdbAirDate(replacement.airDate) ?: continue
            }

            // Air dates carry no time (midnight), so compare against the
            // start of today: an episode airing later today still shows
            // (labeled "Today"); anything before today has aired.
            if (epochMs < startOfToday) continue

            val airLabel = formatAirDateLabel(airDateText)
            val airFull = AirDateCorrection.parse(airDateText)
                ?.format(DateFormats.AIR_DATE)
                ?: ""

            rows.add(
                item to UpcomingEpisode(
                    id = "upcoming:$parentId:s$season:e$episode",
                    parentId = parentId,
                    parentType = parentType,
                    title = item.title,
                    poster = item.poster,
                    backdrop = item.backdrop,
                    season = season,
                    episode = episode,
                    airDateEpochMs = epochMs,
                    airDateLabel = airLabel,
                    // TMDB's next_episode_to_air summary sometimes ships
                    // without a title even when the season detail HAS one;
                    // backfill from the cached season episodes before
                    // giving up (Simkl tracks watched state, it has no
                    // unaired-episode metadata, so TMDB is the only source).
                    // The card may now name an episode later than TMDB's
                    // next_episode_to_air (see the replacement above), so
                    // TMDB's own title only applies while the episode is still
                    // the one it pointed at.
                    episodeTitle = air.name
                        ?.takeIf {
                            it.isNotBlank() && episode == air.episodeNumber
                        }
                        ?: fallbackUpcomingEpisodeTitle(
                            tmdbId = item.tmdbId,
                            season = season,
                            episode = episode,
                            imdbId = parentId
                        ),
                    // Always populated — render sites de-dupe against
                    // [airDateLabel] where both would say the same thing
                    // (hero). The Upcoming card needs the absolute date even
                    // when the label IS the date, because NEW SEASON cards
                    // replace the label chip with "NEW SEASON".
                    airDateFull = airFull,
                    isSeasonPremiere = episode == 1
                )
            )
        }

        // ONE row per show: a show has exactly one next unaired episode, so a
        // second row for it is the duplicate the rail was showing.
        return selectUpcomingPerShow(rows).sortedBy { it.airDateEpochMs }
    }

    /**
     * Backfills a missing next-episode title from the TMDB season detail
     * (memory/disk cached). Returns null when anything is unavailable so
     * the card just renders the S·E line.
     */
    private suspend fun fallbackUpcomingEpisodeTitle(
        tmdbId: Int?,
        season: Int,
        episode: Int,
        imdbId: String
    ): String? {
        if (tmdbId == null || tmdbId <= 0) return null
        return runCatchingCancellable {
            tmdbRepository.getSeasonEpisodes(tmdbId, season, imdbId)
                .firstOrNull { it.episodeNumber == episode }
                ?.name
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Second-source air dates for every show on the rail, keyed by the parent
     * id the rail's items already carry, in the `"season:episode"` shape
     * [AirDateCorrection] expects.
     *
     * Fails open per show: a show the source doesn't know - or a lookup that
     * fails - simply has no entry, and the rail then uses TMDB's own date. The
     * lookups run concurrently because the rail waits on this before it can
     * render; the repository rate-limits them.
     */
    private suspend fun loadSourceAirDates(
        items: List<UpNextItem>
    ): Map<String, Map<String, String>> {
        val targets = LinkedHashMap<String, String>()

        for (item in items) {
            if (item.nextEpisodeAir == null) continue
            val parentId =
                item.parentId?.takeIf { it.isNotBlank() } ?: continue
            if (targets.containsKey(parentId)) continue

            // The source is looked up by IMDB id; a "tmdb:<n>" parent has to
            // be resolved, which the resolution table caches.
            val imdbId = parentId
                .takeIf { it.startsWith("tt", ignoreCase = true) }
                ?: item.tmdbId?.let { tmdbId ->
                    runCatchingCancellable {
                        tmdbRepository.resolveImdbId(
                            tmdbId,
                            item.parentType ?: "series"
                        )
                    }.getOrNull()
                }
                ?: continue

            targets[parentId] = imdbId
        }

        if (targets.isEmpty()) return emptyMap()

        val resolved =
            java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
        coroutineScope {
            targets.map { (parentId, imdbId) ->
                async {
                    resolved[parentId] =
                        airDateRepository.episodeAirDates(imdbId)
                }
            }.awaitAll()
        }
        return resolved
    }



    /**
     * Instant Continue Watching seed: publish a lightweight snapshot built
     * ONLY from the local watch-history rows (no TMDB enrichment, no Simkl
     * round-trip) so the rail renders the moment Home composes. The full
     * observeUpNext pipeline later replaces this with enriched cards.
     */
    private suspend fun publishInstantUpNextSnapshot() {
        val history = try {
            watchHistoryRepository.getContinueWatchingParentsSnapshot()
        } catch (e: Exception) {
            Log.w("HOME_UPNEXT", "Instant up-next snapshot failed", e)
            return
        }
        // A resume row a later watched episode has overtaken is stale and
        // must not seed the rail (see supersededResumeRowIds).
        val visibleHistory = dropSupersededResumeRows(history)

        if (visibleHistory.isEmpty()) return

        val items = visibleHistory.mapNotNull { entry ->
            UpNextItem(
                id = buildString {
                    append("history:")
                    append(entry.id)
                    entry.season?.let { append(":s$it") }
                    entry.episode?.let { append(":e$it") }
                },
                title =
                    upNextDisplayTitleOrNull(
                        entry.name,
                        !entry.poster.isNullOrBlank()
                    )
                        ?: return@mapNotNull null,
                poster = entry.poster,
                badge = UpNextBadge.CONTINUE_WATCHING,
                showTitle = if (entry.season != null && entry.episode != null) {
                    entry.name
                } else {
                    null
                },
                episodeTitle = entry.episodeTitle?.takeIf { it.isNotBlank() },
                episodeDescription = entry.overview?.takeIf { it.isNotBlank() },
                backdrop = entry.backdropUrl,
                clearLogo = entry.clearLogo,
                progressPercent = progressFromHistory(
                    positionMs = entry.positionMs,
                    durationMs = entry.durationMs
                ),
                remainingMinutes = calculateRemainingMinutes(
                    positionMs = entry.positionMs,
                    durationMs = entry.durationMs
                ),
                runtimeMinutes =
                    if (entry.durationMs > 0L) {
                        ((entry.durationMs + 30_000L) / 60_000L)
                            .toInt()
                            .coerceAtLeast(1)
                    } else {
                        null
                    },
                streamUrl = entry.streamUrl,
                parentId = entry.parentId.ifBlank { entry.id },
                parentType = entry.type,
                season = entry.season,
                episode = namedEpisodeNumber(entry.episode),
                // A stream id keys an episode. When the row's episode is the
                // source's 0, there is no episode for it to key, so it is
                // dropped with the number rather than opening a phantom one.
                episodeStreamId = entry.episodeStreamId
                    .takeIf { namedEpisodeNumber(entry.episode) != null },
                startPositionMs = entry.positionMs,
                recencyTimestamp = entry.updatedAt,
                historyRowId = entry.id
            )
        }

        // Only seed when nothing is showing yet (cold start / fresh entry);
        // never clobber a live enriched list with the raw snapshot.
        if (_upNext.value.isEmpty()) {
            _upNext.value = applyContinueWatchingDismissals(
                dedupeAndSortUpNext(
                    collapseInstantSnapshotItems(items)
                )
            )
        }
    }

    /**
     * Simkl-backed cards from the currently displayed rail (historyRowId is
     * null exactly for items built from the Simkl feed). During a refresh
     * the local-first publish used to REPLACE the whole list, so every
     * Simkl card vanished for the seconds-to-minutes the slow Simkl+TMDB
     * re-merge needed after the watched-state caches were cleared on
     * resume. Carrying them over keeps the rail stable: full list -> full
     * list (locally refreshed) -> full list (enriched merge).
     */
    private fun previousSimklUpNextItems(): List<UpNextItem> =
        _upNext.value.filter { it.historyRowId == null }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeUpNext() {

        viewModelScope.launch {

            // Key the subscription on active-profile + refresh trigger, and
            // RE-RESOLVE the Room flow every cycle (flatMapLatest): a Room
            // flow is bound to the DB instance it was created from, so the
            // old captured-Flow design kept listening to the previous
            // profile's closed database after a switch / first-profile
            // creation. Re-subscribing picks up the active profile's DB -
            // including cloud rows pulled by SupabaseSync.onProfileSwitched.
            combine(
                com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile,
                _refreshTrigger
            ) { _, _ -> }
                .flatMapLatest {
                    watchHistoryRepository.continueWatchingParentsFlow()
                }
                .debounce(UP_NEXT_DEBOUNCE_MS)
                .collectLatest { rawHistory ->

                    // Continue Watching follows the furthest point watched:
                    // drop in-progress rows a later completed episode has
                    // overtaken (see supersededResumeRowIds). They would
                    // otherwise win the rail and strand the show on an
                    // abandoned episode.
                    val history =
                        dropSupersededResumeRows(rawHistory)

                    val requestVersion =
                        nextUpNextRequestVersion()

                    try {

                    val lookupSemaphore =
                        Semaphore(MAX_CONCURRENT_UP_NEXT_LOOKUPS)

                    val localItems =
    coroutineScope {
    // Local continue-watching rows are already unfinished resume rows,
    // including movies that were stopped before completion.
    history.map { entry ->
        async {
            lookupSemaphore.withPermit {

        val isEpisodePlayback =
            entry.season != null && entry.episode != null

        // A series row is a series whatever it says about one episode, and the
        // app must not read "season 2, episode 0" as "not an episode
        // playback". Such a row used to fall through this builder's whole TMDB
        // path: the next episode was never resolved, the watched count came
        // out as 0, and the rail showed "S02" over "0 of 310 aired episodes
        // watched" for a show the viewer is deep into. Rows that carry an
        // episode pair are still resolved whatever their type - an
        // anime-typed row is still an episode - so this only widens the set.
        val isSeriesRow =
            upNextMediaType(entry.type) == "series"

        val resolvesAsSeries =
            isSeriesRow || isEpisodePlayback

        // The row's episode through the same 0-is-not-an-episode rule the card
        // prints with (see EpisodeNumbering).
        val namedRowEpisode =
            namedEpisodeNumber(entry.episode)

        var episodeRating: Double? = null
        var episodeThumbnail: String? = null
        var backdropUrl: String? = entry.backdropUrl

        // Name resolved from TMDB, used when the stored row's name is missing
        // or is an internal id (see [upNextDisplayTitleOrNull]).
        var resolvedLocalName: String? = null

        // Movie resume rows can predate backdrop persistence or come from a
        // caller that only supplied a poster. Restore the artwork from the
        // cached TMDB metadata so resume cards keep their movie identity.
        if (!isEpisodePlayback && backdropUrl.isNullOrBlank()) {
            val restoredBackdrop = runCatchingCancellable {
                tmdbRepository.fetchEnrichedMetaCached(
                    entry.parentId.trim().ifBlank { entry.id.trim() },
                    entry.type
                )?.backdropPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "https://image.tmdb.org/t/p/w780$it" }
            }.getOrNull()
            if (!restoredBackdrop.isNullOrBlank()) {
                historyDao.updateBackdropIfMissing(entry.id, restoredBackdrop)
            }
            backdropUrl = restoredBackdrop
        }

        // Cache completed episode keys per show so we only query the
        // DAO once per parentId instead of once per history row. Read below,
        // once the show's TMDB id is known: the read is flavor-tolerant (see
        // localHistoryParentIdsForShow), so it needs the id twins.
        var localCompletedForParent: Set<Pair<Int, Int>> = emptySet()

        // Whole-show watched/total computed from TMDB aired episodes. Stay
        // null when we can't resolve TMDB so we fall back to the stored
        // per-season total below.
        var tmdbEpisodeTotals: ShowEpisodeTotals? = null
        var tmdbEpisodesRemaining: Int? = null

        // "Next episode to air" captured from the TMDB detail resolved
        // below; threaded onto the built UpNextItem so the Upcoming rail
        // can show it without any extra network calls.
        var capturedNextEpisodeAir: TmdbEpisodeAirInfo? = null

        // TMDB show id resolved with the detail (for the Upcoming title
        // fallback, which looks the next episode up in the season cache).
        var capturedTmdbId: Int? = null

        // Finale flags derived from the shared-watched-state resolution
        // below; default false so movies / unresolvable titles stay plain
        // RESUME cards.
        var localSeasonFinale =
            false

        var localSeriesFinale =
            false

        // The episode the show resolves to continue at; used to complete the
        // pair a card prints when the row itself names no episode.
        var resolvedSeriesTarget: ResolvedHomeSeriesTarget? = null

        if (resolvesAsSeries) {
            try {
                val parentId =
                    entry.parentId
                        .trim()
                        .ifBlank { entry.id.trim() }

                val tmdbDetail =
                    when {
                        parentId.startsWith("tmdb:", ignoreCase = true) -> {
                            parentId
                                .substringAfter(":")
                                .toIntOrNull()
                                ?.let { tmdbId ->
                                    tmdbRepository.getDetailByTmdbId(
                                        tmdbId,
                                        entry.type
                                    )
                                }
                        }

                        parentId.startsWith("tt", ignoreCase = true) -> {
                            tmdbRepository.fetchEnrichedMetaCached(
                                parentId,
                                entry.type
                            )
                        }

                        parentId.toIntOrNull() != null -> {
                            tmdbRepository.getDetailByTmdbId(
                                parentId.toInt(),
                                entry.type
                            )
                        }

                        else -> {
                            null
                        }
                    }

                localCompletedForParent =
                    try {
                        historyDao
                            .getCompletedForParents(
                                localHistoryParentIds(
                                    parentId,
                                    tmdbDetail?.id ?: 0
                                )
                            )
                            .mapNotNull { e ->
                                e.season?.let { s -> e.episode?.let { ep -> s to ep } }
                            }
                            .toSet()
                    } catch (_: Exception) {
                        emptySet()
                    }

                // Only replace stored art with art that actually resolved: a
                // failed detail lookup must not blank a card that already had a
                // backdrop.
                tmdbDetail?.backdropPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let { backdropUrl = "https://image.tmdb.org/t/p/w780$it" }

                resolvedLocalName =
                    upNextDisplayTitleOrNull(
                        tmdbDetail?.name,
                        !entry.poster.isNullOrBlank()
                    )
                        ?: upNextDisplayTitleOrNull(
                            tmdbDetail?.title,
                            !entry.poster.isNullOrBlank()
                        )

                // Capture the show's next aired episode for the Upcoming
                // rail — the detail response is already in hand here.
                capturedNextEpisodeAir = tmdbDetail?.nextEpisodeToAir
                capturedTmdbId = tmdbDetail?.id

                val tmdbId = tmdbDetail?.id

                if (tmdbId != null && tmdbId > 0) {
                    // Count watched/total/remaining through the SAME shared-watched-state
                    // mechanism the SIMKL path uses (the one that correctly renders
                    // "X of Y episodes watched"), so local history stays consistent with
                    // SIMKL across the hero and the continue-watching cards.
                    preloadWatchedEpisodeStateForShow(
                        parentId = parentId,
                        tmdbShowId = tmdbId
                    )
                    val cachedTotals = showEpisodeTotalsCache[tmdbId]

                    // Resolve the show's continue point when no earlier row of
                    // this show has walked it in this pass, and whenever THIS
                    // row names no episode: the card has to be able to print
                    // one, and the counted totals come from the shared watched
                    // state the resolution builds. A row that says "season 2,
                    // no episode" therefore resolves to the next unwatched
                    // episode of season 2 instead of falling through with no
                    // episode and a watched count of zero.
                    val needsResolvedEpisode =
                        namedRowEpisode == null || entry.season == null

                    val target =
                        if (cachedTotals == null || needsResolvedEpisode) {
                            resolveSeriesTargetFromSharedWatchedState(
                                parentId = parentId,
                                tmdbId = tmdbId,
                                simklSeason = entry.season,
                                simklEpisode = entry.episode
                            )
                        } else {
                            null
                        }

                    if (target != null) {
                        resolvedSeriesTarget = target
                        localSeasonFinale =
                            target.isSeasonFinale
                        localSeriesFinale =
                            target.isSeriesFinale
                        tmdbEpisodesRemaining = target.episodesRemaining
                    }

                    tmdbEpisodeTotals =
                        target
                            ?.let { resolved ->
                                resolved.episodesWatched?.let { watched ->
                                    resolved.episodesTotal?.let { total ->
                                        ShowEpisodeTotals(watched, total)
                                    }
                                }
                            }
                            ?: cachedTotals

                    tmdbEpisodeTotals?.let { totals ->
                        showEpisodeTotalsCache[tmdbId] = totals
                    }
                }

                // The rating and still exist only for a row that names a real
                // episode; a season-only series row has none to look up, and
                // its episode (and totals) already came from the resolution
                // above.
                if (
                    tmdbId != null &&
                    tmdbId > 0 &&
                    entry.season != null &&
                    namedRowEpisode != null
                ) {
                    // Single season lookup gives us both the episode's
                    // rating and its still image, instead of firing a
                    // second redundant getEpisodeRating call for the
                    // same season detail.
                    val matchedEpisode =
                        tmdbRepository.getSeasonEpisodes(
                            tvId = tmdbId,
                            season = entry.season,
                            imdbId = parentId
                        ).firstOrNull {
                            it.episodeNumber == namedRowEpisode
                        }

                    episodeRating =
                        matchedEpisode
                            ?.voteAverage
                            ?.takeIf { it > 0.0 }

                    episodeThumbnail =
                        matchedEpisode?.thumbnail
                }

                Log.d(
                    "HOME_UPNEXT",
                    "Episode rating: ${entry.name} " +
                        "S${entry.season}E${entry.episode} " +
                        "tmdbId=$tmdbId rating=$episodeRating"
                )

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(
                    "HOME_UPNEXT",
                    "Failed to resolve episode rating for ${entry.name}",
                    e
                )
            }
        }

        val localWatchedCount =
            tmdbEpisodeTotals?.watched
                ?: localCompletedForParent.size

        // Whole-show total from the shared SIMKL-style TMDB count; fall back to the
        // stored per-season total only when that isn't resolvable.
        val localEpisodesTotal: Int? =
            tmdbEpisodeTotals?.total
                ?: entry.totalEpisodesInSeason

        val localEpisodesRemaining: Int? =
            tmdbEpisodesRemaining
                ?: localEpisodesTotal?.let { (it - localWatchedCount).coerceAtLeast(0) }

        // The pair the card prints: the row's own season/episode, with the
        // episode filled from the show's resolved continue point when the row
        // names none (see upNextCardEpisodePair).
        val (cardSeason, cardEpisode) =
            upNextCardEpisodePair(
                rowSeason = entry.season,
                rowEpisode = entry.episode,
                resolvedSeason = resolvedSeriesTarget?.season,
                resolvedEpisode = resolvedSeriesTarget?.episode
            )

        val cardEpisodeTitle =
            entry.episodeTitle
                ?.takeIf { it.isNotBlank() }
                ?: resolvedSeriesTarget?.episodeTitle

        val cardEpisodeDescription =
            entry.overview
                ?.takeIf { it.isNotBlank() }
                ?: resolvedSeriesTarget?.episodeDescription

        // A stream id keys an episode; the row's own is kept only when the row
        // named one, otherwise the resolved episode's stream opens the right
        // thing.
        val cardEpisodeStreamId =
            entry.episodeStreamId
                .takeIf { namedRowEpisode != null }
                ?: resolvedSeriesTarget?.streamId

        UpNextItem(
            id = buildString {
                append("history:")
                append(entry.id)
                entry.season?.let { append(":s$it") }
                entry.episode?.let { append(":e$it") }
            },

            title =
                upNextDisplayTitleOrNull(
                    entry.name,
                    !entry.poster.isNullOrBlank() || !backdropUrl.isNullOrBlank()
                )
                    ?: resolvedLocalName
                    // No name at all: skip this card. The same show's enriched
                    // twin (Simkl / MDBList) still gets one, and the row stays
                    // resumable from Detail - a card titled with an internal
                    // id is worse than no card.
                    ?: return@async null,
            poster = entry.poster,
            badge = UpNextBadge.CONTINUE_WATCHING,

            showTitle =
                if (resolvesAsSeries) entry.name else null,

            episodeTitle = cardEpisodeTitle,
            episodeDescription = cardEpisodeDescription,
            episodesWatched = localWatchedCount.takeIf { it > 0 },
            episodesTotal = localEpisodesTotal,
            episodesRemaining = localEpisodesRemaining,

            // Episode-specific TMDB rating.
            // Your UI currently calls this field imdbRating.
            tmdbRating = null,
            imdbRating =
                episodeRating ?: resolvedSeriesTarget?.episodeRating,
            episodeThumbnail =
                episodeThumbnail ?: resolvedSeriesTarget?.episodeThumbnail,
            backdrop = backdropUrl,
            clearLogo = entry.clearLogo,

            runtimeMinutes =
                if (entry.durationMs > 0L) {
                    ((entry.durationMs + 30_000L) / 60_000L)
                        .toInt()
                        .coerceAtLeast(1)
                } else {
                    null
                },

            remainingMinutes =
                calculateRemainingMinutes(
                    positionMs = entry.positionMs,
                    durationMs = entry.durationMs
                ),

            subtitle = null,

            progressPercent =
                progressFromHistory(
                    positionMs = entry.positionMs,
                    durationMs = entry.durationMs
                ),

            streamUrl = entry.streamUrl,

            parentId =
                entry.parentId.ifBlank { entry.id },

            parentType = entry.type,

            season = cardSeason,
            episode = cardEpisode,
            // Dropped with the number: a stream id keys an episode, and the
            // source's 0 names none, so keeping it only opened a phantom.
            episodeStreamId = cardEpisodeStreamId,

            startPositionMs = entry.positionMs,
            recencyTimestamp = entry.updatedAt,
            historyRowId = entry.id,

            isSeasonFinale =
                localSeasonFinale,

            isSeriesFinale =
                localSeriesFinale,

            nextEpisodeAir = capturedNextEpisodeAir,

            tmdbId = capturedTmdbId
        )
            }
        }
    }.awaitAll().filterNotNull()
}

                        // Shows this pass proves are finished locally, in the
                        // rail's own identity vocabulary. Tracked so a tracker
                        // card for one of them can be dropped below without
                        // waiting for the tracker feed to catch up.
                        //
                        // Deliberately NOT ConcurrentHashMap.newKeySet(): its
                        // KeySetView is API 24, and this app ships minSdk 23,
                        // so on a Fire OS 6 / Android 6 box the call would
                        // NoSuchMethodError the moment the Continue Watching
                        // rail built (and it failed Android Lint's NewApi
                        // check on every other build). A synchronized wrapper
                        // is API 1 and is enough for this set, which is only
                        // added to and emptiness-checked - never iterated
                        // while its concurrent writers are still running.
                        val locallyFinishedShowKeys =
                            java.util.Collections.synchronizedSet(HashSet<String>())

                        fun withoutLocallyFinishedTrackerCards(
                            items: List<UpNextItem>
                        ): List<UpNextItem> =
                            if (locallyFinishedShowKeys.isEmpty()) {
                                items
                            } else {
                                items.filterNot { item ->
                                    trackerCardLocallyFinished(
                                        item,
                                        locallyFinishedShowKeys
                                    )
                                }
                            }

                        // A series with watched episodes but no in-progress row
                        // still has unwatched aired episodes, so it belongs on the
                        // rail pointing at the next one. Marking episodes watched
                        // (or finishing them) removes the resume rows the rail is
                        // built from, which dropped a show with plenty left to
                        // watch. Caught-up shows resolve to no next episode and are
                        // still kept off the rail - and their names are recorded
                        // so their tracker twins leave too.
                        val localCards =
                            localItems +
                                loadLocalNextUpItems(
                                    localItems,
                                    locallyFinishedShowKeys
                                )

                        // Publish local cards first: the enriched local rows
                        // are ready here, so the rail shows real content while
                        // the (potentially slow) Simkl network fetch runs.
                        // MERGE with the Simkl cards still on screen instead of
                        // replacing the list — replacing it made every Simkl
                        // card vanish on each Home resume until the slow
                        // re-merge finished. applyContinueWatchingDismissals
                        // still filters anything the user removed, so a
                        // removed title cannot linger through the carry-over.
                        // A profile switch clears the rail and bumps the
                        // request version, so a build that was already past
                        // its last suspension when the switch landed can
                        // still be holding the rows of the profile the user
                        // just left (its history DB stays readable through
                        // the retire grace period). Gate this publish
                        // exactly like the merged one below.
                        if (
                            localCards.isNotEmpty() &&
                            isLatestUpNextRequest(requestVersion)
                        ) {
                            _upNext.value =
                                applyContinueWatchingDismissals(
                                    dedupeAndSortUpNext(
                                        localCards +
                                            withoutLocallyFinishedTrackerCards(
                                                previousSimklUpNextItems()
                                            )
                                    )
                                )
                            // Warm hero art for the resume rows too: their
                            // previews often carry only a poster, so without
                            // a prefetch the hero flashes the poster as a
                            // zoomed backdrop when the user scrolls up.
                            prefetchHeroArt(
                                _upNext.value.mapNotNull { up ->
                                    up.parentId?.let { parentId ->
                                        MetaPreview(
                                            id = parentId,
                                            type = up.parentType ?: "movie",
                                            name = up.title,
                                            poster = up.poster,
                                            background = up.backdrop,
                                            logo = up.clearLogo
                                        )
                                    }
                                }
                            )
                        }

                        val simklResult =
                            loadSimklUpNextItems()

                        if (simklResult is SimklUpNextResult.Failed) {

                            Log.w(
                                "HOME_UPNEXT",
                                "SIMKL failed; preserving existing Up Next data",
                                simklResult.error
                            )

                            if (
                                isLatestUpNextRequest(requestVersion)
                            ) {

                                // Simkl is unreachable: keep the PREVIOUS
                                // Simkl cards on screen (stale data beats a
                                // rail that empties out on every resume)
                                // rather than dropping them. Removals are
                                // still honored by applyContinueWatching
                                // Dismissals.
                                val mdbListItems =
                                    loadMdbListUpNextItems()

                                _upNext.value =
                                    applyContinueWatchingDismissals(
                                        dedupeAndSortUpNext(
                                            localCards +
                                                withoutLocallyFinishedTrackerCards(
                                                    previousSimklUpNextItems() +
                                                        mdbListItems
                                                )
                                        )
                                    )
                            }

                            return@collectLatest
                        }

                        val simklItems =
                            when (simklResult) {
                                is SimklUpNextResult.Success -> simklResult.items
                                SimklUpNextResult.NotConfigured -> emptyList()
                                is SimklUpNextResult.Failed -> emptyList()
                            }

                        // Paused MDBList sessions merge alongside the Simkl
                        // cards; dedupe keeps the richer local/Simkl twin.
                        val mdbListItems =
                            loadMdbListUpNextItems()

                        val merged =
                            dedupeAndSortUpNext(
                                localCards +
                                    withoutLocallyFinishedTrackerCards(
                                        simklItems + mdbListItems
                                    )
                            )

                        if (!isLatestUpNextRequest(requestVersion)) {
                            return@collectLatest
                        }

                        val result =
                            if (merged.isNotEmpty()) {

                                merged

                            } else if (localCards.isNotEmpty()) {

                                dedupeAndSortUpNext(
                                    localCards
                                )

                            } else {

                                emptyList()
                            }

                        _upNext.value =
                            applyContinueWatchingDismissals(
                                result
                            )

                    } catch (e: kotlinx.coroutines.CancellationException) {

                        throw e

                    } catch (e: Exception) {

                        Log.e(
                            "HOME_UPNEXT",
                            "observeUpNext failed: ${e.message}",
                            e
                        )
                    }
                }
        }
    }

    /**
     * Drops in-progress rows a LATER completed episode has already overtaken
     * (see [supersededResumeRowIds]). Continue Watching must follow the
     * furthest point watched - a stale partial row from an early episode must
     * not win the rail while the user is many episodes ahead. On any read
     * failure the rows are returned untouched (show the old behavior rather
     * than hide a show).
     */
    private suspend fun dropSupersededResumeRows(
        resumeRows: List<WatchHistoryEntity>
    ): List<WatchHistoryEntity> {
        if (resumeRows.isEmpty()) return resumeRows
        val completedRows =
            try {
                historyDao.getCompletedSeriesRows()
            } catch (_: Exception) {
                return resumeRows
            }
        if (completedRows.isEmpty()) return resumeRows
        val superseded = supersededResumeRowIds(resumeRows, completedRows)
        if (superseded.isEmpty()) return resumeRows
        Log.d(
            "HOME_UPNEXT",
            "Dropping ${superseded.size} superseded resume row(s)"
        )
        return resumeRows.filterNot { it.id in superseded }
    }

    private suspend fun nextUpNextRequestVersion(): Long {

        return upNextRequestMutex.withLock {

            upNextRequestVersion += 1

            upNextRequestVersion
        }
    }

    private suspend fun isLatestUpNextRequest(
        requestVersion: Long
    ): Boolean {

        return upNextRequestMutex.withLock {

            requestVersion ==
                upNextRequestVersion
        }
    }

    private suspend fun loadSimklUpNextItems():
        SimklUpNextResult {

        if (
            !simklRepository.isConfigured() ||
            !simklRepository.hasToken()
        ) {

            return SimklUpNextResult.NotConfigured
        }

        return try {                val raw =
                simklRepository
                    .getContinueWatching()

            val lookupSemaphore =
                Semaphore(
                    MAX_CONCURRENT_SIMKL_UP_NEXT_LOOKUPS
                )

            val items =
                coroutineScope {

                    raw
                        .filter { item ->
                            !item.mediaType.trim().equals("movie", ignoreCase = true) ||
                                (item.source == "playback" && (item.progress ?: 0f) > 0f)
                        }
                        .map { item ->

                        async {

                            lookupSemaphore.withPermit {

                                buildSimklUpNextItem(
                                    item
                                )
                            }
                        }
                    }
                        .awaitAll()
                        .filterNotNull()
                }

            Log.d(
                "HOME_UPNEXT",
                "SIMKL load succeeded: " +
                    "raw=${raw.size}, " +
                    "resolved=${items.size}"
            )

            SimklUpNextResult.Success(
                items
            )

        } catch (
            e: kotlinx.coroutines.CancellationException
        ) {

            throw e

        } catch (e: Exception) {

            Log.e(
                "HOME_UPNEXT",
                "SIMKL load failed: ${e.message}",
                e
            )

            SimklUpNextResult.Failed(
                e
            )
        }
    }

    /**
     * Paused MDBList playback sessions (GET /sync/playback), shaped for the
     * same Continue Watching merge as the Simkl cards. Runs alongside the
     * Simkl loader when an MDBList key is set, so progress paused on either
     * tracker (including another device) surfaces here.
     */
    private suspend fun loadMdbListUpNextItems(): List<UpNextItem> {
        val appContext = getApplication<Application>()

        if (!MdbListClient.isConfigured(appContext)) {
            return emptyList()
        }

        return try {
            val sessions =
                runCatchingCancellable { MdbListClient.getPlaybackSessions(appContext) }
                    .getOrDefault(emptyList())

            // Resolved concurrently, bounded like the local next-up pass:
            // each card now resolves the show's watched/total counts, so a
            // serial pass would put up to MAX_MDBLIST_UP_NEXT_ITEMS season
            // walks end to end on Home's critical path.
            val semaphore =
                Semaphore(MDBLIST_UP_NEXT_CONCURRENCY)

            val items =
                coroutineScope {
                    sessions
                        .take(MAX_MDBLIST_UP_NEXT_ITEMS)
                        .map { session ->
                            async {
                                semaphore.withPermit {
                                    runCatchingCancellable {
                                        buildMdbListUpNextItem(
                                            appContext,
                                            session
                                        )
                                    }.getOrNull()
                                }
                            }
                        }
                        .toList()
                        .awaitAll()
                        .filterNotNull()
                }

            Log.d(
                "HOME_UPNEXT",
                "MDBList load succeeded: raw=${sessions.size}, resolved=${items.size}"
            )

            items
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HOME_UPNEXT", "MDBList load failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Builds one Continue Watching card from a paused MDBList session.
     * Drops sessions whose episode is already completed locally (the stale
     * 99%-watched leftover the completion push created), mirrors the same
     * guard the Simkl playback path uses. The TMDB lookup reuses the same
     * enrichment cache the Simkl cards use.
     */
    private suspend fun buildMdbListUpNextItem(
        appContext: Context,
        session: MdbListPlaybackItem
    ): UpNextItem? {

        val navigationId = session.imdbId
            ?: session.tmdbId?.let { "tmdb:$it" }
            ?: return null

        val isExplicitResume = session.progress > 0.0

        if (!session.isMovie &&
            session.season != null &&
            session.episode != null
        ) {
            val parentIds = listOfNotNull(
                session.imdbId,
                session.tmdbId?.let { "tmdb:$it" }
            )
            val stale = parentIds.any { parentId ->
                runCatchingCancellable {
                    historyDao
                        .getCompletedForParent(parentId)
                        .any { row ->
                            row.season == session.season &&
                                row.episode == session.episode
                        }
                }.getOrDefault(false)
            }
            if (stale) {
                // Server-side mirror of the local drop: the completion
                // push already fired, so the leftover paused session would
                // otherwise resurface forever.
                runCatchingCancellable {
                    MdbListClient.scrobbleClear(
                        appContext,
                        isMovie = false,
                        imdbId = session.imdbId,
                        tmdbId = session.tmdbId,
                        season = session.season,
                        episode = session.episode
                    )
                }
                return null
            }
        }

        var posterUrl: String? = null
        var backdropUrl: String? = null
        var showTitle: String? = null
        var episodeTitle: String? = null
        var runtimeMinutes: Int? = session.runtimeMinutes.takeIf { it > 0 }
        var resolvedSeason = session.season
        var resolvedEpisode = session.episode
        var resolvedStartPositionMs = 0L

        val detail = runCatchingCancellable {
            tmdbLookupSemaphore.withPermit {
                tmdbRepository.fetchEnrichedMetaCached(
                    navigationId,
                    if (session.isMovie) "movie" else "series"
                )
            }
        }.getOrNull()

        if (detail != null) {
            posterUrl = detail.posterPath
                ?.takeIf { it.isNotBlank() }
                ?.let { "${TmdbRepository.POSTER_BASE}$it" }
            backdropUrl = detail.backdropPath
                ?.takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/w780$it" }
            if (!session.isMovie) {
                showTitle = detail.name
                runtimeMinutes = detail.displayRuntimeMinutes()
                    ?: runtimeMinutes
            }
        }

        if (!session.isMovie) {
            resolvedSeason = session.season ?: 1
            // A session the tracker could not place carries no episode, and
            // inventing E01 for it read as confidently as a real number. Leave
            // it unnamed so the label falls back to the season alone.
            resolvedEpisode = namedEpisodeNumber(session.episode)
        }

        // The show's watched/total aired counts, its finale flags and the
        // episode the watched state points at, from the resolver the local and
        // Simkl cards already use. Without it an MDBList card carried no
        // episode counts, so its hero showed no "X of Y aired episodes
        // watched" line and it carried no next-episode-to-air either - which is
        // also what kept these cards out of the Upcoming rail. A viewer whose
        // tracker is MDBList had thinner cards than a Simkl one for no reason.
        //
        // Counts are wired only when THIS device has watched something of the
        // show. The shared watched state is this profile's own completed rows
        // plus Simkl's (see preloadWatchedEpisodeStateForShow) - MDBList's
        // watched history is deliberately not in it - so resolving a show the
        // device has never played would report "0 of 24 aired episodes
        // watched" over a viewer who is five episodes in on another TV. No
        // evidence means no line, which is exactly what these cards showed
        // before.
        var episodesWatched: Int? = null
        var episodesTotal: Int? = null
        var episodesRemaining: Int? = null
        var targetIsSeasonFinale = false
        var targetIsSeriesFinale = false

        val tmdbShowId = detail?.id ?: session.tmdbId ?: 0

        val hasLocalProgress =
            !session.isMovie &&
                tmdbShowId > 0 &&
                runCatchingCancellable {
                    historyDao
                        .getCompletedForParents(
                            localHistoryParentIds(
                                navigationId,
                                tmdbShowId
                            )
                        )
                        .isNotEmpty()
                }.getOrDefault(false)

        val resolvedTarget =
            if (hasLocalProgress) {
                preloadWatchedEpisodeStateForShow(
                    parentId = navigationId,
                    tmdbShowId = tmdbShowId
                )

                resolveSeriesTargetFromSharedWatchedState(
                    parentId = navigationId,
                    tmdbId = tmdbShowId,
                    simklSeason = null,
                    simklEpisode = null
                )
            } else {
                null
            }

        if (resolvedTarget != null) {
            episodesWatched = resolvedTarget.episodesWatched
            episodesTotal = resolvedTarget.episodesTotal
            episodesRemaining = resolvedTarget.episodesRemaining
            targetIsSeasonFinale = resolvedTarget.isSeasonFinale
            targetIsSeriesFinale = resolvedTarget.isSeriesFinale
            episodeTitle = episodeTitle ?: resolvedTarget.episodeTitle
        }

        // The finale tags and the arrival chip describe the episode the CARD
        // shows, so the resolved target only speaks for them when the target IS
        // that episode - a session behind the watched frontier would otherwise
        // tag this card with another episode's finale.
        val targetIsCardEpisode =
            resolvedTarget != null &&
                resolvedTarget.season == resolvedSeason &&
                resolvedTarget.episode == resolvedEpisode

        // One shared rule, so a viewer without Simkl sees the same arrival
        // chips the Simkl cards have always had (see upNextArrivalBadge). A
        // film can never be a new season or a new episode.
        val badge =
            if (session.isMovie) {
                UpNextBadge.CONTINUE_WATCHING
            } else {
                upNextArrivalBadge(
                    isResume = isExplicitResume,
                    airDate =
                        resolvedTarget?.airDate
                            ?.takeIf { targetIsCardEpisode },
                    episode = resolvedEpisode
                )
            }

        val progressFraction =
            (session.progress / 100.0).coerceIn(0.0, 1.0)

        val durationMs = runtimeMinutes
            ?.takeIf { it > 0 }
            ?.times(60_000L)
            ?: 0L
        resolvedStartPositionMs =
            (durationMs * progressFraction).toLong()

        // The action word follows the badge, so the chip and the line under
        // the title cannot contradict each other ("NEW SEASON" over "Resume -
        // S2E1" was what a hardcoded prefix produced).
        val subtitle =
            if (session.isMovie) {
                "Resume movie"
            } else {
                upNextTrackerSubtitle(
                    prefix = upNextBadgePrefix(badge),
                    season = resolvedSeason,
                    episode = resolvedEpisode,
                    fallback = null
                )
            }

        // Never fall back to the navigation id, and never accept one as the
        // name either: "tmdb:12345" is not a title. The tracker's own title
        // field carries a raw id when ITS enrichment failed, which is also why
        // these cards arrive with no poster - a blank tile with an internal id
        // on it, sitting next to the same show's real card.
        val hasArtwork = !posterUrl.isNullOrBlank()
        val displayTitle =
            upNextDisplayTitleOrNull(detail?.name, hasArtwork)
                ?: upNextDisplayTitleOrNull(detail?.title, hasArtwork)
                ?: upNextDisplayTitleOrNull(session.title, hasArtwork)
                ?: return null

        return UpNextItem(
            id = "mdblist:${session.sessionId}",
            title = displayTitle,
            poster = posterUrl,
            badge = badge,
            showTitle = if (session.isMovie) null
            else showTitle ?: displayTitle,
            episodeTitle = episodeTitle,
            episodesWatched = episodesWatched,
            episodesTotal = episodesTotal,
            episodesRemaining = episodesRemaining,
            tmdbRating = detail?.voteAverage?.takeIf { it > 0.0 },
            runtimeMinutes = runtimeMinutes,
            remainingMinutes = calculateRemainingMinutesFromProgress(
                runtimeMinutes = runtimeMinutes,
                progress = session.progress.toFloat()
            ),
            subtitle = subtitle,
            progressPercent = if (isExplicitResume) {
                progressFraction.toFloat()
            } else {
                null
            },
            parentId = navigationId,
            parentType = if (session.isMovie) "movie" else "series",
            season = if (session.isMovie) null else resolvedSeason,
            episode = if (session.isMovie) null else resolvedEpisode,
            startPositionMs = resolvedStartPositionMs,
            recencyTimestamp = session.updatedAtMs,
            backdrop = backdropUrl,
            isSeasonFinale = targetIsCardEpisode && targetIsSeasonFinale,
            isSeriesFinale = targetIsCardEpisode && targetIsSeriesFinale,
            // The show's next UNAIRED episode, so this card can reach the
            // Upcoming rail on the same terms as a local or Simkl one.
            nextEpisodeAir = detail?.nextEpisodeToAir,
            tmdbId = tmdbShowId.takeIf { it > 0 } ?: session.tmdbId,
            playbackId = null
        )
    }

    private suspend fun buildSimklUpNextItem(
        item: SimklContinueWatchingItem
    ): UpNextItem? {

        val navigationId =
            item.imdbId
                ?: item.tmdbId?.let {
                    "tmdb:$it"
                }
                ?: item.simklId?.let {
                    "simkl:$it"
                }

        if (
            navigationId.isNullOrBlank()
        ) {
            return null
        }

        var posterUrl =
            item.posterUrl

        // Name resolved from TMDB, used when the tracker's own title is
        // missing or is an internal id (see [upNextDisplayTitleOrNull]).
        var resolvedName: String? = null

        val recencyTimestamp =
            parseTimestampMillis(
                item.lastWatchedAt
            )

        val isExplicitResume =
            item.source == "playback" &&
                (item.progress ?: 0f) > 0f

        var badge =
            if (isExplicitResume) {
                UpNextBadge.CONTINUE_WATCHING
            } else {
                UpNextBadge.NEXT_UP
            }

        // Nullable: the resolution below can replace it with the badge's pair,
        // and a card whose pair is unknown keeps the tracker's own line (see
        // upNextTrackerSubtitle).
        var subtitle: String? =
            buildSimklSubtitle(
                item,
                isExplicitResume
            )

        var showTitle: String? = null
        var episodeTitle: String? = null
        var episodeDescription: String? = null
        var tmdbRating: Double? = null
        var episodeThumbnail: String? = null
        var episodeRating: Double? = null
        var backdropUrl: String? = null
        var runtimeMinutes: Int? = null
        var episodesWatched: Int? = null
var episodesTotal: Int? = null
        var episodesRemaining: Int? = null

        var resolvedSeason =
            item.season

        var resolvedEpisode =
            item.episode

        var resolvedStreamId:
            String? = null

        var resolvedStartPositionMs =
            0L

        // Finale flags from the resolved target (last aired episode of its
        // season / of the show). Used to tag the card SEASON/SERIES FINALE
        // instead of the plain resume/next-up tags.
        var targetIsSeasonFinale =
            false

        var targetIsSeriesFinale =
            false

        val needsTmdbLookup =
            true

        // "Next episode to air" captured from the TMDB detail fetched
        // below; threaded onto the built UpNextItem for the Upcoming rail.
        var capturedNextAirInfo: TmdbEpisodeAirInfo? = null

        // TMDB show id resolved with the detail (Upcoming title fallback).
        var capturedTmdbId: Int? = null

        if (needsTmdbLookup) {

            val detail =
                try {

                    tmdbLookupSemaphore
                        .withPermit {

                            tmdbRepository
                                .fetchEnrichedMetaCached(
                                    navigationId,
                                    item.mediaType
                                )
                        }

                } catch (_: Exception) {
                    null
                }

            // Capture the next aired episode for the Upcoming rail — the
            // detail response is already fetched here.
            capturedNextAirInfo = detail?.nextEpisodeToAir
            capturedTmdbId = detail?.id

            if (
                posterUrl.isNullOrBlank()
            ) {

                posterUrl =
                    detail
                        ?.posterPath
                        ?.let {
                            "${TmdbRepository.POSTER_BASE}$it"
                        }
            }

            // Populate display metadata from TMDB.
            if (detail != null) {

                backdropUrl =
                    detail.backdropPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "https://image.tmdb.org/t/p/w780$it" }

                resolvedName =
                    upNextDisplayTitleOrNull(
                        detail.name,
                        !posterUrl.isNullOrBlank()
                    )
                        ?: upNextDisplayTitleOrNull(
                            detail.title,
                            !posterUrl.isNullOrBlank()
                        )

                showTitle =
                    if (item.mediaType == "series") {
                        detail.name
                    } else {
                        null
                    }

                tmdbRating =
                    detail.voteAverage
                        ?.takeIf {
                            it > 0.0
                        }

                runtimeMinutes =
                    detail.displayRuntimeMinutes()
            }

            if (
                item.mediaType == "series" &&
                (
                    detail?.id != null ||
                        !item.imdbId.isNullOrBlank()
                    )
            ) {

                val showLookupKey =
                    item.imdbId
                        ?: navigationId

                val numericTmdbId =
                    detail?.id ?: 0

                preloadWatchedEpisodeStateForShow(
                    parentId =
                        showLookupKey,

                    tmdbShowId =
                        numericTmdbId
                )

                val resolvedTarget =
                    resolveSeriesTargetFromSharedWatchedState(
                        parentId =
                            showLookupKey,

                        tmdbId =
                            numericTmdbId,

                        simklSeason =
                            item.season,

                        simklEpisode =
                            item.episode
                    )

                if (
                    resolvedTarget != null
                ) {

                    resolvedSeason =
                        resolvedTarget.season

                    resolvedEpisode =
                        resolvedTarget.episode

                    resolvedStreamId =
                        resolvedTarget.streamId

                    resolvedStartPositionMs =
                        resolvedTarget.startPositionMs

         runtimeMinutes =
    resolvedTarget.runtimeMinutes
        ?: detail?.displayRuntimeMinutes()
        
        episodesRemaining =
    resolvedTarget.episodesRemaining
                    
                    episodeTitle =
    resolvedTarget.episodeTitle

episodeDescription =
    resolvedTarget.episodeDescription

episodeThumbnail =
    resolvedTarget.episodeThumbnail

episodeRating =
    resolvedTarget.episodeRating

episodesWatched =
    resolvedTarget.episodesWatched

episodesTotal =
    resolvedTarget.episodesTotal

                    targetIsSeasonFinale =
                        resolvedTarget.isSeasonFinale

                    targetIsSeriesFinale =
                        resolvedTarget.isSeriesFinale

                    // Shared with the local and MDBList builders: a resume
                    // wins over news, a recently aired episode is the arrival
                    // it is, and anything older is just what is up next.
                    badge =
                        upNextArrivalBadge(
                            isResume =
                                resolvedTarget.isResume || isExplicitResume,
                            airDate =
                                resolvedTarget.airDate,
                            episode =
                                resolvedEpisode
                        )

                    // The pair is printed only when it is known; a card
                    // whose resolution came back with nothing keeps the
                    // tracker's own line instead of a dangling "Up Next - ",
                    // or the invented "S1 E1" the empty walk used to leave.
                    subtitle =
                        upNextTrackerSubtitle(
                            prefix =
                                upNextBadgePrefix(badge),
                            season =
                                resolvedSeason,
                            episode =
                                resolvedEpisode,
                            fallback =
                                subtitle
                        )
                }
            }
        }

        if (
            posterUrl?.isBlank() == true
        ) {
            posterUrl = null
        }

        // The tracker's title, or the name TMDB resolved, or no card at all.
        // A Simkl item whose title is a bare id is the same phantom the
        // MDBList builder drops: nothing recognizable to show.
        val displayTitle =
            upNextDisplayTitleOrNull(
                item.title,
                !posterUrl.isNullOrBlank()
            )
                ?: resolvedName
                ?: return null

        return UpNextItem(

            id =
                "simkl:${item.id}",

            title =
                displayTitle,

            poster =
                posterUrl,

            badge =
                badge,

            // Display metadata
            showTitle =
                showTitle
                    ?: if (item.mediaType == "series") {
                        item.title
                    } else {
                        null
                    },

            episodeTitle =
                episodeTitle,

            episodeDescription =
                episodeDescription,

            tmdbRating =
                tmdbRating,

            // Episode-specific TMDB rating (falls back to the show's
            // overall rating in the UI only when this is null).
            // Your UI currently calls this field imdbRating.
            imdbRating =
                episodeRating,

            episodeThumbnail =
                episodeThumbnail,            backdrop = backdropUrl,

            // Simkl items carry no clear-logo artwork; TMDB lookups above
            // only resolve posters/backdrops. Keep it null so the card
            // falls back to the title text.
            clearLogo =
                null,


            runtimeMinutes =
                runtimeMinutes,

            episodesRemaining =
    episodesRemaining,

            episodesWatched =
    episodesWatched,

episodesTotal =
    episodesTotal,

            // Only meaningful for items with an actual watched position
                        // Calculate time remaining from the actual Simkl playback
            // percentage. This is used for Continue Watching items.
            remainingMinutes =
                if (item.source == "playback") {
                    calculateRemainingMinutesFromProgress(
                        runtimeMinutes = runtimeMinutes,
                        progress = item.progress
                    )
                } else {
                    null
                },
            
            subtitle =
                subtitle,

            progressPercent =
                if (item.source == "playback") {

                    item.progress
                        ?.takeIf {
                            it > 0f
                        }
                        ?.let {
                            (it / 100f)
                                .coerceIn(
                                    0f,
                                    1f
                                )
                        }

                } else {
                    null
                },

            streamUrl =
                null,

            parentId =
                navigationId,

            parentType =
                item.mediaType,

            season =
                resolvedSeason,

            episode =
                resolvedEpisode,

            episodeStreamId =
                resolvedStreamId,

            startPositionMs =
                resolvedStartPositionMs,

            isSeasonFinale =
                targetIsSeasonFinale,

            isSeriesFinale =
                targetIsSeriesFinale,

            nextEpisodeAir = capturedNextAirInfo,

            tmdbId = capturedTmdbId,

            recencyTimestamp =
                recencyTimestamp,

            playbackId =
                item.playbackId
        )
    }

    /**
     * Local-history parent ids that describe THIS show: the id the card is keyed
     * by plus the twins the same show is stored under (see
     * [localHistoryParentIdsForShow]). The IMDB twin is resolved from the TMDB
     * id the caller already has; that lookup is memory + disk cached after the
     * first call.
     *
     * Continue Watching used to read only its own flavor, so a show whose
     * episodes were completed under the other id looked unwatched here - the
     * card fell back to "season 1 episode 1" while the detail screen, which has
     * this same twin list, showed the markers correctly.
     */
    private suspend fun localHistoryParentIds(
        parentId: String,
        tmdbId: Int
    ): List<String> {
        if (parentId.isBlank()) return emptyList()

        val imdbId =
            if (tmdbId > 0) {
                runCatchingCancellable {
                    tmdbRepository.resolveImdbId(tmdbId, "series")
                }.getOrNull()
            } else {
                null
            }

        return localHistoryParentIdsForShow(
            parentId = parentId,
            tmdbShowId = tmdbId,
            imdbId = imdbId
        )
    }

    private suspend fun preloadWatchedEpisodeStateForShow(
        parentId: String,
        tmdbShowId: Int
    ) {

        while (true) {

            val shouldLoad =
                watchedStateMutex.withLock {

                    val alreadyLoaded =
                        watchedEpisodeKeysByShow
                            .containsKey(parentId) &&
                            trackerWatchedEpisodesByShow
                                .containsKey(parentId)

                    val alreadyLoading =
                        parentId in
                            watchedStatePreloadInFlight

                    if (alreadyLoaded) {

                        false

                    } else if (alreadyLoading) {

                        null

                    } else {

                        watchedStatePreloadInFlight +=
                            parentId

                        true
                    }
                }

            if (shouldLoad == null) {

                delay(50)

                continue

            } else if (!shouldLoad) {

                return

            } else {

                break
            }
        }

        try {

            // The id forms THIS show is stored under (card id + its TMDB and
            // IMDB twins). Reused to read local rows and to look the show up
            // in the MDBList snapshot, whose keys carry the same id forms.
            val showParentIds =
                localHistoryParentIds(
                    parentId,
                    tmdbShowId
                )

            val localCompletedEntries =
                try {

                    historyDao
                        .getCompletedForParents(
                            showParentIds
                        )

                } catch (_: Exception) {
                    emptyList()
                }

            val simklCompletedEpisodes =
                if (
                    simklRepository.isConfigured() &&
                    simklRepository.hasToken()
                ) {

                    try {

                        simklRepository
                            .getWatchedEpisodesForShowByImdb(
                                imdbId =
                                    parentId,

                                tmdbId =
                                    tmdbShowId
                            )

                    } catch (_: Exception) {
                        emptySet()
                    }

                } else {
                    emptySet()
                }

            /*
             * MDBList's watched episodes for this show, so a viewer who runs
             * MDBList but no Simkl gets the same watched-state treatment
             * Simkl users already had. Guarded on isConfigured and read from
             * the same cached snapshot the badges use, so it normally costs a
             * map lookup; an unset key, an offline fetch or a spent request
             * budget all come back empty, which the merge below reads as "no
             * evidence" rather than "nothing watched" (the local rows and
             * Simkl's set still answer).
             */
            val mdbListCompletedEpisodes =
                if (MdbListClient.isConfigured(getApplication())) {

                    try {

                        MdbListClient
                            .watchedEpisodesForShow(
                                context =
                                    getApplication(),

                                showKeys =
                                    showParentIds
                            )

                    } catch (_: Exception) {
                        emptySet()
                    }

                } else {
                    emptySet()
                }

            // The union of both trackers. Everything downstream - the episode
            // counts, the next-episode resolution and the caught-up rules -
            // reads this one set, which is the whole point of tracker parity.
            val trackerCompletedEpisodes =
                simklCompletedEpisodes + mdbListCompletedEpisodes

            val mergedWatchedKeys =
                WatchedEpisodeState
                    .buildMergedWatchedKeys(
                        parentId =
                            parentId,

                        localCompletedEntries =
                            localCompletedEntries,

                        simklCompletedEpisodes =
                            trackerCompletedEpisodes
                    )

            watchedStateMutex.withLock {

                trackerWatchedEpisodesByShow[
                    parentId
                ] =
                    trackerCompletedEpisodes

                watchedEpisodeKeysByShow[
                    parentId
                ] =
                    mergedWatchedKeys
            }

        } finally {

            watchedStateMutex.withLock {

                watchedStatePreloadInFlight
                    .remove(parentId)
            }
        }
        
    }

private suspend fun resolveSeriesTargetFromSharedWatchedState(
    parentId: String,
    tmdbId: Int,
    simklSeason: Int?,
    simklEpisode: Int?
): ResolvedHomeSeriesTarget? {

    val (
        simklWatchedEpisodes,
        watchedEpisodeKeys
    ) =
        watchedStateMutex.withLock {
            Pair(
                trackerWatchedEpisodesByShow[parentId].orEmpty(),
                watchedEpisodeKeysByShow[parentId].orEmpty()
            )
        }

    var totalAiredEpisodes = 0
    var watchedAiredEpisodes = 0

    // Furthest episode actually watched (season, then episode). Continue
    // Watching follows the furthest point, the way Simkl and MDBList do, so a
    // stale resume row for an episode long since passed (a partial S1E5 while
    // the user is on S3E15) cannot win over where the user actually is.
    var furthestWatchedSeason: Int? = null
    var furthestWatchedEpisode: Int = 0

    /*
     * Calculate the full watched/total episode count for the show.
     *
     * This intentionally starts at season 1 rather than using
     * MAX_FORWARD_SEASON_LOOKAHEAD, because this is for the hero's
     * "X of Y episodes watched" display.
     */
    var season = 1

    // Reuse the full season map another row of the SAME show already walked
    // (local and Simkl paths share this cache by TMDB id): the resolver's
    // next-episode target, finale flags and "X of Y" totals all derive from
    // this map, so a cache hit skips the entire TMDB season walk.
    val cachedSeasonEpisodesBySeason =
        showSeasonEpisodesCache[tmdbId]

    val seasonEpisodesBySeason: MutableMap<Int, List<ResolvedEpisode>> =
        cachedSeasonEpisodesBySeason?.toMutableMap()
            ?: mutableMapOf()

    if (cachedSeasonEpisodesBySeason == null) {

    // Small concurrent batches instead of a 50-season serial walk: for long
    // shows that loop used to do 30+ sequential TMDB lookups per row, which
    // dominated Continue Watching load time. A whole empty batch means we're
    // past the last aired season, so stop.
    while (season <= 50) {
        val batchEnd =
            minOf(season + UP_NEXT_SEASON_BATCH - 1, 50)

        val batchResults: List<Pair<Int, List<ResolvedEpisode>>> =
            coroutineScope {
                (season..batchEnd).map { s ->
                    async {
                        s to (
                            try {
                                tmdbLookupSemaphore.withPermit {
                                    tmdbRepository.getSeasonEpisodes(
                                        tmdbId,
                                        s,
                                        parentId
                                    )
                                }
                            } catch (_: Exception) {
                                emptyList()
                            }
                            )
                    }
                }.awaitAll()
            }

        var anySeasonInBatch = false
        for ((s, episodes) in batchResults) {
            seasonEpisodesBySeason[s] = episodes
            if (episodes.isNotEmpty()) {
                anySeasonInBatch = true
            }
        }
        if (!anySeasonInBatch) break
        season = batchEnd + 1
    }

    // Publish the walked season map for other rows of the same show. Only
    // self-walked maps get stored (a cache-seeded call must not re-store a
    // map it didn't build), and an empty walk means every lookup failed, so
    // that isn't cached either.
    if (seasonEpisodesBySeason.isNotEmpty()) {
        showSeasonEpisodesCache[tmdbId] =
            seasonEpisodesBySeason.toMap()
    }
    }

    season = 1
    while (season <= 50) {

        val seasonEpisodes =
            seasonEpisodesBySeason[season].orEmpty()

        if (seasonEpisodes.isEmpty()) {
            break
        }

        val watchedEpisodesForSeason =
            WatchedEpisodeState
                .effectiveWatchedEpisodesForSeason(
                    parentId = parentId,
                    season = season,
                    simklWatchedEpisodes =
                        simklWatchedEpisodes,
                    watchedEpisodeKeys =
                        watchedEpisodeKeys
                )

        for (episode in seasonEpisodes) {

            if (!isAiredOrUnknown(episode.airDate)) {
                continue
            }

            totalAiredEpisodes++

            if (
                episode.episodeNumber in
                    watchedEpisodesForSeason
            ) {
                watchedAiredEpisodes++

                val reachedSeason = furthestWatchedSeason
                if (
                    reachedSeason == null ||
                    season > reachedSeason ||
                    (
                        season == reachedSeason &&
                            episode.episodeNumber > furthestWatchedEpisode
                        )
                ) {
                    furthestWatchedSeason = season
                    furthestWatchedEpisode = episode.episodeNumber
                }
            }
        }

        season++
    }

    // Episode runtime fallback: TMDB often leaves per-episode runtime empty
    // for TV. Without a runtime the Simkl playback card can't render
    // time-left from its progress percentage. Fall back to the most common
    // non-zero runtime across the show's own episode listings (already
    // fetched above, so this costs nothing).
    val fallbackEpisodeRuntimeMinutes =
        seasonEpisodesBySeason.values
            .asSequence()
            .flatMap { episodes -> episodes.asSequence() }
            .mapNotNull { it.runtimeMinutes }
            .filter { it > 0 }
            .groupBy { it }
            .maxByOrNull { it.value.size }
            ?.key

    // Shared per-pass TMDB detail lookup for one show. Declared BEFORE the
    // finale helpers below: local functions can only reference earlier
    // declarations in the same scope, and seasonFinaleFor needs this.
    val showDetailCache =
        java.util.concurrent.ConcurrentHashMap<Int, TmdbDetail?>()

    suspend fun showDetailFor(
        tmdbId: Int
    ): TmdbDetail? {

        showDetailCache[tmdbId]?.let {
            return it
        }

        val detail =
            try {
                tmdbLookupSemaphore.withPermit {
                    tmdbRepository.getDetailByTmdbId(
                        tmdbId,
                        "tv"
                    )
                }
            } catch (_: Exception) {
                null
            }

        showDetailCache[tmdbId] = detail
        return detail
    }

    // Finale detection helpers: a season finale is the LAST EPISODE of its
    // season — not merely the most recently AIRED one. The old "last aired"
    // rule wrongly tagged mid-air seasons: when the newest aired episode was
    // 5 of 10 (the rest scheduled with future air dates), episode 5 rendered
    // as "SEASON FINALE". The season length is the higher of the last
    // LISTED episode and TMDB's declared episode count (the declared count
    // guards against a lagging season listing). An unaired last episode
    // still never renders as a finale — the watchable-target guard below
    // refuses to tag it.
    //
    // A season whose listing holds a single episode is a new season part-way
    // through arriving, not a one-episode season, so it is never a finale
    // either -- the floor in SeasonRules says so, and that is what stops a
    // premiere wearing the SEASON FINALE badge.
    suspend fun seasonFinaleFor(
        season: Int,
        episode: Int
    ): Boolean {

        val episodes =
            seasonEpisodesBySeason[season].orEmpty()

        if (episodes.isEmpty()) {
            return false
        }

        // Short-circuit before any TMDB detail lookup: only the season's
        // highest listed episode can possibly be the finale.
        val lastListedEpisode =
            episodes.maxOfOrNull {
                it.episodeNumber
            }
                ?: return false

        if (episode < lastListedEpisode) {
            return false
        }

        // The target must BE the last listed episode and must be watchable
        // (aired, or with no air date on record).
        val targetEpisode =
            episodes.firstOrNull {
                it.episodeNumber == episode
            }
                ?: return false

        if (!isAiredOrUnknown(targetEpisode.airDate)) {
            return false
        }

        // Declared count guards a lagging listing: TMDB may have created
        // only the first episodes of a 10-episode season so far, in which
        // case the last LISTED episode (say 5) must not count as the finale
        // when the season's declared length is 10.
        val declaredEpisodeCount =
            try {
                showDetailFor(tmdbId)
                    ?.episodeCountForSeason(season)
            } catch (_: Exception) {
                null
            }
                ?: 0

        val seasonLength =
            maxOf(lastListedEpisode, declaredEpisodeCount)

        return SeasonRules
            .isSeasonFinale(
                episodeNumber = episode,
                seasonLength = seasonLength
            )
    }

    suspend fun seriesFinaleFor(
        season: Int,
        episode: Int
    ): Boolean {

        if (
            !seasonFinaleFor(
                season,
                episode
            )
        ) {
            return false
        }

        val lastSeasonWithAiredEpisodes =
            seasonEpisodesBySeason
                .filterValues { episodes ->
                    episodes.any {
                        isAiredOrUnknown(
                            it.airDate
                        )
                    }
                }
                .keys
                .maxOrNull()
                ?: return false

        return season >= lastSeasonWithAiredEpisodes
    }

    /**
     * Whether the series itself has concluded, per TMDB's series-level status.
     * A season finale of a still-running show (Returning Series, In
     * Production, Planned, or status unknown) must NOT be labeled "Series
     * Finale" — the show may air more seasons. Only Ended/Canceled qualifies.
     * Cached per show for the lifetime of this resolution pass.
     */
    val seriesEndedCache =
        java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    suspend fun isSeriesEnded(
        tmdbId: Int
    ): Boolean {

        seriesEndedCache[tmdbId]?.let {
            return it
        }

        val ended =
            try {

                val status =
                    showDetailFor(tmdbId)?.status

                status == "Ended" ||
                    status == "Canceled"

            } catch (_: Exception) {
                // Unknown status must never claim the series has ended.
                false
            }

        seriesEndedCache[tmdbId] = ended
        return ended
    }

    val episodesTotal =
        totalAiredEpisodes
            .takeIf { it > 0 }

    val episodesWatched =
        watchedAiredEpisodes
            .coerceAtMost(
                totalAiredEpisodes
            )
            .takeIf {
                episodesTotal != null
            }

    /*
     * First preference:
     * an actual local playback resume.
     */
    val resume =
        try {
            // Flavor-tolerant read: the in-progress row can live under this
            // show's other id flavor, and a resume this resolution cannot see
            // is a resume the card cannot print.
            historyDao.getResumeForParents(
                localHistoryParentIds(
                    parentId,
                    tmdbId
                )
            )
        } catch (_: Exception) {
            null
        }

    // Ignore a resume row a LATER watched episode has already overtaken: the
    // show resumes from the furthest point below, not from the abandoned
    // episode. This is the "Continue S1E5 while on S3E15" report.
    //
    // The episode is read through namedEpisodeNumber for the same reason the
    // card's label is: a row written before the 0-is-not-an-episode rule
    // carries the source's 0, and 0 is a number the season list below used to
    // contain, so the match succeeded and the season resolved straight back to
    // E00 - which is how a resume row outlived the fix and kept the rail
    // reading "S02 · E00" over a blank episode line.
    val resumeSeason = resume?.season
    val resumeEpisode = namedEpisodeNumber(resume?.episode)
    val furthestWatched = furthestWatchedSeason
    val resumeIsSuperseded =
        furthestWatched != null &&
            resumeSeason != null &&
            resumeEpisode != null &&
            (
                furthestWatched > resumeSeason ||
                    (
                        furthestWatched == resumeSeason &&
                            furthestWatchedEpisode > resumeEpisode
                        )
                )

    if (
        resume != null &&
        resume.season != null &&
        resumeEpisode != null &&
        resume.positionMs > 0L &&
        !resumeIsSuperseded
    ) {

        val resumeEpisodes =
            try {
                tmdbLookupSemaphore.withPermit {
                    tmdbRepository.getSeasonEpisodes(
                        tmdbId,
                        resume.season,
                        parentId
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }

        val matchedResumeEpisode =
            resumeEpisodes.firstOrNull {
                it.episodeNumber ==
                    resumeEpisode
            }

        if (matchedResumeEpisode != null) {

            return ResolvedHomeSeriesTarget(

                season =
                    resume.season,

                episode =
                    matchedResumeEpisode
                        .episodeNumber,

                streamId =
                    resume.episodeStreamId
                        ?: matchedResumeEpisode
                            .streamId,

                startPositionMs =
                    resume.positionMs,

                isResume =
                    true,

                airDate =
                    matchedResumeEpisode.airDate,

                episodeTitle =
                    matchedResumeEpisode.name,

                episodeDescription =
                    matchedResumeEpisode.overview,

                runtimeMinutes =
                    matchedResumeEpisode.runtimeMinutes
                        ?: fallbackEpisodeRuntimeMinutes,

                episodeThumbnail =
                    matchedResumeEpisode.thumbnail,

                episodeRating =
                    matchedResumeEpisode.voteAverage
                        ?.takeIf { it > 0.0 },

                episodesWatched =
                    episodesWatched,

                episodesTotal =
                    episodesTotal,

                episodesRemaining =
                    calculateEpisodesRemaining(
                        parentId = parentId,
                        tmdbId = tmdbId,
                        startingSeason =
                            resume.season,
                        startingEpisode =
                            resumeEpisode
                    ),

                isSeasonFinale =
                    seasonFinaleFor(
                        resume.season,
                        matchedResumeEpisode
                            .episodeNumber
                    ),

                isSeriesFinale =
                    isSeriesEnded(tmdbId) &&
                        seriesFinaleFor(
                            resume.season,
                            matchedResumeEpisode
                                .episodeNumber
                        )
            )
        }
    }

    /*
     * Find the next unwatched episode in the current season.
     */
    // The continue point, when one is actually KNOWN: the tracker's own
    // episode, or the furthest episode this profile has watched. With no
    // tracker-provided point that is the furthest watched episode rather than
    // S1E1 - Continue Watching is "continue", not "backfill every gap", and
    // it must not snap back to an abandoned episode. Both are real data;
    // neither is a guess.
    //
    // Null matters. The pair used to fall back to season 1 / episode 1 so the
    // walks below always had a floor, and the fall-through at the end of this
    // function then handed that invented floor back as the show's continue
    // point. For a show whose season walk resolved NOTHING - an episode list
    // that never answered, a tracker id TMDB has no show for - the card read
    // "season 1 episode 1" for a show the viewer was part-way through, which
    // is what was reported. A pair this function cannot know is now null.
    val knownSeason =
        simklSeason ?: furthestWatchedSeason

    // A tracker episode number of 0 is "no episode": see
    // EpisodeNumbering. Kept as one, it was returned verbatim below - the
    // card read "S02 · E00", the player "Season 2 Episode 00", for a
    // show whose next episode TMDB knows perfectly well. Dropping it here
    // lets the furthest-watched walk pick the right one.
    val knownEpisode =
        namedEpisodeNumber(
            simklEpisode
        )
            ?: if (
                knownSeason != null &&
                knownSeason == furthestWatchedSeason &&
                furthestWatchedEpisode > 0
            ) {
                furthestWatchedEpisode
            } else {
                null
            }

    // Where the walks below START. A missing pair still needs a floor - season
    // 1, episode 1 is "search from the beginning" - but a floor is not a
    // finding: it never leaves this function as an answer.
    val startingSeason =
        knownSeason ?: 1

    val startingEpisode =
        knownEpisode ?: 1

    val currentSeasonEpisodes =
        try {
            tmdbLookupSemaphore.withPermit {
                tmdbRepository.getSeasonEpisodes(
                    tmdbId,
                    startingSeason,
                    parentId
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    val watchedEpisodesForCurrentSeason =
        WatchedEpisodeState
            .effectiveWatchedEpisodesForSeason(
                parentId = parentId,
                season = startingSeason,
                simklWatchedEpisodes =
                    simklWatchedEpisodes,
                watchedEpisodeKeys =
                    watchedEpisodeKeys
            )

    val nextUnwatchedInSeason =
        currentSeasonEpisodes
            .firstOrNull { episode ->

                episode.episodeNumber >=
                    startingEpisode &&

                    episode.episodeNumber !in
                        watchedEpisodesForCurrentSeason &&

                    isAiredOrUnknown(
                        episode.airDate
                    )
            }

    if (nextUnwatchedInSeason != null) {

        return ResolvedHomeSeriesTarget(

            season =
                startingSeason,

            episode =
                nextUnwatchedInSeason
                    .episodeNumber,

            streamId =
                nextUnwatchedInSeason
                    .streamId,

            airDate =
                nextUnwatchedInSeason
                    .airDate,

            episodeTitle =
                nextUnwatchedInSeason
                    .name,

            episodeDescription =
                nextUnwatchedInSeason
                    .overview,

            runtimeMinutes =
                nextUnwatchedInSeason
                    .runtimeMinutes
                    ?: fallbackEpisodeRuntimeMinutes,

            episodeThumbnail =
                nextUnwatchedInSeason
                    .thumbnail,

            episodeRating =
                nextUnwatchedInSeason
                    .voteAverage
                        ?.takeIf { it > 0.0 },

            episodesWatched =
                episodesWatched,

            episodesTotal =
                episodesTotal,

            episodesRemaining =
                calculateEpisodesRemaining(
                    parentId = parentId,
                    tmdbId = tmdbId,
                    startingSeason =
                        startingSeason,
                    startingEpisode =
                        nextUnwatchedInSeason
                            .episodeNumber
                ),

                isSeasonFinale =
                    seasonFinaleFor(
                        startingSeason,
                        nextUnwatchedInSeason
                            .episodeNumber
                    ),

                isSeriesFinale =
                    isSeriesEnded(tmdbId) &&
                        seriesFinaleFor(
                            startingSeason,
                            nextUnwatchedInSeason
                                .episodeNumber
                        )
        )
    }

    /*
     * Search future seasons for the next unwatched aired episode.
     */
    val knownWatchedSeasons =
        watchedEpisodeKeys
            .mapNotNull(::parseEpisodeKey)
            .map {
                (_, season, _) ->
                season
            }

    val highestKnownSeason =
        maxOf(
            startingSeason,

            simklWatchedEpisodes
                .maxOfOrNull {
                    (season, _) ->
                    season
                }
                ?: startingSeason,

            knownWatchedSeasons
                .maxOfOrNull {
                    it
                }
                ?: startingSeason
        )

    val lastSeasonToCheck =
        maxOf(
            highestKnownSeason + 2,
            startingSeason +
                MAX_FORWARD_SEASON_LOOKAHEAD
        )

    for (
        futureSeason in
        (startingSeason + 1)..lastSeasonToCheck
    ) {

        val seasonEpisodes =
            try {
                tmdbLookupSemaphore.withPermit {
                    tmdbRepository.getSeasonEpisodes(
                        tmdbId,
                        futureSeason,
                        parentId
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }

        if (seasonEpisodes.isEmpty()) {
            // First empty season = end of the show (TMDB season
            // listings are contiguous). The old continue kept
            // scanning up to MAX_FORWARD_SEASON_LOOKAHEAD empty
            // seasons per row, which made Continue Watching crawl.
            break
        }

        val watchedEpisodesForSeason =
            WatchedEpisodeState
                .effectiveWatchedEpisodesForSeason(
                    parentId = parentId,
                    season = futureSeason,
                    simklWatchedEpisodes =
                        simklWatchedEpisodes,
                    watchedEpisodeKeys =
                        watchedEpisodeKeys
                )

        val firstUnwatchedAired =
            seasonEpisodes.firstOrNull {
                episode ->

                episode.episodeNumber !in
                    watchedEpisodesForSeason &&

                    isAiredOrUnknown(
                        episode.airDate
                    )
            }

        if (firstUnwatchedAired != null) {

            return ResolvedHomeSeriesTarget(

                season =
                    futureSeason,

                episode =
                    firstUnwatchedAired
                        .episodeNumber,

                streamId =
                    firstUnwatchedAired
                        .streamId,

                airDate =
                    firstUnwatchedAired
                        .airDate,

                episodeTitle =
                    firstUnwatchedAired
                        .name,

                episodeDescription =
                    firstUnwatchedAired
                        .overview,

                runtimeMinutes =
                    firstUnwatchedAired
                        .runtimeMinutes
                        ?: fallbackEpisodeRuntimeMinutes,

                episodesWatched =
                    episodesWatched,

                episodesTotal =
                    episodesTotal,

                episodesRemaining =
                    calculateEpisodesRemaining(
                        parentId = parentId,
                        tmdbId = tmdbId,
                        startingSeason =
                            futureSeason,
                        startingEpisode =
                            firstUnwatchedAired
                                .episodeNumber
                    ),

                isSeasonFinale =
                    seasonFinaleFor(
                        futureSeason,
                        firstUnwatchedAired
                            .episodeNumber
                    ),

                isSeriesFinale =
                    isSeriesEnded(tmdbId) &&
                        seriesFinaleFor(
                            futureSeason,
                            firstUnwatchedAired
                                .episodeNumber
                        )
            )
        }
    }

    /*
     * Nothing to continue.
     *
     * Every aired episode this profile has is watched - the current-season
     * scan and the future-season scan both came up empty - and the caller
     * passed no next-episode hint of its own. The fall-through below used to
     * hand back the position it STARTED from instead: the last episode the
     * viewer watched (or E1 of the season they are on), with
     * episodesRemaining = 0. Callers read a non-null target as "there is
     * something here", so a show that was completely finished kept a
     * "next up" card labeled with the very episode just watched - while the
     * detail page said "caught up". There is no episode to continue to, so
     * say so: null leaves a caught-up show off Continue Watching, and what is
     * left for it (a next UNAIRED episode) is the Upcoming rail's job (see
     * loadCaughtUpUpcomingItems).
     *
     * Guarded on the walk having actually counted the show, and on there being
     * no hint: a tracker's queued next episode can run ahead of TMDB's aired
     * set, and the Simkl feed deliberately keeps exactly that show on the rail
     * (ShowCompletionRules.isContinueWatchingCandidate), while a season walk
     * that failed to load must keep today's behavior rather than hide the
     * show.
     */
    if (
        hasNothingLeftToWatch(
            simklSeason = simklSeason,
            simklEpisode = simklEpisode,
            watchedAiredEpisodes = watchedAiredEpisodes,
            totalAiredEpisodes = totalAiredEpisodes
        )
    ) {
        return null
    }

    // Nothing was resolved to continue AT, so the pair comes from what is
    // actually known - the tracker's own episode or the furthest one watched
    // - and stays null when nothing is. This is the fall-through that made a
    // show whose season walk came back empty read "season 1 episode 1": the
    // invented floor was returned as if it were a finding.
    return ResolvedHomeSeriesTarget(

        season =
            knownSeason,

        episode =
            knownEpisode,

        episodesWatched =
            episodesWatched,

        episodesTotal =
            episodesTotal,

        episodesRemaining =
            0
    )
}


private suspend fun calculateEpisodesRemaining(
    parentId: String,
    // PROBE2
    tmdbId: Int,
    startingSeason: Int,
    startingEpisode: Int
): Int? {

    if (tmdbId <= 0) {
        return null
    }

    val (
        simklWatchedEpisodes,
        watchedEpisodeKeys
    ) = watchedStateMutex.withLock {
        Pair(
            trackerWatchedEpisodesByShow[parentId].orEmpty(),
            watchedEpisodeKeysByShow[parentId].orEmpty()
        )
    }

    var remaining = 0

    val lastSeasonToCheck =
        startingSeason +
            MAX_FORWARD_SEASON_LOOKAHEAD

    for (
        season in
        startingSeason..lastSeasonToCheck
    ) {

        val seasonEpisodes =
            try {
                tmdbLookupSemaphore.withPermit {
                    tmdbRepository.getSeasonEpisodes(
                        tmdbId,
                        season,
                        parentId
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }

        if (seasonEpisodes.isEmpty()) {
            // First empty season = end of the show (TMDB season
            // listings are contiguous). The old continue kept
            // scanning up to MAX_FORWARD_SEASON_LOOKAHEAD empty
            // seasons per row, which made Continue Watching crawl.
            break
        }

        val watchedEpisodesForSeason =
            WatchedEpisodeState
                .effectiveWatchedEpisodesForSeason(
                    parentId = parentId,
                    season = season,
                    simklWatchedEpisodes =
                        simklWatchedEpisodes,
                    watchedEpisodeKeys =
                        watchedEpisodeKeys
                )

        for (episode in seasonEpisodes) {

            if (
                !isAiredOrUnknown(
                    episode.airDate
                )
            ) {
                continue
            }

            if (
                season == startingSeason &&
                episode.episodeNumber <
                    startingEpisode
            ) {
                continue
            }

            if (
                episode.episodeNumber !in
                    watchedEpisodesForSeason
            ) {
                remaining++
            }
        }
    }

    return remaining
}

  private fun buildSimklSubtitle(
        item: SimklContinueWatchingItem,
        isExplicitResume: Boolean
    ): String {

        return when {

            item.mediaType == "series" &&
                item.season != null &&
                item.episode != null -> {

                if (isExplicitResume) {

                    "Resume - ${
                        formatSeasonEpisode(
                            item.season,
                            item.episode
                        )
                    }"

                } else {

                    "Up Next - ${
                        formatSeasonEpisode(
                            item.season,
                            item.episode
                        )
                    }"
                }
            }

            item.mediaType == "series" &&
                item.season != null -> {

                if (isExplicitResume) {
                    "Resume - S${item.season}"
                } else {
                    "Up Next - S${item.season}"
                }
            }

            isExplicitResume ->
                "Resume"

            else ->
                "Up Next"
        }
    }












    fun loadRails(
        forceRefresh: Boolean = false
    ) {

        viewModelScope.launch {

            loadRailsInternal(
                forceRefresh =
                    forceRefresh
            )
        }
    }

    /**
     * Landscape artwork (backdrop + clearlogo) for a rail's items, through the
     * shared vocabulary in `ui/components/LandscapeArt.kt` - the same key and
     * the same merge rule the KB folder layouts use, so the two screens cannot
     * spell either differently. What is Home's own here is the cache in front
     * of the lookups, below.
     */
    private suspend fun resolveLandscapeArt(
        metas: List<MetaPreview>,
        tmdbOnly: Boolean = false
    ): Map<String, Pair<String?, String?>> =
        landscapeArtFor(
            requests = metas.map { meta ->
                LandscapeArtRequest(
                    id = meta.id,
                    type = meta.type,
                    addonBackdrop = meta.background,
                    addonLogo = meta.logo,
                    tmdbOnly = tmdbOnly
                )
            },
            tmdbArtOf = { request -> homeLandscapeArt(request) }
        )

    /**
     * The TMDB artwork for one item, through this build's memo and the artwork
     * semaphore.
     *
     * No fast-path on the addon's own fields: the addon background is typically
     * the same primary backdrop the hero shows, so returning it early made
     * landscape cards mirror the hero. TMDB is always consulted; the addon
     * fields stay as fallbacks in the shared merge.
     */
    private suspend fun homeLandscapeArt(
        request: LandscapeArtRequest
    ): Pair<String?, String?> {

        val key = request.key

        landscapeLookupMemo[key]?.let { remembered ->

            // An empty pair is a remembered answer too: this title is already
            // known to have no TMDB artwork, and asking again is a round trip
            // for nothing.
            PerfTrace.record("home.artReuse", 0L)
            return remembered
        }

        val startedAtMs =
            android.os.SystemClock.elapsedRealtime()

        val detail =
            landscapeArtSemaphore.withPermit {

                runCatchingCancellable {

                    tmdbRepository.fetchEnrichedMetaCached(
                        imdbId = request.id,
                        type = request.type
                    )
                }.getOrNull()
            }

        val art =
            detail.landscapeArtUrls()

        val elapsedMs =
            android.os.SystemClock.elapsedRealtime() - startedAtMs

        PerfTrace.record("home.artLookup", elapsedMs)

        if (art.first == null && art.second == null) {

            // Named on its own so the report can say how many empty answers
            // were remembered rather than asked for again - the misses the
            // repository deliberately does not cache.
            PerfTrace.record("home.artEmpty", elapsedMs)
        }

        rememberLandscapeLookup(key, art)
        return art
    }


    /**
     * Stage 2 of the digital-release filter: titles that survived the cheap
     * catalog-date pass get verified against TMDB's release_dates payload
     * (digital type 4/6, physical type 5, theatrical type 2/3). A movie
     * still inside its theatrical window with no home release — or one
     * that hasn't released at all — is dropped. Series always pass (they
     * are episodically available), and unknown results keep the title.
     * Lookups go through the repository's 12h/30d cache and are throttled
     * so a cold first load doesn't stampede TMDB.
     */
    private suspend fun applyDigitalAvailabilityFilter(
        metas: List<MetaPreview>
    ): List<MetaPreview> =
        // One implementation with the KB folders and add-on search
        // (TmdbRepository.filterByHomeAvailabilityById), so an un-released movie
        // is hidden the same way on every surface. The semaphore that used to
        // live here moved with it.
        tmdbRepository.filterByHomeAvailabilityById(
            items = metas,
            id = { meta -> meta.id },
            type = { meta -> meta.type }
        )

    /**
     * Remaining auto-retries for an all-failed cold-start rail build (see the
     * retry block inside [loadRailsInternal]). A successful non-empty build
     * resets the budget; each all-failed attempt consumes one so a device
     * that boots with no network stops instead of retrying forever.
     */
    /**
     * Fills in landscape-card artwork for rails that are already on screen.
     *
     * Artwork used to be resolved inside each rail's own load, before that rail
     * could be published, so every item of every row had to come back from TMDB
     * before the viewer saw anything - a per-item lookup, at six concurrent
     * requests, standing directly in front of a build whose entire job is to
     * show rows. The rail is what the viewer is waiting for; its card art is
     * not. Rails now publish straight away and this fills the art in behind
     * them, one rail at a time (the items within a rail still resolve in
     * parallel), which also holds the warm to a rail's worth of requests rather
     * than a burst of every rail's at once.
     *
     * Pinned "Top ... Today" rails take TMDB art or nothing - their own
     * backgrounds carry burned-in promo text - which is the `tmdbOnly` flag, and
     * why [RailInfo.pinned] is read here rather than passed down.
     *
     * [buildEpoch] is the profile this build belonged to. A rail whose profile
     * has since been switched away from is left alone, for the same reason the
     * publish path refuses to repaint it.
     */
    private fun warmLandscapeArt(rails: List<Rail>, buildEpoch: Long) {
        if (rails.isEmpty()) return
        viewModelScope.launch {
            for (rail in rails) {
                applyLandscapeArt(
                    railKey = railKeyOf(rail),
                    items = rail.items,
                    buildEpoch = buildEpoch
                )
            }
        }
    }

    /**
     * Resolves artwork for [items] and merges it into the rail [railKey], if that
     * rail is still on screen and still this profile's.
     *
     * [items] is deliberately the items whose art is missing rather than the
     * whole rail: a page needs only its own cards resolved, and the rest are
     * already done - the first page by the build's warm, earlier pages by
     * theirs.
     *
     * The merge is a union rather than a replacement because more than one of
     * these can be in flight for the same rail (a page arriving while the
     * build's warm is still resolving, or two pages in quick succession) and
     * each holds only its own items' art. Replacing would drop whichever arrived
     * first.
     */
    private suspend fun applyLandscapeArt(
        railKey: String,
        items: List<MetaPreview>,
        buildEpoch: Long
    ) {
        val known =
            _rails.value
                .firstOrNull { rail ->
                    railKeyOf(rail) == railKey
                }
                ?.landscapeArt
                .orEmpty()

        // Only what is genuinely unknown. A rebuild inherits the art its
        // predecessor resolved (see previousLandscapeArt) and a page brings its
        // own new items, so a whole rail arriving here is the ordinary case -
        // and reducing it to the unseen items is the difference between one
        // lookup and a rail's worth of them.
        val missing =
            items.filter { meta -> meta.landscapeArtKey() !in known }

        if (missing.isEmpty()) return

        val art =
            resolveLandscapeArt(
                metas = missing,
                tmdbOnly = railInfo[railKey]?.pinned == true
            )

        if (art.isEmpty()) return

        // The profile this belonged to has been switched away from, so its art
        // must not land on the new profile's rail of the same key.
        if (buildEpoch != railBuildEpoch) return

        _rails.update { current ->
            current.map { existing ->
                if (railKeyOf(existing) == railKey) {
                    existing.copy(
                        landscapeArt = existing.landscapeArt + art
                    )
                } else {
                    existing
                }
            }
        }
    }

    private var railLoadRetriesLeft = RAIL_LOAD_RETRY_MAX_ATTEMPTS

    private suspend fun loadRailsInternal(
        forceRefresh: Boolean,
        clearCatalogCache: Boolean = forceRefresh,
        coalesce: Boolean = false
    ) {

        if (
            !forceRefresh &&
            _rails.value.isNotEmpty()
        ) {
            return
        }

        // A background caller that wants a rebuild while one is already running
        // does not queue a second full build behind it. It records that a rebuild
        // is wanted and returns; the build that is running runs it once, at the
        // end (see the tail of this function). Every add-on change, dynamic-
        // catalog refresh and resume used to queue its own full build, so a cold
        // start whose add-on state changed mid-build paid for all of them.
        //
        // Only the fire-and-forget callers pass this. A caller that has to know
        // its rebuild happened - a manual refresh, a profile switch, a retry -
        // leaves it false and still waits for a build of its own.
        if (coalesce && railBuildInFlight) {
            railRebuildQueued = true
            railRebuildClearCache = railRebuildClearCache || clearCatalogCache
            return
        }

        val hideUpcoming =
            AppPreferences.getHomeRailHideUpcoming(
                getApplication()
            )

        val landscapeCards =
            AppPreferences.getHomeLandscapeCards(
                getApplication()
            )

        lastAppliedHideUpcoming =
            hideUpcoming

        lastAppliedLandscape =
            landscapeCards

        _isLoading.value =
            _rails.value.isEmpty()

        // Claim the build slot before the first suspension. With the immediate
        // main dispatcher this runs as the coroutine starts, so a resume later in
        // the same frame already sees it (see railBuildInFlight).
        railBuildInFlight = true
        val buildStartedAtMs =
            android.os.SystemClock.elapsedRealtime()

        // Profile guard for this build: a profile switch bumps
        // [railBuildEpoch] and empties the rail list, so a build that
        // started for the profile the user just left must not publish — it
        // would repaint the old rows over the new profile's Home for as
        // long as the load keeps streaming.
        val buildEpochAtStart = railBuildEpoch
        val profileIdAtStart =
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id

        // Keep any previous error on screen until THIS attempt succeeds or
        // fails - the old code nulled it up front, so any automatic reload
        // (launch refresh, addon change, resume) instantly erased the
        // message before the user could read it. It "flashed" on open.

        if (clearCatalogCache) {
            repository.clearCatalogCache()
        }

        // Set by the all-catalogs-failed retry below when it decides to try
        // again. That retry deliberately runs AFTER the rail mutex is released
        // rather than from inside it - see there for why that is not a detail.
        var retryAfterMs = -1L

        try {

            railsRefreshMutex.withLock {

                // Rebuilding rails invalidates pagination bookkeeping: rail
                // identities are stable, but their item offsets are not.
                railInfo.clear()
                loadingRails.clear()
                exhaustedRails.clear()
                railSourceOffset.clear()
                closeCatalogGrid()

                // Snapshot the artwork of what is on screen before this build
                // starts replacing it: those rails' art is what their
                // replacements inherit (see previousLandscapeArt).
                previousLandscapeArt =
                    _rails.value.associate { rail -> railKeyOf(rail) to rail.landscapeArt }

                // A new build asks TMDB again. The memo exists to stop the
                // repeats WITHIN one build, not to outlive it: the repository
                // deliberately does not cache a miss, and a lookup that came
                // back empty while Wi-Fi was still coming up has to be retried.
                landscapeLookupMemo.clear()

                // The pinned "Top ... Today" rails are loaded inside the
                // fan-out's own scope, BESIDE the catalog requests rather than
                // before them. They used to be awaited here, which put their
                // whole latency ahead of the first catalog request ever being
                // made - on a cold start, seconds of a fast network doing
                // nothing. Every catalog rail still waits for them before it
                // publishes (see below), so what the viewer sees first is
                // unchanged: only sooner.
                var pinned: List<Rail> = emptyList()

                // The fan-out is timed from HERE, before the pinned rails are
                // launched, so home.catalogRails covers the overlap.
                val fanOutStartedAtMs =
                    android.os.SystemClock.elapsedRealtime()

                val addonsById =
                    addonManager
                        .installedAddons
                        .value
                        .associateBy { it.id }

                val pendingCatalogs =
                    addonManager
                        .getHomeCatalogConfigurations()
                        .asSequence()
                        // Some manifests (e.g. AIOStreams) list the same catalog
                        // more than once; dedupe so Home never builds two rails
                        // with the same UI key (duplicate LazyColumn keys crash
                        // the rail list, which is why catalogs showed in the
                        // add-on screen but never appeared on Home).
                        .distinctBy { configuration ->
                            configuration.addonId + "::" +
                                configuration.catalog.type.trim().lowercase() + "::" +
                                configuration.catalog.id.trim().lowercase()
                        }
                        .mapNotNull { configuration ->

                            val addon =
                                addonsById[configuration.addonId]
                                    ?: return@mapNotNull null

                            if (
                                "catalog" !in addon.resources ||
                                addon.manifestUrl == TOP_TODAY_MANIFEST_URL
                            ) {
                                return@mapNotNull null
                            }

                            val baseUrl =
                                addon.manifestUrl
                                    .removeSuffix("manifest.json")
                                    .removeSuffix("/")

                            PendingCatalogLoad(
                                addonName = addon.displayName,
                                baseUrl = baseUrl,
                                catalogId = configuration.catalog.id,
                                catalogType = configuration.catalog.type,
                                catalogRawName = configuration.catalog.displayName,
                                // A name the viewer set is shown verbatim, not
                                // title-cased: "AI" must not become "Ai".
                                catalogUserNamed = configuration.catalog.customName?.isNotBlank() == true
                            )
                        }
                        .toList()

                // Progressive publication: each catalog rail lands in the
                // UI the moment it resolves, in catalog order — the screen
                // no longer waits for the slowest addon before painting.
                val collected =
                    java.util.Collections.synchronizedList(
                        mutableListOf<Rail>()
                    )

                /** Guards the one-shot home.firstRail sample below. */
                val firstRailRecorded =
                    java.util.concurrent.atomic.AtomicBoolean(false)

                coroutineScope {

                    val pinnedDeferred =
                        async {

                            val rails =
                                mutableListOf<Rail>()

                            if (tmdbRepository.kidsMaxAge() != null) {
                                // Kids profile: the general-audience "Top
                                // ... Today" rails don't belong here - after
                                // ceiling filtering they are usually
                                // near-empty (today's top titles are mostly
                                // adult fare). Hardcoded kids rails replace
                                // them.
                                loadPinnedKidsRails(
                                    rails,
                                    hideUpcoming,
                                    landscapeCards
                                )
                            } else {
                                loadPinnedTopTodayRails(
                                    rails,
                                    hideUpcoming,
                                    landscapeCards
                                )
                            }

                            // When the pinned rails LANDED, measured from the
                            // build start. They now run beside the catalog
                            // fan-out, so this no longer includes it.
                            PerfTrace.record(
                                "home.pinned",
                                android.os.SystemClock.elapsedRealtime() -
                                    buildStartedAtMs
                            )

                            rails
                        }

                    pendingCatalogs
                        .map { pending ->
                            async {
                                loadCatalogRail(
                                    pending,
                                    hideUpcoming,
                                    landscapeCards
                                )?.let { rail ->

                                    // The fetch above needed no such wait -
                                    // it started with the pinned rails - but
                                    // the PUBLISH does, and not because of the
                                    // order of the appends: the pinned rows are
                                    // never streamed. They land in the single
                                    // publish at the end of this build
                                    // (finalRails), which REPLACES the list, so
                                    // a row streamed before it is moved down.
                                    // Waiting here means the first catalog row
                                    // appears at about the moment the pinned
                                    // batch is ready, rather than appearing and
                                    // then moving. It costs no fetch time (that
                                    // is already done) and, whenever the pinned
                                    // batch is the slower half, no latency
                                    // either - see home.firstRail.
                                    pinnedDeferred.await()

                                    // Stale-profile guard (see the final
                                    // publish below): never stream a
                                    // previous profile's rows back in.
                                    if (buildEpochAtStart != railBuildEpoch) {
                                        return@let
                                    }
                                    collected.add(rail)
                                    // The first row on screen: the number a
                                    // "Home is slow" report is really about,
                                    // since rails publish as they resolve -
                                    // here, once the pinned batch above is in
                                    // hand (see the wait above).
                                    // Atomic because the fan-out resolves
                                    // several rails on different threads.
                                    if (
                                        firstRailRecorded.compareAndSet(
                                            false,
                                            true
                                        )
                                    ) {
                                        PerfTrace.record(
                                            "home.firstRail",
                                            android.os.SystemClock.elapsedRealtime() -
                                                buildStartedAtMs
                                        )
                                    }
                                    // Append just this rail right after the
                                    // last catalog rail currently shown, so
                                    // rails appear progressively in catalog
                                    // order without republishing the whole
                                    // batch (the earlier shared-list append
                                    // made rails jump/duplicate visually).
                                    _rails.update { current ->
                                        val without =
                                            current.filterNot { existing ->
                                                railKeyOf(existing) == railKeyOf(rail)
                                            }
                                        if (
                                            without.isEmpty()
                                        ) {
                                            listOf(rail)
                                        } else {
                                            val lastCatalogIdx =
                                                without.indexOfLast { existing ->
                                                    railKeyOf(existing) in
                                                        collected.map { railKeyOf(it) }
                                                }
                                            if (
                                                lastCatalogIdx < 0
                                            ) {
                                                without + rail
                                            } else {
                                                without.subList(0, lastCatalogIdx + 1) +
                                                    listOf(rail) +
                                                    without.subList(lastCatalogIdx + 1, without.size)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        .awaitAll()

                    pinned = pinnedDeferred.await()
                }

                // The catalog fan-out, timed from before the pinned rails were
                // launched to the last rail resolved. It OVERLAPS home.pinned
                // now, so the two no longer add up to the build - which is the
                // point: this is the half the viewer waits on, and it starts at
                // once instead of after the pinned rows.
                PerfTrace.record(
                    "home.catalogRails",
                    android.os.SystemClock.elapsedRealtime() - fanOutStartedAtMs
                )

                // Everything this build publishes, on screen. It sits between
                // home.firstRail (the first row) and home.refreshAll (which ends
                // with bookkeeping the viewer never sees).
                //
                // It is the figure that says whether the overlap above bought
                // anything, but NOT by being compared with home.pinned alone:
                // home.pinned and home.catalogRails are both measured from
                // before the pinned launch, so their SUM is the total a build
                // that fetched the pinned rails before starting the fan-out
                // would have cost. A railsReady below that sum is the overlap's
                // saving; a railsReady level with it means the shared request
                // throttle was the real limit and the overlap bought nothing,
                // in which case the fan-out should go back to starting after
                // the pinned rows.
                PerfTrace.record(
                    "home.railsReady",
                    android.os.SystemClock.elapsedRealtime() - buildStartedAtMs
                )

                val finalRails =
                    pinned + collected

                // Stale-profile guard: the profile changed while this build
                // was in flight, so these rows belong to the profile the user
                // just left. Drop the build instead of repainting them — the
                // new profile's own build owns the rail list now.
                if (buildEpochAtStart != railBuildEpoch) {
                    Log.d(
                        "HOME_RAILS",
                        "dropping stale rail build (profile switched mid-load)"
                    )
                    return
                }

                _railsProfileId.value = profileIdAtStart

                _rails.value =
                    finalRails.distinctBy { railKeyOf(it) }

                // The rows are on screen; now fill in their landscape art. This
                // used to happen inside each rail's own load, before the rail
                // could be published at all (see warmLandscapeArt).
                if (landscapeCards) {
                    warmLandscapeArt(
                        _rails.value,
                        buildEpochAtStart
                    )
                }

                // Freshness stamp for onHomeResumed()'s stale-rails guard.
                railsBuiltAtMs = System.currentTimeMillis()

                // Warm the hero-art caches for everything on screen BEFORE
                // the user focuses it: focusing then becomes a cache hit and
                // the hero swaps art in one frame instead of flashing the
                // raw addon backdrop + plain title for the resolve time.
                prefetchHeroArt(
                    finalRails.flatMap { it.items }
                )

                // Success: NOW clear any stale error (was previously done at
                // attempt START, which wiped the message before it could be
                // read - the "flashing error" on open).
                _error.value =
                    null

                // Cold-start resilience: when every catalog fetch fails
                // simultaneously (network not yet up when the TV launcher
                // restores the app, DNS briefly unresolved, Wi-Fi still
                // associating) Home used to stay empty until some other
                // event (opening the home manager, toggling a setting)
                // happened to fire a rebuild. Detect the all-failed build
                // and retry the whole load a couple of times with backoff.
                if (
                    finalRails.isEmpty() &&
                    pendingCatalogs.isNotEmpty() &&
                    !forceRefresh &&
                    railLoadRetriesLeft > 0
                ) {
                    railLoadRetriesLeft -= 1
                    val backoffMs =
                        RAIL_LOAD_RETRY_BASE_DELAY_MS *
                            (RAIL_LOAD_RETRY_MAX_ATTEMPTS - railLoadRetriesLeft)

                    Log.w(
                        "HOME_RAILS",
                        "rail load produced 0 rails from " +
                            "${pendingCatalogs.size} catalogs - retrying in ${backoffMs}ms " +
                            "($railLoadRetriesLeft retries left)"
                    )

                    _isLoading.value = true
                    // Handed to the caller instead of run from here. This block
                    // is INSIDE railsRefreshMutex, and the retry is a whole
                    // loadRailsInternal - which takes that same mutex. A kotlinx
                    // Mutex is not reentrant, so recursing from here waited on a
                    // lock this very coroutine was holding: the retry never ran,
                    // the lock was never handed back, and Home stayed empty for
                    // the life of the process. It hit on exactly the cold start
                    // the retry was written for - the launcher restoring the app
                    // before Wi-Fi is up, so every catalog fails once - which is
                    // how a "No catalogs available" Home could survive every
                    // later rebuild, refresh and resume.
                    retryAfterMs = backoffMs
                    return@withLock
                }

                if (finalRails.isNotEmpty()) {
                    railLoadRetriesLeft = RAIL_LOAD_RETRY_MAX_ATTEMPTS
                } else if (
                    pendingCatalogs.isNotEmpty() &&
                    railLoadRetriesLeft == 0
                ) {
                    // Every attempt failed and the budget is spent: show a
                    // readable error INSTEAD of silently leaving Home empty.
                    // The next successful load clears it.
                    _error.value =
                        "Couldn't reach your add-ons. Check the network " +
                            "connection, then press OK to retry."
                }

                _isLoading.value =
                    false

                refreshWatchedStatus(
                    finalRails
                )

                Log.d(
                    "HOME_RAILS",
                    "rail load complete: " +
                        "pinned=${pinned.size}, " +
                        "catalogs=${pendingCatalogs.size}, " +
                        "rails=${finalRails.size}"
                )
            }

            // Out here the mutex is free, so the retry can take it. The backoff
            // is still paid before the second attempt, exactly as before.
            if (retryAfterMs >= 0L) {
                delay(retryAfterMs)
                loadRailsInternal(
                    forceRefresh = false,
                    clearCatalogCache = true
                )
            }

            // ...and the rebuild a background caller asked for while this one was
            // running. Once, here, outside the lock - the same shape as the retry
            // above, and for the same reason: what the queue used to hold was one
            // full build list per caller.
            if (railRebuildQueued) {
                railRebuildQueued = false
                val clearCache = railRebuildClearCache
                railRebuildClearCache = false
                loadRailsInternal(
                    forceRefresh = true,
                    clearCatalogCache = clearCache
                )
            }

        } catch (
            e: kotlinx.coroutines.CancellationException
        ) {

            throw e

        } catch (e: Exception) {

            Log.e(
                "HOME_RAILS",
                "loadRails failed: ${e.message}",
                e
            )

            // Cold-start races (TV launcher restoring the app before Wi-Fi/DNS
            // settle) throw hard here, then the retry below succeeds a second
            // later - showing the message immediately just flashes it. Stay
            // silent while the retry budget still has attempts; surface the
            // error only when the failure is final (or the user explicitly
            // triggered this load and deserves immediate feedback).
            val retriesStillPending = railLoadRetriesLeft > 0 && !forceRefresh

            if (
                _rails.value.isEmpty() &&
                !retriesStillPending
            ) {

                _error.value =
                    "Failed to load: ${e.message}"
            }

        } finally {

            // Unconditional, unlike the spinner below: the build slot belongs to
            // THIS call, and a build that lost its profile still has to hand it
            // back. Leaving it claimed would retire the empty-Home rebuild (see
            // railBuildInFlight) for the rest of the session.
            railBuildInFlight = false

            // Only the build that still owns the active profile may lower the
            // spinner; a build that lost the profile must leave the flag to
            // the newer build, so Home can't flash its "no catalogs" card
            // while the new profile's rails stream in.
            if (buildEpochAtStart == railBuildEpoch) {
                _isLoading.value =
                    false
            }
        }
    }

    private suspend fun loadCatalogRail(
        pending: PendingCatalogLoad,
        hideUpcoming: Boolean,
        landscapeCards: Boolean
    ): Rail? {

        // First page: whatever the addon returns for skip=0 (its own page
        // size - 20, 50, 100, ...). The rest of the catalog streams in via
        // loadMoreForRail as the user scrolls, so a 1000-item catalog is
        // never fetched up front but is still fully reachable.
        val metas =
            try {
                fetchCatalogThrottled(
                    baseUrl = pending.baseUrl,
                    type = pending.catalogType,
                    catalogId = pending.catalogId,
                    skip = 0
                )
            } catch (e: Exception) {

                Log.e(
                    "HOME_RAILS",
                    "catalog load failed " +
                        "addon=${pending.addonName}, " +
                        "catalog=${pending.catalogId}: " +
                        e.message,
                    e
                )

                return null
            }

        if (
            metas.isEmpty()
        ) {
            return null
        }

        val filtered =
            if (hideUpcoming) {
                tmdbRepository.kidsFilterMetas(
                    applyDigitalAvailabilityFilter(
                        filterUpcoming(metas)
                    )
                )
            } else {
                tmdbRepository.kidsFilterMetas(metas)
            }

        if (
            filtered.isEmpty()
        ) {
            return null
        }

        val rail =
            Rail(
                addonName = pending.addonName,
                catalogName = catalogDisplayName(
                    pending.catalogRawName,
                    pending.catalogUserNamed
                ),
                type = pending.catalogType,
                items = filtered,
                catalogId = pending.catalogId,
                baseUrl = pending.baseUrl,
                // Inherited from the rail this replaces, and filled in behind
                // the row for whatever is still unknown: see
                // previousLandscapeArt and warmLandscapeArt.
                landscapeArt = previousLandscapeArt[
                    railKeyOf(pending.addonName, pending.catalogId, pending.catalogType)
                ] ?: emptyMap()
            )

        railInfo[railKeyOf(rail)] =
            RailInfo(
                addonName = pending.addonName,
                catalogId = pending.catalogId,
                catalogType = pending.catalogType,
                catalogRawName = pending.catalogRawName,
                baseUrl = pending.baseUrl,
                hideUpcoming = hideUpcoming,
                landscapeCards = landscapeCards,
                pinned = false
            )

        // Record where this page ended so the next scroll asks for exactly
        // the following items. A short page is NOT treated as exhausted -
        // that assumption is what capped small-page addons at their first
        // batch. The catalog is only considered done when a page comes back
        // empty (see loadMoreForRail).
        railSourceOffset[railKeyOf(rail)] = metas.size

        return rail
    }

    private suspend fun fetchCatalogThrottled(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int = 0,
        maxItems: Int = Int.MAX_VALUE
    ): List<MetaPreview> {

        return catalogRequestSemaphore
            .withPermit {

                repository.getCatalog(
                    baseUrl = baseUrl,
                    type = type,
                    catalogId = catalogId,
                    skip = skip
                ).take(maxItems)
            }
    }

    /**
     * Stable identity for a rail across pagination updates: same value as
     * the LazyColumn key built in HomeScreen (minus the type prefix). Both
     * sides must stay in sync.
     */
    private fun railKeyOf(rail: Rail): String {

        return railKeyOf(rail.addonName, rail.catalogId, rail.type)
    }

    /**
     * The same identity, from its parts.
     *
     * Exists so a rail's identity is knowable before the rail is built, which is
     * what [previousLandscapeArt] needs to hand a rebuilding rail the artwork of
     * the one it is replacing.
     */
    private fun railKeyOf(addonName: String, catalogId: String?, type: String): String {

        return addonName + "::" + catalogId + "::" + type
    }


    /**
     * Kids-profile replacement for the pinned "Top ... Today" rails: two
     * hardcoded rails ("Top Kids Movies" / "Top Kids Shows") sourced from
     * TMDB discover — popular family + animation, certified-release floor —
     * so kids get a real, always-populated version of the rows the main
     * profile sees. Every item still runs through the ceiling filter
     * (TMDB certification check) before it lands on the rail.
     */
    private suspend fun loadPinnedKidsRails(
        result: MutableList<Rail>,
        hideUpcoming: Boolean,
        landscapeCards: Boolean
    ) {
        val isTv = listOf(false, true)
        coroutineScope {
            isTv.map { tv ->
                async {
                    try {
                        val filters = com.kennyb1201.kbstream.data.kb.KBFilters(
                            // Movies: Animation OR Family. Shows: Kids OR Animation.
                            // (OR-comma on purpose; adult-tagged genres like
                            // Action & Adventure or News would leak in.)
                            withGenres = if (tv) "10762,16" else "16,10751",
                            voteCountGte = 20,
                            releaseDateGte = "1970-01-01"
                        )
                        val items = tmdbRepository.discoverKB(
                            mediaType = if (tv) "tv" else "movie",
                            page = 1,
                            sortBy = "popularity.desc",
                            filters = filters
                        ).orEmpty()
                            .take(INITIAL_RAIL_PAGE_SIZE)

                        val metas = items.map { item ->
                            MetaPreview(
                                id = "tmdb:" + item.id,
                                type = if (tv) "series" else "movie",
                                name = item.name ?: item.title.orEmpty(),
                                poster = item.posterPath
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { TmdbRepository.POSTER_BASE + it },
                                background = item.backdropPath
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { TmdbRepository.BACKDROP_BASE + it },
                                releaseInfo = (item.firstAirDate ?: item.releaseDate)
                                    ?.takeIf { it.length >= 4 }
                                    ?.take(4)
                            )
                        }

                        // The app-wide digital-release filter applies to the
                        // kids rails too. It did not: these rails were the one
                        // path that opted out, so a not-yet-at-home title - an
                        // upcoming family movie whose only copies are
                        // theatrical rips - showed here while every add-on rail
                        // hid it. Then the belt-and-braces ceiling re-check
                        // (discoverKB already filters when kids mode is on).
                        val filtered =
                            if (hideUpcoming) {
                                tmdbRepository.kidsFilterMetas(
                                    applyDigitalAvailabilityFilter(
                                        filterUpcoming(metas)
                                    )
                                )
                            } else {
                                tmdbRepository.kidsFilterMetas(metas)
                            }

                        if (filtered.isEmpty()) return@async null

                        val rail = Rail(
                            addonName = KIDS_ADDON_NAME,
                            catalogName = if (tv) "Top Kids Shows" else "Top Kids Movies",
                            type = if (tv) "series" else "movie",
                            items = filtered,
                            catalogId = if (tv) "top_kids_shows" else "top_kids_movies",
                            baseUrl = null,
                            landscapeArt = previousLandscapeArt[
                                railKeyOf(
                                    KIDS_ADDON_NAME,
                                    if (tv) "top_kids_shows" else "top_kids_movies",
                                    if (tv) "series" else "movie"
                                )
                            ] ?: emptyMap(),
                            // "Top Kids Movies" is a standing too.
                            ranked = true
                        )

                        railInfo[railKeyOf(rail)] = RailInfo(
                            addonName = KIDS_ADDON_NAME,
                            catalogId = rail.catalogId ?: "",
                            catalogType = rail.type,
                            catalogRawName = rail.catalogName,
                            baseUrl = "",
                            hideUpcoming = hideUpcoming,
                            landscapeCards = landscapeCards,
                            pinned = true
                        )

                        // The source is one fixed TMDB page — no pagination.
                        exhaustedRails.add(railKeyOf(rail))

                        rail
                    } catch (e: Exception) {
                        Log.e("HOME_RAILS", "kids pinned rail load failed tv=$tv: " + e.message, e)
                        null
                    }
                }
            }
                .awaitAll()
                .filterNotNull()
                .forEach { rail -> result += rail }
        }
    }

    private suspend fun loadPinnedTopTodayRails(
        result: MutableList<Rail>,
        hideUpcoming: Boolean,
        landscapeCards: Boolean
    ) {

        val baseUrl = TOP_TODAY_MANIFEST_URL
            .substringBefore("/manifest.json")
            .removeSuffix("/")

        // Pinned rails load in parallel (previously sequential — with the
        // request semaphore tightened this serialized the whole home load),
        // preserving TOP_TODAY_CATALOGS order in the result list.
        coroutineScope {

            TOP_TODAY_CATALOGS
                .map {
                    (
                        catalogId,
                        type,
                        catalogName
                    ) ->
                    async {

                        try {

                            val metas =
                                fetchCatalogThrottled(
                                    baseUrl = baseUrl,
                                    type = type,
                                    catalogId = catalogId,
                                    skip = 0
                                )

                            if (
                                metas.isEmpty()
                            ) {
                                return@async null
                            }

                            // App-wide digital-release filter applies to the
                            // pinned rails too (same toggle as addon rails),
                            // followed by the kids-mode ceiling filter.
                            val filteredMetas =
                                if (hideUpcoming) {
                                    tmdbRepository.kidsFilterMetas(
                                        applyDigitalAvailabilityFilter(metas)
                                    )
                                } else {
                                    tmdbRepository.kidsFilterMetas(metas)
                                }

                            if (
                                filteredMetas.isEmpty()
                            ) {
                                return@async null
                            }

                            val rail =
                                Rail(
                                    addonName = TOP_TODAY_ADDON_NAME,
                                    catalogName = formatCatalogName(catalogName),
                                    type = type,
                                    items = filteredMetas,
                                    catalogId = catalogId,
                                    baseUrl = baseUrl,
                                    landscapeArt = previousLandscapeArt[
                                        railKeyOf(TOP_TODAY_ADDON_NAME, catalogId, type)
                                    ] ?: emptyMap(),
                                    // The row is a ranking - "Top Movies
                                    // Today" - and the add-on states it in
                                    // the order it sends.
                                    ranked = true
                                )

                            railInfo[railKeyOf(rail)] =
                                RailInfo(
                                    addonName = TOP_TODAY_ADDON_NAME,
                                    catalogId = catalogId,
                                    catalogType = type,
                                    catalogRawName = catalogName,
                                    baseUrl = baseUrl,
                                    hideUpcoming = hideUpcoming,
                                    landscapeCards = landscapeCards,
                                    pinned = true
                                )

                            railSourceOffset[railKeyOf(rail)] = metas.size

                            rail
                        } catch (e: Exception) {

                            Log.e(
                                "HOME_RAILS",
                                "pinned Top Today load failed " +
                                    "catalog=$catalogId: " +
                                    e.message,
                                e
                            )

                            null
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
                .forEach { rail ->
                    result += rail
                }
        }
    }

    private fun refreshWatchedStatus(
        rails: List<Rail>
    ) {

        viewModelScope.launch {

            val requestVersion =
                watchedRefreshMutex.withLock {

                    watchedRefreshVersion += 1

                    watchedRefreshVersion
                }

            try {

                val preloadItems =
                    rails
                        .asSequence()
                        .flatMap { rail ->
                            rail.items.asSequence()
                        }
                        .mapNotNull { meta ->

                            // Accept BOTH id forms: imdb "tt…" ids (addon
                            // rails, Continue Watching) and "tmdb:<n>" ids
                            // (KB/TMDB-discover rails — the hardcoded kids
                            // rails). The old tt-only filter silently
                            // dropped every tmdb-keyed item, which is why
                            // the kids profile showed no watched markers:
                            // its rails are 100% tmdb ids. The repository
                            // stores and resolves both forms under the same
                            // "type::id" cache key, so no conversion is
                            // needed.
                            val id =
                                meta.id
                                    .trim()
                                    .takeIf {
                                        it.startsWith("tt") ||
                                            it.startsWith("tmdb:")
                                    }
                                    ?: return@mapNotNull null

                            val mediaType =
                                normalizeMediaType(
                                    meta.type
                                )
                                    ?: return@mapNotNull null

                            id to mediaType
                        }
                        .distinct()
                        .toList()

                if (
                    preloadItems.isEmpty()
                ) {

                    val isCurrent =
                        watchedRefreshMutex
                            .withLock {

                                requestVersion ==
                                    watchedRefreshVersion
                            }

                    if (isCurrent) {

                        _watchedKeys.value =
                            emptySet()
                    }

                    return@launch
                }

                val resolvedWatchedKeys =
                    watchedStatusRepository
                        .preloadAndGetWatchedKeys(
                            preloadItems
                        )

                val resolvedPartialWatchedKeys =
                    watchedStatusRepository
                        .preloadAndGetPartiallyWatchedKeys(
                            preloadItems
                        )

                val isCurrent =
                    watchedRefreshMutex
                        .withLock {

                            requestVersion ==
                                watchedRefreshVersion
                        }

                if (
                    !isCurrent
                ) {
                    return@launch
                }

                _watchedKeys.value =
                    resolvedWatchedKeys


                // The completed checkmark wins over the eye badge when a
                // show resolves to both.
                _partialWatchedKeys.value =
                    resolvedPartialWatchedKeys - resolvedWatchedKeys
                Log.d(
                    "HOME_WATCHED",
                    "marker refresh complete: " +
                        "input=${preloadItems.size}, " +
                        "watched=${resolvedWatchedKeys.size}, " +
                        "rails=${rails.size}"
                )

            } catch (
                e: kotlinx.coroutines.CancellationException
            ) {

                throw e

            } catch (e: Exception) {

                Log.e(
                    "HOME_WATCHED",
                    "marker refresh failed: ${e.message}",
                    e
                )
            }
        }
    }


    override fun onCleared() {

        periodicRefreshJob?.cancel()

        super.onCleared()
    }

    private suspend fun probeInstalledAddonsForMeta(
    id: String,
    type: String
): Meta? {
    // Same ordering as DetailViewModel: idPrefix-declaring addons that match
    // the id first, then legacy accept-all addons, then the rest.
    val candidates =
        addonManager.orderMetaAddonsForId(
            addons = addonManager.installedAddons.value,
            rawId = id,
            type = type
        )

    for (addon in candidates) {
        val baseUrl =
            addon.manifestUrl
                .removeSuffix("manifest.json")
                .removeSuffix("/")

        val meta = safeSuspend {
            repository.getMeta(
                baseUrl = baseUrl,
                type = type,
                id = id
            )
        }

        if (meta != null) return meta
    }
    return null
    }

    // NOTE: this init block MUST sit below every property declaration in
    // this class. Kotlin initializes properties top-down, and with
    // Dispatchers.Main.immediate a collector/launch started in init can
    // run DURING the constructor - touching any property declared below
    // init would NPE (same initialization-order crash as SearchViewModel,
    // Sentry ANDROID-9). loadRails()/observeUpNext() read late-declared
    // state, so the block was relocated above companion object.
    init {

        Log.e(
            "HOME_VM",
            "HomeViewModel init"
        )

        observeAddonChanges()

        loadRails()

        observeUpNext()

        // A finished title leaves the rail only when the tracker feeds are
        // read again after the completion has been pushed (see
        // ContinueWatchingRefreshBus). Home's ON_RESUME refresh can beat that
        // push, so the player asks for one more merge once the push has
        // actually landed - otherwise the card lingers until the next resume.
        viewModelScope.launch {
            ContinueWatchingRefreshBus.requests.collect {

                // The completion invalidated Simkl's feed, but Home's own
                // resume refresh may already have re-cached it (see
                // SimklCacheKeys.mayPublishContinueWatchingFetch): drop the
                // feed here as well, so this merge cannot read the
                // pre-completion list from its TTL cache and leave the
                // finished show on the rail for the rest of the window.
                runCatchingCancellable {
                    simklRepository.clearContinueWatchingCache()
                }

                refreshUpNext()

                // A lagging Simkl feed: the re-merge above may have read the
                // pre-completion list (the push had resolved on OUR side, but
                // Simkl's feed had not caught up yet) and re-cached it. Drop
                // the feed and re-merge a few more times over the next couple
                // of minutes so the finished title leaves without the viewer
                // bouncing out of Home and back. A second completion restarts
                // the window rather than stacking one.
                completionRefreshRetryJob?.cancel()
                completionRefreshRetryJob =
                    launch {
                        // The schedule holds ABSOLUTE offsets from this moment,
                        // so the waits are the gaps between them - delaying by
                        // each entry directly would make every retry wait for
                        // the sum of all the entries before it.
                        var retriedAtMs = 0L
                        for (retryAtMs in COMPLETION_REFRESH_RETRY_MS) {
                            delay(retryAtMs - retriedAtMs)
                            retriedAtMs = retryAtMs
                            runCatchingCancellable {
                                simklRepository.clearContinueWatchingCache()
                            }
                            refreshUpNext()
                        }
                    }
            }
        }

        observeProfileSwitches()

        // Instant Continue Watching: seed the rail from the warm watch
        // history right away so the UI has cards the moment Home renders;
        // the full enriched pipeline in observeUpNext replaces this
        // snapshot when it finishes (TMDB enrichments + Simkl merge).
        viewModelScope.launch {
            publishInstantUpNextSnapshot()
        }

        startPeriodicSimklRefresh()

        viewModelScope.launch {

            WatchStateBus.updates.collect { update ->

                val current =
                    _watchedKeys.value
                        .toMutableSet()

                if (update.isWatched) {
                    current.add(update.key)
                } else {
                    current.remove(update.key)
                }

                _watchedKeys.value =
                    current

                // The badge state as the write resolved it - not "anything
                // that is not watched is bare". A manual whole-title mark
                // resolves to the checkmark (no eye); unmarking PART of a
                // series resolves to the eye, and this event is what paints
                // it immediately instead of leaving the tile bare until the
                // next marker preload.
                val partial =
                    _partialWatchedKeys.value
                        .toMutableSet()

                if (update.isPartiallyWatched) {
                    partial.add(update.key)
                } else {
                    partial.remove(update.key)
                }

                _partialWatchedKeys.value =
                    partial

                // Dynamic addon catalogs (BingeCat "Because you watched …",
                // collections that grow as you watch) are computed from the
                // watch history server-side. Rail items were fetched once
                // and otherwise stay frozen until a profile switch — so
                // schedule ONE debounced catalog refetch per burst of watch
                // writes (marking a whole season emits hundreds of events).
                scheduleDynamicCatalogRefresh()
            }
        }
    }

    /**
     * Debounced rebuild of the addon catalog rails after watch-state
     * changes. The delay lets a burst of bus events (bulk season mark,
     * binge playback writing progress) settle so the rebuild runs once,
     * not per event. clearCatalogCache=true forces actual network fetches:
     * a warm cache would just re-serve the pre-watch list and the whole
     * exercise would be pointless.
     */
    private var dynamicCatalogRefreshJob: kotlinx.coroutines.Job? = null

    private fun scheduleDynamicCatalogRefresh() {
        dynamicCatalogRefreshJob?.cancel()
        dynamicCatalogRefreshJob = viewModelScope.launch {
            delay(DYNAMIC_CATALOG_REFRESH_DELAY_MS)

            // Every bus event is an explicit watched-state change (mark /
            // unmark / partial), and those change what Continue Watching
            // should hold: a show whose season was just unmarked has
            // something to resume again, while one just marked watched does
            // not. Bump the trigger so the up-next rail re-merges against a
            // FRESH Simkl feed instead of the list built before the change
            // (the debounce keeps a burst of writes to one recompute).
            // Hold this rebuild out of a playing video's way (see
            // EpgWriteGate). The event behind it is a watch write, and a
            // session files one as it starts - the first seconds of playback -
            // so the three-second debounce in front of this lands exactly in
            // the window a viewer reports as "started playing, then buffered
            // for a second or two". What runs then is a FULL rails rebuild:
            // clearCatalogCache forces real network fetches, up to
            // MAX_CONCURRENT_CATALOG_REQUESTS of them at once, plus the TMDB
            // enrichment each rail resolves through - all of it on the link
            // the video's own loader is filling from. The gate is what already
            // keeps guide writes out of a starting player for the same reason
            // (it is named in EpgWriteGate's own file after a 6.9s rebuffer
            // stall from exactly this overlap); the rebuild now waits with
            // them. A HOLD, not a skip: the marks that triggered it still have
            // to show, and Home's ON_RESUME path is where the viewer sees it.
            EpgWriteGate.holdWhilePlaying()

            _refreshTrigger.value += 1

            runCatchingCancellable {
                loadRailsInternal(
                    forceRefresh = true,
                    clearCatalogCache = true,
                    coalesce = true
                )
            }
        }
    }

    companion object {

        /**
         * Ceiling on the per-build artwork memo (see landscapeLookupMemo),
         * sized for the rail items one build resolves. Paging far past it drops
         * the memo and pays for a repeated lookup, which is what every lookup
         * cost before the memo existed.
         */
        private const val LANDSCAPE_MEMO_MAX_KEYS = 2_000

        // Dwell before hero network resolution kicks in (see resolveHeroMeta).
        // 150ms: still rides out fast D-pad scrolls (one focus event per
        // card), but 100ms less dead time per resolve than the old 250ms —
        // artwork+detail are cached/aggressive enough to absorb the extra
        // in-flight requests.
        private const val HERO_RESOLVE_DWELL_MS = 150L

        private const val TMDB_MAX_CONCURRENT_LOOKUPS =
            5

        /**
         * Concurrent catalog requests a rail build keeps in flight.
         *
         * Sized to the add-on client's own per-host budget
         * (com.kennyb1201.kbstream.data.addon.ADDON_MAX_REQUESTS_PER_HOST), not
         * below it. While this sat at 6 the transport could run twice as many
         * catalog fetches as the app would ever hand it, so the build - not the
         * network - was the queue: on a field report addon.catalog averaged
         * 603ms over 117 calls, and 23 catalogs per Home six at a time is four
         * waves where two will do. The pinned rails share this semaphore
         * through fetchCatalogThrottled, so it covers pagination too.
         */
        internal const val MAX_CONCURRENT_CATALOG_REQUESTS =
            12

        // How many items a TMDB-sourced rail (the kids picks) keeps from its
        // single discover page. Addon catalogs no longer cap the first page:
        // they page by the addon's own batch size via railSourceOffset, so a
        // small-page addon still reaches its whole catalog.
        private const val INITIAL_RAIL_PAGE_SIZE =
            30

        // Hard ceiling on how many items one rail / grid accumulates. Real
        // addons page far below this; it exists only so a misbehaving addon
        // that never reports the end cannot exhaust memory. The identical-
        // page check in loadMoreForRail still ends a skip-ignoring addon at
        // its second page, long before this.
        private const val MAX_RAIL_ITEMS =
            4000

        // Paging 3's bookkeeping for the catalog grid. Batch addons serve
        // their own page size anyway (the source forwards the raw count, not
        // this), so this only has to be a sane non-zero page for Paging's
        // initial-load arithmetic.
        private const val CATALOG_GRID_PAGE_SIZE =
            100

        // How far from the end the grid asks for its next page. The rails
        // size their runway in items from the viewport, capped at
        // PREFETCH_MAX_ITEMS because a wall-to-wall grid lays out far more
        // cells per screen than a rail lays out cards.
        private const val CATALOG_GRID_PREFETCH_DISTANCE =
            16

        private const val UP_NEXT_DEBOUNCE_MS =
            100L

        private const val MAX_FORWARD_SEASON_LOOKAHEAD =
            50

        private const val MAX_SIMKL_UP_NEXT_ITEMS =
            30

        private const val MAX_CONCURRENT_SIMKL_UP_NEXT_LOOKUPS =
            3

        private const val MAX_CONCURRENT_UP_NEXT_LOOKUPS =
            6

        private const val UP_NEXT_SEASON_BATCH =
            4

        private const val MAX_MDBLIST_UP_NEXT_ITEMS =
            30

        private const val PERIODIC_SIMKL_REFRESH_MS =
            15 * 60 * 1000L

        /**
         * How long after a completion Home keeps re-merging Continue Watching.
         *
         * Simkl's Continue Watching feed can lag the watched-push we send on
         * completion by a minute or more server-side. The completion asks for
         * one immediate re-merge, but if that read lands before the feed has
         * caught up it re-caches the pre-completion list - and the next read
         * is served from that copy for the feed's whole 3-minute TTL, which is
         * exactly how a finished episode "stays" on the rail for a couple of
         * minutes. These are the delays of a few follow-up re-merges, each of
         * which drops the cached feed first, so a lagging feed is picked up on
         * its own. Bounded (the periodic refresh and the TTL cover anything
         * after this) and monotonic.
         */
        private val COMPLETION_REFRESH_RETRY_MS =
            longArrayOf(
                15_000L,
                40_000L,
                75_000L,
                120_000L,
                180_000L
            )

        /** Watch-write burst settle time before dynamic catalog rails refetch. */
        private const val DYNAMIC_CATALOG_REFRESH_DELAY_MS = 3_000L

        /**
         * On Home resume, rails older than this force a network refetch:
         * the app may have sat backgrounded for hours while the addon's
         * dynamic catalogs (BingeCat because-you-watched, etc.) changed.
         */
        private const val RAILS_STALE_RESUME_MS = 10 * 60 * 1000L

        /** Cold-start rail retry tuning: 2 retries at +4s / +8s. */
        private const val RAIL_LOAD_RETRY_MAX_ATTEMPTS = 2

        private const val RAIL_LOAD_RETRY_BASE_DELAY_MS = 4_000L

        private const val KIDS_ADDON_NAME =
            "KBStream Kids Picks"

        private const val TOP_TODAY_ADDON_NAME =
            "TMDB Top Today"

        private const val TOP_TODAY_MANIFEST_URL =
            "https://toptoday.llamayu.com/landscapeTags=true|landscapeLogos=true|landscapeRanked=false|portraitTags=true|portraitLogos=false|portraitRanked=true|posterLang=en|digitalOnly=true|listLang=en/manifest.json"

        private val TOP_TODAY_CATALOGS =
            listOf(

                Triple(
                    "top_movies_today",
                    "movie",
                    "Top Movies Today"
                ),

                Triple(
                    "top_shows_today",
                    "series",
                    "Top Shows Today"
                )
            )
    }
}
