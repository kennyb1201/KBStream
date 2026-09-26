package com.kennyb1201.kbstream.ui.home

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.airdates.AirDateCorrection
import com.kennyb1201.kbstream.data.airdates.TvmazeAirDateRepository
import com.kennyb1201.kbstream.data.history.WatchHistoryDao
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryRepository
import com.kennyb1201.kbstream.data.library.LibraryMirror
import com.kennyb1201.kbstream.data.library.LocalLibraryStore
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.mdblist.MdbListPlaybackItem
import com.kennyb1201.kbstream.data.reporting.PerfTrace
import com.kennyb1201.kbstream.data.simkl.SimklContinueWatchingItem
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.simkl.UPCOMING_DIAGNOSTICS
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbEpisodeAirInfo
import com.kennyb1201.kbstream.data.tmdb.TmdbHeroArtworkRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tv.TvLauncherPublisher
import com.kennyb1201.kbstream.data.watched.WatchStateBus
import com.kennyb1201.kbstream.data.watched.WatchedEpisodeState
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.data.tmdb.bestLogoPath
import com.kennyb1201.kbstream.data.tmdb.alternatePosterPath
import com.kennyb1201.kbstream.data.tmdb.cardBackdropPath
import com.kennyb1201.kbstream.data.tmdb.tmdbImageOriginal
import com.kennyb1201.kbstream.data.tmdb.isAvailableAtHome
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import org.json.JSONObject

private const val PREFS_DISMISSED_UPNEXT =
    "continue_watching_dismissals"

/**
 * Ceiling on how many caught-up shows one Upcoming refresh looks up on TMDB.
 * The candidate list is already narrowed to followed shows Simkl knows have
 * unaired episodes; this only keeps a huge library from turning one refresh
 * into dozens of lookups.
 *
 * Raised from 25 when a finished show started counting as a candidate too
 * (a returning show reads as "completed" until its new season airs), which
 * doubled the eligible set: at 25 a library with plenty of returning shows
 * silently dropped the tail of them from the rail, which is the symptom the
 * cap must never cause. Every candidate is one cached TMDB detail lookup.
 */
private const val MAX_CAUGHT_UP_UPCOMING_ITEMS =
    50

/**
 * How long a loaded set of caught-up Upcoming cards is reused. The Upcoming
 * schedule re-derives on every Continue Watching publish (two of those per
 * refresh, plus one per watched-state change), and the candidates behind it
 * are network work: within this window the previous answer stands.
 */
private const val CAUGHT_UP_UPCOMING_TTL_MS =
    60_000L

/** Sync bookkeeping key inside the dismissals prefs store. */
private const val DISMISSALS_SYNCED_AT = "dismissals_synced_at"

data class Rail(
    val addonName: String,
    val catalogName: String,
    val type: String,
    val items: List<MetaPreview>,
    val catalogId: String? = null,
    val baseUrl: String? = null,
    // Landscape-card artwork per item id ("movie:tmdb:603" style key):
    // resolved backdrop + clearlogo, filled when the landscape toggle is
    // on. Poster mode never reads these.
    val landscapeArt: Map<String, Pair<String?, String?>> = emptyMap()
)

enum class UpNextBadge {
    CONTINUE_WATCHING,
    NEXT_UP,
    NEW_EPISODE,
    NEW_SEASON
}

data class UpNextItem(
    val id: String,
    val title: String,
    val poster: String?,
    val badge: UpNextBadge,

    // Display metadata
    val showTitle: String? = null,
    val episodeTitle: String? = null,
    val episodeDescription: String? = null,
    val tmdbRating: Double? = null,
    val imdbRating: Double? = null,
    val runtimeMinutes: Int? = null,
    val remainingMinutes: Int? = null,
    val episodesRemaining: Int? = null,
    val episodesWatched: Int? = null,    val episodesTotal: Int? = null,
    val episodeThumbnail: String? = null,
    val backdrop: String? = null,
    val clearLogo: String? = null,

    // Upcoming-rail items only: the relative air-date label ("Today",
    // "In 5 days") and the absolute calendar date ("Mon, Sep 15") so the
    // hero can say "Airs Tomorrow" with the real date underneath.
    val airDateLabel: String? = null,
    val airDateFull: String? = null,

    val subtitle: String? = null,
    val progressPercent: Float? = null,
    val streamUrl: String? = null,
    val parentId: String? = null,
    val parentType: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeStreamId: String? = null,
    val startPositionMs: Long = 0L,
    val recencyTimestamp: Long = 0L,

    /**
     * Raw watch_history row id backing this item ("history:..." without the
     * prefix). Non-history items (SIMKL) leave it null. Long-press Remove
     * uses it to fall back when parentId is unavailable.
     */
    val historyRowId: String? = null,

    /**
     * Simkl /sync/playback session id when this card came from a paused
     * Simkl playback session. Long-press Remove deletes the session so the
     * card stops reappearing from the remote Continue Watching feed.
     */
    val playbackId: Int? = null,

    /**
     * True when this card's episode is the last aired episode of its
     * season. Renders as the "SEASON FINALE" tag on the rail and hero.
     */
    val isSeasonFinale: Boolean = false,

    /**
     * True when this card's episode is the show's final aired episode
     * (the last episode of the last aired season). Takes precedence over
     * [isSeasonFinale] and renders as the "SERIES FINALE" tag.
     */
    val isSeriesFinale: Boolean = false,

    /**
     * TMDB "next episode to air" for this title (season, episode number
     * and air date), captured from the same detail response the Continue
     * Watching enrichment already fetches — so the Upcoming rail costs no
     * extra network calls. Null when unknown / not a returning series.
     */
    val nextEpisodeAir: TmdbEpisodeAirInfo? = null,

    /** TMDB show id when enrichment resolved one, for episode-title fallback. */
    val tmdbId: Int? = null
) {

    /**
     * The watched count the hero's "X of Y aired episodes watched" line shows -
     * reported as 0 rather than as nothing whenever the total is known.
     *
     * [episodesWatched] is null for a title with no COMPLETED episode yet: the
     * local-history builder stores the count with `takeIf { it > 0 }`, so a show
     * the viewer has just started arrives with a total and no count at all - and
     * the hero, which needs both halves to make a sentence, showed no episode
     * line for exactly the case the line is asked about (reported: started a
     * show, paused mid-episode 1, and the hero was the only card without a
     * count, since every other show had at least one episode finished).
     *
     * A known total with no count means zero watched. A movie, or a show whose
     * episode list never resolved, has no total - and those still get no line,
     * because there is nothing truthful to say. The count is clamped to the
     * total so a stale watched tally cannot read "13 of 12".
     */
    val episodesWatchedForDisplay: Int?
        get() = episodesTotal
            ?.takeIf { it > 0 }
            ?.let { total -> (episodesWatched ?: 0).coerceIn(0, total) }
}

/**
 * Media type as the Continue Watching dedupe rule sees it. Anything the app
 * cannot place (a rail type like "anime") collapses to "unknown" rather than
 * inventing a second bucket for the same show.
 */
internal fun upNextMediaType(type: String?): String =
    when (type?.trim()?.lowercase()) {
        "movie" -> "movie"
        "series", "show", "tv" -> "series"
        else -> "unknown"
    }

/**
 * Dedupe id form: "tt..." stays as it is, prefixed ids lose their prefix
 * ("tmdb:123" and "simkl:9" become "123" / "9").
 */
internal fun upNextIdentifier(rawId: String?): String? {
    if (rawId.isNullOrBlank()) return null

    val trimmed =
        rawId.trim().lowercase()

    return when {
        trimmed.startsWith("tt") -> trimmed
        trimmed.startsWith("tmdb:") -> trimmed.removePrefix("tmdb:")
        trimmed.startsWith("simkl:") -> trimmed.removePrefix("simkl:")
        else -> trimmed
    }
}

/** Id prefixes that are internal lookup keys, never part of a title. */
private val RAW_ID_PREFIXES = setOf("tmdb", "imdb", "simkl", "tvdb", "trakt")

/**
 * True when [value] is an internal media id rather than a name.
 *
 * Cards reach the rail titled with one of these when the source's own name
 * field was missing or its enrichment failed: "tmdb:12345", "tt0111161", or a
 * bare "12345". The tell is that the same failure is what leaves the card
 * with no artwork, so those two symptoms travel together - which is why a
 * bare number is only an id when the card has NO poster. Real titles are
 * numbers too ("1917", "2012"), and the prefixed forms are unambiguous.
 */
internal fun looksLikeRawMediaId(
    value: String,
    hasArtwork: Boolean = true
): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return false

    // tt0111161
    if (
        trimmed.startsWith("tt", ignoreCase = true) &&
        trimmed.length > 2 &&
        trimmed.substring(2).all { it.isDigit() }
    ) {
        return true
    }

    // tmdb:12345 / imdb:tt0111161 / simkl:123 / tvdb:456
    val prefix = trimmed.substringBefore(":", missingDelimiterValue = "")
    if (prefix.lowercase() in RAW_ID_PREFIXES) {
        val rest = trimmed.substringAfter(":", missingDelimiterValue = "")
        if (rest.isNotEmpty() && rest.substringAfter("tt").all { it.isDigit() }) {
            return true
        }
    }

    // Bare number: only an id when nothing identifies the card as a real
    // title, i.e. it has no artwork either.
    return !hasArtwork && trimmed.all { it.isDigit() }
}

/**
 * A card's display title, or null when there is no real name to show.
 *
 * Blank and id-like names both mean "this card has no title". Callers fall
 * back to a resolved name or skip the card; putting an internal id on screen
 * is what made the phantom duplicates so confusing - they could not even be
 * paired with the right card by name.
 */
internal fun upNextDisplayTitleOrNull(
    raw: String?,
    hasArtwork: Boolean = true
): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (looksLikeRawMediaId(trimmed, hasArtwork)) return null
    return trimmed
}

/** Title key: the fallback identity of a show when its ids disagree. */
internal fun upNextTitleKey(item: UpNextItem): String =
    "title:${upNextMediaType(item.parentType)}:${item.title.trim().lowercase()}"

/** One card per show: keyed by parent id when the card has one, title otherwise. */
internal fun upNextShowKey(item: UpNextItem): String {
    val normalizedParentId =
        upNextIdentifier(item.parentId)

    if (normalizedParentId != null) {
        return "parent:${upNextMediaType(item.parentType)}:$normalizedParentId"
    }

    return upNextTitleKey(item)
}

/**
 * Every identity this card carries, for the duplicate-collapse pass.
 *
 * A show reaches the rail under more than one id at once: local history and
 * add-on catalogs write "tt..." while the tracker cards and TMDB enrichment
 * use "tmdb:<n>". [upNextShowKey] can only name the one flavour a card
 * happens to carry, so the same show produced two different keys and survived
 * the collapse.
 *
 * What made that visible was a card whose enrichment had failed: no poster,
 * and the raw navigation id where its title should have been - so the title
 * fallback could not pair it with its twin either. Both twins resolve the
 * numeric TMDB id (enrichment fills it in, and a "tmdb:<n>" id normalizes to
 * the same number), so emitting a key per form the card knows lets them meet.
 *
 * [upNextShowKey] itself is deliberately NOT widened: it is the persisted
 * dismissal key, and changing its shape would orphan every dismissal already
 * stored on the device and in the cloud.
 */
internal fun upNextIdentityKeys(item: UpNextItem): Set<String> {
    val mediaType =
        upNextMediaType(item.parentType)

    return buildSet {
        upNextIdentifier(item.parentId)?.let { add("parent:$mediaType:$it") }

        item.tmdbId
            ?.takeIf { it > 0 }
            ?.let { add("parent:$mediaType:$it") }

        add(upNextTitleKey(item))
    }
}

/**
 * [upNextIdentityKeys] narrowed to one episode, so the same episode reached
 * from two id flavours pairs up - and a *different* episode of that show does
 * not, because it is a separate thing to continue.
 */
internal fun upNextEpisodeKeys(item: UpNextItem): Set<String> {
    val season =
        item.season

    val episode =
        item.episode

    if (season == null || episode == null) {
        return emptySet()
    }

    return upNextIdentityKeys(item)
        .mapTo(linkedSetOf()) { key -> "$key:$season:$episode" }
}

/**
 * Drops the redundant twin cards a show can pick up on the rail:
 *
 *  1. "up next" style cards (Next up / New episode / New season) for a show
 *     that already has an unfinished episode on the rail. A paused episode IS
 *     what "continue watching" means for that show, so the suggestion for the
 *     next one is only useful once the current episode is done.
 *  2. a remote card for the very same episode a local resume row covers,
 *     which can be keyed differently (imdb row vs tmdb/simkl card) and so
 *     survive the show-level dedupe. The local card wins: it is the one that
 *     can be resumed here with the exact stream it was paused on.
 *
 * Both cases are the same underlying bug - one show on the rail twice,
 * because the local row and the tracker card carry different id flavors
 * (which is how a show mid-S4E5 showed up alongside "New Episode S4E6").
 * Matching therefore falls back to the show title when the parent keys
 * disagree, and to the card's resolved TMDB id when even the titles cannot be
 * compared - a card whose enrichment failed carries the raw navigation id as
 * its title, which matches nothing (see [upNextIdentityKeys]).
 */
internal fun collapseDuplicateUpNextCards(
    items: List<UpNextItem>
): List<UpNextItem> {

    fun hasSomethingToResume(item: UpNextItem): Boolean =
        item.badge == UpNextBadge.CONTINUE_WATCHING ||
            item.startPositionMs > 0L ||
            (item.progressPercent ?: 0f) > 0f

    fun isLocal(item: UpNextItem): Boolean =
        item.historyRowId != null

    val resumeKeys =
        items
            .filter { item -> hasSomethingToResume(item) }
            .flatMap { item -> upNextIdentityKeys(item) }
            .toSet()

    val localEpisodeKeys =
        items
            .filter { item -> isLocal(item) }
            .flatMap { item -> upNextEpisodeKeys(item) }
            .toSet()

    if (resumeKeys.isEmpty() && localEpisodeKeys.isEmpty()) {
        return items
    }

    return items.filterNot { item ->
        val matchesAnInProgressShow =
            upNextIdentityKeys(item)
                .any { key -> key in resumeKeys }

        val redundantSuggestion =
            !hasSomethingToResume(item) &&
                matchesAnInProgressShow

        val remoteTwinOfALocalEpisode =
            !isLocal(item) &&
                upNextEpisodeKeys(item)
                    .any { key -> key in localEpisodeKeys }

        redundantSuggestion || remoteTwinOfALocalEpisode
    }
}

/**
 * One row in the Home "Upcoming" rail: a show's next unaired episode,
 * derived for free from the Continue Watching enrichment (the TMDB detail
 * it already fetches carries next_episode_to_air). No extra network calls.
 */
data class UpcomingEpisode(
    val id: String,
    val parentId: String,
    val parentType: String,
    val title: String,
    val poster: String?,
    val backdrop: String?,
    val season: Int,
    val episode: Int,
    val airDateEpochMs: Long,
    val airDateLabel: String,
    /** Episode title from TMDB, when available for the unaired episode. */
    val episodeTitle: String? = null,
    /** Absolute calendar date ("Mon, Sep 15") for the hero's date line. */
    val airDateFull: String = "",
    /** True when E01 — renders the card's badge as "NEW SEASON". */
    val isSeasonPremiere: Boolean = false
)

private data class ResolvedHomeSeriesTarget(
    val season: Int,
    val episode: Int,
    val streamId: String? = null,
    val startPositionMs: Long = 0L,
    val isResume: Boolean = false,
    val airDate: String? = null,
    val episodeTitle: String? = null,
    val episodeDescription: String? = null,
    val runtimeMinutes: Int? = null,
    val episodesWatched: Int? = null,
    val episodesTotal: Int? = null,
    val episodesRemaining: Int? = null,
    val episodeThumbnail: String? = null,
    val episodeRating: Double? = null,

    /** True when this target is the last aired episode of its season. */
    val isSeasonFinale: Boolean = false,

    /** True when this target is the show's final aired episode. */
    val isSeriesFinale: Boolean = false,
)

private data class ShowEpisodeTotals(
    val watched: Int,
    val total: Int
)

private sealed interface SimklUpNextResult {

    data class Success(
        val items: List<UpNextItem>
    ) : SimklUpNextResult

    data object NotConfigured : SimklUpNextResult

    data class Failed(
        val error: Throwable
    ) : SimklUpNextResult
}

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

    // Caps parallel TMDB release-date lookups for the digital filter.
    private val tmdbFilterSemaphore = Semaphore(permits = 4)

    // Caps parallel TMDB artwork lookups for landscape cards.
    private val landscapeArtSemaphore = Semaphore(permits = 6)

    private val watchedRefreshMutex =
        Mutex()

    private val simklWatchedEpisodesByShow =
        mutableMapOf<String, Set<Pair<Int, Int>>>()

    private val watchedEpisodeKeysByShow =
        mutableMapOf<String, Set<String>>()

    private val watchedStatePreloadInFlight =
        mutableSetOf<String>()

    private var upNextRequestVersion = 0L

    private var watchedRefreshVersion = 0L

    private var periodicRefreshJob: Job? = null

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
     * Upcoming episodes: one entry per in-progress show whose TMDB detail
     * carries a future "next episode to air", plus the next UNAIRED episode
     * of every show this profile is caught up on
     * ([loadCaughtUpUpcomingItems]) - a caught-up show has nothing to resume,
     * so it never reaches Continue Watching and this is the only rail that
     * can surface what it has coming, whether that is a new season or the
     * next episode of one already airing. Sorted by air date.
     *
     * Re-published whenever [upNext] changes, which is also when the watch
     * state behind both sources is freshest.
     */
    val upcomingSchedule: StateFlow<List<UpcomingEpisode>> =
        _upNext
            .asStateFlow()
            .map { items ->
                buildUpcomingSchedule(
                    items + loadCaughtUpUpcomingItems()
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
     * cancelled coroutine keep running instead of stopping -- it then
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
                // detail + artwork chain before being cancelled, wasting
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
                                    tmdbRepository.fetchEnrichedMetaCached(
                                        requestedId.trim(),
                                        requestedType
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
                                ?.let { tmdbImageOriginal(it) }
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
            ?: item.description
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
                        clearCatalogCache = false
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

                watchHistoryRepository.deleteResumeRowsForParent(parentId)

                // Fallback path removed a single row by its raw id; also
                // drop that exact row so the item always leaves the rail.
                item.historyRowId?.let { rowId ->
                    watchHistoryRepository.deleteById(rowId)
                }

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

                    // Playback-sourced cards (paused mid-title) live in a
                    // separate Simkl playback table - removing history alone
                    // does not clear the paused session, so the card would
                    // keep reappearing from the remote feed. A paused title
                    // is usually backed by BOTH a local resume row and a
                    // Simkl session, and the rail dedupe can surface the
                    // local twin (which carries no playbackId) - so sweep
                    // every open session matching this parent instead of
                    // only the one this card happened to carry.
                    simklRepository.deletePlaybackSessionsForParent(
                        parentId = parentId,
                        title = item.title
                    )
                    item.playbackId?.let { playbackId ->
                        simklRepository.deletePlaybackSession(
                            playbackId
                        )
                    }
                }

                // MDBList-backed cards come back from its paused playback
                // feed the same way; POST /scrobble/clear drops that session
                // so the removed title stops resurfacing.
                if (MdbListClient.isConfigured(getApplication())) {
                    runCatching {
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
            } catch (e: Exception) {

                Log.e(
                    "HOME_UPNEXT",
                    "Failed to remove continue watching parent=$parentId",
                    e
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

        if (
            dismissedContinueWatching.isEmpty() ||
            kidsSafe.isEmpty()
        ) {
            return kidsSafe
        }

        var changed =
            false

        val filtered =
            kidsSafe.filter { item ->

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
            runCatching {
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
                            runCatching {
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

            runCatching {

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

    /** Simkl is signed in, so long-press adds will mirror there too. */
    fun simklConnectedForLibrary(): Boolean =
        LibraryMirror.simklConnected(getApplication())

    /** MDBList API key is set, so long-press adds will mirror there too. */
    fun mdbListConnectedForLibrary(): Boolean =
        LibraryMirror.mdbListConnected(getApplication())

    /**
     * True when the title is already on this profile's local My List.
     * Accepts either id form (imdb or tmdb) so the check matches however
     * the entry was saved.
     */
    fun isInLocalLibrary(mediaType: String, imdbId: String?, tmdbId: Int?): Boolean {
        val appContext = getApplication<Application>()
        return (tmdbId != null &&
            LocalLibraryStore.isInMyList(appContext, mediaType, null, tmdbId)) ||
            (imdbId != null &&
                LocalLibraryStore.isInMyList(appContext, mediaType, imdbId, null))
    }

    /**
     * Long-press "Add to Library" on a catalog poster: saves to this
     * profile's local My List, then mirrors to the Simkl and/or MDBList
     * watchlists when connected (best-effort; local write always wins).
     */
    fun addToLibrary(
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?,
        title: String,
        year: Int? = null,
        posterUrl: String? = null
    ) {
        LibraryMirror.addToLibrary(
            context = getApplication(),
            scope = viewModelScope,
            mediaType = mediaType,
            imdbId = imdbId,
            tmdbId = tmdbId,
            title = title,
            year = year,
            posterUrl = posterUrl
        )
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

            runCatching {

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
        val railsEmpty = _rails.value.isEmpty()

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
                    clearCatalogCache = railsStale
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

    private data class RailInfo(
        val addonName: String,
        val catalogId: String,
        val catalogType: String,
        val catalogRawName: String,
        val baseUrl: String,
        val hideUpcoming: Boolean,
        val landscapeCards: Boolean,
        val pinned: Boolean
    )

    fun refreshWatchedStatusForCurrentRails() {

        refreshWatchedStatus(
            _rails.value
        )
    }

    // ---- Catalog grid ("Open in Grid" from a rail's long-press menu) ----

    /** Full-catalog grid state: one addon catalog browsed as a poster grid. */
    data class CatalogGridState(
        val title: String,
        val addonName: String,
        val items: List<MetaPreview> = emptyList(),
        val isLoading: Boolean = true,
        val isLoadingMore: Boolean = false,
        val hasMore: Boolean = true,
        val error: String? = null
    )

    private val _catalogGrid =
        MutableStateFlow<CatalogGridState?>(null)

    val catalogGrid: StateFlow<CatalogGridState?> =
        _catalogGrid.asStateFlow()

    // Grid pagination mirrors the rail bookkeeping but is independent: the
    // grid keeps paging past the rail's current offset.
    private var gridNextSkip = 0
    private var gridLoadJob: kotlinx.coroutines.Job? = null

    /**
     * Route-based variant: reopens the grid for a catalog identified by its
     * Home rail title + addon name (survives process restoration). No-op if
     * that catalog is already open.
     */
    fun openCatalogInGrid(title: String, addonName: String) {

        val current =
            _catalogGrid.value

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
     * Seeds with the rail's already-loaded items (instant paint), then
     * fetches the next page in the background.
     */
    fun openCatalogInGrid(rail: Rail) {

        val info =
            railInfo[railKeyOf(rail)]

        gridLoadJob?.cancel()

        val seeded =
            CatalogGridState(
                title = rail.catalogName,
                addonName = rail.addonName,
                items = rail.items
            )

        _catalogGrid.value =
            seeded

        if (
            info == null
        ) {
            // Rail came from a code path without pagination info
            // (e.g. Continue Watching); nothing further to page.
            _catalogGrid.value =
                seeded.copy(
                    isLoading = false,
                    hasMore = false
                )
            return
        }

        // Resume exactly where the rail left off in the source catalog. Falls
        // back to the displayed count only if the offset was never recorded
        // (e.g. a rail restored from cache), which is correct for the common
        // contiguous case.
        gridNextSkip =
            railSourceOffset[railKeyOf(rail)] ?: rail.items.size

        // A rail already known to be exhausted has nothing left to page.
        if (railKeyOf(rail) in exhaustedRails) {
            _catalogGrid.value =
                seeded.copy(
                    isLoading = false,
                    hasMore = false
                )
            return
        }

        gridLoadJob =
            fetchGridPage(info)
    }

    fun loadMoreGridItems() {

        val state =
            _catalogGrid.value
                ?: return

        if (
            state.isLoading ||
            state.isLoadingMore ||
            !state.hasMore
        ) {
            return
        }

        val info =
            railInfo.values.firstOrNull { info ->
                formatCatalogName(info.catalogRawName) == state.title &&
                    info.addonName == state.addonName
            }
                ?: return

        gridLoadJob =
            fetchGridPage(info)
    }

    fun closeCatalogGrid() {

        gridLoadJob?.cancel()
        gridLoadJob = null
        _catalogGrid.value = null
    }

    /**
     * Re-fetches the open grid's current page after [CatalogGridState.error].
     *
     * Neither existing entry point can serve as a retry: [openCatalogInGrid]
     * returns early when the grid already holds this same catalog, and
     * [loadMoreGridItems] refuses when the state reports no more pages --
     * which is exactly what a failure leaves behind, since the catch block
     * sets `hasMore = false`. So a first page that failed could only be
     * recovered by backing out to Home and re-opening the rail. This puts the
     * paging flag back, clears the error, and re-runs the same fetch with
     * `isLoading` true so the grid shows its spinner while it tries.
     */
    fun retryCatalogGrid() {

        val state =
            _catalogGrid.value
                ?: return

        val info =
            railInfo.values.firstOrNull { info ->
                formatCatalogName(info.catalogRawName) == state.title &&
                    info.addonName == state.addonName
            }
                ?: return

        gridLoadJob?.cancel()

        _catalogGrid.value =
            state.copy(
                isLoading = true,
                isLoadingMore = false,
                hasMore = true,
                error = null
            )

        gridLoadJob =
            fetchGridPage(info)
    }

    private fun fetchGridPage(
        info: RailInfo
    ): kotlinx.coroutines.Job {

        _catalogGrid.value =
            _catalogGrid.value?.copy(
                isLoadingMore = true
            )

        return viewModelScope.launch {

            try {

                val metas =
                    fetchCatalogThrottled(
                        baseUrl = info.baseUrl,
                        type = info.catalogType,
                        catalogId = info.catalogId,
                        skip = gridNextSkip
                    )

                if (
                    metas.isEmpty()
                ) {
                    _catalogGrid.value =
                        _catalogGrid.value?.copy(
                            hasMore = false,
                            isLoadingMore = false
                        )
                    return@launch
                }

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

                val existing =
                    _catalogGrid.value?.items
                        .orEmpty()
                        .mapTo(mutableSetOf()) { it.id }

                val deduped =
                    filtered.filter { it.id !in existing }

                if (
                    deduped.isEmpty()
                ) {
                    _catalogGrid.value =
                        _catalogGrid.value?.copy(
                            hasMore = false,
                            isLoadingMore = false
                        )
                    return@launch
                }

                val newGridCount =
                    (_catalogGrid.value?.items?.size ?: 0) + deduped.size

                _catalogGrid.value =
                    _catalogGrid.value?.copy(
                        items = _catalogGrid.value?.items
                            .orEmpty() + deduped,
                        isLoading = false,
                        isLoadingMore = false,
                        // Same runaway ceiling as the rails.
                        hasMore = newGridCount < MAX_RAIL_ITEMS
                    )

                gridNextSkip += metas.size
            } catch (
                e: kotlinx.coroutines.CancellationException
            ) {
                throw e
            } catch (e: Exception) {

                Log.e(
                    "HOME_GRID",
                    "grid page load failed: ${e.message}",
                    e
                )

                _catalogGrid.value =
                    _catalogGrid.value?.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        hasMore = false,
                        error = e.message
                    )
            }
        }
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

                val newArt =
                    if (
                        info.landscapeCards
                    ) {
                        resolveLandscapeArt(filtered)
                    } else {
                        emptyMap()
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
                                items = rail.items + deduped,
                                landscapeArt = rail.landscapeArt + newArt
                            )
                        } else {
                            rail
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

                        if (
                            simklRepository.isConfigured() &&
                            simklRepository.hasToken()
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

            simklWatchedEpisodesByShow.clear()

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
        val seenParents = HashSet<String>()
        val upcoming = ArrayList<UpcomingEpisode>()

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
            // (labelled "Today"); anything before today has aired.
            if (epochMs < startOfToday) continue
            if (!seenParents.add(parentId)) continue

            val airLabel = formatAirDateLabel(airDateText)
            val airFull = AirDateCorrection.parse(airDateText)
                ?.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
                ?: ""

            upcoming.add(
                UpcomingEpisode(
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

        return upcoming.sortedBy { it.airDateEpochMs }
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
        return runCatching {
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
                    runCatching {
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

    private fun parseTmdbAirDate(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDate.parse(value)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun formatAirDateLabel(raw: String?): String {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return "Date TBA"
        return try {
            val date = LocalDate.parse(value)
            val today = LocalDate.now(ZoneId.systemDefault())
            val days = ChronoUnit.DAYS.between(today, date)
            when {
                days == 0L -> "Today"
                days == 1L -> "Tomorrow"
                days in 2..6 -> "In $days days"
                else -> date.format(
                    DateTimeFormatter.ofPattern("EEE, MMM d")
                )
            }
        } catch (_: Exception) {
            "Date TBA"
        }
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
        if (history.isEmpty()) return

        val items = history.mapNotNull { entry ->
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
                episode = entry.episode,
                episodeStreamId = entry.episodeStreamId,
                startPositionMs = entry.positionMs,
                recencyTimestamp = entry.updatedAt,
                historyRowId = entry.id
            )
        }

        // Only seed when nothing is showing yet (cold start / fresh entry);
        // never clobber a live enriched list with the raw snapshot.
        if (_upNext.value.isEmpty()) {
            _upNext.value = applyContinueWatchingDismissals(
                dedupeAndSortUpNext(items)
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
                .collectLatest { history ->

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
            val restoredBackdrop = runCatching {
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
        // DAO once per parentId instead of once per history row.
        val localCompletedForParent: Set<Pair<Int, Int>> =
            if (isEpisodePlayback) {
                try {
                    val resolvedParentId = entry.parentId.trim().ifBlank { entry.id.trim() }
                    historyDao.getCompletedForParent(resolvedParentId)
                        .mapNotNull { e ->
                            e.season?.let { s -> e.episode?.let { ep -> s to ep } }
                        }
                        .toSet()
                } catch (_: Exception) {
                    emptySet()
                }
            } else {
                emptySet()
            }

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

        if (
            isEpisodePlayback &&
            entry.season != null && entry.episode != null
        ) {
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

                backdropUrl =
                    tmdbDetail?.backdropPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "https://image.tmdb.org/t/p/w780$it" }

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
                    if (cachedTotals != null) {
                        // Same show already walked its full season list in
                        // this pass - reuse the counts instead of repeating
                        // the whole TMDB season walk for every row. Finale
                        // flags stay false here: detecting them needs the
                        // full season walk, and post-dedupe cached rows are
                        // rare same-show duplicates.
                        tmdbEpisodeTotals = cachedTotals
                    } else {
                        val target = resolveSeriesTargetFromSharedWatchedState(
                            parentId = parentId,
                            tmdbId = tmdbId,
                            simklSeason = entry.season,
                            simklEpisode = entry.episode
                        )
                        localSeasonFinale =
                            target?.isSeasonFinale == true
                        localSeriesFinale =
                            target?.isSeriesFinale == true
                        tmdbEpisodesRemaining = target?.episodesRemaining
                        tmdbEpisodeTotals =
                            target?.episodesWatched?.let { w ->
                                target.episodesTotal?.let { t -> ShowEpisodeTotals(w, t) }
                            }
                        tmdbEpisodeTotals?.let { showEpisodeTotalsCache[tmdbId] = it }
                    }
                }

                if (tmdbId != null && tmdbId > 0) {
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
                            it.episodeNumber == entry.episode
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
                if (isEpisodePlayback) entry.name else null,

            episodeTitle = entry.episodeTitle?.takeIf { it.isNotBlank() },
            episodeDescription = entry.overview?.takeIf { it.isNotBlank() },
            episodesWatched = localWatchedCount.takeIf { it > 0 },
            episodesTotal = localEpisodesTotal,
            episodesRemaining = localEpisodesRemaining,

            // Episode-specific TMDB rating.
            // Your UI currently calls this field imdbRating.
            tmdbRating = null,
            imdbRating = episodeRating,
            episodeThumbnail = episodeThumbnail,
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

            season = entry.season,
            episode = entry.episode,
            episodeStreamId = entry.episodeStreamId,

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
                            localItems.isNotEmpty() &&
                            isLatestUpNextRequest(requestVersion)
                        ) {
                            _upNext.value =
                                applyContinueWatchingDismissals(
                                    dedupeAndSortUpNext(
                                        localItems + previousSimklUpNextItems()
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
                                            localItems +
                                                previousSimklUpNextItems() +
                                                mdbListItems
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
                            dedupeAndSortUpNext(localItems + simklItems + mdbListItems)

                        if (!isLatestUpNextRequest(requestVersion)) {
                            return@collectLatest
                        }

                        val result =
                            if (merged.isNotEmpty()) {

                                merged

                            } else if (localItems.isNotEmpty()) {

                                dedupeAndSortUpNext(
                                    localItems
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
                runCatching { MdbListClient.getPlaybackSessions(appContext) }
                    .getOrDefault(emptyList())

            val items = sessions
                .take(MAX_MDBLIST_UP_NEXT_ITEMS)
                .mapNotNull { session ->
                    buildMdbListUpNextItem(appContext, session)
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
                runCatching {
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
                runCatching {
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

        val detail = runCatching {
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
            resolvedEpisode = session.episode ?: 1
        }

        val progressFraction =
            (session.progress / 100.0).coerceIn(0.0, 1.0)

        val durationMs = runtimeMinutes
            ?.takeIf { it > 0 }
            ?.times(60_000L)
            ?: 0L
        resolvedStartPositionMs =
            (durationMs * progressFraction).toLong()

        val subtitle = if (session.isMovie) {
            "Resume movie"
        } else {
            "Resume - ${formatSeasonEpisode(resolvedSeason, resolvedEpisode)}"
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
            badge = UpNextBadge.CONTINUE_WATCHING,
            showTitle = if (session.isMovie) null
            else showTitle ?: displayTitle,
            episodeTitle = episodeTitle,
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
            tmdbId = session.tmdbId,
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

        var subtitle =
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

                    val airedRecently =
                        resolvedTarget.airDate
                            ?.let {
                                isWithinDays(
                                    it,
                                    NEW_RELEASE_WINDOW_DAYS
                                )
                            }
                            ?: false

                    badge =
                        when {

                            resolvedTarget.isResume ||
                                isExplicitResume -> {

                                UpNextBadge.CONTINUE_WATCHING
                            }

                            airedRecently &&
                                resolvedEpisode <= 1 -> {

                                UpNextBadge.NEW_SEASON
                            }

                            airedRecently -> {

                                UpNextBadge.NEW_EPISODE
                            }

                            else -> {

                                UpNextBadge.NEXT_UP
                            }
                        }

                    subtitle =
                        when {

                            badge ==
                                UpNextBadge.CONTINUE_WATCHING -> {

                                "Resume - ${
                                    formatSeasonEpisode(
                                        resolvedSeason,
                                        resolvedEpisode
                                    )
                                }"
                            }

                            badge ==
                                UpNextBadge.NEW_SEASON -> {

                                "New Season - ${
                                    formatSeasonEpisode(
                                        resolvedSeason,
                                        resolvedEpisode
                                    )
                                }"
                            }

                            badge ==
                                UpNextBadge.NEW_EPISODE -> {

                                "New Episode - ${
                                    formatSeasonEpisode(
                                        resolvedSeason,
                                        resolvedEpisode
                                    )
                                }"
                            }

                            else -> {

                                "Up Next - ${
                                    formatSeasonEpisode(
                                        resolvedSeason,
                                        resolvedEpisode
                                    )
                                }"
                            }
                        }
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
        // MDBList builder drops: nothing recognisable to show.
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
                            simklWatchedEpisodesByShow
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

            val localCompletedEntries =
                try {

                    historyDao
                        .getCompletedForParent(
                            parentId
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

            val mergedWatchedKeys =
                WatchedEpisodeState
                    .buildMergedWatchedKeys(
                        parentId =
                            parentId,

                        localCompletedEntries =
                            localCompletedEntries,

                        simklCompletedEpisodes =
                            simklCompletedEpisodes
                    )

            watchedStateMutex.withLock {

                simklWatchedEpisodesByShow[
                    parentId
                ] =
                    simklCompletedEpisodes

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
                simklWatchedEpisodesByShow[parentId].orEmpty(),
                watchedEpisodeKeysByShow[parentId].orEmpty()
            )
        }

    var totalAiredEpisodes = 0
    var watchedAiredEpisodes = 0

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

        return episode >= seasonLength
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
     * Production, Planned, or status unknown) must NOT be labelled "Series
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
            historyDao.getResumeForParent(parentId)
        } catch (_: Exception) {
            null
        }

    if (
        resume != null &&
        resume.season != null &&
        resume.episode != null &&
        resume.positionMs > 0L
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
                    resume.episode
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
                            resume.episode
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
    val startingSeason =
        simklSeason ?: 1

    val startingEpisode =
        simklEpisode ?: 1

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

    return ResolvedHomeSeriesTarget(

        season =
            startingSeason,

        episode =
            startingEpisode,

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
            simklWatchedEpisodesByShow[parentId].orEmpty(),
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

    private fun formatSeasonEpisode(
        season: Int?,
        episode: Int?
    ): String {

        return when {

            season != null &&
                episode != null ->
                "S${season}E${episode}"

            season != null ->
                "S$season"

            episode != null ->
                "E$episode"

            else ->
                ""
        }
    }

    private fun parseTimestampMillis(
        value: String?
    ): Long {

        return try {

            OffsetDateTime
                .parse(value)
                .toInstant()
                .toEpochMilli()

        } catch (_: Exception) {
            0L
        }
    }

    private fun isWithinDays(
        dateStr: String,
        days: Int
    ): Boolean {

        return try {

            val date =
                LocalDate.parse(
                    dateStr
                )

            val today =
                LocalDate.now()

            val diff =
                ChronoUnit.DAYS.between(
                    date,
                    today
                )

            diff in 0..days.toLong()

        } catch (_: Exception) {
            false
        }
    }

    private fun isAiredOrUnknown(
        airDate: String?
    ): Boolean {

        if (
            airDate.isNullOrBlank()
        ) {
            return true
        }

        return try {

            !LocalDate
                .parse(airDate)
                .isAfter(
                    LocalDate.now()
                )

        } catch (_: Exception) {
            true
        }
    }

    private fun badgePriority(
        badge: UpNextBadge
    ): Int =

        when (badge) {

            UpNextBadge.NEW_SEASON ->
                0

            UpNextBadge.NEW_EPISODE ->
                1

            UpNextBadge.CONTINUE_WATCHING ->
                2

            UpNextBadge.NEXT_UP ->
                3
        }

    private fun progressFromHistory(
        positionMs: Long,
        durationMs: Long
    ): Float? {

        if (
            positionMs <= 0L ||
            durationMs <= 0L
        ) {
            return null
        }

        return (
            positionMs.toFloat() /
                durationMs.toFloat()
            ).coerceIn(
                0.005f,
                0.99f
            )
    }

    private fun parseEpisodeKey(
        key: String
    ): Triple<String, Int, Int>? {

        val match =
            Regex(
                """^(.+?)(?::[sS]?(\d+))(?::[eE]?(\d+))$"""
            ).find(
                key.trim()
            )
                ?: return null

        val showId =
            match.groupValues[1]
                .trim()

        val season =
            match.groupValues[2]
                .toIntOrNull()
                ?: return null

        val episode =
            match.groupValues[3]
                .toIntOrNull()
                ?: return null

        if (
            showId.isBlank() ||
            season < 0 ||
            episode < 0
        ) {
            return null
        }

        return Triple(
            showId,
            season,
            episode
        )
    }

    private fun dedupeAndSortUpNext(
        items: List<UpNextItem>
    ): List<UpNextItem> {

        return collapseDuplicateUpNextCards(
            items
        )
            .groupBy(
                ::showDedupeKey
            )
            .values
            .mapNotNull { candidates ->

                candidates.maxWithOrNull(

                    compareBy<UpNextItem> {
                        winnerScore(it)
                    }
                        .thenByDescending {
                            it.recencyTimestamp
                        }

                        .thenBy {
                            targetPrecisionScore(it)
                        }

                        .thenBy {
                            it.title.lowercase()
                        }
                )
            }
            .sortedWith(

                compareBy<UpNextItem> {
                    badgePriority(it.badge)
                }

                    .thenByDescending {
                        it.recencyTimestamp
                    }

                    .thenBy {
                        it.title.lowercase()
                    }
            )
    }

    private fun showDedupeKey(
        item: UpNextItem
    ): String =
        upNextShowKey(item)

    private fun winnerScore(
    item: UpNextItem
): Int {

    var score =
        0

    if (
        item.badge ==
            UpNextBadge.CONTINUE_WATCHING
    ) {
        score += 5_000
    }

    // Prefer entries that actually have calculated
    // remaining playback time.
    if (
        item.remainingMinutes != null &&
        item.remainingMinutes > 0
    ) {
        score += 1_000
    }

    if (
        item.startPositionMs > 0L ||
        (item.progressPercent ?: 0f) > 0f
    ) {
        score += 2_500
    }

    if (
        !item.episodeStreamId
            .isNullOrBlank()
    ) {
        score += 500
    }

    if (
        item.season != null &&
        item.episode != null
    ) {
        score += 250
    }

    return score
}

    private fun targetPrecisionScore(
        item: UpNextItem
    ): Int {

        var score =
            0

        if (
            !item.episodeStreamId
                .isNullOrBlank()
        ) {
            score += 3
        }

        if (
            valueOrDefault(
                item.season,
                0
            ) != 0
        ) {
            score += 2
        }

        if (
            item.episode != null
        ) {
            score += 2
        }

        if (
            !item.streamUrl
                .isNullOrBlank()
        ) {
            score += 1
        }

        if (
            !item.poster
                .isNullOrBlank()
        ) {
            score += 1
        }

        return score
    }

    private fun valueOrDefault(
        value: Int?,
        default: Int
    ): Int =
        value ?: default

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

    private data class PendingCatalogLoad(
        val addonName: String,
        val baseUrl: String,
        val catalogId: String,
        val catalogType: String,
        val catalogRawName: String
    )

    /**
     * Drops titles whose release date (from the catalog's releaseInfo field,
     * e.g. "2026-12-25T00:00:00.000Z") is in the future. Titles with no or
     * unparseable release info are always kept — the filter only removes
     * items we can positively tell are not out yet.
     */
    private fun filterUpcoming(
        metas: List<MetaPreview>
    ): List<MetaPreview> {

        val today = LocalDate.now()

        return metas.filter { meta ->

            val raw = meta.releaseInfo
                ?.trim()
                .orEmpty()

            if (raw.isEmpty()) {
                return@filter true
            }

            parseReleaseDate(raw)?.let { date ->

                !date.isAfter(today)

            } ?: run {

                // Bare year (the common catalog shape, e.g. "2026"):
                // hide only when the year is entirely in the future —
                // the current year is ambiguous, so keep it. Anything
                // unparseable is kept too.
                val year = raw.toIntOrNull()

                year == null || year <= today.year
            }
        }
    }

    private fun parseReleaseDate(raw: String): LocalDate? {

        return runCatching {
            OffsetDateTime.parse(raw).toLocalDate()
        }.recoverCatching {
            LocalDate.parse(raw)
        }.getOrNull()
    }

    /**
     * Resolves landscape-card artwork (backdrop + clearlogo) for a rail's
     * items, keyed by "type:id". The TMDB alternate backdrop always wins so
     * cards never mirror the hero's primary backdrop; the addon's own
     * background/logo are fallbacks (logo keeps addon-first priority).
     * All TMDB hits land in the same 12h/30d cache the digital filter and
     * detail screens use, so rails that were filtered already have warm
     * entries.
     */
    private suspend fun resolveLandscapeArt(
        metas: List<MetaPreview>,
        tmdbOnly: Boolean = false
    ): Map<String, Pair<String?, String?>> {

        return coroutineScope {

            metas.map { meta ->

                async {

                    val key = "${meta.type}:${meta.id}"

                    val addonBackdrop =
                        if (tmdbOnly) {
                            null
                        } else {
                            meta.background?.takeIf { it.isNotBlank() }
                        }

                    val addonLogo =
                        if (tmdbOnly) {
                            null
                        } else {
                            meta.logo?.takeIf { it.isNotBlank() }
                        }

                    // No fast-path on the addon's own fields: the addon
                    // background is typically the same primary backdrop the
                    // Home hero shows, so returning it early made landscape
                    // cards mirror the hero. TMDB is always consulted; the
                    // addon fields stay as fallbacks in the merge below.

                    val detail =
                        landscapeArtSemaphore.withPermit {

                            runCatching {

                                tmdbRepository.fetchEnrichedMetaCached(
                                    imdbId = meta.id,
                                    type = meta.type
                                )
                            }.getOrNull()
                        }

                    // Card backdrop prefers an alternate image so cards
                    // don't mirror the hero's primary backdrop.
                    val tmdbBackdrop =
                        detail?.cardBackdropPath()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { TmdbRepository.BACKDROP_BASE + it }

                    val tmdbLogo =
                        detail?.bestLogoPath()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { TmdbRepository.LOGO_BASE + it }

                    if (tmdbOnly) {
                        // Pinned Top Today rails: the addon's backgrounds
                        // carry burned-in promo text ("Just Added" badges,
                        // title cards), so they are never usable as card
                        // art. TMDB or nothing — when TMDB has no images,
                        // blank markers make the card render its clean
                        // title-only treatment (LandscapeCard treats blank
                        // like missing; HomeScreen's ?: addon fallback can
                        // never fire because the map entry exists).
                        key to (
                            (tmdbBackdrop ?: "") to
                                (tmdbLogo ?: "")
                            )
                    } else {
                        // Backdrop: TMDB (alternate) wins over the addon's
                        // background, which is usually the same primary
                        // image the hero shows. Logo keeps addon-first
                        // priority.
                        key to (
                            (tmdbBackdrop ?: addonBackdrop) to
                                (addonLogo ?: tmdbLogo)
                            )
                    }
                }
            }.awaitAll().toMap()
        }
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
    ): List<MetaPreview> {

        return coroutineScope {

            metas.map { meta ->

                async {

                    if (
                        !meta.type.equals("movie", ignoreCase = true)
                    ) {
                        return@async meta
                    }

                    val detail =
                        tmdbFilterSemaphore.withPermit {

                            tmdbRepository.fetchEnrichedMetaCached(
                                imdbId = meta.id,
                                type = "movie"
                            )
                        }

                    when (detail?.isAvailableAtHome()) {

                        // Positively not at home yet -> filtered out.
                        false -> null

                        // Available, or unknown -> keep.
                        else -> meta
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Remaining auto-retries for an all-failed cold-start rail build (see the
     * retry block inside [loadRailsInternal]). A successful non-empty build
     * resets the budget; each all-failed attempt consumes one so a device
     * that boots with no network stops instead of retrying forever.
     */
    private var railLoadRetriesLeft = RAIL_LOAD_RETRY_MAX_ATTEMPTS

    private suspend fun loadRailsInternal(
        forceRefresh: Boolean,
        clearCatalogCache: Boolean = forceRefresh
    ) {

        if (
            !forceRefresh &&
            _rails.value.isNotEmpty()
        ) {
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

        try {

            railsRefreshMutex.withLock {

                // Rebuilding rails invalidates pagination bookkeeping: rail
                // identities are stable, but their item offsets are not.
                railInfo.clear()
                loadingRails.clear()
                exhaustedRails.clear()
                railSourceOffset.clear()
                closeCatalogGrid()

                val pinned =
                    mutableListOf<Rail>()

                if (tmdbRepository.kidsMaxAge() != null) {
                    // Kids profile: the general-audience "Top ... Today"
                    // rails don't belong here - after ceiling filtering they
                    // are usually near-empty (today's top titles are mostly
                    // adult fare). Hardcoded kids rails replace them.
                    loadPinnedKidsRails(
                        pinned,
                        landscapeCards
                    )
                } else {
                    loadPinnedTopTodayRails(
                        pinned,
                        hideUpcoming,
                        landscapeCards
                    )
                }

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
                                catalogRawName = configuration.catalog.displayName
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

                coroutineScope {

                    pendingCatalogs
                        .map { pending ->
                            async {
                                loadCatalogRail(
                                    pending,
                                    hideUpcoming,
                                    landscapeCards
                                )?.let { rail ->
                                    // Stale-profile guard (see the final
                                    // publish below): never stream a
                                    // previous profile's rows back in.
                                    if (buildEpochAtStart != railBuildEpoch) {
                                        return@let
                                    }
                                    collected.add(rail)
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
                }

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
                    delay(backoffMs)
                    loadRailsInternal(
                        forceRefresh = false,
                        clearCatalogCache = true
                    )
                    return
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
                catalogName = formatCatalogName(
                    pending.catalogRawName
                ),
                type = pending.catalogType,
                items = filtered,
                catalogId = pending.catalogId,
                baseUrl = pending.baseUrl,
                landscapeArt = if (landscapeCards) {
                    resolveLandscapeArt(filtered)
                } else {
                    emptyMap()
                }
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

        return rail.addonName + "::" + rail.catalogId + "::" + rail.type
    }

    private fun formatCatalogName(
        name: String
    ): String {

        return name
            .replace(
                "_",
                " "
            )
            .split(" ")
            .joinToString(" ") { word ->

                word.lowercase()
                    .replaceFirstChar {
                        it.uppercase()
                    }
            }
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

                        // Belt-and-braces ceiling re-check (discoverKB already
                        // filters when kids mode is on).
                        val filtered = tmdbRepository.kidsFilterMetas(metas)
                        if (filtered.isEmpty()) return@async null

                        val rail = Rail(
                            addonName = KIDS_ADDON_NAME,
                            catalogName = if (tv) "Top Kids Shows" else "Top Kids Movies",
                            type = if (tv) "series" else "movie",
                            items = filtered,
                            catalogId = if (tv) "top_kids_shows" else "top_kids_movies",
                            baseUrl = null,
                            landscapeArt = if (landscapeCards) {
                                resolveLandscapeArt(filtered, tmdbOnly = true)
                            } else {
                                emptyMap()
                            }
                        )

                        railInfo[railKeyOf(rail)] = RailInfo(
                            addonName = KIDS_ADDON_NAME,
                            catalogId = rail.catalogId ?: "",
                            catalogType = rail.type,
                            catalogRawName = rail.catalogName,
                            baseUrl = "",
                            hideUpcoming = false,
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
        // request semaphore tightened this serialised the whole home load),
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
                                    landscapeArt = if (landscapeCards) {
                                        resolveLandscapeArt(
                                            filteredMetas,
                                            tmdbOnly = true
                                        )
                                    } else {
                                        emptyMap()
                                    }
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

    private fun normalizeMediaType(
        type: String?
    ): String? =

        when (
            type?.lowercase()
        ) {

            "movie" ->
                "movie"

            "series",
            "show",
            "tv" ->
                "series"

            else ->
                null
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
            _refreshTrigger.value += 1

            runCatching {
                loadRailsInternal(
                    forceRefresh = true,
                    clearCatalogCache = true
                )
            }
        }
    }

    companion object {

        // Dwell before hero network resolution kicks in (see resolveHeroMeta).
        // 150ms: still rides out fast D-pad scrolls (one focus event per
        // card), but 100ms less dead time per resolve than the old 250ms —
        // artwork+detail are cached/aggressive enough to absorb the extra
        // in-flight requests.
        private const val HERO_RESOLVE_DWELL_MS = 150L

        private const val NEW_RELEASE_WINDOW_DAYS =
            7

        private const val TMDB_MAX_CONCURRENT_LOOKUPS =
            5

        private const val MAX_CONCURRENT_CATALOG_REQUESTS =
            6

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
