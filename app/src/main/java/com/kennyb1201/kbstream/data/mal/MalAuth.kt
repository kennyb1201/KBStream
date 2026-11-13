package com.kennyb1201.kbstream.data.mal

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * MAL's OAuth client: the half of the connect that touches the network.
 *
 * MAL has no device flow, so this is Authorization Code + PKCE driven by a
 * browser (see [MalAuthRules]). The pieces that decide the flow's SHAPE are pure
 * and live beside this file; what is here is the HTTP and the storage.
 *
 * The token endpoint is called twice in this app's life, both here: once for the
 * code exchange, and once per refresh when an access token (one hour) dies. The
 * refresh is single-flight - a Home load can have several MAL reads in flight
 * when the hour turns, and each would otherwise spend the single-use refresh
 * token and strand the session; one caller refreshes and the rest wait for it.
 */
internal object MalAuth {

    private const val TAG = "MalAuth"

    private const val TIMEOUT_MS = 12_000L

    private const val FORM = "application/x-www-form-urlencoded"

    private val httpClient = BaseHttpClient.derived {
        callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** Serializes refresh: one network exchange, every waiter adopts its result. */
    private val refreshMutex = Mutex()

    /** What one connect attempt produced. */
    sealed interface ConnectResult {
        data object Connected : ConnectResult

        /** Not configured, or no flow was started: nothing to exchange. */
        data class Failed(val reason: String) : ConnectResult
    }

    /**
     * Starts a connect: a fresh verifier/challenge/state, remembered on this
     * profile, and the URL the browser opens.
     *
     * The pending values are stored BEFORE the URL is handed out. The redirect
     * can arrive in a fresh process (the browser was in front when Android
     * reclaimed this app), and the exchange needs the verifier the challenge was
     * made from - holding it only in memory would lose the flow exactly when the
     * viewer did everything right.
     */
    fun beginConnect(context: Context): String? {
        if (!MalConfig.isConfigured) return null

        val verifier = malCodeVerifier()
        val state = malState()
        MalTokenStore(context).savePendingFlow(verifier, state)

        return malAuthorizeUrl(
            clientId = MalConfig.clientId,
            challenge = malCodeChallenge(verifier),
            state = state
        )
    }

    /**
     * Finishes a connect from the redirect this app received.
     *
     * The state is checked against the one this profile generated, then the code
     * is exchanged with the same verifier. A refusal, a mismatched state and a
     * failed exchange all leave the connection where it was.
     */
    suspend fun completeConnect(context: Context, rawRedirect: String?): ConnectResult {
        if (!MalConfig.isConfigured) return ConnectResult.Failed("MAL is not configured in this build")

        val store = MalTokenStore(context)
        val outcome = malRedirectOutcome(rawRedirect, store.pendingState())

        when (outcome) {
            is MalRedirect.Code -> Unit
            is MalRedirect.Refused -> {
                store.clearPendingFlow()
                return ConnectResult.Failed("MAL authorization was refused (${outcome.error})")
            }
            is MalRedirect.Ignored -> return ConnectResult.Failed(outcome.reason)
        }

        val code = (outcome as MalRedirect.Code).code
        val verifier = store.pendingVerifier()
            ?: return ConnectResult.Failed("no PKCE verifier for this connect")

        val token = exchange(
            malCodeExchangeForm(
                clientId = MalConfig.clientId,
                code = code,
                verifier = verifier
            )
        ) ?: return ConnectResult.Failed("MAL did not issue a token")

        store.save(token)
        store.clearPendingFlow()
        Log.i(TAG, "MAL connected for this profile")
        return ConnectResult.Connected
    }

    /**
     * A usable access token, refreshed when the stored one has died.
     *
     * Returns null when there is no session, MAL is not configured, or the grant
     * is dead - in the last case the connection is marked [MalConnectionState.NeedsReconnect]
     * and nothing calls MAL again until the viewer reconnects. That marker is
     * the difference between "the token expired" and "keep trying forever".
     */
    suspend fun accessToken(context: Context, forceRefresh: Boolean = false): String? {
        if (!MalConfig.isConfigured) return null

        val store = MalTokenStore(context)
        if (store.needsReconnect()) return null

        val current = store.read() ?: return null
        if (!forceRefresh && current.expiresAtMs > System.currentTimeMillis()) {
            return current.accessToken
        }

        return refreshMutex.withLock {
            // Another waiter may have refreshed while this one queued.
            val fresh = MalTokenStore(context).read()
            if (
                fresh != null &&
                fresh.accessToken != current.accessToken &&
                fresh.expiresAtMs > System.currentTimeMillis()
            ) {
                return@withLock fresh.accessToken
            }

            val refreshToken = fresh?.refreshToken ?: store.refreshToken()
            if (refreshToken.isNullOrBlank()) {
                markNeedsReconnect(context, "no refresh token")
                return@withLock null
            }

            val refreshed = exchange(malRefreshForm(MalConfig.clientId, refreshToken), refreshToken)
            if (refreshed == null) {
                markNeedsReconnect(context, "refresh failed")
                return@withLock null
            }

            MalTokenStore(context).save(refreshed)
            refreshed.accessToken
        }
    }

    /** Settings → Trackers → MyAnimeList → Disconnect. Local data is untouched. */
    fun disconnect(context: Context) {
        MalTokenStore(context).markSignedOut()
        Log.i(TAG, "MAL disconnected for this profile")
    }

    fun connectionState(context: Context): MalConnectionState {
        val store = MalTokenStore(context)
        return malConnectionState(
            configured = MalConfig.isConfigured,
            hasSession = store.hasSession(),
            needsReconnect = store.needsReconnect()
        )
    }

    private suspend fun markNeedsReconnect(context: Context, reason: String) {
        MalTokenStore(context).setNeedsReconnect(true)
        Log.w(TAG, "MAL needs reconnect: $reason")
    }

    /** One token-endpoint POST. Null on any failure: the caller decides what it means. */
    private suspend fun exchange(form: String, previousRefresh: String? = null): MalToken? =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url(MalConfig.TOKEN_URL)
                    .header("User-Agent", MalConfig.USER_AGENT)
                    .header("Accept", "application/json")
                    .post(form.toRequestBody(FORM.toMediaType()))
                    .build()

            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "MAL token endpoint answered ${response.code}")
                        return@use null
                    }
                    malParseToken(body, System.currentTimeMillis(), previousRefresh)
                }
            }.getOrElse { error ->
                Log.w(TAG, "MAL token request failed: ${error.javaClass.simpleName}")
                null
            }
        }
}
