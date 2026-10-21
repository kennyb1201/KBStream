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
 *
 * [saveError] is the third kind of answer, and the one the "code expired"
 * report came from: Trakt granted the token (`state` is APPROVED) but this
 * device could not keep it. The code was consumed by that very exchange, so
 * re-polling can only read 404 - the caller must say "approved, but not saved"
 * and stop, not retry ([TraktPollExchange]).
 *
 * [statusCode] is the HTTP status the read carried (null when there was none),
 * because [state] alone loses the one distinction the flow's ending needs: a
 * 404 after a poll threw is the spent code being read again, not an expiry.
 */
data class TraktPollResult(
    val state: TraktDeviceFlowRules.Poll,
    val token: TraktToken? = null,
    val retryAfterSeconds: Long? = null,
    val saveError: String? = null,
    val statusCode: Int? = null
)

/**
 * The connect screen's two tiles: how many movies and shows this account has
 * watched. Display-only - Home and the badges read the watched snapshot.
 */
data class TraktWatchedCounts(
    val movies: Int,
    val shows: Int
)

/**
 * Counts from the two watched reads, or null when either answer is unusable.
 *
 * A half-answered pair is not reported: showing one tile and hiding the other
 * would read as "0 shows watched" over an account that has hundreds. Pure so
 * the failure rule is tested without a network or a token.
 */
internal fun traktWatchedCounts(
    movies: List<TraktWatchedMovie>?,
    shows: List<TraktWatchedShow>?
): TraktWatchedCounts? {
    if (movies == null || shows == null) return null
    return TraktWatchedCounts(movies = movies.size, shows = shows.size)
}

/**
 * Every page of one watched read, in page order - or null when ANY page failed.
 *
 * The spec of this helper is deliberately not [readAllPages]'s: the list reads
 * may stop early and keep what they have (a half rail beats no rail), but this
 * feeds the watched snapshot, where a short list is read as "unwatched". So a
 * failure on page 2 must not publish page 1: a non-2xx, a transport error and
 * an exhausted [TraktRateGuard] all answer null, and the caller keeps the
 * previous snapshot / hides the tiles.
 *
 * Trakt pages both watched reads at [pageSize] and names the page count in
 * `X-Pagination-Page-Count`; `X-Pagination-Item-Count` is the fallback for a
 * response that carries only the total. If neither header arrives, a page
 * shorter than [pageSize] ends the read - otherwise a full page means "ask for
 * the next one".
 *
 * One budget slot is spent per page, "as today's single calls" did per read, so
 * the free tier's accounting is unchanged in kind. [acquire] and
 * [noteRetryAfter] are parameters rather than reads of the private [guard] and
 * clock because that is exactly what makes the loop testable without a token or
 * a network (the same reason [traktWatchedCounts] is a top-level function).
 */
internal suspend fun <T> readAllWatchedPages(
    pageSize: Int,
    acquire: () -> Boolean,
    noteRetryAfter: (Response<*>?) -> Unit,
    fetch: suspend (page: Int, limit: Int) -> Response<List<T>>
): List<T>? {
    require(pageSize > 0) { "page size must be positive" }
    val rows = mutableListOf<T>()
    var page = 1
    var pageCount: Int? = null

    while (true) {
        if (!acquire()) return null
        val response = runCatchingCancellable {
            fetch(page, pageSize)
        }.getOrNull() ?: return null
        noteRetryAfter(response)
        if (!response.isSuccessful) return null
        val body = response.body() ?: return null
        rows += body

        val headers = response.headers()
        headers["X-Pagination-Page-Count"]
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { pageCount = it }
        if (pageCount == null) {
            headers["X-Pagination-Item-Count"]
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?.let { pageCount = (it + pageSize - 1) / pageSize }
        }

        // Nothing left to accumulate: a header claiming more pages cannot make
        // an empty page mean anything else, and following it would spin.
        if (body.isEmpty()) break
        val lastPage = pageCount
        if (lastPage != null) {
            if (page >= lastPage) break
        } else if (body.size < pageSize) {
            break
        }
        page += 1
    }
    return rows
}

/**
 * One paused Trakt session, flattened for the Continue Watching merge.
 *
 * [progressPercent] is Trakt's `progress` as-is (0-100): the account has no
 * access to the viewer's runtime here, so the rail converts it against TMDB's
 * runtime exactly as it does for MDBList's sessions.
 */
data class TraktPlaybackSession(
    val sessionId: Long,
    val progressPercent: Double,
    val pausedAt: String?,
    val isMovie: Boolean,
    val imdbId: String?,
    val tmdbId: Int?,
    val title: String?,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?
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

    /**
     * Serializes token refreshes.
     *
     * Trakt's refresh tokens are single-use and rotate on every exchange, so
     * two concurrent callers that both saw the expiry window open would POST
     * the same refresh token; the loser's response (or its save) then
     * overwrites the winner's fresh pair, and the grant can be revoked for
     * reuse. Every refresh goes through this lock, and the due-check is done
     * again inside it, so a caller that queued behind a completed refresh sees
     * the new expiry and returns without a second exchange.
     */
    private val refreshMutex = Mutex()

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

    /**
     * True when this profile holds a Trakt session.
     *
     * A store that cannot be opened at all answers false instead of throwing:
     * this is the first thing the connect screen asks, and a damaged prefs file
     * must show "not connected" rather than crash the page
     * ([TraktTokenStore.save] rebuilds the file on the first write).
     */
    fun hasToken(): Boolean =
        runCatching { store.accessToken() }.getOrNull() != null

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
     * The exchange itself ([TraktPollExchange]) owns the two rules that matter
     * here - a 200 is terminal, and a token that cannot be saved is its own
     * outcome rather than a retry. This method adds only the app's budget and
     * the cross-device publish.
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
        val result = TraktPollExchange(
            fetch = { code -> fetchPoll(code) },
            save = { token -> store.save(token) }
        ).pollOnce(
            deviceCode = deviceCode,
            elapsedSeconds = elapsedSeconds,
            expiresInSeconds = expiresInSeconds
        )
        // Cross-device sync: share the new session so a sibling comes up
        // already connected (see
        // [com.kennyb1201.kbstream.data.sync.TraktAuthRules]). Only a session
        // that actually reached this device is worth publishing.
        if (result.state == TraktDeviceFlowRules.Poll.APPROVED && result.saveError == null) {
            publishAuthToSync()
        }
        return result
    }

    /** The single HTTP request behind one poll, flattened for the exchange. */
    private suspend fun fetchPoll(deviceCode: String): TraktPollHttp {
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
        return TraktPollHttp(
            statusCode = response.code(),
            retryAfterSeconds = retryAfter,
            body = body,
            oauthError = oauthError
        )
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
        // Cheap pre-check outside the lock: the common case is "not due", and
        // it must not queue behind another screen's refresh.
        val first = store.read() ?: return false
        if (!TraktDeviceFlowRules.refreshDue(first.expiresAtEpochMillis, nowMs)) return true
        return refreshMutex.withLock { refreshIfDueLocked(nowMs) }
    }

    /** [refreshIfDue]'s body, run under [refreshMutex] with a re-read pair. */
    private suspend fun refreshIfDueLocked(nowMs: Long): Boolean {
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
        // A refresh ROTATES the single-use refresh token, so the account copy
        // is now stale: push the new pair or a sibling will be signed out as
        // soon as its own copy expires.
        publishAuthToSync()
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
        // Tombstone, not a bare clear: a deliberate disconnect is the only
        // thing that may end the session on another device, and a blank blob
        // is indistinguishable from "this profile never connected Trakt"
        // (see TraktAuthRules).
        store.markSignedOut()
        invalidateWatchedSnapshot()
        publishAuthToSync()
    }

    /**
     * Publishes this profile's Trakt session immediately, bypassing the bulk
     * push's "nothing to publish" skip. Connect and refresh need it so a
     * sibling adopts the new (rotated) pair without waiting for a full sync,
     * and disconnect needs it because a tombstone has no token to trigger the
     * ordinary gate.
     */
    private fun publishAuthToSync() {
        val appContext = context ?: return
        runCatching {
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_TRAKT_AUTH,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder
                    .buildTraktAuth(appContext)
            )
        }
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
     * snapshot, and both read EVERY page: Trakt pages them at 100, so page 1 is
     * not the history. Anything that goes wrong - including a failure on a page
     * past the first - returns the snapshot already in hand, never a partial or
     * empty one: beyond the cut a short list would read as "unwatched". A spent
     * budget returns the cache without touching the network, the same rule
     * [TraktRateGuard] states. The cache is keyed by the access token so a
     * profile switch (a different profile's token) can never be served the
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
            val authorization = "Bearer $token"
            val shows = readAllWatchedPages(
                pageSize = TRAKT_PAGE_SIZE,
                acquire = guard::tryAcquire,
                noteRetryAfter = ::noteRetryAfter,
                fetch = { page, limit ->
                    api.watchedShows(authorization, page = page, limit = limit)
                }
            ) ?: return@withLock previous ?: TraktWatchedSnapshot()
            val movies = readAllWatchedPages(
                pageSize = TRAKT_PAGE_SIZE,
                acquire = guard::tryAcquire,
                noteRetryAfter = ::noteRetryAfter,
                fetch = { page, limit ->
                    api.watchedMovies(authorization, page = page, limit = limit)
                }
            ) ?: return@withLock previous ?: TraktWatchedSnapshot()

            val built = TraktWatchRules.snapshot(shows = shows, movies = movies)
            cachedSnapshot = built
            cachedSnapshotAt = System.currentTimeMillis()
            cachedSnapshotKey = token
            built
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

    /**
     * Another device's session was adopted (see
     * [com.kennyb1201.kbstream.data.sync.TraktAuthRules]). The snapshot belongs
     * to the OLD account, so it must not answer the next badge read; the local
     * history and watched markers are untouched.
     */
    internal fun onSyncedSessionChanged() {
        invalidateWatchedSnapshot()
    }

    /**
     * The account's watched movie/show totals for the connect screen.
     *
     * The same two paged reads the snapshot uses, through the same helper: a
     * count of page 1 is the bug this replaces, not a cheap approximation. Any
     * failure - a non-2xx, a transport error, no token, an exhausted budget,
     * on ANY page - answers null, and the screen hides the row rather than
     * showing a total that is short.
     */
    suspend fun watchedCounts(): TraktWatchedCounts? {
        if (!TraktConfig.isConfigured) return null
        val token = validAccessToken() ?: return null
        val authorization = "Bearer $token"
        val movies = readAllWatchedPages(
            pageSize = TRAKT_PAGE_SIZE,
            acquire = guard::tryAcquire,
            noteRetryAfter = ::noteRetryAfter,
            fetch = { page, limit ->
                api.watchedMovies(authorization, page = page, limit = limit)
            }
        ) ?: return null
        val shows = readAllWatchedPages(
            pageSize = TRAKT_PAGE_SIZE,
            acquire = guard::tryAcquire,
            noteRetryAfter = ::noteRetryAfter,
            fetch = { page, limit ->
                api.watchedShows(authorization, page = page, limit = limit)
            }
        ) ?: return null
        return traktWatchedCounts(movies = movies, shows = shows)
    }

    /**
     * The account's paused sessions, flattened into resume candidates.
     *
     * Read only when the viewer has made Trakt the authoritative resume source
     * - there is no point spending a request on every Home rebuild otherwise.
     * Failures answer an empty list: a tracker outage may never empty the rail
     * (the other sources still speak).
     */
    suspend fun playbackSessions(): List<TraktPlaybackSession> {
        if (!TraktConfig.isConfigured) return emptyList()
        val token = validAccessToken() ?: return emptyList()
        val authorization = "Bearer $token"
        // PAGED. Both reads used to take `limit = 100` and no page walk, so an
        // account with more paused sessions than that silently lost the tail of
        // the resume merge. Each side is walked with the same header rule the
        // watched reads use; one side failing leaves the other's answer intact.
        val movies = readAllPlaybackRows(authorization) { page, limit ->
            api.playbackMovies(authorization, page = page, limit = limit)
        }
        val episodes = readAllPlaybackRows(authorization) { page, limit ->
            api.playbackEpisodes(authorization, page = page, limit = limit)
        }
        return buildList {
            movies.orEmpty().forEach { row ->
                row.asPlaybackSession(isMovie = true)?.let(::add)
            }
            episodes.orEmpty().forEach { row ->
                row.asPlaybackSession(isMovie = false)?.let(::add)
            }
        }
    }

    /**
     * Every paused session of one kind, page by page.
     *
     * Answers null on a failed page, exactly like [readAllWatchedPages]: a
     * truncated resume list is worse than none, because the rail would look
     * authoritative while missing sessions. The page count comes from Trakt's
     * own `X-Pagination-Page-Count` when it sends one, and a short page ends
     * the walk when it does not.
     */
    private suspend fun readAllPlaybackRows(
        authorization: String,
        fetch: suspend (page: Int, limit: Int) -> Response<List<TraktPlaybackItem>>
    ): List<TraktPlaybackItem>? {
        val rows = mutableListOf<TraktPlaybackItem>()
        var page = 1
        while (true) {
            if (!guard.tryAcquire()) return null
            val response = runCatchingCancellable {
                fetch(page, TRAKT_PAGE_SIZE)
            }.getOrNull() ?: return null
            noteRetryAfter(response)
            if (!response.isSuccessful) return null
            val body = response.body().orEmpty()
            rows += body
            val pageCount = response.headers()["X-Pagination-Page-Count"]
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
            if (body.isEmpty()) return rows
            if (pageCount != null && page >= pageCount) return rows
            if (body.size < TRAKT_PAGE_SIZE) return rows
            page += 1
        }
    }

    /**
     * Drops one of this account's paused sessions, so "Remove from Continue
     * Watching" on a Trakt card does not resurface on the next merge.
     */
    suspend fun removePlaybackSession(id: Long): Boolean {
        if (id <= 0L || !TraktConfig.isConfigured) return false
        val token = validAccessToken() ?: return false
        if (!guard.tryAcquire()) return false
        return runCatchingCancellable {
            api.deletePlaybackSession("Bearer $token", id)
        }.getOrNull()?.isSuccessful == true
    }

    private fun TraktPlaybackItem.asPlaybackSession(isMovie: Boolean): TraktPlaybackSession? {
        if (progress == null) return null
        val media = (if (isMovie) movie else show) ?: return null
        return TraktPlaybackSession(
            sessionId = id ?: 0L,
            progressPercent = progress.coerceIn(0.0, 100.0),
            pausedAt = pausedAt,
            isMovie = isMovie,
            imdbId = media.ids?.imdb,
            tmdbId = media.ids?.tmdb,
            title = media.title,
            year = media.year,
            season = if (isMovie) null else episode?.season,
            episode = if (isMovie) null else episode?.number,
            episodeTitle = if (isMovie) null else episode?.title
        )
    }

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
