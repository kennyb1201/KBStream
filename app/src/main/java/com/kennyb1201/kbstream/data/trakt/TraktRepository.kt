package com.kennyb1201.kbstream.data.trakt

import android.content.Context
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.kennyb1201.kbstream.data.reporting.NetworkTraceInterceptor
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * One poll's answer: the flow state, plus the token when there is one.
 *
 * [retryAfterSeconds] is set only where Trakt asked for a pause, so the caller
 * can widen its interval without parsing headers of its own.
 */
data class TraktPollResult(
    val state: TraktDeviceFlowRules.Poll,
    val token: TraktToken? = null,
    val retryAfterSeconds: Long? = null
)

/**
 * Trakt, as this app speaks to it.
 *
 * The device-code grant, the proactive refresh and the disconnect; the
 * scrobble history writes; the watched snapshot that Home and the badges read;
 * user-list reads for KB folders; and comment reads for Detail. Nothing here
 * touches a player or the local history database: the players call in
 * fire-and-forget, so no Trakt failure can block playback.
 *
 * Two rules are structural rather than documented:
 *
 *  - **[revokeAndClear] clears the Trakt session and nothing else.** Local
 *    history, resume rows and watched markers belong to the viewer. Simkl's
 *    disconnect used to delete them (audit m10) and that is exactly the bug this
 *    method is written not to repeat.
 *  - **Every request goes through [TraktRateGuard].** Auth is a handful of calls,
 *    so the guard cannot bite here, but it is the same guard the later phases
 *    need and starting without it is how MDBList's 40-attempt storm happened.
 */
class TraktRepository private constructor(private val context: Context?) {

    companion object {
        private const val PREFS_TAG = "TraktRepository"

        // The watched snapshot is account-wide and read by badges and Continue
        // Watching alike; 15 minutes is the same cadence the watched
        // repository already uses for the other trackers' sets, so the three
        // do not make the screen wait on three different refresh schedules.
        private const val SNAPSHOT_TTL_MS = 15L * 60L * 1_000L

        /** The review rail is a handful of the best comments, not a feed. */
        private const val MAX_COMMENTS = 20

        /** One Trakt read page (Trakt's own maximum page size). */
        private const val TRAKT_PAGE_SIZE = 100

        @Volatile
        private var instance: TraktRepository? = null

        fun getInstance(context: Context? = null): TraktRepository {
            return instance ?: synchronized(this) {
                instance ?: TraktRepository(context?.applicationContext).also { instance = it }
            }
        }
    }

    internal val store = TraktTokenStore(context)

    internal val guard = TraktRateGuard()

    private val snapshotMutex = Mutex()

    @Volatile
    private var cachedSnapshot: TraktWatchedSnapshot? = null

    @Volatile
    private var cachedSnapshotAt: Long = 0L

    @Volatile
    private var cachedSnapshotKey: String? = null

    private val moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    /** Reads an OAuth error body, which Retrofit does not parse for a non-2xx. */
    private val errorAdapter by lazy {
        moshi.adapter(TraktTokenResponse::class.java)
    }

    // Derived from the process-wide base client so Trakt traffic reuses its
    // sockets; the headers interceptor and the timing trace are this service's own.
    private val okHttpClient by lazy {
        BaseHttpClient.derived {
            addInterceptor(TraktHeadersInterceptor())
            addInterceptor(NetworkTraceInterceptor())
        }
    }

    internal val api: TraktApiService by lazy {
        Retrofit.Builder()
            .baseUrl(TraktConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TraktApiService::class.java)
    }

    /** A build with a client id can talk to Trakt; one without says so. */
    fun isConfigured(): Boolean = TraktConfig.isConfigured

    fun hasToken(): Boolean = store.accessToken() != null

    /** The connected account's slug, as recorded at connect time. */
    fun accountSlug(): String? = store.accountSlug()

    fun savedExpiresAtEpochMillis(): Long = store.expiresAtEpochMillis()

    /** Step 1: ask for a code to show the viewer. */
    suspend fun requestDeviceCode(): TraktDeviceCodeResponse {
        require(TraktConfig.isConfigured) { "TRAKT_CLIENT_ID is missing" }
        check(guard.tryAcquire()) { "Trakt request budget exhausted" }
        return api.requestDeviceCode(TraktDeviceCodeRequest(TraktConfig.clientId))
    }

    /**
     * Step 2: one poll. Never throws for a state Trakt reports as a status.
     *
     * @param elapsedSeconds how long ago the code was issued
     * @param expiresInSeconds the code's lifetime from [requestDeviceCode]
     */
    suspend fun pollOnce(
        deviceCode: String,
        elapsedSeconds: Long,
        expiresInSeconds: Int
    ): TraktPollResult {
        if (!TraktConfig.isConfigured) return TraktPollResult(TraktDeviceFlowRules.Poll.FAILED)
        if (!guard.tryAcquire()) {
            return TraktPollResult(
                TraktDeviceFlowRules.Poll.FAILED,
                retryAfterSeconds = guard.blockedForMs() / 1_000L
            )
        }
        val response = api.pollDeviceToken(
            TraktDeviceTokenRequest(
                code = deviceCode,
                clientId = TraktConfig.clientId
            )
        )
        val retryAfter = response.headers()["Retry-After"]?.toLongOrNull()
        if (response.code() == 429 && retryAfter != null) guard.noteRetryAfter(retryAfter)

        val body = response.body()
        // A non-2xx has no parsed body; the OAuth error code lives in the raw one.
        val oauthError = if (body == null) {
            runCatching {
                errorAdapter.fromJson(response.errorBody()?.string().orEmpty())?.error
            }.getOrNull()
        } else {
            null
        }
        val state = TraktDeviceFlowRules.poll(
            statusCode = response.code(),
            hasToken = !body?.accessToken.isNullOrBlank(),
            elapsedSeconds = elapsedSeconds,
            expiresInSeconds = expiresInSeconds,
            error = oauthError
        )
        if (state != TraktDeviceFlowRules.Poll.APPROVED) {
            return TraktPollResult(state, retryAfterSeconds = retryAfter)
        }
        val granted = body ?: return TraktPollResult(TraktDeviceFlowRules.Poll.FAILED)
        val token = TraktToken(
            accessToken = granted.accessToken.orEmpty(),
            refreshToken = granted.refreshToken,
            expiresAtEpochMillis = TraktDeviceFlowRules.expiresAtEpochMillis(
                createdAtEpochSeconds = granted.createdAtEpochSeconds,
                expiresInSeconds = granted.expiresInSeconds,
                nowMs = System.currentTimeMillis()
            )
        )
        store.save(token)
        return TraktPollResult(TraktDeviceFlowRules.Poll.APPROVED, token)
    }

    /**
     * Refreshes the access token when the refresh window has opened.
     *
     * Proactive, per the plan: seven-day tokens are refreshed with a day in
     * hand, so a player's first scrobble never pays for a token round-trip.
     * Trakt's refresh tokens are single-use, so the stored pair is always
     * replaced by what the response carries.
     *
     * @return true when a usable token is held afterwards
     */
    suspend fun refreshIfDue(nowMs: Long = System.currentTimeMillis()): Boolean {
        val token = store.read() ?: return false
        if (!TraktDeviceFlowRules.refreshDue(token.expiresAtEpochMillis, nowMs)) return true
        val refresh = token.refreshToken ?: return false
        if (!TraktConfig.isConfigured) return false
        if (!guard.tryAcquire()) return false
        val response = runCatchingCancellable {
            api.refreshToken(
                TraktRefreshRequest(
                    refreshToken = refresh,
                    clientId = TraktConfig.clientId
                )
            )
        }.getOrNull() ?: return false
        val body = response.body() ?: return false
        val access = body.accessToken?.takeIf { it.isNotBlank() } ?: return false
        store.save(
            TraktToken(
                accessToken = access,
                refreshToken = body.refreshToken?.takeIf { it.isNotBlank() } ?: refresh,
                expiresAtEpochMillis = TraktDeviceFlowRules.expiresAtEpochMillis(
                    createdAtEpochSeconds = body.createdAtEpochSeconds,
                    expiresInSeconds = body.expiresInSeconds,
                    nowMs = nowMs
                )
            )
        )
        return true
    }

    /** The access token for a request, refreshed first when it is due. */
    suspend fun validAccessToken(nowMs: Long = System.currentTimeMillis()): String? {
        val token = store.read() ?: return null
        if (TraktDeviceFlowRules.refreshDue(token.expiresAtEpochMillis, nowMs)) {
            refreshIfDue(nowMs)
        }
        return store.accessToken()
    }

    /** Reads the connected account's slug for the screen's account line. */
    suspend fun loadAccountSlug(): String? {
        val token = validAccessToken() ?: return null
        if (!guard.tryAcquire()) return store.accountSlug()
        val settings = runCatchingCancellable {
            api.userSettings("Bearer $token")
        }.getOrNull() ?: return store.accountSlug()
        val slug = settings.user?.ids?.slug ?: settings.user?.username
        if (!slug.isNullOrBlank()) store.setAccountSlug(slug)
        return slug
    }

    /**
     * Disconnects.
     *
     * Revokes the grant when the network allows it, then drops ONLY the Trakt
     * session keys: history, resume rows, watched markers and the local library
     * are untouched, and stay exactly as they are after the disconnect.
     */
    suspend fun revokeAndClear() {
        val token = store.read()
        val revocable = token?.accessToken ?: token?.refreshToken
        if (revocable != null && TraktConfig.isConfigured && guard.tryAcquire()) {
            runCatchingCancellable {
                api.revokeToken(
                    authorization = "Bearer $revocable",
                    body = TraktRevokeRequest(
                        token = revocable,
                        clientId = TraktConfig.clientId
                    )
                )
            }
        }
        store.clear()
        invalidateWatchedSnapshot()
    }

    // ------------------------------------------------------------------
    // Phase B: scrobbling
    // ------------------------------------------------------------------

    /**
     * Mirrors one player event to Trakt's `/scrobble/{start,pause,stop}`.
     *
     * Fire-and-forget by construction: every reason not to send (no client id,
     * no token, a spent budget, an id Trakt cannot resolve) answers `false`
     * instead of throwing, so a tracker outage can never reach playback. The
     * progress is a whole percent, per [TraktScrobbleRules.progressPercent].
     */
    suspend fun scrobble(
        action: String,
        parentId: String,
        parentType: String,
        season: Int? = null,
        episode: Int? = null,
        progress: Double,
        tmdbId: Int? = null
    ): Boolean {
        if (!TraktConfig.isConfigured || parentId.isBlank()) return false
        val token = validAccessToken() ?: return false
        if (!guard.tryAcquire()) return false

        val ids = idsFor(parentId, tmdbId)
        if (ids.imdb == null && ids.tmdb == null) return false
        val percent = TraktScrobbleRules.progressPercent(progress)
        val body = if (TraktScrobbleRules.isMovie(parentType)) {
            TraktScrobbleRequest(
                movie = TraktMediaEntry(ids = ids),
                progress = percent
            )
        } else {
            val showSeason = season ?: return false
            val showEpisode = episode ?: return false
            TraktScrobbleRequest(
                show = TraktMediaEntry(ids = ids),
                episode = TraktEpisodeNumber(showSeason, showEpisode),
                progress = percent
            )
        }

        val response = runCatchingCancellable {
            api.scrobble(action = action, authorization = "Bearer $token", body = body)
        }.getOrNull() ?: return false
        noteRetryAfter(response)
        if (!response.isSuccessful) return false
        // A stop at Trakt's own threshold records the title as watched, so the
        // snapshot the badges read is now stale. A start/pause changes nothing
        // in the watched history and is not worth a refetch.
        if (action == "stop") invalidateWatchedSnapshot()
        return true
    }

    /**
     * Marks a movie watched through `/sync/history`.
     *
     * Used by the players' completion path (and by the local mark-watched
     * paths) where the intent is explicit rather than inferred from a stop
     * percentage.
     */
    suspend fun pushWatchedMovie(
        parentId: String,
        tmdbId: Int? = null
    ): Boolean {
        if (parentId.isBlank()) return false
        val token = validAccessToken() ?: return false
        if (!guard.tryAcquire()) return false
        val ids = idsFor(parentId, tmdbId)
        if (ids.imdb == null && ids.tmdb == null) return false
        val response = runCatchingCancellable {
            api.addToHistory(
                authorization = "Bearer $token",
                body = TraktSyncHistoryRequest(
                    movies = listOf(TraktMediaEntry(ids = ids))
                )
            )
        }.getOrNull() ?: return false
        noteRetryAfter(response)
        if (!response.isSuccessful) return false
        invalidateWatchedSnapshot()
        return true
    }

    /**
     * Marks a whole show watched.
     *
     * Trakt expands an ids-only show entry to every episode it knows, which is
     * the documented meaning of the request (see `/sync/history`): the app's
     * own "mark series watched" is the same whole-show intent.
     */
    suspend fun pushWatchedShow(
        showId: String,
        tmdbId: Int? = null
    ): Boolean = postShowHistory(showId, tmdbId, add = true)

    /** Removes a movie from the account's watched history. */
    suspend fun removeWatchedMovie(
        parentId: String,
        tmdbId: Int? = null
    ): Boolean {
        if (parentId.isBlank()) return false
        val token = validAccessToken() ?: return false
        if (!guard.tryAcquire()) return false
        val ids = idsFor(parentId, tmdbId)
        if (ids.imdb == null && ids.tmdb == null) return false
        val response = runCatchingCancellable {
            api.removeFromHistory(
                authorization = "Bearer $token",
                body = TraktSyncHistoryRequest(
                    movies = listOf(TraktMediaEntry(ids = ids))
                )
            )
        }.getOrNull() ?: return false
        noteRetryAfter(response)
        if (!response.isSuccessful) return false
        invalidateWatchedSnapshot()
        return true
    }

    /** Removes a whole show from the account's watched history. */
    suspend fun removeWatchedShow(
        showId: String,
        tmdbId: Int? = null
    ): Boolean = postShowHistory(showId, tmdbId, add = false)

    private suspend fun postShowHistory(
        showId: String,
        tmdbId: Int?,
        add: Boolean
    ): Boolean {
        if (showId.isBlank()) return false
        val token = validAccessToken() ?: return false
        if (!guard.tryAcquire()) return false
        val ids = idsFor(showId, tmdbId)
        if (ids.imdb == null && ids.tmdb == null) return false
        val body = TraktSyncHistoryRequest(
            shows = listOf(TraktShowHistoryEntry(ids = ids))
        )
        val response = runCatchingCancellable {
            if (add) {
                api.addToHistory("Bearer $token", body)
            } else {
                api.removeFromHistory("Bearer $token", body)
            }
        }.getOrNull() ?: return false
        noteRetryAfter(response)
        if (!response.isSuccessful) return false
        invalidateWatchedSnapshot()
        return true
    }

    /** Removes one episode from the account's watched history. */
    suspend fun removeWatchedEpisode(
        showId: String,
        season: Int,
        episode: Int,
        tmdbId: Int? = null
    ): Boolean {
        if (showId.isBlank()) return false
        val token = validAccessToken() ?: return false
        if (!guard.tryAcquire()) return false
        val ids = idsFor(showId, tmdbId)
        if (ids.imdb == null && ids.tmdb == null) return false
        val response = runCatchingCancellable {
            api.removeFromHistory(
                authorization = "Bearer $token",
                body = TraktSyncHistoryRequest(
                    shows = listOf(
                        TraktShowHistoryEntry(
                            ids = ids,
                            seasons = listOf(
                                TraktSeasonHistoryEntry(
                                    number = season,
                                    episodes = listOf(TraktEpisodeNumber(season, episode))
                                )
                            )
                        )
                    )
                )
            )
        }.getOrNull() ?: return false
        noteRetryAfter(response)
        if (!response.isSuccessful) return false
        invalidateWatchedSnapshot()
        return true
    }

    /** Marks one episode of a show watched through `/sync/history`. */
    suspend fun pushWatchedEpisode(
        showId: String,
        season: Int,
        episode: Int,
        tmdbId: Int? = null
    ): Boolean {
        if (showId.isBlank()) return false
        val token = validAccessToken() ?: return false
        if (!guard.tryAcquire()) return false
        val ids = idsFor(showId, tmdbId)
        if (ids.imdb == null && ids.tmdb == null) return false
        val response = runCatchingCancellable {
            api.addToHistory(
                authorization = "Bearer $token",
                body = TraktSyncHistoryRequest(
                    shows = listOf(
                        TraktShowHistoryEntry(
                            ids = ids,
                            seasons = listOf(
                                TraktSeasonHistoryEntry(
                                    number = season,
                                    episodes = listOf(TraktEpisodeNumber(season, episode))
                                )
                            )
                        )
                    )
                )
            )
        }.getOrNull() ?: return false
        noteRetryAfter(response)
        if (!response.isSuccessful) return false
        invalidateWatchedSnapshot()
        return true
    }

    // ------------------------------------------------------------------
    // Phase C: watched state
    // ------------------------------------------------------------------

    /**
     * The account's watched history, cached for [SNAPSHOT_TTL_MS].
     *
     * Two reads (`/sync/watched/shows` and `/sync/watched/movies`) answer one
     * snapshot. Anything that goes wrong returns the snapshot already in hand -
     * never an empty one, which downstream would read as "nothing watched" -
     * and a spent budget returns the cache without touching the network, the
     * same rule [TraktRateGuard] states. The cache is keyed by the access token
     * so a profile switch (a different profile's token) can never be served the
     * previous profile's history.
     */
    suspend fun watchedSnapshot(forceRefresh: Boolean = false): TraktWatchedSnapshot {
        if (!TraktConfig.isConfigured) return TraktWatchedSnapshot()
        val token = validAccessToken() ?: return TraktWatchedSnapshot()

        cachedSnapshot?.let { cached ->
            if (!forceRefresh &&
                cachedSnapshotKey == token &&
                System.currentTimeMillis() - cachedSnapshotAt < SNAPSHOT_TTL_MS
            ) {
                return cached
            }
        }

        return snapshotMutex.withLock {
            val previous = cachedSnapshot?.takeIf { cachedSnapshotKey == token }
            if (!forceRefresh &&
                previous != null &&
                System.currentTimeMillis() - cachedSnapshotAt < SNAPSHOT_TTL_MS
            ) {
                return@withLock previous
            }
            if (!guard.tryAcquire()) return@withLock previous ?: TraktWatchedSnapshot()

            val showsResponse = runCatchingCancellable {
                api.watchedShows("Bearer $token")
            }.getOrNull()
            noteRetryAfter(showsResponse)
            val moviesResponse = if (showsResponse != null && showsResponse.isSuccessful) {
                runCatchingCancellable {
                    api.watchedMovies("Bearer $token")
                }.getOrNull()
            } else {
                null
            }
            moviesResponse?.let(::noteRetryAfter)

            val showsOk = showsResponse?.isSuccessful == true
            val moviesOk = moviesResponse?.isSuccessful == true
            if (!showsOk && !moviesOk) return@withLock previous ?: TraktWatchedSnapshot()

            val built = TraktWatchRules.snapshot(
                shows = showsResponse?.body().takeIf { showsOk },
                movies = moviesResponse?.body().takeIf { moviesOk }
            )
            // A half-answered refresh keeps the half it did not hear about,
            // rather than clearing that half's badges until the next TTL.
            val merged = when {
                !showsOk && previous != null -> built.copy(shows = previous.shows)
                !moviesOk && previous != null -> built.copy(movieKeys = previous.movieKeys)
                else -> built
            }
            cachedSnapshot = merged
            cachedSnapshotAt = System.currentTimeMillis()
            cachedSnapshotKey = token
            merged
        }
    }

    /**
     * The account's watched episodes for one show, by any of [showKeys].
     *
     * Empty when the tracker has nothing on record; the caller distinguishes
     * "no answer" by catching the call, exactly as the Simkl/MDBList union
     * already does.
     */
    suspend fun watchedEpisodesForShow(showKeys: List<String>): Set<Pair<Int, Int>> =
        watchedSnapshot().watchedEpisodesFor(showKeys)

    /** Drops the cached snapshot so the next read re-downloads it. */
    private fun invalidateWatchedSnapshot() {
        cachedSnapshot = null
        cachedSnapshotAt = 0L
        cachedSnapshotKey = null
    }

    // ------------------------------------------------------------------
    // Phase D: external lists
    // ------------------------------------------------------------------

    /**
     * A Trakt user list's movies and shows, in list order.
     *
     * The API addresses one media type at a time, so both reads happen here
     * and the two halves are merged. A failed half contributes nothing (the
     * folder's rail then shows what did arrive); a failure of both is an empty
     * list, which the KB loader already renders as "this rail has nothing"
     * rather than failing the folder.
     */
    suspend fun listItems(ref: TraktListRef): List<TraktListEntry> {
        if (!TraktConfig.isConfigured) return emptyList()
        val token = validAccessToken() ?: return emptyList()
        val movies = readListHalf(ref, "movies")
        val shows = readListHalf(ref, "shows")
        return movies + shows
    }

    private suspend fun readListHalf(
        ref: TraktListRef,
        type: String
    ): List<TraktListEntry> = readAllPages { page ->
        val user = ref.user
        if (user == null) {
            api.listItemsById(listId = ref.listId, type = type, page = page)
        } else {
            api.listItems(
                user = user,
                listId = ref.listId,
                type = type,
                page = page
            )
        }
    }

    /**
     * Every page of one Trakt list read — no title cap.
     *
     * Trakt pages at 100 and reports how many pages there are in
     * `X-Pagination-Page-Count`. That header is the terminator: fetch page 1,
     * then every page it names. When a proxy or a stubbed response omits the
     * header, a FULL page means "probably more", so the next one is fetched;
     * a short page ends the read.
     *
     * Two guards keep an uncapped read from becoming an unbounded one:
     *
     *  - **[TraktRateGuard]** is spent one page at a time, exactly like every
     *    other call. Exhaustion stops the read with the pages already in hand
     *    rather than blanking the rail (the "exhaustion is not empty" rule).
     *  - **A page that adds no new row ends the read.** An add-on-style server
     *    that ignores `page` and repeats page 1 forever would otherwise loop;
     *    duplicate detection is the real safety net, not a page number cap.
     *
     * Rows are de-duplicated by Trakt id (then IMDb/TMDB/slug), so a repeated
     * page can never double a rail.
     */
    private suspend fun readAllPages(
        call: suspend (page: Int) -> Response<List<TraktListItem>>
    ): List<TraktListEntry> {
        val entries = mutableListOf<TraktListEntry>()
        val seen = HashSet<String>()
        var page = 1
        var pageCount = 1
        while (page <= pageCount) {
            if (!guard.tryAcquire()) break
            val response = runCatchingCancellable { call(page) }.getOrNull() ?: break
            noteRetryAfter(response)
            if (!response.isSuccessful) break

            val rows = response.body().orEmpty()
            var added = 0
            rows.forEach { row ->
                val media = row.movie ?: row.show ?: return@forEach
                val isMovie = row.movie != null || row.type == "movie"
                if (!seen.add(mediaKey(isMovie, media))) return@forEach
                entries += TraktListEntry(media = media, isMovie = isMovie)
                added += 1
            }

            val headerPages = response.headers()["X-Pagination-Page-Count"]
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
            pageCount = headerPages
                ?: if (rows.size >= TRAKT_PAGE_SIZE) page + 1 else page

            if (rows.isEmpty()) break
            if (added == 0) break
            page += 1
        }
        return entries
    }

    /** Stable identity for one list/watched row across pages. */
    private fun mediaKey(isMovie: Boolean, media: TraktMediaEntry): String {
        val ids = media.ids
        val id = ids?.trakt?.toString()
            ?: ids?.imdb
            ?: ids?.tmdb?.toString()
            ?: ids?.slug
            ?: "${media.title}:${media.year}"
        return "${if (isMovie) "movie" else "show"}:$id"
    }

    /**
     * The account's watchlist, as the Library tab reads it.
     *
     * The Library's other watchlist sources (Simkl's plan-to-watch, MDBList's
     * watchlist) are one read each; this keeps that shape. Every failure is an
     * empty list, so an outage leaves the tab's other sources intact rather
     * than half-drawn.
     */
    suspend fun watchlist(): List<TraktListEntry> {
        if (!TraktConfig.isConfigured) return emptyList()
        val token = validAccessToken() ?: return emptyList()
        return readAllPages { page ->
            api.watchlist("Bearer $token", page = page)
        }
    }

    /**
     * The connected account's personal lists, for the Library's list rail.
     *
     * A list without any id is skipped: the rail and the item read are both
     * addressed by a Trakt id or slug, and a row that can never be opened is
     * worse than no row.
     */
    suspend fun userLists(): List<TraktUserList> {
        if (!TraktConfig.isConfigured) return emptyList()
        val token = validAccessToken() ?: return emptyList()
        if (!guard.tryAcquire()) return emptyList()
        val response = runCatchingCancellable {
            api.userLists("Bearer $token")
        }.getOrNull() ?: return emptyList()
        noteRetryAfter(response)
        if (!response.isSuccessful) return emptyList()
        return response.body().orEmpty()
            .filter { it.name?.isNotBlank() == true }
            .filter { it.ids?.trakt != null || it.ids?.slug != null }
    }

    /**
     * Adds one title to the account's watchlist — the mirror behind the
     * Library's "Add to Library".
     *
     * Ids only where Trakt accepts them (a show entry is addressed by ids), and
     * title/year on the movie entry so the watchlist row reads correctly even
     * before the app's next TMDB enrichment. A title with no id Trakt can
     * resolve is refused before a request is made.
     */
    suspend fun addToWatchlist(
        isMovie: Boolean,
        imdbId: String?,
        tmdbId: Int?,
        title: String,
        year: Int?
    ): Boolean {
        val ids = TraktIds(
            imdb = imdbId?.trim()?.takeIf { it.startsWith("tt") },
            tmdb = tmdbId?.takeIf { it > 0 }
        )
        if (ids.imdb == null && ids.tmdb == null) return false
        val token = validAccessToken() ?: return false
        if (!guard.tryAcquire()) return false
        val body = TraktSyncHistoryRequest(
            movies = if (isMovie) {
                listOf(TraktMediaEntry(title = title, year = year, ids = ids))
            } else {
                null
            },
            shows = if (isMovie) null else listOf(TraktShowHistoryEntry(ids = ids))
        )
        val response = runCatchingCancellable {
            api.addToWatchlist("Bearer $token", body)
        }.getOrNull() ?: return false
        noteRetryAfter(response)
        return response.isSuccessful
    }

    /** Removes one title from the account's watchlist. */
    suspend fun removeFromWatchlist(
        isMovie: Boolean,
        imdbId: String?,
        tmdbId: Int?
    ): Boolean {
        val body = removalBody(isMovie, imdbId, tmdbId) ?: return false
        val token = validAccessToken() ?: return false
        if (!guard.tryAcquire()) return false
        val response = runCatchingCancellable {
            api.removeFromWatchlist("Bearer $token", body)
        }.getOrNull() ?: return false
        noteRetryAfter(response)
        return response.isSuccessful
    }

    /**
     * Adds one title to a personal list the account owns — the picker's
     * "Add to list…" destination for a Trakt list.
     *
     * Addressed by the list's slug or id (both come back on the list read), and
     * the title by whichever ids the app holds. A list id and a title id are
     * each required; neither request shape has a meaningful empty form.
     */
    suspend fun addToList(
        listId: String,
        isMovie: Boolean,
        imdbId: String?,
        tmdbId: Int?,
        title: String,
        year: Int?
    ): Boolean {
        if (listId.isBlank()) return false
        val ids = TraktIds(
            imdb = imdbId?.trim()?.takeIf { it.startsWith("tt") },
            tmdb = tmdbId?.takeIf { it > 0 }
        )
        if (ids.imdb == null && ids.tmdb == null) return false
        val token = validAccessToken() ?: return false
        if (!guard.tryAcquire()) return false
        val body = TraktSyncHistoryRequest(
            movies = if (isMovie) {
                listOf(TraktMediaEntry(title = title, year = year, ids = ids))
            } else {
                null
            },
            shows = if (isMovie) null else listOf(TraktShowHistoryEntry(ids = ids))
        )
        val response = runCatchingCancellable {
            api.addToList("Bearer $token", listId, body)
        }.getOrNull() ?: return false
        noteRetryAfter(response)
        return response.isSuccessful
    }

    /** Removes one title from a personal list the account owns. */
    suspend fun removeFromList(
        listId: String,
        isMovie: Boolean,
        imdbId: String?,
        tmdbId: Int?
    ): Boolean {
        if (listId.isBlank()) return false
        val body = removalBody(isMovie, imdbId, tmdbId) ?: return false
        val token = validAccessToken() ?: return false
        if (!guard.tryAcquire()) return false
        val response = runCatchingCancellable {
            api.removeFromList("Bearer $token", listId, body)
        }.getOrNull() ?: return false
        noteRetryAfter(response)
        return response.isSuccessful
    }

    /**
     * The removal body both watchlist/list writes share.
     *
     * Null when neither id can address the title: an empty ids object is a
     * request Trakt answers with an error, and this app never sends one.
     */
    private fun removalBody(
        isMovie: Boolean,
        imdbId: String?,
        tmdbId: Int?
    ): TraktSyncHistoryRequest? {
        val ids = TraktIds(
            imdb = imdbId?.trim()?.takeIf { it.startsWith("tt") },
            tmdb = tmdbId?.takeIf { it > 0 }
        )
        if (ids.imdb == null && ids.tmdb == null) return null
        return TraktSyncHistoryRequest(
            movies = if (isMovie) listOf(TraktMediaEntry(ids = ids)) else null,
            shows = if (isMovie) null else listOf(TraktShowHistoryEntry(ids = ids))
        )
    }

    // ------------------------------------------------------------------
    // Phase E1: reviews
    // ------------------------------------------------------------------

    /**
     * Trakt comments (reviews and shouts) for a title.
     *
     * Trakt does not accept a TMDB id in the comments path, so a TMDB-only
     * title is resolved through `/search/tmdb/{id}` first; an IMDb id needs no
     * resolution. Sorted by likes, because the row is a review rail rather than
     * a chronological feed. Empty on any failure - reviews must never block a
     * detail page.
     */
    suspend fun commentsFor(
        tmdbId: Int?,
        imdbId: String?,
        isMovie: Boolean
    ): List<TraktComment> {
        if (!TraktConfig.isConfigured) return emptyList()
        val token = validAccessToken() ?: return emptyList()
        val subject = imdbId
            ?.trim()
            ?.takeIf { it.startsWith("tt") }
            ?: tmdbId?.let { resolveCommentSubject("Bearer $token", it, isMovie) }
            ?: return emptyList()
        if (!guard.tryAcquire()) return emptyList()
        val response = runCatchingCancellable {
            api.comments(
                type = if (isMovie) "movies" else "shows",
                id = subject
            )
        }.getOrNull() ?: return emptyList()
        noteRetryAfter(response)
        if (!response.isSuccessful) return emptyList()
        return response.body().orEmpty()
            .filter { !it.comment.isNullOrBlank() }
            .take(MAX_COMMENTS)
    }

    private suspend fun resolveCommentSubject(
        authorization: String,
        tmdbId: Int,
        isMovie: Boolean
    ): String? {
        if (!guard.tryAcquire()) return null
        val response = runCatchingCancellable {
            api.searchById(
                idType = "tmdb",
                id = tmdbId.toString(),
                type = if (isMovie) "movie" else "show"
            )
        }.getOrNull() ?: return null
        noteRetryAfter(response)
        if (!response.isSuccessful) return null
        val entry = response.body().orEmpty()
            .firstOrNull { if (isMovie) it.movie != null else it.show != null }
        val ids = if (isMovie) entry?.movie?.ids else entry?.show?.ids
        return ids?.trakt?.toString()?.takeIf { it.isNotBlank() }
            ?: ids?.slug?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /**
     * The id object every Trakt write travels with.
     *
     * `parentId` is the app's own id (an IMDb id or the numeric TMDB id), and
     * [tmdbId] is the resolved twin when the app only knew an IMDb/other id. A
     * title with neither id cannot be addressed by Trakt and is refused by the
     * caller before a request is made (the id object would be empty).
     */
    private fun idsFor(parentId: String, tmdbId: Int?): TraktIds {
        val trimmed = parentId.trim()
        val imdb = when {
            trimmed.startsWith("imdb:") -> trimmed.substringAfter(':')
            trimmed.startsWith("tt") -> trimmed.substringBefore(':')
            else -> null
        }?.trim()?.takeIf { it.isNotBlank() }
        val tmdb = when {
            trimmed.startsWith("tmdb:") -> trimmed.substringAfter(':').toIntOrNull()
            trimmed.all(Char::isDigit) -> trimmed.toIntOrNull()
            else -> null
        } ?: tmdbId?.takeIf { it > 0 }
        return TraktIds(imdb = imdb, tmdb = tmdb)
    }

    /** A 429 parks the next call for its `Retry-After`, or a minute by default. */
    private fun noteRetryAfter(response: Response<*>?) {
        if (response?.code() != 429) return
        val seconds = response.headers()["Retry-After"]?.toLongOrNull() ?: 60L
        guard.noteRetryAfter(seconds)
    }

}
