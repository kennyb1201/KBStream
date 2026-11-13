package com.kennyb1201.kbstream.data.bingecat

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.data.library.LibraryListRename
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
    val mappedId: Long,
    /**
     * Whether [lists] is the account's whole list set as of [refreshedAtMs].
     *
     * False when the lists leg failed - [lists] then carries the rows this
     * device already had, or nothing at all on a cold snapshot. The freshness
     * stamp cannot say this on its own: a partial refresh re-stamps the
     * snapshot, so a caller that only compared [refreshedAtMs] against its own
     * read time would treat one failed request as "the account has no lists"
     * and prune every pinned BingeCat rail on the strength of it.
     */
    val listsComplete: Boolean = true
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

    /**
     * Whether this profile holds a session it can actually CALL with.
     *
     * A stored pair the server has already refused is not a connection: it is
     * the state that produced `connected=yes ... last_error=no-token` while the
     * Library sat empty (field report, build 253). [store]'s refusal marker is
     * what separates the two, and recording it here is what keeps the
     * diagnostics line and every gate reading the same fact.
     *
     * The needs-reapproval flag is re-derived on the same read, from the store
     * rather than from the last attempt, so a session that comes back (a
     * reconnect, a successful refresh, an adopted account copy) clears it
     * without anything else having to remember to.
     */
    fun hasToken(): Boolean {
        val usable = store.read() != null && !store.needsReconnect()
        val reconnect = needsReconnect()
        BingeCatTrace.recordSession(usable)
        BingeCatTrace.recordNeedsReconnect(reconnect)
        BingeCatTrace.recordNeedsReapproval(
            reconnect || (
                usable && mappedProfileId() != null &&
                    (!hasWriteScope() || !hasListsWriteScope())
                )
        )
        return usable
    }

    /**
     * Whether BingeCat needs the viewer to run the connect flow again.
     *
     * Two shapes, one answer: the store is EMPTY while this profile is mapped to
     * a BingeCat profile (the credential is gone and only a fresh grant brings
     * the lists back), or the store holds a pair the server REFUSED (a 401 we no
     * longer delete on - see [BingeCatTokenStore.markNeedsReconnect]).
     *
     * A profile that never connected and never mapped is not in this state, and
     * neither is a deliberate disconnect (that clears the mapping and leaves the
     * tombstone): both must stay quiet rather than asking for a reconnect. The
     * plain store read, NOT [hasToken], so the two facts cannot recurse into each
     * other.
     */
    fun needsReconnect(): Boolean {
        if (!isConfigured()) return false
        val refused = store.needsReconnect()
        val noSession = store.read() == null
        if (!refused && !noSession) return false
        return refused || mappedProfileId() != null
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
        // The same honesty the gates use: a refused pair the applier left in
        // place is not a connection, and the screen must not read as one.
        BingeCatTrace.recordSession(store.read() != null && !store.needsReconnect())
        BingeCatTrace.recordNeedsReconnect(needsReconnect())
    }

    /**
     * The MAPPING changed underneath this repository - it arrived from the
     * account (see [com.kennyb1201.kbstream.data.sync.BingeCatProfileMapRules]).
     *
     * Only the cached snapshot goes: it was read for the PREVIOUS BingeCat
     * profile, and its own `mappedId` guard would keep refusing to serve it
     * anyway - clearing it just makes the next read the new profile's. The
     * session, the local library and the watched markers are not touched.
     */
    fun onSyncedMappingChanged() {
        cached = null
        BingeCatTrace.recordMapping(mapping.mappedProfileId(), mapping.mappedProfileName())
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
     * itself enforces. And the same profile refusal its sibling
     * ([favoritesContains]) has always had: BingeCat's mapping is account-wide,
     * so a snapshot read under another app profile still carries that profile's
     * rows. No in-app caller asks today, which is exactly when a trap like that
     * gets walked into.
     */
    fun watchlistContains(mediaType: String, imdbId: String?, tmdbId: Int?): Boolean {
        val snapshot = cached ?: return false
        val mapped = mappedProfileId() ?: return false
        if (snapshot.mappedId != mapped) return false
        val active = context?.let { ProfileStorage.activeProfileId(it) }
        if (snapshot.profileId != active) return false
        return snapshot.watchlist.any { row -> row.matches(mediaType, imdbId, tmdbId) }
    }

    /**
     * True when [mediaType]/[imdbId]/[tmdbId] is on the mapped profile's
     * BingeCat FAVORITES according to the LAST READ - the thumbs' membership
     * rule (the favorites spec's F3, matched by TMDB or IMDb id).
     *
     * The sibling of [watchlistContains] over the snapshot's `saved` rows (the
     * `sync/favorites` read), with the same two refusals: false while nothing is
     * cached, and false for a different mapping, because a stale account's rows
     * must never light a thumb on this one.
     *
     * One refusal more than its sibling, because this answer paints a page: the
     * snapshot must also belong to the app profile on screen now. BingeCat's
     * mapping is account-wide, so a snapshot read under another profile still
     * carries that profile's Favorites - and a thumb lit from it would be another
     * profile's saved title on this one's Detail page (the same rule
     * [snapshot] already applies to its own cache).
     */
    fun favoritesContains(mediaType: String, imdbId: String?, tmdbId: Int?): Boolean {
        val snapshot = cached ?: return false
        val mapped = mappedProfileId() ?: return false
        if (snapshot.mappedId != mapped) return false
        val active = context?.let { ProfileStorage.activeProfileId(it) }
        if (snapshot.profileId != active) return false
        return snapshot.saved.any { row -> row.matches(mediaType, imdbId, tmdbId) }
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
     * True when the thumbs' favorites leg can go out - the same gate as saving,
     * deliberately read through the same function.
     *
     * Favorites is a saved-list kind on the same two routes and the contract
     * asks `lists:write` for both, so a second scope question here would be a
     * second answer to one fact. An unmapped profile, no token, or a step-1/2
     * grant all answer false: the thumbs then stay local-only, silently, which
     * is the spec's own outcome for each.
     */
    fun canFavorite(): Boolean = canSave()

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
        isConfigured() && !needsReconnect() && hasToken() && mappedProfileId() != null &&
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
            // A new grant is a new read: the snapshot held by the lost (or
            // refused) session must not keep the Library empty for a TTL after
            // the viewer reconnects (spec F3).
            cached = null
            BingeCatTrace.recordSession(true)
            // A fresh grant is the way OUT of a reconnect state, so the two
            // flags are cleared by the save that just retired the marker.
            BingeCatTrace.recordNeedsReconnect(false)
            BingeCatTrace.recordNeedsReapproval(false)
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

    /**
     * The account's profiles, for the mapping step. Null when the read failed.
     *
     * A SUCCESSFUL fetch is also the mapping's only proof of life (spec F3): the
     * mapping is a BingeCat id, and a profile deleted on the account leaves it
     * pointing at nothing - `isReady()` true, every read a 404, and the picker's
     * own list showing the truth. So the mapped id is checked here, after a
     * listing that actually came back.
     *
     * One deliberate refusal: a fetch that returns NO profiles at all does not
     * count as "omits the id". An account always has at least one profile, so an
     * empty list is a server state rather than a fact about the mapping, and
     * clearing (and publishing a tombstone) on it would unmap a healthy profile
     * on a transient answer. A failed read (`null`) never reaches this check at
     * all: a failure is not evidence the profile is gone.
     */
    suspend fun profiles(): List<BingeCatProfile>? {
        val body = authorizedBody { auth -> service.profiles(auth) } ?: return null
        val profiles = body.profiles.filter { (it.id ?: 0L) > 0L }
        val mapped = mappedProfileId()
        if (profiles.isNotEmpty() && mapped != null && profiles.none { it.id == mapped }) {
            clearStaleMapping()
        }
        return profiles
    }

    /**
     * The mapped BingeCat profile is gone from the account.
     *
     * The same clearing a deliberate unmap does, and it publishes the same
     * tombstone: leaving the dead id in the account's row would hand it to every
     * sibling that syncs before its own profile fetch, and each of them would
     * re-adopt a profile the account no longer has. A blank that is not
     * tombstoned is not an option ("never publish a blank"), and the row is
     * per KBStream profile, so nothing but this profile's mapping moves.
     */
    private fun clearStaleMapping() {
        mapping.clear()
        cached = null
        BingeCatTrace.recordMapping(null, null)
        PrefsPayloadBuilder.recordBingeCatMappingChanged(context, cleared = true)
        publishMappingToSync()
    }

    /**
     * Files this KBStream profile's choice of BingeCat profile.
     *
     * The mapping is no longer device-local: it publishes as soon as it is made,
     * because the bulk push only runs on sign-in / "Sync now" and a viewer who
     * maps a profile on the TV expects the phone to read it without a re-select
     * (and without waiting for a full sync). Re-mapping publishes too - the new
     * choice is what carries the newer stamp, which is what lets a sibling tell
     * it from a stale row.
     */
    fun setMappedProfile(profile: BingeCatProfile) {
        // Nothing landed, so nothing is published: re-asserting the PREVIOUS id
        // as the new choice would silently undo the viewer's pick on every
        // sibling. (A profile with no usable id cannot be stored; the picker
        // already filters those out, and this is the one place that decides.)
        if (!mapping.set(profile)) return
        BingeCatTrace.recordMapping(profile.id, profile.name)
        cached = null
        PrefsPayloadBuilder.recordBingeCatMappingChanged(context, cleared = false)
        publishMappingToSync()
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
     *
     * The MAPPING is unmapped deliberately here too, so it publishes its own
     * tombstone beside the session's: a sibling that kept the id would hold a
     * mapping with no session behind it, which is neither "connected and
     * unmapped" nor a tracker - an orphan the viewer never chose (see
     * [com.kennyb1201.kbstream.data.sync.BingeCatProfileMapRules]).
     *
     * Every step is pinned to the profile this call STARTED on. The revoke is a
     * network round-trip, and both stores resolve the ACTIVE profile per call:
     * a switch landing inside that round-trip used to tombstone the incoming
     * profile's session and mapping - then publish those tombstones to the
     * account, signing a sibling out of a connection nobody disconnected.
     */
    suspend fun disconnect(): Boolean {
        val profileId = context?.let { ProfileStorage.activeProfileId(it) }
        val pinnedStore = BingeCatTokenStore(context, profileId)
        val pinnedMapping = BingeCatProfileMap(context, profileId)
        val token = pinnedStore.read()?.accessToken
        var revoked = false
        if (!token.isNullOrBlank()) {
            val response = runCatchingCancellable {
                service.revoke(
                    BingeCatRevokeRequest(token = token, clientId = BingeCatConfig.clientId)
                )
            }.getOrNull()
            revoked = response?.isSuccessful == true
        }
        val cleared = runCatching { pinnedStore.markSignedOut() }.getOrDefault(false)
        pinnedMapping.clear()
        cached = null
        BingeCatTrace.recordSession(false)
        BingeCatTrace.recordNeedsReconnect(false)
        BingeCatTrace.recordNeedsReapproval(false)
        BingeCatTrace.recordMapping(null, null)
        PrefsPayloadBuilder.recordBingeCatMappingChanged(context, cleared = true)
        if (cleared) publishAuthToSync()
        // Its own row and its own marker: the mapping tombstone does not depend
        // on whether the grant was revoked (a revoke can fail offline while the
        // disconnect on this device still stands).
        publishMappingToSync()
        return revoked
    }

    /**
     * Re-asserts this device's session to the account, when it has one.
     *
     * The session is published at connect, at every refresh and at disconnect -
     * which covers a device that connected AFTER the account's row existed, but
     * not one that connected BEFORE it did. A TV whose grant predates the shared
     * session (or whose one publish was lost) has nothing that would ever
     * trigger another: the bulk push only runs on sign-in or "Sync now", so the
     * row stayed local and every sibling kept showing "Device link required" -
     * the "the BingeCat login isn't syncing" report. Opening the BingeCat page is
     * proof the viewer is looking at THIS connection, so it re-asserts what this
     * device holds: the same one-row enqueue connect uses.
     *
     * Nothing is published without a session: a blank store is "never connected"
     * (or a deliberate disconnect, which publishes its own tombstone) and must
     * never blank the account's copy - see [com.kennyb1201.kbstream.data.sync.BingeCatAuthRules].
     */
    fun publishSessionIfConnected() {
        if (store.read() == null) return
        publishAuthToSync()
    }

    /**
     * Re-asserts this device's MAPPING to the account, when it has one.
     *
     * The mapping's sibling of [publishSessionIfConnected], and it exists for
     * the same report one layer down: a profile mapped by a build that predates
     * the mapping row has no publish of its own, and the bulk push only runs on
     * sign-in / "Sync now" - so every sibling kept asking for a BingeCat
     * profile that had already been chosen, once per KBStream profile. Opening
     * the BingeCat page is proof the viewer is looking at THIS mapping, so it
     * re-asserts it: the same one-row enqueue `setMappedProfile` uses, carrying
     * the mapping's own change stamp, so it can never outrank a newer choice
     * made on another device.
     *
     * Nothing is published for an unmapped profile: a blank that is not
     * tombstoned is "never mapped here" and must never erase a sibling's mapping
     * (see [com.kennyb1201.kbstream.data.sync.BingeCatProfileMapRules]).
     */
    fun publishMappingIfMapped() {
        if (mapping.mappedProfileId() == null) return
        publishMappingToSync()
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

    /**
     * Publishes this profile's BingeCat MAPPING immediately, bypassing the bulk
     * push's "nothing to publish" skip.
     *
     * Mapping, re-mapping and disconnect need it for the same reason the session
     * does: the bulk push waits for sign-in / "Sync now", and a mapping made on
     * the TV is exactly what the phone is waiting for (see
     * [com.kennyb1201.kbstream.data.sync.BingeCatProfileMapRules]).
     */
    private fun publishMappingToSync() {
        val appContext = context ?: return
        runCatching {
            SupabaseSync.enqueuePrefs(
                appContext,
                PrefsPayloadBuilder.KEY_BINGECAT_PROFILE_MAP,
                PrefsPayloadBuilder.buildBingeCatProfileMap(appContext)
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
                    // A 401 refuses the ACCESS token, not the grant: keep the pair and
                    // flag a reconnect, so the state says so and nothing prompts for it.
                    store.markNeedsReconnect()
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

    /**
     * The thumbs' favorites leg: puts the title on the mapped profile's
     * Favorites.
     *
     * Same gates and same failure contract as the watchlist ([submitSavedWrite]),
     * and the same one-thing-on-success rule: only BingeCat's snapshot is
     * dropped, so the next Library open - and the next thumb paint - re-reads.
     * False only when a write was attempted and did not land; a gate answering
     * "not now" is true, because nothing failed.
     */
    suspend fun addToFavorites(
        isMovie: Boolean,
        imdbId: String?,
        tmdbId: Int?,
        title: String? = null
    ): Boolean = addToFavoritesImpl(isMovie, imdbId, tmdbId, title)

    /** The thumbs' unsave leg, on the same contract. */
    suspend fun removeFromFavorites(
        isMovie: Boolean,
        imdbId: String?,
        tmdbId: Int?,
        title: String? = null
    ): Boolean = removeFromFavoritesImpl(isMovie, imdbId, tmdbId, title)

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

    /**
     * Renames one user list by its own `ref` (`lists:write`).
     *
     * A success answers with the list's ref AFTER the rename when the answer
     * carries one: a Home pin is keyed on that ref, and a service that derives
     * it from the name moves the pin's key under a rename. Null is a rename that
     * did not land, and nothing about the list's rail may change either (see
     * [LibraryListRename]).
     */
    suspend fun renameList(ref: String, name: String): LibraryListRename? {
        val trimmed = name.trim()
        if (ref.isBlank() || trimmed.isEmpty()) return null
        val body = BingeCatListPatchRequest(name = trimmed)
        return when (
            val result = submitListWrite("list rename $ref") { token, _ ->
                service.patchList("Bearer $token", ref, body)
            }
        ) {
            ListWriteResult.Skipped -> null
            ListWriteResult.Failed -> null
            is ListWriteResult.Accepted -> {
                val answer = result.body
                if (!listWriteLanded(answer)) return null
                val renamedRef = (answer as? BingeCatListResponse)?.list?.ref
                LibraryListRename(renamedRef?.takeIf { it.isNotBlank() })
            }
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
                    // A 401 refuses the ACCESS token, not the grant: keep the pair and
                    // flag a reconnect, so the state says so and nothing prompts for it.
                    store.markNeedsReconnect()
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
            val refreshed = refresh(mapped, activeProfile, nowMs, current)
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

    /**
     * A list's entries, fetched on selection rather than with the snapshot.
     *
     * Null when the walk failed, exactly as [readSavedPages] does: the pages
     * already fetched are not the list, and every caller draws what it gets as
     * the whole thing (`orEmpty()` at the rail, the picker and the list pane), so
     * a truncated answer would be served as a complete one.
     */
    suspend fun listRows(ref: String): List<BingeCatRow>? {
        if (!isReady()) return null
        val rows = mutableListOf<BingeCatRow>()
        var page = 1
        var truncated = true
        while (page <= MAX_PAGES) {
            val body = authorizedBody { auth ->
                service.listItems(authorization = auth, ref = ref, page = page, limit = PAGE_SIZE)
            } ?: return null
            rows += body.items.mapNotNull { bingecatListRow(it) }
            if (body.items.size < PAGE_SIZE) {
                truncated = false
                break
            }
            page += 1
        }
        // m24: a walk that stopped because MAX_PAGES ran out is NOT the list -
        // it is the first 1,000 entries of it, and callers draw what they get as
        // the whole thing. Null is this file's own answer for "not the complete
        // set" (see the failure arm below and readLists' doc), so a >1,000-item
        // list now reads as unavailable instead of as a silently short one.
        return if (truncated) null else rows
    }

    /** The cached snapshot, for a screen that cannot wait (never a network read). */
    fun cachedSnapshot(): BingeCatSnapshot? = cached

    /**
     * One full revalidation. Partial answers are kept: a lists read that failed
     * does not throw away a watchlist that answered - and it does not publish an
     * EMPTY list set where the previous read had rows either, because "the
     * account holds nothing" is a claim the failure never made. A leg that
     * failed keeps what this device already had for the same mapping, and
     * [BingeCatSnapshot.listsComplete] records which legs actually answered.
     */
    private suspend fun refresh(
        mapped: Long,
        activeProfile: String?,
        nowMs: Long,
        previous: BingeCatSnapshot?
    ): BingeCatSnapshot? {
        val watchlist = readKind(mapped, kind = "watchlist")
        val saved = readKind(mapped, kind = "favorites")
        val lists = readLists(mapped)
        val watched = readWatched(mapped)
        if (watchlist == null && saved == null && lists == null && watched == null) {
            return null
        }
        // The rows this device already had, but only for THIS mapping: another
        // profile's (or another BingeCat account's) rows are not a fallback.
        val kept = previous?.takeIf { it.mappedId == mapped && it.profileId == activeProfile }
        return BingeCatSnapshot(
            watchlist = legRows(watchlist, kept?.watchlist),
            saved = legRows(saved, kept?.saved),
            lists = lists ?: kept?.lists.orEmpty(),
            watched = watched ?: kept?.watched.orEmpty(),
            refreshedAtMs = nowMs,
            profileId = activeProfile,
            mappedId = mapped,
            listsComplete = lists != null
        )
    }

    /**
     * A leg's rows for a snapshot: a complete answer replaces what was there, a
     * partial one (one of its two resource types failed) only fills a gap, and
     * a failed leg leaves the previous rows alone.
     */
    private fun legRows(leg: LegRead?, kept: List<BingeCatRow>?): List<BingeCatRow> =
        when {
            leg == null -> kept.orEmpty()
            leg.complete || kept == null -> leg.rows
            else -> kept
        }

    /**
     * One leg's answer: the rows it read, and whether BOTH halves answered.
     *
     * A half that answered is never discarded - the shows read failing must not
     * throw away the movies that did answer - so [rows] can be partial, and the
     * snapshot decides what a partial leg may replace (see [legRows]).
     */
    private class LegRead(val rows: List<BingeCatRow>, val complete: Boolean)

    /**
     * One `sync/{kind}` read, movies and shows merged.
     *
     * BingeCat, like Trakt, has no all-types GET here: the two resource types
     * are separate paths, so both are walked and concatenated in that order.
     * Null means both halves failed (or this profile may not read lists at all);
     * otherwise [LegRead.complete] says whether both answered.
     */
    private suspend fun readKind(mapped: Long, kind: String): LegRead? {
        val movies = readSavedPages(mapped, kind, "movies")
        val shows = readSavedPages(mapped, kind, "shows")
        if (movies == null && shows == null) return null
        return LegRead(
            rows = movies.orEmpty() + shows.orEmpty(),
            complete = movies != null && shows != null
        )
    }

    private suspend fun readSavedPages(
        mapped: Long,
        kind: String,
        resourceType: String
    ): List<BingeCatRow>? {
        val rows = mutableListOf<BingeCatRow>()
        var page = 1
        // m24: set false only by a SHORT page - the one end-of-walk signal this
        // API has. Still true after the loop means MAX_PAGES ran out mid-list.
        var truncated = true
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
            if (body.size < PAGE_SIZE) {
                truncated = false
                break
            }
            page += 1
        }
        // m24: the same ceiling rule as listRows - see there.
        return if (truncated) null else rows
    }

    /**
     * The account's lists, every page of them.
     *
     * The response models no total and no `has_more`, so a short page is the
     * only end-of-list signal there is; one page was enough to be wrong (a
     * hundred-list account silently lost its tail, and the tail's rails with
     * it). Null on any failure: a partial set here is indistinguishable from a
     * complete one, and the caller prunes Home rails with it.
     */
    private suspend fun readLists(mapped: Long): List<BingeCatList>? {
        val lists = mutableListOf<BingeCatList>()
        var page = 1
        var truncated = true
        while (page <= MAX_PAGES) {
            val body = authorizedBody { auth ->
                service.lists(
                    authorization = auth,
                    profileId = mapped,
                    page = page,
                    limit = PAGE_SIZE
                )
            } ?: return null
            page++
            for (summary in body.lists) {
                val ref = summary.ref?.takeIf { it.isNotBlank() } ?: continue
                if (summary.deletedAt != null) continue
                val name = summary.name?.takeIf { it.isNotBlank() } ?: continue
                lists += BingeCatList(ref = ref, name = name, itemCount = summary.itemCount ?: 0)
            }
            if (body.lists.size < PAGE_SIZE) {
                truncated = false
                break
            }
        }
        // m24: a >1,000-list account used to lose its tail silently, and this
        // set is what prunes Home rails - so the loss showed up as missing rails
        // with nothing to explain them. Null refuses the truncated answer
        // outright, which is the rule this file already states for a failed page.
        return if (truncated) null else lists
    }

    /**
     * The profile's watched timeline, every page of it, folded for the Upcoming
     * rail (F2a).
     *
     * The rail's third tracker leg, and the reason it is read from here rather
     * than from the badge's snapshot: the badge needs keys ('this row was
     * watched'), while a caught-up card needs the EPISODES, which is what the
     * feed's `episodes[]` carries. Without them a tracker can only say "watched
     * sometime" and the rail's gate - last aired episode watched - has nothing
     * to test.
     *
     * Three gates and one soft failure, all of them states rather than errors:
     *
     *  - not [isReady] (unconfigured, no token, no mapped profile) -> null, no
     *    request;
     *  - a grant without `history:read` -> null, no request, because the 403 it
     *    would earn says nothing a reconnect prompt does not already say;
     *  - a failed page (network, 401 after one refresh, 429, any non-2xx) ->
     *    null. A PARTIAL timeline is worse than none here: a show whose plays
     *    are on the page that failed would read as not caught up, and the rail
     *    would hide a card the evidence supports - the field report's own
     *    failure mode, arriving from the other direction.
     *
     * Costs the account-wide read the badges already make, on the same route,
     * and no new cache: the caller asks once per rail build and folds it.
     */
    suspend fun watchedHistorySnapshot(): BingeCatWatchedSnapshot? {
        if (!isReady()) return null

        if (!BingeCatWriteRules.hasHistoryReadScope(store.scope())) return null

        val mapped = mappedProfileId() ?: return null

        val items = mutableListOf<BingeCatHistoryItem>()
        var cursor: String? = null
        var pages = 0
        // m24: true until the API says there is no next page. A cursor that
        // stopped moving, or the MAX_PAGES ceiling, both leave the tail unread -
        // and this snapshot decides which cards show as watched.
        var truncated = true

        while (pages < MAX_PAGES) {
            val body = authorizedBody { auth ->
                service.history(
                    authorization = auth,
                    profileId = mapped,
                    cursor = cursor,
                    limit = PAGE_SIZE
                )
            } ?: return null

            items += body.items

            val next = body.nextCursor?.takeIf { it.isNotBlank() }
            if (next == null) {
                truncated = false
                break
            }
            // A cursor that does not move would spin the loop on the same page;
            // MAX_PAGES bounds it either way, but stopping is the honest answer.
            if (next == cursor) break
            cursor = next
            pages++
        }

        // m24: the same rule as the other walks - a truncated history is not
        // the history, so it is refused rather than served as complete.
        if (truncated) return null
        return bingeCatWatchedSnapshot(items)
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
                // A 401 refuses the ACCESS token, not the grant: keep the pair and
                // flag a reconnect, so the state says so and nothing prompts for it.
                store.markNeedsReconnect()
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
                    // A 401 refuses the ACCESS token, not the grant: keep the pair and
                    // flag a reconnect, so the state says so and nothing prompts for it.
                    store.markNeedsReconnect()
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
                // A 401 refuses the ACCESS token, not the grant: keep the pair and
                // flag a reconnect, so the state says so and nothing prompts for it.
                store.markNeedsReconnect()
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
                // A 401 refuses the ACCESS token, not the grant: keep the pair and
                // flag a reconnect, so the state says so and nothing prompts for it.
                store.markNeedsReconnect()
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
                // A 401 refuses the ACCESS token, not the grant: keep the pair and
                // flag a reconnect, so the state says so and nothing prompts for it.
                store.markNeedsReconnect()
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

    /**
     * A usable access token, refreshing it first when it is close to expiry.
     *
     * Null means "BingeCat cannot be called with what this profile holds", and
     * every null is reported as the state it is: a refusal (no session, no
     * refresh token, a server-rejected refresh) marks the store as needing a
     * reconnect, so the connect flow is offered instead of the app quietly
     * painting nothing (spec F2). An EXPIRED access token is never handed out:
     * the old code returned it when there was no refresh token, which earned a
     * 401 and then deleted the whole pair - the field report's `no-token` with
     * the mapping still in place.
     */
    private suspend fun ensureFreshToken(force: Boolean = false): String? {
        val current = store.read()
        // Nothing to call with, or a pair the server already refused: no request
        // is worth making, and the honest answer is "reconnect".
        if (current == null || store.needsReconnect()) {
            refuseSession()
            return null
        }
        val now = System.currentTimeMillis()
        if (!force && current.expiresAtEpochMillis - now > EXPIRY_SKEW_MS) {
            return current.accessToken
        }
        val refreshToken = current.refreshToken
        if (refreshToken.isNullOrBlank()) {
            // Expired with no way to renew it from here. The token is still
            // offered ONCE: BingeCat can accept a token a clock-skewed expiry
            // calls dead, and a grant that never carried a refresh token has no
            // other way to work. What made this destructive was the 401 path
            // DELETING the pair - that now flags a reconnect instead, so a token
            // the server really has stopped accepting ends in the honest state
            // rather than in `no-token` with the mapping still in place.
            return current.accessToken
        }
        return refreshMutex.withLock {
            val latest = store.read()
            if (latest == null || store.needsReconnect()) {
                refuseSession()
                return@withLock null
            }
            if (!force && latest.expiresAtEpochMillis - now > EXPIRY_SKEW_MS) {
                return@withLock latest.accessToken
            }
            val latestRefresh = latest.refreshToken
            if (latestRefresh.isNullOrBlank()) {
                // The same rule as the check before the lock: a pair with no
                // refresh token (raced away, or a grant that never carried one)
                // is offered once, and the server's own answer is the verdict.
                return@withLock latest.accessToken
            }
            val response = runCatchingCancellable {
                service.refreshToken(
                    BingeCatRefreshTokenRequest(
                        refreshToken = latestRefresh,
                        clientId = BingeCatConfig.clientId
                    )
                )
            }.getOrNull()
            // A transport failure is not a verdict on the grant: the pair stays
            // and only this attempt fails, so a later call can still refresh.
            if (response == null) {
                BingeCatTrace.recordError("refresh-failed")
                return@withLock null
            }
            val granted = response.body()
            // Locals, because a property of another module's class cannot be
            // smart-cast (the same rule the device-code path follows).
            val access = granted?.accessToken?.takeIf { it.isNotBlank() }
            if (!response.isSuccessful || access == null) {
                // The server answered and refused (invalid_grant on a rotated
                // refresh token, a revoked grant, a 401): the pair cannot be
                // revived here, so it is flagged for the connect flow rather
                // than retried on every read.
                BingeCatTrace.recordError("refresh-failed")
                refuseSession()
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
            // Persisted BEFORE the token is returned: a refresh that rotated
            // the pair and never saved it leaves the next call holding a
            // refresh token BingeCat has already consumed.
            runCatching { store.save(token) }.getOrNull() ?: return@withLock null
            // A refresh ROTATES the pair (and may invalidate the old refresh
            // token), so the account copy is now stale: push the new one or a
            // sibling is signed out as soon as its own copy expires.
            publishAuthToSync()
            token.accessToken
        }
    }

    /**
     * The honest report of a session that cannot call BingeCat.
     *
     * The store keeps its pair (see [BingeCatTokenStore.markNeedsReconnect]) and
     * this records the state the viewer needs: not connected, reconnect needed.
     * The reason stays ["no-token"] so the diagnostics line names the same cause
     * the gates answered with.
     */
    private fun refuseSession() {
        // Only a STORED pair can be "refused". A profile that never connected
        // has nothing to refuse, and must not be given a marker that would then
        // ask it to reconnect out of a clean "not connected" state.
        if (store.read() != null) store.markNeedsReconnect()
        cached = null
        val reconnect = needsReconnect()
        BingeCatTrace.recordSession(false)
        BingeCatTrace.recordNeedsReconnect(reconnect)
        BingeCatTrace.recordNeedsReapproval(reconnect)
        BingeCatTrace.recordError("no-token")
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
