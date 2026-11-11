package com.kennyb1201.kbstream.data.trakt

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.library.LibraryListRename
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.kennyb1201.kbstream.data.publiclists.PublicListCard
import com.kennyb1201.kbstream.data.publiclists.PublicListDirectory
import com.kennyb1201.kbstream.data.publiclists.PublicListRules
import com.kennyb1201.kbstream.data.publiclists.PublicListSaveOutcome
import com.kennyb1201.kbstream.data.publiclists.PublicListTitle
import com.kennyb1201.kbstream.data.publiclists.PublicListVendor
import com.kennyb1201.kbstream.data.publiclists.PublicListsAnswer
import com.kennyb1201.kbstream.data.reporting.NetworkTraceInterceptor
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
/**
 * What a disconnect actually achieved.
 *
 * Three outcomes, because the screen used to print one message for all of them:
 * the grant may have been revoked on Trakt's side ([REVOKED]), the local session
 * may have been dropped without it - the rate guard or an unreachable Trakt
 * ([SIGNED_OUT_ONLY]) - or the session may still be ON DISK because the
 * encrypted store could not be written ([NOT_CLEARED]). A screen that says
 * "Disconnected" for that last one is a screen that lies and then shows
 * Connected again on the next entry.
 */
enum class TraktDisconnectOutcome {
    REVOKED,
    SIGNED_OUT_ONLY,
    NOT_CLEARED
}

class TraktRepository private constructor(private val context: Context?) {

    /**
     * This profile's queued stop scrobbles (F2). Profile-scoped by its own
     * prefs file, because a stop belongs to the session it was scrobbled
     * through and two profiles sharing a device can watch the same title.
     */
    private val pendingScrobbles: TraktPendingScrobbles by lazy {
        TraktPendingScrobbles(context)
    }

    companion object {
        private const val PREFS_TAG = "TraktRepository"

        // The watched snapshot is account-wide and read by badges and Continue
        // Watching alike; 15 minutes is the same cadence the watched
        // repository already uses for the other trackers' sets, so the three
        // do not make the screen wait on three different refresh schedules.
        private const val SNAPSHOT_TTL_MS = 15L * 60L * 1_000L

        /**
         * How long the paused-session list stays fresh (m22). Shorter than the
         * watched snapshot's 15 minutes: resume positions move as the viewer
         * watches, and a stale one is a resume that starts in the wrong place.
         */
        private const val PLAYBACK_SESSIONS_TTL_MS = 5L * 60L * 1_000L

        /** The review rail is a handful of the best comments, not a feed. */
        private const val MAX_COMMENTS = 20

        /** One Trakt read page (Trakt's own maximum page size). */
        private const val TRAKT_PAGE_SIZE = 100

        /**
         * How long a public directory stays fresh.
         *
         * Long on purpose: these are other people's lists, ordered by long-term
         * (popular) or weekly (trending) activity, so a 30-minute-old directory
         * is not meaningfully stale - and every re-read is a call the rate guard
         * pays for.
         */
        private const val PUBLIC_LISTS_TTL_MS = 30 * 60 * 1000L

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

    // The paused-session list's own cache (m22), keyed by access token so a
    // profile switch cannot be answered from another account's read.
    @Volatile
    private var playbackSessionsCache: List<TraktPlaybackSession>? = null
    @Volatile
    private var playbackSessionsCachedFor: String? = null
    @Volatile
    private var playbackSessionsCachedAtMs = 0L

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
     *
     * Returns what it managed to do, because the two halves fail independently:
     * the revoke is skipped whenever the rate guard is spent or the build has no
     * client id, and [TraktTokenStore.markSignedOut] throws when the encrypted
     * store will not open - in which case the session is still on disk and the
     * caller must NOT report a disconnect (see [TraktDisconnectOutcome]).
     */
    suspend fun revokeAndClear(): TraktDisconnectOutcome {
        val token = store.read()
        val revocable = token?.accessToken ?: token?.refreshToken
        var revoked = false
        if (revocable != null && TraktConfig.isConfigured && guard.tryAcquire()) {
            // Best-effort by design: an unreachable Trakt must not stop the
            // local sign-out, it only means the grant outlives it.
            revoked = runCatchingCancellable {
                api.revokeToken(
                    authorization = "Bearer $revocable",
                    body = TraktRevokeRequest(
                        token = revocable,
                        clientId = TraktConfig.clientId
                    )
                )
            }.isSuccess
        }
        // Tombstone, not a bare clear: a deliberate disconnect is the only
        // thing that may end the session on another device, and a blank blob
        // is indistinguishable from "this profile never connected Trakt"
        // (see TraktAuthRules).
        //
        // Nothing is published when this does not take: a tombstone for a
        // session that survived would sign the account out on every OTHER
        // device while this one stayed connected.
        val cleared = runCatchingCancellable { store.markSignedOut() }.getOrDefault(false)
        if (!cleared) return TraktDisconnectOutcome.NOT_CLEARED
        invalidateWatchedSnapshot()
        publishAuthToSync()
        return if (revoked) {
            TraktDisconnectOutcome.REVOKED
        } else {
            TraktDisconnectOutcome.SIGNED_OUT_ONLY
        }
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

        val ids = idsFor(parentId, tmdbId)
        if (ids.imdb == null && ids.tmdb == null) return false
        val percent = TraktScrobbleRules.progressPercent(progress)
        val body = scrobbleBody(parentType, ids, season, episode, percent) ?: return false

        // The entry a failure would queue, built before the call so the queued
        // copy is the body that was actually attempted (F2).
        val failed = PendingScrobble(
            action = action,
            parentId = parentId,
            parentType = parentType,
            season = season,
            episode = episode,
            progress = percent,
            tmdbId = tmdbId,
            attemptedAtMs = System.currentTimeMillis()
        )

        if (!guard.tryAcquire()) {
            // A spent budget is a failed write, not a missing one: the play
            // still happened, so its stop is queued like any other refusal.
            queueFailedStop(failed)
            return false
        }

        val response = runCatchingCancellable {
            api.scrobble(action = action, authorization = "Bearer $token", body = body)
        }.getOrNull()
        if (response == null) {
            // Transport failure - the airplane-mode case.
            queueFailedStop(failed)
            return false
        }
        noteRetryAfter(response)
        if (!response.isSuccessful) {
            if (TraktScrobbleQueueRules.isRetryable(response.code())) queueFailedStop(failed)
            return false
        }
        // A stop at Trakt's own threshold records the title as watched, so the
        // snapshot the badges read is now stale. A start/pause changes nothing
        // in the watched history and is not worth a refetch.
        if (action == "stop") invalidateWatchedSnapshot()
        // This call just proved the guard is not blocked and the network is up,
        // which is exactly the next window a queued stop was waiting for.
        flushPendingScrobbles()
        return true
    }

    /**
     * The request body for one scrobble, or null when the arguments cannot make
     * one (a series without its season/episode).
     *
     * Shared by [scrobble] and [flushPendingScrobbles] so a replayed stop is
     * built by the same code as a live one - a queued entry must not be able to
     * drift into a differently shaped request.
     */
    private fun scrobbleBody(
        parentType: String,
        ids: TraktIds,
        season: Int?,
        episode: Int?,
        percent: Int
    ): TraktScrobbleRequest? {
        if (TraktScrobbleRules.isMovie(parentType)) {
            return TraktScrobbleRequest(
                movie = TraktMediaEntry(ids = ids),
                progress = percent
            )
        }
        val showSeason = season ?: return null
        val showEpisode = episode ?: return null
        return TraktScrobbleRequest(
            show = TraktMediaEntry(ids = ids),
            episode = TraktEpisodeNumber(showSeason, showEpisode),
            progress = percent
        )
    }

    /**
     * Queues a STOP that did not reach Trakt (F2).
     *
     * Only a stop is durable: a start/pause failure costs nothing, because the
     * play continues and the next event retries it - the shape Simkl's scrobble
     * leg already relies on. A stop is the LAST event of a play, so a lost one
     * is a play Trakt never records, with no later event to save it.
     *
     * The write is uncancellable and failure-proof: the player's own scope can
     * end the moment the play does, and a tracker's queue must never be able to
     * change what playback does or throw into it. A store that cannot write is
     * logged, not propagated - the failure it represents is the one we already
     * had.
     */
    private suspend fun queueFailedStop(entry: PendingScrobble) {
        if (!TraktScrobbleQueueRules.isStop(entry.action)) return
        withContext(NonCancellable) {
            runCatching { pendingScrobbles.record(entry) }
                .onFailure { Log.w(PREFS_TAG, "could not queue stop scrobble: ${it.message}") }
        }
    }

    /**
     * Retries the queued stop scrobbles, and returns how many Trakt accepted.
     *
     * The trigger is "the next Trakt window this device gets" - deliberately not
     * a timer or a worker (F2): a queued stop is retried from the successful
     * call that proves the guard is unblocked and the network is up, and
     * [watchedSnapshot] runs that check on every Home/Library build, so a device
     * that comes back from airplane mode flushes on the first screen that asks
     * Trakt anything. Entries are dropped after
     * [TraktScrobbleQueueRules.MAX_AGE_MS] - never retried forever - and a 429
     * on a replay keeps its entry (the guard has just re-parked) instead of
     * discarding the play.
     */
    internal suspend fun flushPendingScrobbles(): Int {
        if (!TraktConfig.isConfigured) return 0
        val pending = runCatching { pendingScrobbles.read() }.getOrNull().orEmpty()
        if (pending.isEmpty()) return 0

        val now = System.currentTimeMillis()
        var delivered = 0

        for (entry in pending) {
            if (TraktScrobbleQueueRules.isExpired(entry, now)) {
                Log.i(PREFS_TAG, "dropping stale scrobble: ${entry.playKey}")
                pendingScrobbles.remove(entry.playKey)
                continue
            }
            // Still parked: every later entry would be refused the same way.
            if (guard.isBlocked()) return delivered

            val token = validAccessToken() ?: return delivered
            val ids = idsFor(entry.parentId, entry.tmdbId)
            val body = scrobbleBody(
                parentType = entry.parentType,
                ids = ids,
                season = entry.season,
                episode = entry.episode,
                percent = entry.progress
            )
            if ((ids.imdb == null && ids.tmdb == null) || body == null) {
                // Unaddressable now: nothing can ever make this replay work.
                pendingScrobbles.remove(entry.playKey)
                continue
            }
            if (!guard.tryAcquire()) return delivered

            val response = runCatchingCancellable {
                api.scrobble(
                    action = entry.action,
                    authorization = "Bearer $token",
                    body = body
                )
            }.getOrNull()
            if (response == null) continue
            noteRetryAfter(response)

            when {
                response.isSuccessful -> {
                    pendingScrobbles.remove(entry.playKey)
                    invalidateWatchedSnapshot()
                    delivered++
                }
                // The guard is parked again by noteRetryAfter above; the entry
                // stays for the window after that one.
                response.code() == 429 -> return delivered
                // Trakt is down or the transport blinked: keep it.
                TraktScrobbleQueueRules.isRetryable(response.code()) -> continue
                // Trakt answered "no" to this body: replaying cannot help.
                else -> pendingScrobbles.remove(entry.playKey)
            }
        }

        if (delivered > 0) Log.i(PREFS_TAG, "replayed $delivered queued scrobble(s)")
        return delivered
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

        /*
         * The next allowed window for anything queued when Trakt was
         * unreachable (F2). Every screen that shows badges asks this, so a
         * device back from airplane mode flushes its missed stops on the first
         * Home/Library build - and the read below then already reflects them,
         * because a delivered stop invalidates the memo before it is filled.
         * A no-op when the queue is empty (one prefs read), and it returns early
         * while the rate guard is still parked.
         */
        flushPendingScrobbles()

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
        // m22: the paused-session list answers from the account too, so a
        // session the account changed must not outlive its own cache.
        invalidatePlaybackSessions()
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
     * Read whenever Trakt is configured. The resume authority is a
     * winner-picker, not a feed switch: it decides which card wins a title the
     * sources all know (see `ResumeAuthorityRules`), while the cards of every
     * other configured source stay in the merge as the fallback - so gating this
     * read on the authority left a Trakt-only show with no card at all under the
     * default LOCAL setting. Failures answer an empty list: a tracker outage may
     * never empty the rail (the other sources still speak).
     */
    suspend fun playbackSessions(): List<TraktPlaybackSession> {
        if (!TraktConfig.isConfigured) return emptyList()
        val token = validAccessToken() ?: return emptyList()
        // m22: the answer is cached for a few minutes, keyed by the access
        // token. Home resumes re-fire this read, and it was two full page walks
        // of /sync/playback with no cache, disk fallback or snapshot - unlike
        // every sibling leg (MDBList 15 min, Simkl 3 min, Trakt's own watched
        // snapshot 15 min). Each page spends a rate-guard token, and the guard's
        // 900/5 min sits just under Trakt's 1,000 ceiling, so Home<->Detail
        // navigation spent the shared budget on identical answers. The key is the
        // token because a profile switch is a different token, and a shorter TTL
        // than the watched snapshot's because resume positions move.
        val now = System.currentTimeMillis()
        playbackSessionsCache?.let { cached ->
            if (playbackSessionsCachedFor == token &&
                now - playbackSessionsCachedAtMs < PLAYBACK_SESSIONS_TTL_MS
            ) {
                return cached
            }
        }
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
        val answer = buildList {
            movies.orEmpty().forEach { row ->
                row.asPlaybackSession(isMovie = true)?.let(::add)
            }
            episodes.orEmpty().forEach { row ->
                row.asPlaybackSession(isMovie = false)?.let(::add)
            }
        }
        // Only a complete read is cached: a failed page answers what it had,
        // and storing that would freeze a truncated resume list for the TTL.
        if (movies != null && episodes != null) {
            playbackSessionsCache = answer
            playbackSessionsCachedFor = token
            playbackSessionsCachedAtMs = System.currentTimeMillis()
        }
        return answer
    }

    /**
     * Drops the cached paused-session list (m22).
     *
     * Called when this device's own playback state changes, so a scrobble is
     * reflected on the next Home pass instead of after the TTL.
     */
    fun invalidatePlaybackSessions() {
        playbackSessionsCache = null
        playbackSessionsCachedFor = null
        playbackSessionsCachedAtMs = 0L
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
        // The two halves are read separately, so they arrive as "all films, then
        // all shows" - not the order the list's owner arranged. Rank is the
        // field that holds that order; entries without one keep their relative
        // position after the ranked ones (sortedWith is stable) instead of being
        // dropped.
        return (movies + shows).sortedWith(
            compareBy<TraktListEntry> { it.rank == null }.thenBy { it.rank ?: 0 }
        )
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
                entries += TraktListEntry(media = media, isMovie = isMovie, rank = row.rank)
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

    // ------------------------------------------------------------------
    // Public list browsing (Settings → Trakt → Browse public lists)
    //
    // The browse half only. The list ROWS these produce become Library's
    // `TRAKT_LIST` rows (see LibraryViewModel), which is how a saved list
    // reaches Home - by the viewer pinning its Library row, exactly like a list
    // they made themselves. No Home rail is created here.
    // ------------------------------------------------------------------

    /** One directory's last read, for the screen to paint while it refetches. */
    private data class CachedPublicLists(
        val cards: List<PublicListCard>,
        val atMs: Long
    )

    /**
     * Public directories, keyed by [PublicListDirectory.wire].
     *
     * In memory, not on disk: these are other people's lists, they change
     * slowly, and a directory is worthless to a viewer who has signed out - so
     * the cache lives exactly as long as the process that can use it.
     */
    private val publicListCache =
        java.util.concurrent.ConcurrentHashMap<String, CachedPublicLists>()

    /**
     * The lists this account has liked, as far as this process knows.
     *
     * The save leg's "Already in your lists" answer without a read per save:
     * filled by [likedPublicLists] and by a successful like, emptied by an
     * unlike. It is a shortcut, never the authority - Trakt's 409 is still
     * handled as "already liked" for the case where this set is behind.
     */
    private val likedListRefs: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * A public directory as this process last read it.
     *
     * @param allowStale true for the paint-before-refetch the screen does; false
     *   (the default) for the read short-circuit, which only a still-fresh entry
     *   may answer.
     */
    fun cachedPublicLists(
        directory: PublicListDirectory,
        allowStale: Boolean = false
    ): List<PublicListCard>? {
        val entry = publicListCache[directory.wire] ?: return null
        if (!allowStale && System.currentTimeMillis() - entry.atMs > PUBLIC_LISTS_TTL_MS) {
            return null
        }
        return entry.cards
    }

    /**
     * One directory of public lists.
     *
     * Stale-while-revalidate: a fresh entry answers without a request, an older
     * one is still on screen (the ViewModel paints it) while this reads again,
     * and a failed read keeps it rather than blanking the tab - which is why a
     * failure here is an answer, not an empty list.
     */
    suspend fun publicLists(
        directory: PublicListDirectory,
        force: Boolean = false
    ): PublicListsAnswer<List<PublicListCard>> {
        if (!TraktConfig.isConfigured || !hasToken()) return PublicListsAnswer.NotConnected
        if (!force) cachedPublicLists(directory)?.let { return PublicListsAnswer.Loaded(it) }
        val token = validAccessToken() ?: return PublicListsAnswer.NotConnected

        val answer: PublicListsAnswer<List<Pair<TraktPublicList, Int?>>> = when (directory) {
            PublicListDirectory.TRAKT_POPULAR -> readPublicPages { page ->
                api.popularLists("Bearer $token", page = page)
            }.mapRows { row -> row.list?.let { list -> list to row.likeCount } }

            PublicListDirectory.TRAKT_TRENDING -> readPublicPages { page ->
                api.trendingLists("Bearer $token", page = page)
            }.mapRows { row -> row.list?.let { list -> list to row.likeCount } }

            // A vendor mismatch is a wiring mistake, not a network answer: say
            // so instead of pretending the directory was empty.
            else -> PublicListsAnswer.Failed("vendor")
        }

        val cards = answer.toCards()
        (cards as? PublicListsAnswer.Loaded)?.let { loaded ->
            publicListCache[directory.wire] =
                CachedPublicLists(loaded.value, System.currentTimeMillis())
        }
        return cards
    }

    /**
     * A text search over public list NAMES.
     *
     * Never cached: a new query is a new question, and the screen's own session
     * is the only lifetime a result set has (spec F3).
     */
    suspend fun searchPublicLists(query: String): PublicListsAnswer<List<PublicListCard>> {
        if (!TraktConfig.isConfigured || !hasToken()) return PublicListsAnswer.NotConnected
        val trimmed = query.trim()
        if (!PublicListRules.searchEnabled(trimmed)) return PublicListsAnswer.Loaded(emptyList())
        val token = validAccessToken() ?: return PublicListsAnswer.NotConnected
        return readPublicPages { page ->
            api.searchByType("Bearer $token", "list", trimmed, page = page)
        }.mapRows { row -> row.list?.let { list -> list to null } }
            .toCards()
    }

    /**
     * The lists this account has liked - the save leg's read, and what the
     * Library rail draws beside the account's own lists.
     */
    suspend fun likedPublicLists(): PublicListsAnswer<List<PublicListCard>> {
        if (!TraktConfig.isConfigured || !hasToken()) return PublicListsAnswer.NotConnected
        val token = validAccessToken() ?: return PublicListsAnswer.NotConnected
        val answer = readPublicPages { page ->
            api.likedLists("Bearer $token", page = page)
        }.mapRows { row -> row.list?.let { list -> list to null } }
            .toCards()
        return when (answer) {
            is PublicListsAnswer.Loaded -> {
                // `liked = true` because this read IS the liked set: Library uses
                // it to route a row's delete to an unlike rather than to Trakt's
                // hard list delete.
                val cards = answer.value.map { it.copy(liked = true) }
                likedListRefs += cards.map { it.ref }
                PublicListsAnswer.Loaded(cards)
            }

            is PublicListsAnswer.Failed -> PublicListsAnswer.Failed(answer.reason)
            PublicListsAnswer.NotConnected -> PublicListsAnswer.NotConnected
            is PublicListsAnswer.Blocked -> PublicListsAnswer.Blocked(answer.reason)
        }
    }

    /**
     * The rows' cards, or the answer that stopped them.
     *
     * The three reads above hand back (list, row-like-count) pairs because the
     * row shapes differ while the list does not; this is the one place that pair
     * becomes the surface's card, so a failure keeps its own kind (a spent
     * allowance is not a network error) instead of collapsing into an empty list.
     */
    private fun PublicListsAnswer<List<Pair<TraktPublicList, Int?>>>.toCards():
        PublicListsAnswer<List<PublicListCard>> = when (this) {
        is PublicListsAnswer.Loaded ->
            PublicListsAnswer.Loaded(
                value.mapNotNull { (list, likes) -> list.toPublicCard(likes) }
            )

        is PublicListsAnswer.Failed -> PublicListsAnswer.Failed(reason)
        PublicListsAnswer.NotConnected -> PublicListsAnswer.NotConnected
        is PublicListsAnswer.Blocked -> PublicListsAnswer.Blocked(reason)
    }

    /**
     * Saves a public list by LIKING it (spec F4's primary leg, confirmed by
     * Trakt's API definition: `POST /lists/{id}/like`, 204, one like per list
     * per user).
     *
     * A like keeps the list LIVE: later edits its owner makes are read the next
     * time Library refreshes, because Library reads the liked list rather than a
     * snapshot of it.
     */
    suspend fun likePublicList(card: PublicListCard): PublicListSaveOutcome {
        if (!TraktConfig.isConfigured || !hasToken()) return PublicListSaveOutcome.NotConnected
        val ref = card.ref.trim()
        if (ref.isEmpty()) return PublicListSaveOutcome.Failed("empty")
        if (likedListRefs.contains(ref)) return PublicListSaveOutcome.AlreadySaved
        val token = validAccessToken() ?: return PublicListSaveOutcome.NotConnected
        if (!guard.tryAcquire()) return PublicListSaveOutcome.Failed("429")
        val response = runCatchingCancellable {
            api.likeList("Bearer $token", ref)
        }.getOrNull() ?: return PublicListSaveOutcome.Failed("NETWORK")
        noteRetryAfter(response)
        if (!response.isSuccessful) {
            // 409 is Trakt's "it is already liked" - the same answer the local
            // set gives, from the side that cannot be out of date.
            if (response.code() == 409) {
                likedListRefs += ref
                dropPublicListCaches()
                return PublicListSaveOutcome.AlreadySaved
            }
            return PublicListSaveOutcome.Failed(response.code().toString())
        }
        likedListRefs += ref
        dropPublicListCaches()
        return PublicListSaveOutcome.Saved
    }

    /**
     * Removes a like - the Library rail's delete for a liked list (spec F4's
     * "unliking from Library removes the row").
     */
    suspend fun unlikePublicList(card: PublicListCard): Boolean {
        if (!TraktConfig.isConfigured || !hasToken()) return false
        val ref = card.ref.trim()
        if (ref.isEmpty()) return false
        val token = validAccessToken() ?: return false
        if (!guard.tryAcquire()) return false
        val response = runCatchingCancellable {
            api.unlikeList("Bearer $token", ref)
        }.getOrNull() ?: return false
        noteRetryAfter(response)
        if (!response.isSuccessful) return false
        likedListRefs -= ref
        dropPublicListCaches()
        return true
    }

    /**
     * One public list's titles, readable BEFORE it is saved - the items endpoint
     * takes a list's numeric id, which every public list carries
     * ([PublicListCard.ref] is exactly that), so there is no import-first hole.
     */
    suspend fun publicListTitles(card: PublicListCard): PublicListsAnswer<List<PublicListTitle>> {
        if (!TraktConfig.isConfigured || !hasToken()) return PublicListsAnswer.NotConnected
        val token = validAccessToken() ?: return PublicListsAnswer.NotConnected
        val movies = readPublicListHalf(token, card.ref, "movies")
        val shows = readPublicListHalf(token, card.ref, "shows")
        // Both halves failed: that is a failure, not "the list is empty".
        if (!movies.ok && !shows.ok) {
            val code = movies.code ?: shows.code ?: "NETWORK"
            return PublicListsAnswer.Failed(code)
        }
        // Rank is the owner's order; the two halves arrive as "all films, then
        // all shows", so it is what puts them back in one list (the same merge
        // [listItems] does).
        val entries = (movies.rows + shows.rows).sortedWith(
            compareBy<TraktListEntry> { it.rank == null }.thenBy { it.rank ?: 0 }
        )
        return PublicListsAnswer.Loaded(entries.map { it.toPublicTitle() })
    }

    /** Drops the directory and liked-list caches after a write changed them. */
    private fun dropPublicListCaches() {
        publicListCache.clear()
    }

    private data class TraktHalfRead(
        val rows: List<TraktListEntry>,
        val ok: Boolean,
        val code: String? = null
    )

    private suspend fun readPublicListHalf(
        token: String,
        listRef: String,
        type: String
    ): TraktHalfRead {
        val entries = mutableListOf<TraktListEntry>()
        val seen = HashSet<String>()
        var page = 1
        var pageCount = 1
        while (page <= pageCount) {
            if (!guard.tryAcquire()) return TraktHalfRead(entries, entries.isNotEmpty(), "429")
            val response = runCatchingCancellable {
                api.listItemsById(listId = listRef, type = type, page = page)
            }.getOrNull() ?: return TraktHalfRead(entries, entries.isNotEmpty(), "NETWORK")
            noteRetryAfter(response)
            if (!response.isSuccessful) {
                return TraktHalfRead(entries, entries.isNotEmpty(), response.code().toString())
            }
            val rows = response.body().orEmpty()
            var added = 0
            rows.forEach { row ->
                val media = row.movie ?: row.show ?: return@forEach
                val isMovie = row.movie != null || row.type == "movie"
                if (!seen.add(mediaKey(isMovie, media))) return@forEach
                entries += TraktListEntry(media = media, isMovie = isMovie, rank = row.rank)
                added += 1
            }
            pageCount = response.headers()["X-Pagination-Page-Count"]
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: if (rows.size >= TRAKT_PAGE_SIZE) page + 1 else page
            if (rows.isEmpty() || added == 0) break
            page += 1
        }
        return TraktHalfRead(entries, ok = true)
    }

    /**
     * One bounded walk over a public-list read.
     *
     * [PublicListRules.MAX_PAGES] caps it: a directory is a browse surface, not
     * an archive, and a server that ignored `page` would otherwise loop. A page
     * that fails after earlier ones answered keeps them ("a shorter directory
     * beats a blank one"); a first-page failure is the failure.
     */
    private suspend fun <T> readPublicPages(
        call: suspend (page: Int) -> Response<List<T>>
    ): PublicListsAnswer<List<T>> {
        val out = mutableListOf<T>()
        var page = 1
        var pageCount = 1
        while (page <= pageCount && page <= PublicListRules.MAX_PAGES) {
            if (!guard.tryAcquire()) {
                return if (out.isEmpty()) {
                    PublicListsAnswer.Failed("429")
                } else {
                    PublicListsAnswer.Loaded(out)
                }
            }
            val response = runCatchingCancellable { call(page) }.getOrNull()
                ?: return if (out.isEmpty()) {
                    PublicListsAnswer.Failed("NETWORK")
                } else {
                    PublicListsAnswer.Loaded(out)
                }
            noteRetryAfter(response)
            if (!response.isSuccessful) {
                return if (out.isEmpty()) {
                    PublicListsAnswer.Failed(response.code().toString())
                } else {
                    PublicListsAnswer.Loaded(out)
                }
            }
            val rows = response.body().orEmpty()
            out += rows
            pageCount = response.headers()["X-Pagination-Page-Count"]
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: if (rows.size >= PublicListRules.PAGE_SIZE) page + 1 else page
            if (rows.isEmpty()) break
            page += 1
        }
        return PublicListsAnswer.Loaded(out)
    }

    /**
     * Turns a discovery/search/liked page into (list, rowLikeCount) pairs.
     *
     * The row shapes differ while the list does not, so the mapping is a
     * parameter and the walk is shared - which is the whole reason the three
     * reads above are three lambda arguments instead of three copies of a paged
     * read.
     */
    private fun <T> PublicListsAnswer<List<T>>.mapRows(
        extract: (T) -> Pair<TraktPublicList, Int?>?
    ): PublicListsAnswer<List<Pair<TraktPublicList, Int?>>> = when (this) {
        is PublicListsAnswer.Loaded ->
            PublicListsAnswer.Loaded(value.mapNotNull(extract))

        is PublicListsAnswer.Failed -> this
        PublicListsAnswer.NotConnected -> PublicListsAnswer.NotConnected
        is PublicListsAnswer.Blocked -> this
    }

    /**
     * One wire list as the card the surface draws.
     *
     * The card's ref is the list's NUMERIC Trakt id and nothing else. Both calls
     * that use it are `/lists/{id}` - the items read and the like write - and the
     * docs describe that segment as "Trakt ID ... Example: 55", with "You must use
     * an integer id" on the sibling list read. A slug there is a different
     * namespace and answers 404 on both halves, so it is never the address even
     * when the wire carries one.
     *
     * A list with no numeric id is dropped: every later call (like, titles) is
     * addressed by it, so a card that cannot be opened is worse than no card -
     * the same rule [userLists] applies to the Library rail.
     */
    private fun TraktPublicList.toPublicCard(rowLikes: Int?): PublicListCard? {
        val displayName = name?.takeIf { it.isNotBlank() } ?: return null
        val ref = ids?.trakt?.toString() ?: return null
        return PublicListCard(
            vendor = PublicListVendor.TRAKT,
            ref = ref,
            name = displayName,
            owner = user?.username?.takeIf { it.isNotBlank() }
                ?: user?.name?.takeIf { it.isNotBlank() },
            description = description?.takeIf { it.isNotBlank() },
            itemCount = itemCount ?: 0,
            likes = likes ?: rowLikes
        )
    }

    private fun TraktListEntry.toPublicTitle(): PublicListTitle = PublicListTitle(
        tmdbId = media.ids?.tmdb?.takeIf { it > 0 },
        imdbId = media.ids?.imdb?.takeIf { it.startsWith("tt") },
        isMovie = isMovie,
        title = media.title?.takeIf { it.isNotBlank() },
        year = media.year?.takeIf { it > 0 }
    )

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
     * Creates a personal list on the account, private unless the viewer changes
     * it on Trakt afterwards.
     *
     * The name is the only thing the Library's create field collects, and it is
     * the only thing sent: Trakt's body requires nothing else, and a default
     * description would be a sentence the viewer never wrote. A blank name is
     * refused before a request is made - Trakt would answer it with a 422 the
     * row could only render as "failed" anyway.
     *
     * The rail is re-read by the caller after a true; the created list's id is
     * deliberately not returned, because Trakt's list ids are not what the
     * rail is keyed by (see [traktListShelfId]).
     */
    suspend fun createList(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        if (!TraktConfig.isConfigured) return false
        val token = validAccessToken() ?: return false
        if (!guard.tryAcquire()) return false
        val response = runCatchingCancellable {
            api.createList(
                "Bearer $token",
                TraktListWriteRequest(name = trimmed, privacy = "private")
            )
        }.getOrNull() ?: return false
        noteRetryAfter(response)
        return response.isSuccessful
    }

    /**
     * Renames one personal list the account owns.
     *
     * Name only: privacy, description and comment settings are absent from the
     * body, and Trakt keeps their saved values (see [TraktListWriteRequest]). A
     * list that is not the viewer's own - a public list someone else made, or a
     * list this account only follows - is refused by the server and comes back
     * here as null, which is the honest answer: nothing was renamed.
     *
     * A success carries the list's id AFTER the rename, because Trakt derives a
     * list's slug from its name: the id a Home pin was made of can move under a
     * rename, and only this answer says where it moved to (see
     * [LibraryListRename]).
     */
    suspend fun renameList(listId: String, name: String): LibraryListRename? {
        val trimmed = name.trim()
        if (listId.isBlank() || trimmed.isEmpty()) return null
        if (!TraktConfig.isConfigured) return null
        val token = validAccessToken() ?: return null
        if (!guard.tryAcquire()) return null
        val response = runCatchingCancellable {
            api.updateList("Bearer $token", listId, TraktListWriteRequest(name = trimmed))
        }.getOrNull() ?: return null
        noteRetryAfter(response)
        if (!response.isSuccessful) return null
        val slug = response.body()?.ids?.slug?.takeIf { it.isNotBlank() }
        return LibraryListRename(slug)
    }

    /**
     * Deletes one personal list the account owns - a HARD delete on Trakt.
     *
     * This is the remote verb the Library's "Delete from Trakt" names, and it
     * is why that action is spelled differently from the device-local removal:
     * there is no undo here, and the titles Trakt held are not touched (they
     * stay in this profile's Library if they were also saved locally).
     */
    suspend fun deleteList(listId: String): Boolean {
        if (listId.isBlank()) return false
        if (!TraktConfig.isConfigured) return false
        val token = validAccessToken() ?: return false
        if (!guard.tryAcquire()) return false
        val response = runCatchingCancellable {
            api.deleteList("Bearer $token", listId)
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
     *
     * Internal rather than private for one reason: the ratings writes live in
     * their own file ([TraktRatingWrites]) for the same reason Simkl's history
     * writes do, and re-parsing these id spellings there would put the app's id
     * grammar in two homes where it can drift.
     */
    internal fun idsFor(parentId: String, tmdbId: Int?): TraktIds {
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

    /**
     * A 429 parks the next call for its `Retry-After`, or a minute by default.
     *
     * Internal alongside [idsFor]: every write, ratings included, has to honor
     * the same pause, and a second copy of this rule in the ratings file would
     * be a copy that can fall out of step with the guard it feeds.
     */
    internal fun noteRetryAfter(response: Response<*>?) {
        if (response?.code() != 429) return
        val seconds = response.headers()["Retry-After"]?.toLongOrNull() ?: 60L
        guard.noteRetryAfter(seconds)
    }

}
