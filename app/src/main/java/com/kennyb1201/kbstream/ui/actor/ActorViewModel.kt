package com.kennyb1201.kbstream.ui.actor

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonCredit
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
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

class ActorViewModel(application: Application) : AndroidViewModel(application) {
    private val tmdbRepository = TmdbRepository(application)
    private val watchedStatusRepository = WatchedStatusRepository(application)

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
        if (personDetail == null || resolvedMap.isEmpty()) {
            emptySet()
        } else {
            sortedCredits(personDetail)
                .mapNotNull { credit ->
                    val mediaType = normalizeMediaType(credit.mediaType) ?: return@mapNotNull null
                    val imdbId = resolvedMap[creditLookupKey(credit.id, mediaType)] ?: return@mapNotNull null
                    if (watchedStatusRepository.isWatchedCached(imdbId, mediaType)) {
                        watchedKey(imdbId, mediaType)
                    } else {
                        null
                    }
                }
                .toSet()
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptySet()
    )

    fun watchedKey(id: String, type: String): String = "${type.lowercase()}::$id"

    fun creditLookupKey(tmdbId: Int, mediaType: String): String =
        "${mediaType.lowercase()}::$tmdbId"

    fun load(personId: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            _topWorkBackdropUrl.value = null
            _topWorkCredit.value = null
            _error.value = null
            _person.value = null
            _resolvedCreditIds.value = emptyMap()

            try {
                val result = tmdbRepository.getPerson(personId)
                _person.value = result
                _topWorkCredit.value = result?.let { sortedCredits(it).firstOrNull() }

                if (result != null && result.combinedCredits?.cast.isNullOrEmpty()) {
                    _error.value = "TMDB returned this person but with zero credits"
                }
            } catch (e: Exception) {
                _error.value = "TMDB request failed: ${e.message}"
                Log.e("ACTOR_LOAD", "load failed for personId=$personId", e)
            } finally {
                _isLoading.value = false
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

    private fun TmdbPersonCredit.displayTitle(): String =
        title?.ifBlank { null } ?: name?.ifBlank { null } ?: ""

    private fun TmdbPersonCredit.displayDate(): String =
        when (mediaType?.lowercase()) {
            "movie" -> releaseDate ?: ""
            "tv" -> firstAirDate ?: ""
            else -> ""
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

        val detail = runCatching {
            tmdbRepository.getDetailByTmdbId(topCredit.id, normalizedType)
        }.getOrNull()

        _topWorkBackdropUrl.value = detail?.backdropPath
            ?.takeIf { it.isNotBlank() }
            ?.let { TmdbRepository.BACKDROP_BASE + it }
    }

    private fun TmdbPersonCredit.isLikelyTalkOrVariety(): Boolean {
        val text = buildString {
            append(displayTitle())
            append(' ')
            append(character.orEmpty())
        }.lowercase()

        val keywords = listOf(
            "talk show", "late night", "tonight show", "daily show",
            "news", "guest", "host", "interview", "panel", "variety",
            "reality", "game show"
        )

        return mediaType?.lowercase() == "tv" && keywords.any { it in text }
    }

    private fun TmdbPersonCredit.rankScore(): Int {
        var score = 0

        if (mediaType.equals("movie", true)) score += 500
        if (mediaType.equals("tv", true)) score += 250
        if (isLikelyTalkOrVariety()) score -= 500 else score += 220
        if (!posterPath.isNullOrBlank()) score += 100

        val votes = voteCount ?: 0
        if (votes >= 25) score += 40
        if (votes >= 100) score += 80
        if (votes >= 500) score += 120

        score += ((popularity ?: 0.0) * 8).toInt().coerceAtMost(320)
        score += ((voteAverage ?: 0.0) * 18).toInt().coerceAtMost(180)

        val year = displayDate().take(4).toIntOrNull() ?: 0
        if (year >= 1900) score += (year - 1900).coerceAtMost(140)

        return score
    }

    fun sortedCredits(person: TmdbPersonDetail?): List<TmdbPersonCredit> =
        person?.combinedCredits?.cast
            .orEmpty()
            .filter { credit ->
                credit.id > 0 &&
                    !credit.mediaType.isNullOrBlank() &&
                    (credit.mediaType == "movie" || credit.mediaType == "tv")
            }
            .distinctBy { "${it.mediaType}:${it.id}" }
            .sortedWith(
                compareByDescending<TmdbPersonCredit> { it.rankScore() }
                    .thenByDescending { it.voteCount ?: 0 }
                    .thenByDescending { it.popularity ?: 0.0 }
                    .thenByDescending { it.voteAverage ?: 0.0 }
                    .thenByDescending { it.displayDate() }
                    .thenBy { it.displayTitle() }
            )

    private suspend fun resolveAndPreloadWatched(person: TmdbPersonDetail) {
        try {
            val cast = sortedCredits(person)
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
                        val imdbId = runCatching {
                            tmdbRepository.resolveImdbId(credit.id, mediaType)
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
                ?: runCatching {
                    tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()

            if (!imdbId.isNullOrBlank()) {
                onNavigateDetail(normalizedType, imdbId)
            }
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
                ?: runCatching {
                    tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()
                    ?: return@launch

            if (_resolvedCreditIds.value[lookup] == null) {
                _resolvedCreditIds.value = _resolvedCreditIds.value + (lookup to imdbId)
            }

            runCatching {
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
                ?: runCatching {
                    tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                }.getOrNull()
                    ?: return@launch

            if (_resolvedCreditIds.value[lookup] == null) {
                _resolvedCreditIds.value = _resolvedCreditIds.value + (lookup to imdbId)
            }

            runCatching {
                watchedStatusRepository.markUnwatchedLocal(imdbId, normalizedType)
            }.onFailure { e ->
                Log.e("ACTOR_WATCHED", "markUnwatched failed tmdb=$tmdbId", e)
            }
        }
    }

    suspend fun resolveImdbId(tmdbId: Int, type: String): String? =
        tmdbRepository.resolveImdbId(tmdbId, type)
}
