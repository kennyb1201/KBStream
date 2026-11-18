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

        val token = when (
            val exchange = exchange(
                malCodeExchangeForm(
                    clientId = MalConfig.clientId,
                    code = code,
                    verifier = verifier
                )
            )
        ) {
            is MalExchange.Token -> exchange.token
            // The connect already reports its own failure; the marker is for a
            // session that DIED, not for a connect that never landed.
            MalExchange.Denied, MalExchange.Transient ->
                return ConnectResult.Failed("MAL did not issue a token")
        }

        store.save(token)
        store.clearPendingFlow()
        // The session travels now, not on the next bulk push: a sibling should
        // adopt it without re-running the browser flow. Only after the save
        // actually landed - an unwritable store publishes nothing.
        if (store.read() != null) publishAuthToSync(context)
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

            // A refresh that could not REACH MAL is not a dead grant (spec audit
            // 2026-11-18, Onboarding M3): the old null check marked the profile
            // NeedsReconnect on a timeout, a captive portal or a 5xx, which is a
            // connection the viewer had to re-authorise by hand over a network
            // blip. Only a refusal closes the grant; a transient failure answers
            // null for this call and leaves the session alone for the next one.
            val refreshed = when (
                val exchange = exchange(
                    malRefreshForm(MalConfig.clientId, refreshToken),
                    refreshToken
                )
            ) {
                is MalExchange.Token -> exchange.token
                MalExchange.Denied -> {
                    markNeedsReconnect(context, "refresh refused")
                    return@withLock null
                }
                MalExchange.Transient -> {
                    Log.w(TAG, "MAL refresh unanswered; keeping the session for the next call")
                    return@withLock null
                }
            }

            MalTokenStore(context).save(refreshed)
            // MAL's access token lives one hour and its refresh token is
            // single-use: the account copy is stale the moment this rotates, and
            // a sibling left holding the consumed token is stranded within the
            // hour (spec 2026-11-13, F7/S4).
            publishAuthToSync(context)
            refreshed.accessToken
        }
    }

    /** Settings → Trackers → MyAnimeList → Disconnect. Local data is untouched. */
    fun disconnect(context: Context) {
        val cleared = MalTokenStore(context).markSignedOut()
        // A tombstone for a session that SURVIVED would sign the account out on
        // every OTHER device while this one stayed connected, so nothing is
        // published unless the clear actually took (the Trakt disconnect rule).
        if (cleared) publishAuthToSync(context)
        Log.i(TAG, "MAL disconnected for this profile")
    }

    /**
     * Publishes this profile's MAL session immediately, bypassing the bulk
     * push's "nothing to publish" skip (spec 2026-11-13, F3).
     *
     * Wrapped so a sync failure can never fail the connect, refresh or
     * disconnect it follows.
     */
    private fun publishAuthToSync(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_MAL_AUTH,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildMalAuth(appContext)
            )
        }
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

    /**
     * What a token-endpoint POST settled: a token, a refusal, or nothing at all.
     *
     * The three arms exist because a REFUSED grant and an unanswered request
     * mean opposite things (spec audit 2026-11-18, Onboarding M3): one closes the
     * session until a reconnect, the other must leave it exactly as it was.
     */
    private sealed interface MalExchange {
        data class Token(val token: MalToken) : MalExchange

        /** MAL said no: a 4xx (`invalid_grant`, `invalid_client`, 401). */
        data object Denied : MalExchange

        /** Nothing was decided: transport, timeout, 429, 5xx. */
        data object Transient : MalExchange
    }

    /** One token-endpoint POST, classified by what the failure MEANT. */
    private suspend fun exchange(form: String, previousRefresh: String? = null): MalExchange =
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
                        // 4xx is a decision about the GRANT (invalid_grant,
                        // invalid_client, an expired code). 429 and 5xx are the
                        // service asking for time, not refusing the session.
                        return@use if (response.code in 400..499 && response.code != 429) {
                            MalExchange.Denied
                        } else {
                            MalExchange.Transient
                        }
                    }
                    malParseToken(body, System.currentTimeMillis(), previousRefresh)
                        ?.let { MalExchange.Token(it) }
                        ?: MalExchange.Transient
                }
            }.getOrElse { error ->
                Log.w(TAG, "MAL token request failed: ${error.javaClass.simpleName}")
                MalExchange.Transient
            }
        }
}
