package com.kennyb1201.kbstream.ui.actor

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.history.TitleProfileOwnership
import com.kennyb1201.kbstream.data.history.titleOwnedByAnotherProfile
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonCredit
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.watched.WatchedStatusKeys
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The words that demote a TV credit as a talk/variety appearance, matched on
 * WORD boundaries (see [isTalkOrVarietyCredit]). Compiled once at file scope
 * because the filmography sort compares with this test on every step, so a
 * per-call `Regex(...)` would be paid for thousands of times.
 *
 * "guest" is deliberately absent: a dramatic role credited as "Guest" is
 * acting work, not a talk-show cameo.
 */
private val TALK_VARIETY_KEYWORDS: Regex = Regex(
    "\\b(" +
        listOf(
            "talk show", "late night", "tonight show", "daily show",
            "news", "host", "interview", "panel", "variety", "reality",
            "game show"
        ).joinToString("|") { Regex.escape(it) } +
        ")\\b"
)

class ActorViewModel(application: Application) : AndroidViewModel(application) {
    private val tmdbRepository = TmdbRepository.getInstance(application)
    private val watchedStatusRepository = WatchedStatusRepository(application)

    // Caps parallel TMDB imdb-id lookups so a prolific actor's credit list
    // can't flood the shared OkHttp pool and starve whichever screen the
    // user navigated to mid-load (same rationale as Tag/Studio/Collection).
    private val imdbResolveSemaphore = Semaphore(permits = 8)

    private val _person = MutableStateFlow<TmdbPersonDetail?>(null)
    val person: StateFlow<TmdbPersonDetail?> = _person.asStateFlow()

    private val _resolvedCreditIds = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolvedCreditIds: StateFlow<Map<String, String>> = _resolvedCreditIds.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _topWorkBackdropUrl = MutableStateFlow<String?>(null)
    val topWorkBackdropUrl: StateFlow<String?> = _topWorkBackdropUrl.asStateFlow()

    private val _topWorkCredit = MutableStateFlow<TmdbPersonCredit?>(null)
    val topWorkCredit: StateFlow<TmdbPersonCredit?> = _topWorkCredit.asStateFlow()

    val watchedKeys: StateFlow<Set<String>> = combine(
        _person,
        _resolvedCreditIds,
        watchedStatusRepository.observeWatchUpdates()
    ) { personDetail: TmdbPersonDetail?, resolvedMap: Map<String, String>, _ ->
        // The `resolvedMap.isEmpty()` guard is GONE on purpose: a credit whose
        // TMDB->IMDB resolution never ran (the batch is capped at 60), answered
        // nothing, or failed still badges off its own raw `tmdb:<n>` id, which
        // needs no network. An empty map used to mean a credit list with no
        // badges at all, which is the field report this rule exists for - the
        // actor screen's "some shows badged, some bare, while the episodes are
        // correctly marked watched".
        if (personDetail == null) {
            emptySet()
        } else {
            val siblingOwned = siblingOwnedMarkerIdsFor(personDetail, resolvedMap)
            drawnCredits(personDetail)
                .flatMap { credit ->
                    val mediaType = normalizeMediaType(credit.mediaType) ?: return@flatMap emptyList()
                    // The IMDB flavor first, this credit's own `tmdb:<n>` second,
                    // both when both match - the rule lives in [WatchedStatusKeys].
                    WatchedStatusKeys.watchedKeysOf(
                        tmdbId = credit.id,
                        mediaType = mediaType,
                        resolvedImdbId = resolvedMap[creditLookupKey(credit.id, mediaType)],
                        matches = watchedStatusRepository::isWatchedCached
                    )
                }
                .toSet()
                // A title another profile positively owns draws no checkmark
                // here, however the marker resolved (see
                // [siblingOwnedMarkerIdsFor]).
                .filterNot { key -> keyOwnedByAnotherProfile(key, siblingOwned) }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptySet()
    )

    // Eye-badge twin of watchedKeys: shows started-but-not-finished, from
    // the same cached watch state. The completed checkmark wins when a key
    // resolves to both.
    val partialWatchedKeys: StateFlow<Set<String>> = combine(
        _person,
        _resolvedCreditIds,
        watchedStatusRepository.observeWatchUpdates()
    ) { personDetail: TmdbPersonDetail?, resolvedMap: Map<String, String>, _ ->
        // Same two candidates and the same dropped `resolvedMap.isEmpty()` guard
        // as watchedKeys above, against the partially-watched state.
        if (personDetail == null) {
            emptySet()
        } else {
            val siblingOwned = siblingOwnedMarkerIdsFor(personDetail, resolvedMap)
            drawnCredits(personDetail)
                .flatMap { credit ->
                    val mediaType = normalizeMediaType(credit.mediaType) ?: return@flatMap emptyList()
                    WatchedStatusKeys.watchedKeysOf(
                        tmdbId = credit.id,
                        mediaType = mediaType,
                        resolvedImdbId = resolvedMap[creditLookupKey(credit.id, mediaType)],
                        matches = watchedStatusRepository::isPartiallyWatchedCached
                    )
                }
                .toSet()
                // Same sibling-ownership filter as the checkmark set above.
                .filterNot { key -> keyOwnedByAnotherProfile(key, siblingOwned) } - watchedKeys.value
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptySet()
    )

    /**
     * The key a credit tile is compared under. Delegates to
     * [WatchedStatusKeys.key], so the keys the screen tests and the keys the
     * sets above are built from cannot describe the same title differently.
     */
    fun watchedKey(id: String, type: String): String = WatchedStatusKeys.key(id, type)

    fun creditLookupKey(tmdbId: Int, mediaType: String): String =
        "${mediaType.lowercase()}::$tmdbId"

    /**
     * Which load owns the state, and the person it is for.
     *
     * This ViewModel is activity-scoped, so opening actor A, backing out and
     * opening actor B genuinely runs two loads through ONE instance. Without a
     * guard the slower one won: A's late answer put A's credits, backdrop and
     * watched badges under B's page until the viewer left and re-entered.
     */
    private var loadGeneration = 0

    fun load(personId: Int) {
        val generation = ++loadGeneration
        viewModelScope.launch {
            _isLoading.value = true
            _topWorkBackdropUrl.value = null
            _topWorkCredit.value = null
            _error.value = null
            _person.value = null
            _resolvedCreditIds.value = emptyMap()

            try {
                val result = tmdbRepository.getPerson(personId)
                // A newer load owns the page now: publish nothing. Everything
                // below - the error line, the spinner and the two follow-up
                // launches - is that page's to set, not this answer's.
                if (generation != loadGeneration) return@launch
                _person.value = result
                _topWorkCredit.value = result?.let { sortedCredits(it).firstOrNull() }

                // A person with ONLY crew credits is not a failure: the page
                // draws their directing/writing work (see [sortedCrewCredits]),
                // so the gate is the sections actually drawn - cast AND crew -
                // rather than the cast list alone.
                if (result != null && drawnCredits(result).isEmpty()) {
                    _error.value = "TMDB returned this person but with zero credits"
                }
            } catch (e: Exception) {
                if (generation != loadGeneration) return@launch
                _error.value = "TMDB request failed: ${e.message}"
                Log.e("ACTOR_LOAD", "load failed for personId=$personId", e)
            } finally {
                if (generation == loadGeneration) _isLoading.value = false
            }

            _person.value?.let { loadedPerson ->
                launch {
                    resolveAndPreloadWatched(loadedPerson)
                }
                launch {
                    loadTopWorkBackdrop(loadedPerson)
                }
            }
        }
    }

    private suspend fun loadTopWorkBackdrop(person: TmdbPersonDetail) {
        val topCredit = sortedCredits(person).firstOrNull()
        _topWorkCredit.value = topCredit

        if (topCredit == null) {
            _topWorkBackdropUrl.value = null
            return
        }

        val normalizedType = normalizeMediaType(topCredit.mediaType)
        if (normalizedType == null) {
            _topWorkBackdropUrl.value = null
            return
        }

        val detail = runCatchingCancellable {
            tmdbRepository.getDetailByTmdbId(topCredit.id, normalizedType)
        }.getOrNull()

        _topWorkBackdropUrl.value = detail?.backdropPath
            ?.takeIf { it.isNotBlank() }
            ?.let { TmdbRepository.BACKDROP_BASE + it }
    }

    fun sortedCredits(person: TmdbPersonDetail?): List<TmdbPersonCredit> =
        sortedCastFilmography(person?.combinedCredits?.cast.orEmpty())

    /**
     * The person's DIRECTING and WRITING credits, sorted by the same rule as
     * the cast filmography and wearing the same badges.
     *
     * Detail's people rail leads with the writer and the director, and it
     * routes both to this same page. Before this section existed that tap
     * landed on a CAST-ONLY page - a cameo, a bit part, or nothing at all for a
     * director with no acting credits - so the page hid the very work the
     * viewer tapped to see.
     */
    fun sortedCrewCredits(person: TmdbPersonDetail?): List<TmdbPersonCredit> =
        sortedCrewFilmography(person?.combinedCredits?.crew.orEmpty())

    /**
     * Every credit this page draws, cast and crew, in one list: the badge sets
     * and the watched preload iterate this, so the crew rail badges exactly like
     * the cast rails do.
     */
    private fun drawnCredits(person: TmdbPersonDetail?): List<TmdbPersonCredit> =
        sortedCredits(person) + sortedCrewCredits(person)

    /**
     * The item id a card records in a return target, and the role half of the
     * key its rail keys it with: the title id plus the ROLE (the character for
     * cast, the job for crew). A dual-role credit's two cards are thereby
     * distinguishable, so backing out of Detail lands on the role that was
     * pressed rather than always the first (see [BrowseReturnFocus]).
     */
    fun creditItemId(credit: TmdbPersonCredit): String = actorCreditItemId(credit)

    /**
     * Ids (IMDb or `tmdb:<n>`) of this person's credits that ANOTHER profile
     * positively owns, so their badge is never painted here however the marker
     * resolved.
     *
     * The watched cache key is `"type::id"` with no profile component (see
     * WatchedStatusRepository.cacheKey), and the tracker feeds behind it are per
     * ACCOUNT - so a kids-profile completion can badge a title on this profile's
     * actor page, the same leak shape as the PAW Patrol eye Home's fix closed.
     * Home's watched/eye sets are filtered at publication for exactly this
     * reason (HomeViewModel.keyOwnedByAnotherProfile); these actor sets read the
     * same cache family through the same methods and skipped that filter.
     */
    private fun siblingOwnedMarkerIdsFor(
        person: TmdbPersonDetail,
        resolvedMap: Map<String, String>
    ): Set<String> {
        val activeProfileId = ProfileManager.activeProfile.value?.id
            ?.takeIf { it.isNotBlank() }
            ?: return emptySet()

        val owners = TitleProfileOwnership.snapshot(getApplication())
        if (owners.isEmpty()) return emptySet()

        return drawnCredits(person)
            .filter { credit ->
                titleOwnedByAnotherProfile(
                    ownerByTitleKey = owners,
                    type = credit.mediaType,
                    title = credit.filmographyTitle(),
                    activeProfileId = activeProfileId
                )
            }
            .flatMap { credit ->
                val mediaType = normalizeMediaType(credit.mediaType)
                listOfNotNull(
                    WatchedStatusKeys.tmdbKeyId(credit.id),
                    mediaType?.let { resolvedMap[creditLookupKey(credit.id, it)] }
                )
            }
            .toSet()
    }

    /** True when [key] (`type::id`) names a title another profile owns. */
    private fun keyOwnedByAnotherProfile(key: String, siblingOwned: Set<String>): Boolean =
        siblingOwned.any { id -> key.endsWith("::$id") }

    private suspend fun resolveAndPreloadWatched(person: TmdbPersonDetail) {
        try {
            // The badge preload first, and UNCAPPED: every credit's own
            // `tmdb:<n>` flavor, over the whole list rather than the `.take(60)`
            // resolution batch below. It costs local DB plus the already-cached
            // Simkl snapshot - no network - and it is what lets a credit past
            // the cap, or one whose resolution comes back null, badge at all.
            // (Same rule, and same order, as Tag/Studio/Decade/Collection.)
            val tmdbPairs = drawnCredits(person)
                .mapNotNull { credit ->
                    val mediaType = normalizeMediaType(credit.mediaType) ?: return@mapNotNull null
                    if (credit.id <= 0) null else WatchedStatusKeys.tmdbKeyId(credit.id) to mediaType
                }
                .distinct()
            watchedStatusRepository.preload(tmdbPairs)

            val cast = drawnCredits(person)
                .mapNotNull { credit ->
                    val mediaType = normalizeMediaType(credit.mediaType) ?: return@mapNotNull null
                    credit to mediaType
                }
                .take(60)

            if (cast.isEmpty()) {
                _resolvedCreditIds.value = emptyMap()
                Log.d("ACTOR_WATCHED", "no actor credits eligible for watched preload")
                return
            }

            val resolvedPairs = supervisorScope {
                cast.map { (credit, mediaType) ->
                    async {
                        val imdbId = runCatchingCancellable {
                            imdbResolveSemaphore.withPermit {
                                tmdbRepository.resolveImdbId(credit.id, mediaType)
                            }
                        }.getOrNull()
                        Triple(credit, mediaType, imdbId)
                    }
                }.map { it.await() }
            }

            val resolvedMap = linkedMapOf<String, String>()
            val preloadItems = linkedSetOf<Pair<String, String>>()

            resolvedPairs.forEach { (credit, mediaType, imdbId) ->
                if (!imdbId.isNullOrBlank()) {
                    resolvedMap[creditLookupKey(credit.id, mediaType)] = imdbId
                    preloadItems += imdbId to mediaType
                }
            }

            _resolvedCreditIds.value = resolvedMap

            if (preloadItems.isEmpty()) {
                Log.d("ACTOR_WATCHED", "no resolvable imdb ids for actor credits")
                return
            }

            watchedStatusRepository.preload(preloadItems.toList())

            Log.d(
                "ACTOR_WATCHED",
                "resolveAndPreloadWatched done, credits=${cast.size}, resolved=${preloadItems.size}"
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation is not a failure: rethrow so the scope that was
            // canceled stops here instead of logging it and clearing state as
            // if the work had simply failed.
            throw e
        } catch (e: Exception) {
            _resolvedCreditIds.value = emptyMap()
            Log.e("ACTOR_WATCHED", "resolveAndPreloadWatched failed", e)
        }
    }

    fun resolveAndNavigate(
        tmdbId: Int,
        mediaType: String,
        onNavigateDetail: (String, String) -> Unit
    ) {
        viewModelScope.launch {
            val normalizedType = normalizeMediaType(mediaType) ?: return@launch
            val imdbId = _resolvedCreditIds.value[creditLookupKey(tmdbId, normalizedType)]
                ?: runCatchingCancellable {
                    tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()

            // The Detail route resolves a TMDB id itself (see
            // TmdbRepository.fetchEnrichedMeta), so a credit TMDB has no IMDb id
            // for - or one whose resolution failed offline - still opens its
            // page instead of dying silently on the press.
            val routeId = imdbId?.takeIf { it.isNotBlank() }
                ?: WatchedStatusKeys.tmdbKeyId(tmdbId)
            onNavigateDetail(normalizedType, routeId)
        }
    }

    private fun normalizeMediaType(mediaType: String?): String? =
        when (mediaType?.lowercase()) {
            "movie" -> "movie"
            "tv", "series" -> "series"
            else -> null
        }

    /**
     * Long-press "Mark as Watched" on an actor credit poster: resolves the
     * TMDB id to an IMDB id, records the persistent local watched override
     * (mirrored to SIMKL when connected) and caches the resolution so the
     * rail badge updates reactively.
     */
    fun markAsWatched(
        tmdbId: Int,
        mediaType: String
    ) {
        viewModelScope.launch {
            val normalizedType = normalizeMediaType(mediaType) ?: return@launch
            val lookup = creditLookupKey(tmdbId, normalizedType)
            val imdbId = _resolvedCreditIds.value[lookup]
                ?: runCatchingCancellable {
                    tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()

            if (imdbId.isNullOrBlank()) {
                // No IMDb id to key the mark under: write it under this credit's
                // own `tmdb:<n>` flavor, which the rail badge already consults
                // (see [WatchedStatusKeys]). Returning here instead was a silent
                // no-op on the menu row the viewer had just pressed.
                runCatchingCancellable {
                    watchedStatusRepository.markWatchedLocal(
                        WatchedStatusKeys.tmdbKeyId(tmdbId),
                        normalizedType
                    )
                }.onFailure { e ->
                    Log.e("ACTOR_WATCHED", "markAsWatched failed tmdb=$tmdbId", e)
                }
                return@launch
            }

            if (_resolvedCreditIds.value[lookup] == null) {
                _resolvedCreditIds.value = _resolvedCreditIds.value + (lookup to imdbId)
            }

            runCatchingCancellable {
                watchedStatusRepository.markWatchedLocal(imdbId, normalizedType)
            }.onFailure { e ->
                Log.e("ACTOR_WATCHED", "markAsWatched failed tmdb=$tmdbId", e)
            }
        }
    }

    /**
     * Long-press "Mark as Unwatched" on an actor credit poster: resolves the
     * TMDB id to an IMDB id, removes the persistent local watched override
     * (mirrored as a Simkl history delete when connected) and caches the
     * resolution so the rail badge updates reactively.
     */
    fun markUnwatched(
        tmdbId: Int,
        mediaType: String
    ) {
        viewModelScope.launch {
            val normalizedType = normalizeMediaType(mediaType) ?: return@launch
            val lookup = creditLookupKey(tmdbId, normalizedType)
            val imdbId = _resolvedCreditIds.value[lookup]
                ?: runCatchingCancellable {
                    tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()

            if (imdbId.isNullOrBlank()) {
                // Same fallback as markAsWatched: clear under the credit's own
                // `tmdb:<n>` flavor rather than leaving the press a no-op.
                runCatchingCancellable {
                    watchedStatusRepository.markUnwatchedLocal(
                        WatchedStatusKeys.tmdbKeyId(tmdbId),
                        normalizedType
                    )
                }.onFailure { e ->
                    Log.e("ACTOR_WATCHED", "markUnwatched failed tmdb=$tmdbId", e)
                }
                return@launch
            }

            if (_resolvedCreditIds.value[lookup] == null) {
                _resolvedCreditIds.value = _resolvedCreditIds.value + (lookup to imdbId)
            }

            runCatchingCancellable {
                watchedStatusRepository.markUnwatchedLocal(imdbId, normalizedType)
            }.onFailure { e ->
                Log.e("ACTOR_WATCHED", "markUnwatched failed tmdb=$tmdbId", e)
            }
        }
    }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? =
        tmdbRepository.resolveImdbId(tmdbId, type)
}

/*
 * The filmography RULES, pure and top-level so they are unit-tested directly
 * ([ActorFilmographyRulesTest]) rather than through a ViewModel that wants an
 * Application: the cast sort and its role-dedup (a dual-role credit keeps both
 * cards), the crew selection, the role-carrying item id, and the talk/variety
 * demotion the sort applies.
 */

/** The title a credit is drawn and sorted under, cast or crew. */
internal fun TmdbPersonCredit.filmographyTitle(): String =
    title?.ifBlank { null } ?: name?.ifBlank { null } ?: ""

/** The credit's release / first-air date, by media type. */
internal fun TmdbPersonCredit.filmographyDate(): String =
    when (mediaType?.lowercase()) {
        "movie" -> releaseDate ?: ""
        "tv" -> firstAirDate ?: ""
        else -> ""
    }

/**
 * Whether a TV credit reads as a talk/variety appearance, and so is demoted
 * below acting work.
 *
 * WORD-BOUNDED, not a substring test: the old `keyword in text` rule sank whole
 * dramatic casts, because "Ghosts" contains "host" and "The Newsroom" and
 * "NewsRadio" contain "news". "guest" is deliberately absent from the list - a
 * dramatic role credited as "Guest" is acting work, not a talk-show cameo.
 */
internal fun TmdbPersonCredit.isTalkOrVarietyCredit(): Boolean {
    if (mediaType?.lowercase() != "tv") return false
    val text = "${filmographyTitle()} ${character.orEmpty()}".lowercase()
    return TALK_VARIETY_KEYWORDS.containsMatchIn(text)
}

/** The credit's popularity rank within a filmography; talk/variety demoted. */
internal fun TmdbPersonCredit.filmographyRankScore(): Int {
    var score = 0

    if (mediaType.equals("movie", true)) score += 500
    if (mediaType.equals("tv", true)) score += 250
    if (isTalkOrVarietyCredit()) score -= 500 else score += 220
    if (!posterPath.isNullOrBlank()) score += 100

    val votes = voteCount ?: 0
    if (votes >= 25) score += 40
    if (votes >= 100) score += 80
    if (votes >= 500) score += 120

    score += ((popularity ?: 0.0) * 8).toInt().coerceAtMost(320)
    score += ((voteAverage ?: 0.0) * 18).toInt().coerceAtMost(180)

    val year = filmographyDate().take(4).toIntOrNull() ?: 0
    if (year >= 1900) score += (year - 1900).coerceAtMost(140)

    return score
}

/** Credits this page can draw: a real TMDB id and a movie/series type. */
internal fun List<TmdbPersonCredit>.eligibleCredits(): List<TmdbPersonCredit> =
    filter { credit ->
        credit.id > 0 &&
            !credit.mediaType.isNullOrBlank() &&
            (credit.mediaType == "movie" || credit.mediaType == "tv")
    }

/** Directing/writing crew only: the section the actor page gained. */
internal fun TmdbPersonCredit.isDirectingOrWritingCrew(): Boolean =
    when (department?.trim()?.lowercase()) {
        "directing", "writing" -> true
        else -> false
    }

/** The shared filmography sort: popularity order, talk/variety demoted. */
internal fun filmographyRanking(): Comparator<TmdbPersonCredit> =
    compareByDescending<TmdbPersonCredit> { it.filmographyRankScore() }
        .thenByDescending { it.voteCount ?: 0 }
        .thenByDescending { it.popularity ?: 0.0 }
        .thenByDescending { it.voteAverage ?: 0.0 }
        .thenByDescending { it.filmographyDate() }
        .thenBy { it.filmographyTitle() }

/**
 * A cast filmography, ready to draw: eligible credits, deduped by ROLE (a
 * performer with two roles in one production keeps both cards), ranked.
 */
internal fun sortedCastFilmography(cast: List<TmdbPersonCredit>): List<TmdbPersonCredit> =
    cast.eligibleCredits()
        .distinctBy { "${it.mediaType}:${it.id}:${it.character.orEmpty()}" }
        .sortedWith(filmographyRanking())

/**
 * A crew filmography: eligible DIRECTING/WRITING credits, deduped by job,
 * ranked by the same rule as the cast list.
 */
internal fun sortedCrewFilmography(crew: List<TmdbPersonCredit>): List<TmdbPersonCredit> =
    crew.eligibleCredits()
        .filter { it.isDirectingOrWritingCrew() }
        .distinctBy { "${it.mediaType}:${it.id}:${it.job.orEmpty()}" }
        .sortedWith(filmographyRanking())

/**
 * The item id a card records in a return target: the title id plus the ROLE
 * (the character for cast, the job for crew). A dual-role credit's two cards
 * are thereby distinguishable, so backing out of Detail lands on the role that
 * was pressed rather than always the first (see [BrowseReturnFocus]).
 */
internal fun actorCreditItemId(credit: TmdbPersonCredit): String =
    "${credit.id}:${credit.character?.takeIf { it.isNotBlank() } ?: credit.job.orEmpty()}"
