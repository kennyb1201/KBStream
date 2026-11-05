package com.kennyb1201.kbstream.data.bingecat

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** One issued device code, as the connect screen needs it. */
data class BingeCatDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int
)

/** Why a device-code request did not produce a code. */
data class BingeCatCodeFailure(val message: String)

/** The answer to one device-code request: a code, or the reason there is none. */
sealed interface BingeCatCodeResult {
    data class Issued(val code: BingeCatDeviceCode) : BingeCatCodeResult

    data class Failed(val failure: BingeCatCodeFailure) : BingeCatCodeResult
}

/** One poll's answer. */
data class BingeCatPollResult(
    val state: BingeCatDeviceFlowRules.Poll,
    val message: String? = null,
    val retryAfterSeconds: Long? = null,
    val saveError: String? = null
)

/**
 * Everything the Library section needs, in one read.
 *
 * [profileId] is the KBStream profile the numbers were read FOR and [mappedId]
 * the BingeCat profile they came from. Both are compared on every use, so a
 * mapping change or a profile switch can never serve the previous account's
 * rows out of this cache (spec F2's Mom trap, closed in the cache as well as on
 * the wire).
 */
data class BingeCatSnapshot(
    val watchlist: List<BingeCatRow>,
    val saved: List<BingeCatRow>,
    val lists: List<BingeCatList>,
    val watched: Set<String>,
    val refreshedAtMs: Long,
    val profileId: String?,
    val mappedId: Long
)

/**
 * One public-list discovery read's answer.
 *
 * The gates are states, not errors, exactly as they are for the reads and
 * writes: `NeedsReapproval` is a grant that never asked for `search:read` (the
 * screen turns it into a reconnect prompt), and `NotReady` is "no token, so
 * nothing was asked". Only [Failed] is a fault, and it carries the kind rather
 * than a message so the screen owns the wording.
 */
sealed interface BingeCatPublicBrowse<out T> {
    data class Loaded<T>(val value: T) : BingeCatPublicBrowse<T>

    data object NeedsReapproval : BingeCatPublicBrowse<Nothing>

    data object NotReady : BingeCatPublicBrowse<Nothing>

    data class Failed(val reason: String) : BingeCatPublicBrowse<Nothing>
}

/**
 * Whether a public list's titles can be read BEFORE it is imported (spec Q4).
 *
 * The contract has no public-list items route: the only list-items read is
 * `/lists/{ref}/items`, addressed by a TYPED ref (`internal:N`, `external:N`,
 * `tmdb:N`, `global:N`). A public list's key is not documented to be one, so
 * the screen probes that read once and says which of these two worlds it is in
 * - it never draws a title grid out of `cover_posters`, which are artwork.
 */
sealed interface BingeCatTitleProbe {
    /** Q4a: the ref resolved, so the titles are readable before import. */
    data class Titles(val rows: List<BingeCatRow>) : BingeCatTitleProbe

    /** Q4b: the ref did not resolve - titles appear after import, via Library. */
    data object NotAddressable : BingeCatTitleProbe

    /** The probe could not be answered (network, 429); the detail is still usable. */
    data class Failed(val reason: String) : BingeCatTitleProbe
}

/** One import attempt: the gates, the answer, and the first poll's wait. */
sealed interface BingeCatImportAttempt {
    /** BingeCat answered about the one list that was sent. */
    data class Answered(
        val feedback: BingeCatImportFeedback,
        /** The acceptance's own `Retry-After`, for the first job poll. */
        val retryAfterSeconds: Long
    ) : BingeCatImportAttempt

    /** The grant has no `lists:write`: re-connect, nothing was attempted. */
    data object NeedsReapproval : BingeCatImportAttempt

    /** Not connected, or no mapped profile: nothing was attempted. */
    data object NotReady : BingeCatImportAttempt

    /** A request was attempted and failed (network, 429, HTTP code, park). */
    data class Failed(val reason: String) : BingeCatImportAttempt
}

/** One job poll: a verdict, or why there is none. */
sealed interface BingeCatJobPoll {
    data class Status(
        val outcome: BingeCatJobOutcome,
        /** Seconds to wait before the next poll (the job's `Retry-After`). */
        val retryAfterSeconds: Long
    ) : BingeCatJobPoll

    /** The poll could not read the job; the caller decides whether to retry. */
    data class Failed(val reason: String) : BingeCatJobPoll
}

/**
 * BingeCat, read-only, as this app speaks to it.
 *
 * The connect grant, the proactive refresh, the disconnect, the per-profile
 * mapping, the three reads v1 ships (watchlist + saved list + lists, and history
 * for the section's own watched marker), and the two write legs: watched
 * history (step 2, `history:write`) and the watchlist/user-list saves (step 3,
 * `lists:write`), plus step 6's public-list browse and import (discovery on
 * `search:read`, the import on `lists:write`). Nothing else writes: no settings
 * call, no publishing switch, no BingeCat Stremio addon (see
 * [BingeCatConfig.SCOPES] for exactly what is asked).
 *
 * Three structural rules, each answering a specific failure:
 *
 *  - **Every data path is the explicit `/api/v1/profiles/{id}/…` form.** The
 *    root routes follow BingeCat's active profile, which is whatever the
 *    account last opened in a browser - so they are absent from
 *    [BingeCatApiService] entirely.
 *  - **No mapping, no request.** An unmapped profile returns null without
 *    touching the network, which is what keeps a kids profile from ever
 *    inheriting Profile 1's BingeCat rows.
 *  - **A failure never empties the section.** The last good snapshot survives a
 *    429, a network fault or a rejected token; the caller cannot tell a stale
 *    list from a fresh one because both are the viewer's own list.
 */
class BingeCatRepository private constructor(private val context: Context?) {

    private val TAG = "BINGECAT_REPO"

    companion object {
        /** Revalidate at most once per half hour per profile (spec F3). */
        const val CACHE_TTL_MS = 30L * 60L * 1_000L

        private const val PAGE_SIZE = 100

        /** Public-list discovery pages: the contract's own SearchLimit ceiling. */
        private const val PUBLIC_LIST_PAGE_SIZE = 50

        /** A hard ceiling on any paged walk, so a bad header cannot spin. */
        private const val MAX_PAGES = 10

        /** Refresh this long before the token actually expires. */
        private const val EXPIRY_SKEW_MS = 60_000L

        @Volatile
        private var instance: BingeCatRepository? = null

        fun getInstance(context: Context? = null): BingeCatRepository =
            instance ?: synchronized(this) {
                instance ?: BingeCatRepository(context?.applicationContext).also { instance = it }
            }
    }

    internal val store = BingeCatTokenStore(context)

    internal val mapping = BingeCatProfileMap(context)

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val service: BingeCatApiService by lazy {
        val client = BaseHttpClient.get().newBuilder()
            .addInterceptor(BingeCatHeadersInterceptor())
            .build()
        Retrofit.Builder()
            .baseUrl(BingeCatConfig.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(BingeCatApiService::class.java)
    }

    /**
     * Serializes refreshes.
     *
     * BingeCat rotates both token values on a refresh, so two callers that both
     * saw the expiry window open would POST the same refresh token; the loser's
     * save then overwrites the winner's fresh pair and the grant can be
     * revoked. The due-check is repeated inside the lock for that reason.
     */
    private val refreshMutex = Mutex()

    private val cacheMutex = Mutex()

    @Volatile
    private var cached: BingeCatSnapshot? = null

    // ── Connection state ─────────────────────────────────────────────────

    fun isConfigured(): Boolean = BingeCatConfig.isConfigured

    fun hasToken(): Boolean {
        val has = store.read() != null
        BingeCatTrace.recordSession(has)
        return has
    }

    fun accountScope(): String? = store.scope()

    /**
     * The session changed underneath this repository - it arrived from the
     * account (see [com.kennyb1201.kbstream.data.sync.BingeCatAuthRules]).
     *
     * Only the cached snapshot goes: it was read with the previous token. The
     * mapping is this profile's own choice and stays, and the viewer's library,
     * history and resume rows are not BingeCat's to touch.
     */
    fun onSyncedSessionChanged() {
        cached = null
        BingeCatTrace.recordSession(store.read() != null)
    }

    fun mappedProfileId(): Long? = mapping.mappedProfileId()?.also { id ->
        BingeCatTrace.recordMapping(id, mapping.mappedProfileName())
    }

    fun mappedProfileName(): String? = mapping.mappedProfileName()

    /**
     * True when [mediaType]/[imdbId]/[tmdbId] is on the mapped profile's
     * watchlist according to the LAST READ (spec F2's membership rule).
     *
     * False while nothing is cached, and false for a different mapping: a stale
     * account's rows must never answer for this one, the same rule the snapshot
     * itself enforces.
     */
    fun watchlistContains(mediaType: String, imdbId: String?, tmdbId: Int?): Boolean {
        val snapshot = cached ?: return false
        val mapped = mappedProfileId() ?: return false
        if (snapshot.mappedId != mapped) return false
        return snapshot.watchlist.any { row -> row.matches(mediaType, imdbId, tmdbId) }
    }

    /** True when this profile may make a data request at all. */
    fun isReady(): Boolean = isConfigured() && hasToken() && mappedProfileId() != null

    /**
     * Whether the stored grant carries `history:write` (step 2's gate 2).
     *
     * A step-1 grant - the three read scopes - never gains the write scope
     * silently: OAuth only widens on a fresh approval. The tracker therefore
     * reads this instead of attempting a write that would earn a 403, and the
     * connect screen turns it into the re-connect prompt.
     */
    fun hasWriteScope(): Boolean = BingeCatWriteRules.hasWriteScope(store.scope())

    /**
     * Whether the stored grant carries `lists:write` (step 3's gate).
     *
     * Its own gate, not a second reading of [hasWriteScope]: a step-2 grant has
     * `history:write` and not this one, and asking the wrong question would turn
     * a working tracker into a 403 (or hide a fixable state).
     */
    fun hasListsWriteScope(): Boolean =
        BingeCatWriteRules.hasListsWriteScope(store.scope())

    /** True when saving can go out: connected, mapped, and granted `lists:write`. */
    fun canSave(): Boolean = isReady() && hasListsWriteScope()

    /**
     * True while this profile is connected and mapped but the grant cannot
     * write one of the two legs: the one state that needs a re-approval, and the
     * only one the connect screen prompts for (never for unmapped or
     * disconnected).
     *
     * Either missing scope counts, because the reconnect fixes both at once: a
     * step-1 grant cannot record plays OR save, a step-2 grant cannot save, and
     * the screen must not claim the connection is fully working in either case.
     */
    fun needsReapproval(): Boolean =
        isConfigured() && hasToken() && mappedProfileId() != null &&
            (!hasWriteScope() || !hasListsWriteScope())

    // ── Device flow ──────────────────────────────────────────────────────

    /**
     * Issues a device code.
     *
     * A rejection is reported with its own reason: `invalid_scope` means the
     * developer app is not allowed v1's scopes (an app-level setting, named in
     * the message), `invalid_client` means the build's client id is wrong, and
     * anything else is the server's own error text - never a bare "failed".
     */
    suspend fun requestDeviceCode(): BingeCatCodeResult {
        if (!isConfigured()) {
            return BingeCatCodeResult.Failed(
                BingeCatCodeFailure(
                    "This build has no BingeCat client id, so BingeCat cannot be " +
                        "connected. Set BINGECAT_CLIENT_ID for the build and reinstall."
                )
            )
        }
        val response = runCatchingCancellable {
            service.requestDeviceCode(
                BingeCatDeviceCodeRequest(
                    clientId = BingeCatConfig.clientId,
                    scope = BingeCatConfig.SCOPES
                )
            )
        }.getOrNull() ?: return BingeCatCodeResult.Failed(
            BingeCatCodeFailure("Could not reach BingeCat. Check the connection and try again.")
        )
        val body = response.body()
        // Locals rather than property reads: the DTO's properties cannot be
        // smart-cast from another module's class.
        val deviceCode = body?.deviceCode?.takeIf { it.isNotBlank() }
        val userCode = body?.userCode?.takeIf { it.isNotBlank() }
        if (response.isSuccessful && body != null && deviceCode != null && userCode != null) {
            return BingeCatCodeResult.Issued(
                BingeCatDeviceCode(
                    deviceCode = deviceCode,
                    userCode = userCode,
                    verificationUrl = body.verificationUrl?.takeIf { it.isNotBlank() }
                        ?: BingeCatConfig.ACTIVATE_URL,
                    expiresInSeconds = body.expiresInSeconds.coerceAtLeast(60),
                    intervalSeconds = body.intervalSeconds.coerceAtLeast(1)
                )
            )
        }
        val error = oauthError(response)
        BingeCatTrace.recordError(
            when (error) {
                "invalid_scope" -> "invalid-scope"
                "invalid_client", "unauthorized_client" -> "401"
                else -> "http:${response.code()}"
            }
        )
        val message = when (error) {
            "invalid_scope" -> BingeCatDeviceFlowRules.scopeRejectionMessage()
            "invalid_client", "unauthorized_client" ->
                "BingeCat rejected this app's client id. Reinstall a build with a " +
                    "working client id, or check the app on BingeCat's developer page."
            else -> error?.let { "BingeCat could not issue a code ($it)." }
                ?: "BingeCat could not issue a code (HTTP ${response.code()})."
        }
        return BingeCatCodeResult.Failed(BingeCatCodeFailure(message))
    }

    /**
     * One poll of the device code, and the persistence of what it grants.
     *
     * A 200-with-token is TERMINAL: BingeCat consumes the code at exchange, so
     * re-polling it can only read `expired_token`. The token is therefore saved
     * the moment the response is in, before anything else, and a save failure
     * is its own outcome ("approved, but could not be kept") rather than a
     * retry - the same rule [com.kennyb1201.kbstream.data.trakt.TraktPollExchange]
     * states for Trakt.
     */
    suspend fun pollOnce(
        deviceCode: String,
        elapsedSeconds: Long,
        expiresInSeconds: Int,
        intervalSeconds: Int
    ): BingeCatPollResult {
        val response = runCatchingCancellable {
            service.pollDeviceToken(
                BingeCatDeviceTokenRequest(
                    code = deviceCode,
                    clientId = BingeCatConfig.clientId
                )
            )
        }.getOrNull() ?: return BingeCatPollResult(
            BingeCatDeviceFlowRules.Poll.FAILED,
            message = "Could not reach BingeCat."
        )
        val body = response.body()
        val error = body?.error ?: oauthError(response)
        val state = BingeCatDeviceFlowRules.poll(
            statusCode = response.code(),
            hasToken = !body?.accessToken.isNullOrBlank(),
            elapsedSeconds = elapsedSeconds,
            expiresInSeconds = expiresInSeconds,
            error = error
        )
        val retryAfter = response.headers()["Retry-After"]?.toLongOrNull()
        if (state != BingeCatDeviceFlowRules.Poll.APPROVED) {
            if (state == BingeCatDeviceFlowRules.Poll.SCOPE_REJECTED) {
                BingeCatTrace.recordError("invalid-scope")
            }
            return BingeCatPollResult(
                state = state,
                message = when (state) {
                    BingeCatDeviceFlowRules.Poll.SCOPE_REJECTED ->
                        BingeCatDeviceFlowRules.scopeRejectionMessage()
                    BingeCatDeviceFlowRules.Poll.EXPIRED ->
                        "That code expired. Generate a new one."
                    BingeCatDeviceFlowRules.Poll.DENIED ->
                        "BingeCat approval was declined."
                    BingeCatDeviceFlowRules.Poll.MISCONFIGURED ->
                        "BingeCat rejected this app's client id."
                    else -> null
                },
                retryAfterSeconds = retryAfter
                    ?: if (state == BingeCatDeviceFlowRules.Poll.SLOW_DOWN) {
                        BingeCatDeviceFlowRules.slowedIntervalSeconds(intervalSeconds).toLong()
                    } else {
                        null
                    }
            )
        }
        val granted = body ?: return BingeCatPollResult(
            BingeCatDeviceFlowRules.Poll.FAILED,
            message = "BingeCat approved the code but sent no token."
        )
        val token = BingeCatToken(
            accessToken = granted.accessToken.orEmpty(),
            refreshToken = granted.refreshToken,
            expiresAtEpochMillis = BingeCatDeviceFlowRules.expiresAtEpochMillis(
                createdAtEpochSeconds = granted.createdAtEpochSeconds,
                expiresInSeconds = granted.expiresInSeconds,
                nowMs = System.currentTimeMillis()
            ),
            scope = granted.scope
        )
        return try {
            store.save(token)
            BingeCatTrace.recordSession(true)
            // Cross-device sync: share the new session so a sibling comes up
            // already connected (see [com.kennyb1201.kbstream.data.sync.BingeCatAuthRules]).
            // Only a session that actually reached this device is worth
            // publishing.
            publishAuthToSync()
            BingeCatPollResult(BingeCatDeviceFlowRules.Poll.APPROVED)
        } catch (error: Exception) {
            BingeCatPollResult(
                BingeCatDeviceFlowRules.Poll.APPROVED,
                saveError = "BingeCat approved the app, but the token could not be " +
                    "saved on this device (${error.javaClass.simpleName})."
            )
        }
    }

    /** The account's profiles, for the mapping step. Null when the read failed. */
    suspend fun profiles(): List<BingeCatProfile>? {
        val body = authorizedBody { auth -> service.profiles(auth) } ?: return null
        return body.profiles.filter { (it.id ?: 0L) > 0L }
    }

    fun setMappedProfile(profile: BingeCatProfile) {
        mapping.set(profile)
        BingeCatTrace.recordMapping(profile.id, profile.name)
        cached = null
    }

    /**
     * Revokes the grant, drops the session and clears the mapping.
     *
     * Local data is not touched: the viewer's My List, watch history and resume
     * rows are theirs, and BingeCat's rows leave the Library only because they
     * are remote data with nothing left to read them from.
     *
     * A deliberate disconnect is the ONLY thing that may end the session on
     * another device, so this leaves the sync tombstone rather than a bare
     * clear - and publishes it immediately, because a tombstone has no token to
     * trigger the ordinary push gate. Nothing is published when the tombstone
     * does not take: signing the account out everywhere while this device kept
     * its session would be worse than not syncing the disconnect.
     */
    suspend fun disconnect(): Boolean {
        val token = store.read()?.accessToken
        var revoked = false
        if (!token.isNullOrBlank()) {
            val response = runCatchingCancellable {
                service.revoke(
                    BingeCatRevokeRequest(token = token, clientId = BingeCatConfig.clientId)
                )
            }.getOrNull()
            revoked = response?.isSuccessful == true
        }
        val cleared = runCatching { store.markSignedOut() }.getOrDefault(false)
        mapping.clear()
        cached = null
        BingeCatTrace.recordSession(false)
        BingeCatTrace.recordMapping(null, null)
        if (cleared) publishAuthToSync()
        return revoked
    }

    /**
     * Publishes this profile's BingeCat session immediately, bypassing the bulk
     * push's "nothing to publish" skip.
     *
     * Connect and refresh need it so a sibling adopts the new (rotated) pair
     * without waiting for a full sync, and disconnect needs it because the
     * tombstone has no token to trigger the ordinary gate.
     */
    private fun publishAuthToSync() {
        val appContext = context ?: return
        runCatching {
            SupabaseSync.enqueuePrefs(
                appContext,
                PrefsPayloadBuilder.KEY_BINGECAT_AUTH,
                PrefsPayloadBuilder.buildBingeCatAuth(appContext)
            )
        }
    }

    // ── Writes (step 2) ──────────────────────────────────────────────────
    //
    // The verbs the fan-out calls, in Simkl's spelling so a call site reads the
    // same either side of the tracker: push/removeWatched{Movie,Show,Episode,
    // Episodes,Season}. Their bodies live in BingeCatHistoryWrites.kt (one
    // action -> the field of the batch that fits it); the gate, the retry rules
    // and the cache rule live in [submitHistory] below, because they need this
    // class's token, service and snapshot.

    /** A finished movie. False only when a write was attempted and failed. */
    suspend fun pushWatchedMovie(
        imdbId: String,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean = pushWatchedMovieImpl(imdbId, title, tmdbId)

    /** A whole-show mark ("Mark as Watched" on a series). */
    suspend fun pushWatchedShow(
        showImdbId: String,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean = pushWatchedShowImpl(showImdbId, title, tmdbId)

    /** One finished episode. */
    suspend fun pushWatchedEpisode(
        showImdbId: String,
        season: Int,
        episode: Int,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean = pushWatchedEpisodeImpl(showImdbId, season, episode, title, tmdbId)

    /** The finished episodes of one file, in ONE batch (the covered-episode case). */
    suspend fun pushWatchedEpisodes(
        showImdbId: String,
        season: Int,
        episodes: List<Int>,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean = pushWatchedEpisodesImpl(showImdbId, season, episodes, title, tmdbId)

    /** A whole season (the season long-press). */
    suspend fun pushWatchedSeason(
        showImdbId: String,
        season: Int,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean = pushWatchedSeasonImpl(showImdbId, season, title, tmdbId)

    suspend fun removeWatchedMovie(imdbId: String, tmdbId: Int? = null): Boolean =
        removeWatchedMovieImpl(imdbId, tmdbId)

    suspend fun removeWatchedShow(showImdbId: String, tmdbId: Int? = null): Boolean =
        removeWatchedShowImpl(showImdbId, tmdbId)

    suspend fun removeWatchedEpisodes(
        showImdbId: String,
        season: Int,
        episodes: List<Int>,
        tmdbId: Int? = null
    ): Boolean = removeWatchedEpisodesImpl(showImdbId, season, episodes, tmdbId)

    suspend fun removeWatchedSeason(
        showImdbId: String,
        season: Int,
        tmdbId: Int? = null
    ): Boolean = removeWatchedSeasonImpl(showImdbId, season, tmdbId)

    /**
     * One history batch, with the tracker's whole failure contract.
     *
     * The gates are checked in order and a failed gate means no request at all:
     * an unmapped (or disconnected) profile is silent, and a grant without
     * `history:write` is the needs-reapproval STATE rather than an error. Both
     * answer TRUE - "nothing was attempted, so nothing failed" - the same rule
     * the MDBList leg already uses for a missing key, because a `false` here
     * would reset the player's completion latch on every tick forever over a
     * condition no retry can fix.
     *
     * The failure contract itself is the reads' one, plus a park: a 401
     * refreshes once (single flight) and drops the session on a second, a 429
     * parks the leg for its `Retry-After` instead of storming, and a 403
     * converges to needs-reapproval with no retry. Never throws: a cancelled
     * coroutine rethrows, everything else is logged and answered false.
     *
     * On success it drops ONLY this profile's BingeCat snapshot, so the next
     * Library open re-reads and badges the play that was just written. Local
     * playback sessions, Continue Watching / Up Next and the other trackers are
     * not BingeCat's to touch.
     */
    internal suspend fun submitHistory(
        add: Boolean,
        batch: BingeCatHistoryBatch,
        label: String
    ): Boolean {
        if (batch.isEmpty) return false
        val mapped = if (isReady()) mappedProfileId() else null
        if (mapped == null) {
            // Unmapped, disconnected or unconfigured: no request, no error.
            return true
        }
        if (!hasWriteScope()) {
            // A step-1 grant: the steady state until the viewer reconnects.
            BingeCatTrace.recordNeedsReapproval(true)
            Log.i(TAG, "write skipped: grant has no ${BingeCatConfig.WRITE_SCOPE} ($label)")
            return true
        }
        BingeCatTrace.recordNeedsReapproval(false)
        val parkedMs = bingeCatWriteParkMs()
        if (parkedMs > 0L) {
            BingeCatTrace.recordWriteFailure("429")
            Log.i(TAG, "write skipped: parked for ${parkedMs}ms after a 429 ($label)")
            return false
        }
        BingeCatTrace.recordWriteAttempt()
        return try {
            val token = ensureFreshToken()
            if (token == null) {
                BingeCatTrace.recordWriteFailure("no-token")
                Log.e(TAG, "write skipped: no token ($label)")
                return false
            }
            var response = send(batch, add, token, mapped)
            if (response == null) {
                BingeCatTrace.recordWriteFailure("network")
                Log.e(TAG, "write failed: network ($label)")
                return false
            }
            if (response.code() == 401) {
                val refreshed = ensureFreshToken(force = true)
                if (refreshed != null) response = send(batch, add, refreshed, mapped)
                if (response == null) {
                    BingeCatTrace.recordWriteFailure("network")
                    return false
                }
                if (response.code() == 401) {
                    // The reads' rule, kept for writes: degrade to disconnected,
                    // never to anonymous calls, so the next screen reads a real
                    // state instead of a token the server has already refused.
                    store.clear()
                    cached = null
                    BingeCatTrace.recordSession(false)
                    BingeCatTrace.recordWriteFailure("401")
                    Log.e(TAG, "write failed: 401 after refresh, session dropped ($label)")
                    return false
                }
            }
            when {
                response.isSuccessful -> {
                    // ONLY BingeCat's own cache: the play is now in the profile's
                    // history, so the next open re-reads and badges it.
                    cached = null
                    BingeCatTrace.recordWriteSuccess(writeResultLabel(response.body()))
                    Log.i(TAG, "write ok: $label ${writeResultLabel(response.body())}")
                    true
                }

                noteBingeCatWriteRateLimit(
                    response.code(),
                    response.headers()["Retry-After"]
                ) -> {
                    BingeCatTrace.recordWriteFailure("429")
                    Log.w(TAG, "write rate-limited: 429, leg parked ($label)")
                    false
                }

                response.code() == 403 -> {
                    // The grant lost the scope server-side: the same state gate
                    // 2 describes, with no retry that could change it.
                    BingeCatTrace.recordNeedsReapproval(true)
                    BingeCatTrace.recordWriteFailure("403-scope")
                    Log.w(TAG, "write refused: 403 scope, needs re-approval ($label)")
                    true
                }

                else -> {
                    val body = runCatching { response.errorBody()?.string() }.getOrNull()
                    BingeCatTrace.recordWriteFailure("http:${response.code()}")
                    Log.e(TAG, "write failed: code=${response.code()} body=$body ($label)")
                    false
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            BingeCatTrace.recordWriteFailure(error.javaClass.simpleName)
            Log.e(TAG, "write error ($label): ${error.message}", error)
            false
        }
    }

    private suspend fun send(
        batch: BingeCatHistoryBatch,
        add: Boolean,
        token: String,
        profileId: Long
    ): Response<*>? = runCatchingCancellable {
        if (add) {
            service.addHistory("Bearer $token", profileId, batch)
        } else {
            service.removeHistory("Bearer $token", profileId, batch)
        }
    }.getOrNull()

    // ── List writes (step 3) ──────────────────────────────────────────────
    //
    // The saving legs behind the Detail Library button, the Library tab's
    // remove and the picker's rows: the profile's watchlist (`sync/watchlist`
    // add + remove) and one user list's items (`/lists/{ref}/items` POST and
    // DELETE). The verbs live in BingeCatListWrites.kt; the gate, the failure
    // contract and the cache rule live here, because they need this class's
    // token, service and snapshot.

    /**
     * The Detail Library button's save leg: adds the title to the mapped
     * profile's watchlist. False only when a write was attempted and failed.
     */
    suspend fun addToWatchlist(
        isMovie: Boolean,
        imdbId: String?,
        tmdbId: Int?,
        title: String? = null
    ): Boolean = addToWatchlistImpl(isMovie, imdbId, tmdbId, title)

    /** The button's (and the Library tab's) unsave leg on the watchlist. */
    suspend fun removeFromWatchlist(
        isMovie: Boolean,
        imdbId: String?,
        tmdbId: Int?,
        title: String? = null
    ): Boolean = removeFromWatchlistImpl(isMovie, imdbId, tmdbId, title)

    /** The picker's add: one user list, addressed by its own typed `ref`. */
    suspend fun addToList(
        ref: String,
        isMovie: Boolean,
        imdbId: String?,
        tmdbId: Int?,
        title: String? = null
    ): Boolean = addToListImpl(ref, isMovie, imdbId, tmdbId, title)

    /** The picker's remove, on the same list `ref` (never a name). */
    suspend fun removeFromList(
        ref: String,
        isMovie: Boolean,
        imdbId: String?,
        tmdbId: Int?,
        title: String? = null
    ): Boolean = removeFromListImpl(ref, isMovie, imdbId, tmdbId, title)

    /** What one list write came back as, before its body is read. */
    private sealed interface ListWriteResult {
        /** A gate answered: nothing was sent, so nothing failed. */
        data object Skipped : ListWriteResult

        /** A request was accepted; [body] is the server's own report. */
        data class Accepted(val body: Any?) : ListWriteResult

        /** A request was attempted and did not land. */
        data object Failed : ListWriteResult
    }

    /**
     * The watchlist add/remove, with the tracker's whole failure contract.
     *
     * Same gates and same rules as [submitHistory] - unmapped is silent, a grant
     * without `lists:write` is the needs-reapproval STATE (answering true, since
     * nothing was attempted), 401 refreshes once, 429 parks, 403 converges, and
     * success drops only this profile's BingeCat snapshot so the next Library
     * open re-reads. The one difference is the question a success answers: an
     * add is true when the title is on the watchlist afterwards (`added` OR
     * `existing`), because a title that was already saved is not a failure - and
     * the `not_found` case is, so the toggle reports it instead of painting a
     * state the account does not have.
     */
    internal suspend fun submitSavedWrite(
        add: Boolean,
        kind: String,
        batch: BingeCatSavedBatch,
        label: String
    ): Boolean {
        if (batch.isEmpty) return false
        return when (
            val result = submitListWrite(label) { token, profileId ->
                if (add) {
                    service.addSavedItems("Bearer $token", profileId, kind, batch)
                } else {
                    service.removeSavedItems("Bearer $token", profileId, kind, batch)
                }
            }
        ) {
            ListWriteResult.Skipped -> true
            ListWriteResult.Failed -> false
            is ListWriteResult.Accepted ->
                if (add) savedAddLanded(result.body) else true
        }
    }

    /**
     * One user list's add/remove, on the same contract as [submitSavedWrite].
     *
     * An add answers true when the list holds the title afterwards; the
     * contract's `not_found` and `type_mismatch` arrays are the two ways a
     * perfectly valid request still changes nothing, and both must read as a
     * failed row rather than a silent one.
     */
    internal suspend fun submitListItemsWrite(
        add: Boolean,
        ref: String,
        items: List<String>,
        label: String
    ): Boolean {
        if (ref.isBlank() || items.isEmpty()) return false
        return when (
            val result = submitListWrite(label) { token, _ ->
                val body = BingeCatListItemsWrite(items)
                if (add) {
                    service.addListItems("Bearer $token", ref, body)
                } else {
                    service.removeListItems("Bearer $token", ref, body)
                }
            }
        ) {
            ListWriteResult.Skipped -> true
            ListWriteResult.Failed -> false
            is ListWriteResult.Accepted ->
                if (add) listItemsAddLanded(result.body) else true
        }
    }

    /**
     * Creates one MANUAL list on the mapped profile (`lists:write`).
     *
     * Same contract as the item writes: the scope gate runs before a request is
     * made, a 403 converges to needs-reapproval, a 429 parks the leg, and a
     * success drops this repository's snapshot so the Library's next read sees
     * the new list. The content type is `all` because the Library this list is
     * made from holds both movies and shows; the create field collects a name
     * and nothing else.
     */
    suspend fun createList(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val body = BingeCatListCreateRequest(name = trimmed)
        return when (
            val result = submitListWrite("list create $trimmed") { token, profileId ->
                service.createList("Bearer $token", profileId, body)
            }
        ) {
            ListWriteResult.Skipped -> false
            ListWriteResult.Failed -> false
            // 201 and 202 both mean the list exists; a 202 also carries the job
            // the AI/filter kinds would need, which a manual list never does.
            is ListWriteResult.Accepted -> true
        }
    }

    /** Renames one user list by its own `ref` (`lists:write`). */
    suspend fun renameList(ref: String, name: String): Boolean {
        val trimmed = name.trim()
        if (ref.isBlank() || trimmed.isEmpty()) return false
        val body = BingeCatListPatchRequest(name = trimmed)
        return when (
            val result = submitListWrite("list rename $ref") { token, _ ->
                service.patchList("Bearer $token", ref, body)
            }
        ) {
            ListWriteResult.Skipped -> false
            ListWriteResult.Failed -> false
            is ListWriteResult.Accepted -> listWriteLanded(result.body)
        }
    }

    /**
     * Deletes one user list by its `ref` - a soft delete on BingeCat's side.
     *
     * Soft means BingeCat keeps the list's rows for a restore; this app offers
     * no undelete, so the rail's confirm names the destination and the row goes
     * away on the next read.
     */
    suspend fun deleteList(ref: String): Boolean {
        if (ref.isBlank()) return false
        return when (
            val result = submitListWrite("list delete $ref") { token, _ ->
                service.deleteList("Bearer $token", ref)
            }
        ) {
            ListWriteResult.Skipped -> false
            ListWriteResult.Failed -> false
            is ListWriteResult.Accepted -> listWriteLanded(result.body)
        }
    }

    /**
     * A create/patch/delete answered with the list it changed; a body that is
     * not that answer is taken at the 2xx's word, the same rule the item
     * writes' landed checks follow.
     */
    private fun listWriteLanded(body: Any?): Boolean = when (body) {
        is BingeCatListResponse -> body.list != null
        else -> true
    }

    /**
     * One list-write request: the gates, the park, the refresh-once rule and the
     * cache drop, shared by both routes.
     *
     * [request] performs the call on the mapped profile's own token, so no
     * caller can address a list without pinning the profile it belongs to.
     */
    private suspend fun submitListWrite(
        label: String,
        request: suspend (token: String, profileId: Long) -> Response<*>?
    ): ListWriteResult {
        val mapped = if (isReady()) mappedProfileId() else null
        if (mapped == null) return ListWriteResult.Skipped
        if (!hasListsWriteScope()) {
            // A step-1 or step-2 grant: saving is off until the viewer reconnects.
            BingeCatTrace.recordNeedsReapproval(true)
            Log.i(TAG, "list write skipped: grant has no ${BingeCatConfig.LISTS_WRITE_SCOPE} ($label)")
            return ListWriteResult.Skipped
        }
        BingeCatTrace.recordNeedsReapproval(false)
        val parkedMs = bingeCatWriteParkMs()
        if (parkedMs > 0L) {
            BingeCatTrace.recordListWriteFailure("429")
            Log.i(TAG, "list write skipped: parked for ${parkedMs}ms after a 429 ($label)")
            return ListWriteResult.Failed
        }
        BingeCatTrace.recordListWriteAttempt()
        return try {
            val token = ensureFreshToken()
            if (token == null) {
                BingeCatTrace.recordListWriteFailure("no-token")
                Log.e(TAG, "list write skipped: no token ($label)")
                return ListWriteResult.Failed
            }
            var response = request(token, mapped)
            if (response == null) {
                BingeCatTrace.recordListWriteFailure("network")
                Log.e(TAG, "list write failed: network ($label)")
                return ListWriteResult.Failed
            }
            if (response.code() == 401) {
                val refreshed = ensureFreshToken(force = true)
                if (refreshed != null) response = request(refreshed, mapped)
                if (response == null) {
                    BingeCatTrace.recordListWriteFailure("network")
                    return ListWriteResult.Failed
                }
                if (response.code() == 401) {
                    store.clear()
                    cached = null
                    BingeCatTrace.recordSession(false)
                    BingeCatTrace.recordListWriteFailure("401")
                    Log.e(TAG, "list write failed: 401 after refresh, session dropped ($label)")
                    return ListWriteResult.Failed
                }
            }
            when {
                response.isSuccessful -> {
                    // ONLY BingeCat's own snapshot: the next Library open re-reads
                    // the watchlist and lists, which is what makes the toggle,
                    // the suffix and the picker ticks reflect this write.
                    cached = null
                    BingeCatTrace.recordListWriteSuccess(listWriteResultLabel(response.body()))
                    Log.i(TAG, "list write ok: $label ${listWriteResultLabel(response.body())}")
                    ListWriteResult.Accepted(response.body())
                }

                noteBingeCatWriteRateLimit(
                    response.code(),
                    response.headers()["Retry-After"]
                ) -> {
                    BingeCatTrace.recordListWriteFailure("429")
                    Log.w(TAG, "list write rate-limited: 429, leg parked ($label)")
                    ListWriteResult.Failed
                }

                response.code() == 403 -> {
                    BingeCatTrace.recordNeedsReapproval(true)
                    BingeCatTrace.recordListWriteFailure("403-scope")
                    Log.w(TAG, "list write refused: 403 scope, needs re-approval ($label)")
                    ListWriteResult.Skipped
                }

                else -> {
                    val body = runCatching { response.errorBody()?.string() }.getOrNull()
                    BingeCatTrace.recordListWriteFailure("http:${response.code()}")
                    Log.e(TAG, "list write failed: code=${response.code()} body=$body ($label)")
                    ListWriteResult.Failed
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            BingeCatTrace.recordListWriteFailure(error.javaClass.simpleName)
            Log.e(TAG, "list write error ($label): ${error.message}", error)
            ListWriteResult.Failed
        }
    }

    /** True when the watchlist add left the title saved (added OR already there). */
    private fun savedAddLanded(body: Any?): Boolean = when (body) {
        is BingeCatSavedAddResponse -> {
            val added = body.added ?: BingeCatSavedCounts()
            val existing = body.existing ?: BingeCatSavedCounts()
            added.movies + added.shows + existing.movies + existing.shows > 0
        }
        // A 201 with no body still means the server accepted the batch.
        else -> true
    }

    /** True when the list holds the title afterwards (added OR already listed). */
    private fun listItemsAddLanded(body: Any?): Boolean = when (body) {
        is BingeCatListItemsAdded -> !body.unchangedAndAbsent
        else -> true
    }

    /** What a list write says it changed, for the log line and diagnostics. */
    private fun listWriteResultLabel(body: Any?): String = when (body) {
        is BingeCatSavedAddResponse -> {
            val added = body.added ?: BingeCatSavedCounts()
            val existing = body.existing ?: BingeCatSavedCounts()
            "saved=${added.movies}m/${added.shows}s existing=${existing.movies}m/${existing.shows}s" +
                " unresolved=${body.notFound?.total ?: 0}"
        }

        is BingeCatSavedRemoveResponse -> {
            val deleted = body.deleted ?: BingeCatSavedCounts()
            "unsaved=${deleted.movies}m/${deleted.shows}s" +
                " unresolved=${body.notFound?.total ?: 0}"
        }

        is BingeCatListItemsAdded ->
            "items added=${body.added.size} existing=${body.existing.size}" +
                " not_found=${body.notFound.size} type_mismatch=${body.typeMismatch.size}"

        is BingeCatListItemsRemoved ->
            "items removed=${body.removed.size} not_found=${body.notFound.size}"

        else -> ""
    }

    /** What a committed batch says it wrote, for the log line and diagnostics. */
    private fun writeResultLabel(body: Any?): String = when (body) {
        is BingeCatHistoryAddResponse -> {
            val added = body.added ?: BingeCatHistoryCounts()
            val updated = body.updated ?: BingeCatHistoryCounts()
            "added=${added.movies}m/${added.episodes}e " +
                "updated=${updated.movies}m/${updated.episodes}e " +
                "unresolved=${body.notFound?.total ?: 0}"
        }

        is BingeCatHistoryRemoveResponse -> {
            val deleted = body.deleted ?: BingeCatDeleteCounts()
            "deleted=${deleted.movies}m/${deleted.shows}s/${deleted.seasons}n/" +
                "${deleted.episodes}e unresolved=${body.notFound?.total ?: 0}"
        }

        else -> ""
    }

    // ── Reads ────────────────────────────────────────────────────────────

    /**
     * The section's snapshot: cached rows published instantly, revalidated at
     * most once per [CACHE_TTL_MS].
     *
     * Returns null only when this profile has nothing readable at all - no
     * mapping, no token, or a first read that failed with nothing cached. A
     * revalidation failure returns the stale snapshot instead (spec F3).
     */
    suspend fun snapshot(nowMs: Long = System.currentTimeMillis()): BingeCatSnapshot? {
        val mapped = mappedProfileId() ?: return null
        if (!hasToken()) return null
        val activeProfile = context?.let { ProfileStorage.activeProfileId(it) }
        val fresh = cached
        if (
            fresh != null &&
            fresh.mappedId == mapped &&
            fresh.profileId == activeProfile &&
            nowMs - fresh.refreshedAtMs < CACHE_TTL_MS
        ) {
            return fresh
        }
        return cacheMutex.withLock {
            val current = cached
            if (
                current != null &&
                current.mappedId == mapped &&
                current.profileId == activeProfile &&
                nowMs - current.refreshedAtMs < CACHE_TTL_MS
            ) {
                return@withLock current
            }
            val refreshed = refresh(mapped, activeProfile, nowMs)
            if (refreshed != null) {
                cached = refreshed
                BingeCatTrace.recordRefresh(
                    watchlist = refreshed.watchlist.size,
                    saved = refreshed.saved.size,
                    lists = refreshed.lists.size,
                    watched = refreshed.watched.size,
                    atMs = refreshed.refreshedAtMs
                )
                return@withLock refreshed
            }
            // Keep whatever this device already had - for THIS mapping only.
            current?.takeIf { it.mappedId == mapped && it.profileId == activeProfile }
        }
    }

    /** A list's entries, fetched on selection rather than with the snapshot. */
    suspend fun listRows(ref: String): List<BingeCatRow>? {
        if (!isReady()) return null
        val rows = mutableListOf<BingeCatRow>()
        var page = 1
        while (page <= MAX_PAGES) {
            val body = authorizedBody { auth ->
                service.listItems(authorization = auth, ref = ref, page = page, limit = PAGE_SIZE)
            } ?: return rows.takeIf { it.isNotEmpty() }
            rows += body.items.mapNotNull { bingecatListRow(it) }
            if (body.items.size < PAGE_SIZE) break
            page += 1
        }
        return rows
    }

    /** The cached snapshot, for a screen that cannot wait (never a network read). */
    fun cachedSnapshot(): BingeCatSnapshot? = cached

    /**
     * One full revalidation. Partial answers are kept: a lists read that failed
     * does not throw away a watchlist that answered.
     */
    private suspend fun refresh(
        mapped: Long,
        activeProfile: String?,
        nowMs: Long
    ): BingeCatSnapshot? {
        val watchlist = readKind(mapped, kind = "watchlist")
        val saved = readKind(mapped, kind = "favorites")
        val lists = readLists(mapped)
        val watched = readWatched(mapped)
        if (watchlist == null && saved == null && lists == null && watched == null) {
            return null
        }
        return BingeCatSnapshot(
            watchlist = watchlist.orEmpty(),
            saved = saved.orEmpty(),
            lists = lists.orEmpty(),
            watched = watched.orEmpty(),
            refreshedAtMs = nowMs,
            profileId = activeProfile,
            mappedId = mapped
        )
    }

    /**
     * One `sync/{kind}` read, movies and shows merged.
     *
     * BingeCat, like Trakt, has no all-types GET here: the two resource types
     * are separate paths, so both are walked and concatenated in that order.
     * Null means the read failed (or this profile may not read lists at all);
     * an empty list means it answered with nothing.
     */
    private suspend fun readKind(mapped: Long, kind: String): List<BingeCatRow>? {
        val movies = readSavedPages(mapped, kind, "movies") ?: return null
        val shows = readSavedPages(mapped, kind, "shows") ?: return null
        return movies + shows
    }

    private suspend fun readSavedPages(
        mapped: Long,
        kind: String,
        resourceType: String
    ): List<BingeCatRow>? {
        val rows = mutableListOf<BingeCatRow>()
        var page = 1
        while (page <= MAX_PAGES) {
            val body = authorizedBody { auth ->
                service.savedItems(
                    authorization = auth,
                    profileId = mapped,
                    kind = kind,
                    resourceType = resourceType,
                    page = page,
                    limit = PAGE_SIZE
                )
            } ?: return null
            rows += body.mapNotNull { bingecatSavedRow(it) }
            if (body.size < PAGE_SIZE) break
            page += 1
        }
        return rows
    }

    private suspend fun readLists(mapped: Long): List<BingeCatList>? {
        val body = authorizedBody { auth ->
            service.lists(authorization = auth, profileId = mapped, limit = PAGE_SIZE)
        } ?: return null
        return body.lists.mapNotNull { summary ->
            val ref = summary.ref?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (summary.deletedAt != null) return@mapNotNull null
            val name = summary.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BingeCatList(ref = ref, name = name, itemCount = summary.itemCount ?: 0)
        }
    }

    /**
     * The recently watched rows, for the BingeCat section's own watched marker.
     *
     * One page: this is a badge hint, not a history mirror, and the marker only
     * ever shows on rows this same snapshot carries. `null` = the read failed;
     * an empty set = nothing watched.
     */
    private suspend fun readWatched(mapped: Long): Set<String>? {
        val body = authorizedBody { auth ->
            service.history(authorization = auth, profileId = mapped, limit = PAGE_SIZE)
        } ?: return null
        return body.items.mapNotNull { bingecatHistoryRow(it)?.watchedKey() }.toSet()
    }

    // ── Public lists (step 6: browse from Settings, import into the profile) ──
    //
    // The management surface behind Settings → BingeCat. Discovery is guarded by
    // `search:read` and the import by `lists:write`, so the two legs have their
    // own gates; the reads are account-agnostic (no profile in the path) while
    // the import is pinned to the mapped profile like every other write. Nothing
    // here touches Home: an imported list is the profile's own list afterwards,
    // and Library shows it through its ordinary list read.

    /** The category tree as this process last read it (null before any read). */
    @Volatile
    private var cachedCategories: List<BingeCatPublicCategory>? = null

    @Volatile
    private var categoriesReadAtMs: Long = 0L

    /** True when the stored grant carries `search:read`, discovery's own scope. */
    fun hasPublicListsScope(): Boolean =
        BingeCatWriteRules.hasPublicListsScope(store.scope())

    /** The last-read tree, for a screen that paints before it can wait. */
    fun cachedPublicCategories(): List<BingeCatPublicCategory>? = cachedCategories

    /**
     * The category tree, cached stale-while-revalidate (spec F2).
     *
     * Categories change slowly, so a tree read within [CACHE_TTL_MS] answers
     * without a request, and a FAILED refresh keeps the stale tree instead of
     * blanking the screen - the failure is recorded in diagnostics, which is
     * where a slow-drift bug is looked for.
     */
    suspend fun publicCategories(
        nowMs: Long = System.currentTimeMillis()
    ): BingeCatPublicBrowse<List<BingeCatPublicCategory>> {
        val stale = cachedCategories
        if (stale != null && nowMs - categoriesReadAtMs < CACHE_TTL_MS) {
            return BingeCatPublicBrowse.Loaded(stale)
        }
        val result = browseRead("categories", "public-categories") { auth ->
            service.publicListCategories(auth)
        }.mapLoaded { it.categories }
        return when (result) {
            is BingeCatPublicBrowse.Loaded -> {
                cachedCategories = result.value
                categoriesReadAtMs = nowMs
                result
            }

            is BingeCatPublicBrowse.Failed -> stale?.let { BingeCatPublicBrowse.Loaded(it) } ?: result

            else -> result
        }
    }

    /** One category's list cards. `subcategory` drills into a child of the tree. */
    suspend fun publicListsInCategory(
        slug: String,
        subcategory: String? = null
    ): BingeCatPublicBrowse<List<BingeCatPublicListRow>> {
        if (slug.isBlank()) return BingeCatPublicBrowse.Failed("no-category")
        return browseRead("category $slug", "public-category") { auth ->
            service.publicListsInCategory(
                authorization = auth,
                slug = slug,
                subcategory = subcategory,
                type = BingeCatPublicListRules.categoryTypeWire(),
                limit = PUBLIC_LIST_PAGE_SIZE
            )
        }.mapLoaded { it.lists }
    }

    /**
     * Public-list SEARCH - lists, never titles.
     *
     * The contract's `type` is `movie | series | anime | all` here (its category
     * route spells the same idea `movies | shows`), which is why the filter has
     * its own enum rather than sharing a string with the browse route.
     */
    suspend fun searchPublicLists(
        query: String,
        type: BingeCatSearchType = BingeCatSearchType.ALL
    ): BingeCatPublicBrowse<List<BingeCatPublicSearchResult>> {
        val trimmed = query.trim()
        if (!BingeCatPublicListRules.searchEnabled(trimmed)) {
            return BingeCatPublicBrowse.Loaded(emptyList())
        }
        return browseRead("search $trimmed", "public-search") { auth ->
            service.searchPublicLists(
                authorization = auth,
                query = trimmed,
                type = type.wireValue,
                limit = PUBLIC_LIST_PAGE_SIZE
            )
        }
    }

    /**
     * Reads a public list's titles through `/lists/{ref}/items` (spec Q4).
     *
     * This IS the test call: the route is the only list-items read the contract
     * has, and its `ref` is a typed grammar. A 400/404/422 therefore means this
     * list's ref is not addressable - Q4b, titles only after import - and is
     * reported as [BingeCatTitleProbe.NotAddressable] rather than as an error.
     * Nothing is inferred from a cover collage: those are artwork.
     */
    suspend fun publicListTitles(ref: String): BingeCatTitleProbe {
        if (ref.isBlank()) return BingeCatTitleProbe.NotAddressable
        if (!isConfigured()) return BingeCatTitleProbe.NotAddressable
        if (!hasToken()) return BingeCatTitleProbe.NotAddressable
        val token = ensureFreshToken()
            ?: return BingeCatTitleProbe.Failed("no-token")
        var response = runCatchingCancellable {
            service.listItems("Bearer $token", ref, page = 1, limit = PAGE_SIZE)
        }.getOrNull() ?: return BingeCatTitleProbe.Failed("network")
        if (response.code() == 401) {
            val refreshed = ensureFreshToken(force = true)
            if (refreshed != null) {
                response = runCatchingCancellable {
                    service.listItems("Bearer $refreshed", ref, page = 1, limit = PAGE_SIZE)
                }.getOrNull() ?: return BingeCatTitleProbe.Failed("network")
            }
            if (response.code() == 401) {
                store.clear()
                cached = null
                BingeCatTrace.recordSession(false)
                return BingeCatTitleProbe.Failed("401")
            }
        }
        if (response.code() == 429) {
            BingeCatTrace.recordPublicListError("429")
            return BingeCatTitleProbe.Failed("429")
        }
        return when {
            response.isSuccessful -> {
                val body = response.body()
                val rows = body?.items.orEmpty().mapNotNull { bingecatListRow(it) }
                BingeCatTitleProbe.Titles(rows)
            }
            // The ref grammar did not accept this list's key: Q4b.
            response.code() == 400 || response.code() == 404 || response.code() == 422 ->
                BingeCatTitleProbe.NotAddressable

            response.code() == 403 -> {
                BingeCatTrace.recordPublicListError("403-scope")
                BingeCatTrace.recordNeedsReapproval(true)
                BingeCatTitleProbe.Failed("403-scope")
            }

            else -> {
                BingeCatTrace.recordPublicListError("http:${response.code()}")
                BingeCatTitleProbe.Failed("http:${response.code()}")
            }
        }
    }

    /**
     * Imports ONE public list into the mapped profile, as a job.
     *
     * The gates run before the request (connect + mapping, then `lists:write`),
     * the failure contract is the list writes' one (401 refreshes once and then
     * drops the session, 429 parks, 403 converges to needs-reapproval), and a
     * success drops this profile's BingeCat snapshot so the Library re-reads its
     * lists on the next open. One list per call, so [importId] is what every
     * verdict in the answer is about.
     */
    suspend fun importPublicList(
        importId: String,
        mode: BingeCatImportMode
    ): BingeCatImportAttempt {
        if (importId.isBlank()) return BingeCatImportAttempt.Failed("no-list")
        val mapped = if (isReady()) mappedProfileId() else null
        if (mapped == null) return BingeCatImportAttempt.NotReady
        if (!hasListsWriteScope()) {
            BingeCatTrace.recordNeedsReapproval(true)
            BingeCatTrace.recordImportFailure("403-scope")
            Log.i(TAG, "import skipped: grant has no ${BingeCatConfig.LISTS_WRITE_SCOPE} ($importId)")
            return BingeCatImportAttempt.NeedsReapproval
        }
        BingeCatTrace.recordNeedsReapproval(false)
        val parkedMs = bingeCatWriteParkMs()
        if (parkedMs > 0L) {
            BingeCatTrace.recordImportFailure("429")
            Log.i(TAG, "import skipped: parked for ${parkedMs}ms after a 429 ($importId)")
            return BingeCatImportAttempt.Failed("429")
        }
        BingeCatTrace.recordImportAttempt()
        return try {
            val token = ensureFreshToken()
            if (token == null) {
                BingeCatTrace.recordImportFailure("no-token")
                return BingeCatImportAttempt.Failed("no-token")
            }
            val body = BingeCatPublicListImportRequest(
                lists = listOf(
                    BingeCatPublicListImportItem(id = importId, importMode = mode.wireValue)
                ),
                enabled = true
            )
            var response = sendImport(token, mapped, body)
            if (response == null) {
                BingeCatTrace.recordImportFailure("network")
                return BingeCatImportAttempt.Failed("network")
            }
            if (response.code() == 401) {
                val refreshed = ensureFreshToken(force = true)
                if (refreshed != null) response = sendImport(refreshed, mapped, body)
                if (response == null) {
                    BingeCatTrace.recordImportFailure("network")
                    return BingeCatImportAttempt.Failed("network")
                }
                if (response.code() == 401) {
                    store.clear()
                    cached = null
                    BingeCatTrace.recordSession(false)
                    BingeCatTrace.recordImportFailure("401")
                    return BingeCatImportAttempt.Failed("401")
                }
            }
            val retryAfter = BingeCatPublicListRules.pollSeconds(
                response.headers()["Retry-After"]
            )
            when {
                response.isSuccessful -> {
                    val accepted = response.body()
                    if (accepted == null) {
                        BingeCatTrace.recordImportFailure("empty")
                        Log.w(TAG, "import answered with no body ($importId)")
                        return BingeCatImportAttempt.Failed("empty")
                    }
                    // The imported list only reaches Library through the
                    // profile's own list read, so that read must not be served
                    // from the pre-import snapshot.
                    cached = null
                    val feedback = BingeCatPublicListRules.feedbackFor(accepted, importId)
                    // Diagnostics names the REASON CLASS for each verdict: a
                    // started/already-imported import is a success, and the two
                    // refusals are failures even though the request answered.
                    when (feedback) {
                        is BingeCatImportFeedback.Started -> BingeCatTrace.recordImportSuccess(
                            "$importId ${mode.wireValue} job=${feedback.jobId ?: "accepted"}"
                        )

                        BingeCatImportFeedback.AlreadyImported ->
                            BingeCatTrace.recordImportSuccess("$importId already-imported")

                        BingeCatImportFeedback.NotFound ->
                            BingeCatTrace.recordImportFailure("not-found")

                        is BingeCatImportFeedback.NotImportable ->
                            BingeCatTrace.recordImportFailure("not-importable")
                    }
                    Log.i(TAG, "import ok: $importId ${mode.wireValue} -> $feedback")
                    BingeCatImportAttempt.Answered(feedback, retryAfter)
                }

                noteBingeCatWriteRateLimit(
                    response.code(),
                    response.headers()["Retry-After"]
                ) -> {
                    BingeCatTrace.recordImportFailure("429")
                    Log.w(TAG, "import rate-limited: 429, leg parked ($importId)")
                    BingeCatImportAttempt.Failed("429")
                }

                response.code() == 403 -> {
                    BingeCatTrace.recordNeedsReapproval(true)
                    BingeCatTrace.recordImportFailure("403-scope")
                    Log.w(TAG, "import refused: 403 scope, needs re-approval ($importId)")
                    BingeCatImportAttempt.NeedsReapproval
                }

                // `import_already_running` is a state, not a fault: BingeCat
                // runs one import per profile, and the answer is to wait.
                response.code() == 409 -> {
                    BingeCatTrace.recordImportFailure("409-import-running")
                    BingeCatImportAttempt.Failed("import-running")
                }

                else -> {
                    val code = oauthError(response)
                    val raw = runCatching { response.errorBody()?.string() }.getOrNull()
                    BingeCatTrace.recordImportFailure(
                        code?.let { "http:${response.code()}:$it" } ?: "http:${response.code()}"
                    )
                    Log.e(
                        TAG,
                        "import failed: code=${response.code()} error=$code body=$raw ($importId)"
                    )
                    BingeCatImportAttempt.Failed(code ?: "http:${response.code()}")
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            BingeCatTrace.recordImportFailure(error.javaClass.simpleName)
            Log.e(TAG, "import error ($importId): ${error.message}", error)
            BingeCatImportAttempt.Failed(error.javaClass.simpleName)
        }
    }

    private suspend fun sendImport(
        token: String,
        profileId: Long,
        body: BingeCatPublicListImportRequest
    ): Response<BingeCatPublicListImportAccepted>? = runCatchingCancellable {
        service.importPublicLists("Bearer $token", profileId, body)
    }.getOrNull()

    /**
     * One poll of the job an import was accepted as.
     *
     * Polled by the `status_url` the acceptance named (never by building a path
     * from the id), with the reads' 401 rule. A 429 or a transport fault is
     * [BingeCatJobPoll.Failed] and leaves the caller's loop to decide; the wait
     * between polls is the job's own `Retry-After`.
     */
    suspend fun pollImportJob(statusUrl: String): BingeCatJobPoll {
        if (statusUrl.isBlank()) return BingeCatJobPoll.Failed("no-job-url")
        val token = ensureFreshToken() ?: return BingeCatJobPoll.Failed("no-token")
        var response = runCatchingCancellable {
            service.job("Bearer $token", statusUrl)
        }.getOrNull() ?: return BingeCatJobPoll.Failed("network")
        if (response.code() == 401) {
            val refreshed = ensureFreshToken(force = true)
            if (refreshed != null) {
                response = runCatchingCancellable {
                    service.job("Bearer $refreshed", statusUrl)
                }.getOrNull() ?: return BingeCatJobPoll.Failed("network")
            }
            if (response.code() == 401) {
                store.clear()
                cached = null
                BingeCatTrace.recordSession(false)
                return BingeCatJobPoll.Failed("401")
            }
        }
        if (response.code() == 429) {
            BingeCatTrace.recordImportFailure("429-poll")
            return BingeCatJobPoll.Failed("429")
        }
        if (!response.isSuccessful) {
            BingeCatTrace.recordImportFailure("http:${response.code()}-poll")
            return BingeCatJobPoll.Failed("http:${response.code()}")
        }
        val body = response.body() ?: return BingeCatJobPoll.Failed("empty")
        return BingeCatJobPoll.Status(
            outcome = BingeCatPublicListRules.jobOutcome(body.status, body.error),
            retryAfterSeconds = BingeCatPublicListRules.pollSeconds(
                response.headers()["Retry-After"]
            )
        )
    }

    /**
     * One discovery read with a live token, on the reads' failure contract.
     *
     * The scope gate runs BEFORE the request: a grant that never asked for
     * `search:read` is the reconnect STATE, not a 403 to discover at runtime.
     */
    private suspend fun <T> browseRead(
        label: String,
        traceLabel: String,
        call: suspend (String) -> Response<T>
    ): BingeCatPublicBrowse<T> {
        if (!isConfigured() || !hasToken()) return BingeCatPublicBrowse.NotReady
        if (!hasPublicListsScope()) {
            BingeCatTrace.recordNeedsReapproval(true)
            BingeCatTrace.recordPublicListError("403-scope")
            Log.i(
                TAG,
                "public-list read skipped: grant has no ${BingeCatConfig.PUBLIC_LISTS_SCOPE} ($label)"
            )
            return BingeCatPublicBrowse.NeedsReapproval
        }
        BingeCatTrace.recordPublicListRead()
        val token = ensureFreshToken() ?: run {
            BingeCatTrace.recordPublicListError("no-token")
            return BingeCatPublicBrowse.Failed("no-token")
        }
        var response = runCatchingCancellable { call("Bearer $token") }.getOrNull()
        if (response == null) {
            BingeCatTrace.recordPublicListError("network")
            Log.w(TAG, "public-list read failed: network ($label)")
            return BingeCatPublicBrowse.Failed("network")
        }
        if (response.code() == 401) {
            val refreshed = ensureFreshToken(force = true)
            if (refreshed != null) {
                response = runCatchingCancellable { call("Bearer $refreshed") }.getOrNull()
            }
            if (response == null) {
                BingeCatTrace.recordPublicListError("network")
                return BingeCatPublicBrowse.Failed("network")
            }
            if (response.code() == 401) {
                store.clear()
                cached = null
                BingeCatTrace.recordSession(false)
                BingeCatTrace.recordPublicListError("401")
                return BingeCatPublicBrowse.Failed("401")
            }
        }
        if (response.code() == 403) {
            BingeCatTrace.recordNeedsReapproval(true)
            BingeCatTrace.recordPublicListError("403-scope")
            return BingeCatPublicBrowse.NeedsReapproval
        }
        if (response.code() == 429) {
            BingeCatTrace.recordPublicListError("429")
            return BingeCatPublicBrowse.Failed("429")
        }
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            BingeCatTrace.recordPublicListError("http:${response.code()}")
            Log.w(TAG, "public-list read failed: code=${response.code()} ($label/$traceLabel)")
            return BingeCatPublicBrowse.Failed("http:${response.code()}")
        }
        return BingeCatPublicBrowse.Loaded(body)
    }

    /** Maps a loaded discovery answer, keeping every gate's own answer. */
    private fun <T, R> BingeCatPublicBrowse<T>.mapLoaded(
        transform: (T) -> R
    ): BingeCatPublicBrowse<R> = when (this) {
        is BingeCatPublicBrowse.Loaded -> BingeCatPublicBrowse.Loaded(transform(value))
        BingeCatPublicBrowse.NeedsReapproval -> BingeCatPublicBrowse.NeedsReapproval
        BingeCatPublicBrowse.NotReady -> BingeCatPublicBrowse.NotReady
        is BingeCatPublicBrowse.Failed -> this
    }

    // ── Plumbing ─────────────────────────────────────────────────────────

    /**
     * One data call with a live token: refresh-on-401 once (single flight),
     * retry once, and never loop.
     *
     * A second 401 drops the session and records it - the spec's rule is
     * "degrade to disconnected, never to anonymous calls" - so the next screen
     * reads a real state instead of a token the server has already refused.
     */
    private suspend fun <T> authorizedBody(
        call: suspend (String) -> Response<T>
    ): T? {
        val token = ensureFreshToken() ?: run {
            BingeCatTrace.recordError("no-token")
            return null
        }
        var response = runCatchingCancellable { call("Bearer $token") }.getOrNull()
        if (response == null) {
            BingeCatTrace.recordError("network")
            return null
        }
        if (response.code() == 401) {
            val refreshed = ensureFreshToken(force = true)
            if (refreshed != null) {
                response = runCatchingCancellable { call("Bearer $refreshed") }.getOrNull()
            }
            if (response == null) {
                BingeCatTrace.recordError("network")
                return null
            }
            if (response.code() == 401) {
                store.clear()
                cached = null
                BingeCatTrace.recordSession(false)
                BingeCatTrace.recordError("401")
                return null
            }
        }
        if (response.code() == 429) {
            BingeCatTrace.recordError("429")
            return null
        }
        if (!response.isSuccessful) {
            BingeCatTrace.recordError("http:${response.code()}")
            return null
        }
        return response.body()
    }

    /** A usable access token, refreshing it first when it is close to expiry. */
    private suspend fun ensureFreshToken(force: Boolean = false): String? {
        val current = store.read() ?: return null
        val now = System.currentTimeMillis()
        if (!force && current.expiresAtEpochMillis - now > EXPIRY_SKEW_MS) {
            return current.accessToken
        }
        val refreshToken = current.refreshToken
        if (refreshToken.isNullOrBlank()) {
            return if (force) null else current.accessToken
        }
        return refreshMutex.withLock {
            val latest = store.read() ?: return@withLock null
            if (!force && latest.expiresAtEpochMillis - now > EXPIRY_SKEW_MS) {
                return@withLock latest.accessToken
            }
            val latestRefresh = latest.refreshToken ?: return@withLock null
            val response = runCatchingCancellable {
                service.refreshToken(
                    BingeCatRefreshTokenRequest(
                        refreshToken = latestRefresh,
                        clientId = BingeCatConfig.clientId
                    )
                )
            }.getOrNull()
            val granted = response?.body()
            // Locals, because a property of another module's class cannot be
            // smart-cast (the same rule the device-code path follows).
            val access = granted?.accessToken?.takeIf { it.isNotBlank() }
            if (response?.isSuccessful != true || access == null) {
                BingeCatTrace.recordError("refresh-failed")
                return@withLock null
            }
            val token = BingeCatToken(
                accessToken = access,
                refreshToken = granted.refreshToken ?: latestRefresh,
                expiresAtEpochMillis = BingeCatDeviceFlowRules.expiresAtEpochMillis(
                    createdAtEpochSeconds = granted.createdAtEpochSeconds,
                    expiresInSeconds = granted.expiresInSeconds,
                    nowMs = System.currentTimeMillis()
                ),
                scope = granted.scope ?: latest.scope
            )
            runCatching { store.save(token) }.getOrNull() ?: return@withLock null
            // A refresh ROTATES the pair (and may invalidate the old refresh
            // token), so the account copy is now stale: push the new one or a
            // sibling is signed out as soon as its own copy expires.
            publishAuthToSync()
            token.accessToken
        }
    }

    /** The OAuth `error` code in a non-2xx body, parsed with the token adapter. */
    private fun oauthError(response: Response<*>): String? {
        val raw = runCatching { response.errorBody()?.string() }.getOrNull() ?: return null
        if (raw.isBlank()) return null
        return runCatching {
            moshi.adapter(BingeCatTokenResponse::class.java).fromJson(raw)?.error
        }.getOrNull()
    }
}
