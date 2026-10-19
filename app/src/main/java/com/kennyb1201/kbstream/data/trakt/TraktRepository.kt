package com.kennyb1201.kbstream.data.trakt

import android.content.Context
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.kennyb1201.kbstream.data.reporting.NetworkTraceInterceptor
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
 * Phase A on purpose: the device-code grant, the proactive refresh, and the
 * disconnect. Scrobbling, the watched union and lists are separate phases and
 * land at their own call sites - this class holds no player or history code, so
 * nothing here can block playback.
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
    }
}
