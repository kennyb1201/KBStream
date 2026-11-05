package com.kennyb1201.kbstream.data.bingecat

import android.content.Context
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.sync.ProfileStorage
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
 * BingeCat, read-only, as this app speaks to it.
 *
 * The connect grant, the proactive refresh, the disconnect, the per-profile
 * mapping, and the three reads v1 ships (watchlist + saved list + lists, and
 * history for the section's own watched marker). Nothing writes: there is no
 * scrobble, no list mutation and no settings call, and the scope string does
 * not even ask for them (see [BingeCatConfig.SCOPES]).
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

    companion object {
        /** Revalidate at most once per half hour per profile (spec F3). */
        const val CACHE_TTL_MS = 30L * 60L * 1_000L

        private const val PAGE_SIZE = 100

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

    fun mappedProfileId(): Long? = mapping.mappedProfileId()?.also { id ->
        BingeCatTrace.recordMapping(id, mapping.mappedProfileName())
    }

    fun mappedProfileName(): String? = mapping.mappedProfileName()

    /** True when this profile may make a data request at all. */
    fun isReady(): Boolean = isConfigured() && hasToken() && mappedProfileId() != null

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
        store.clear()
        mapping.clear()
        cached = null
        BingeCatTrace.recordSession(false)
        BingeCatTrace.recordMapping(null, null)
        return revoked
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
