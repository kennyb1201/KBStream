package com.kennyb1201.kbstream.data.anime

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * AniList's connect and session (spec 2026-11-13 phase 2, F1; rewritten by spec
 * 2026-11-14, F3).
 *
 * THE FLOW IS THE AUTHORIZATION CODE GRANT, driven by a browser:
 *
 *  1. a flow started HERE stores a random `state` and opens
 *     [AniListConnectRules.authorizeUrl] (`response_type=code`, the app's own
 *     `kbstream://oauth/anilist` redirect),
 *  2. the viewer approves on AniList, which sends the browser back to this app
 *     with a one-time `code` (and the state, when this device generated one),
 *  3. [completeConnect] verifies the state, exchanges the code with the app's
 *     bundled secret, validates the resulting token with `Viewer { id name }`,
 *     stores the session and publishes it to sync.
 *
 * The old implicit grant is why this file used to say "there is no client secret
 * and no token exchange anywhere in it". AniList removed that grant - its
 * authorize endpoint answers `unsupported_grant_type` for `response_type=token` -
 * so the paste-a-token flow it fed is gone, and the secret is now bundled by
 * Kenny's decision of 2026-11-14 (see [AniListConfig]).
 *
 * NO REFRESH. AniList issues no refresh token: a token is a JWT good for about a
 * year, its own `exp` claim is the deadline, and when it passes (or a 401 says it
 * is dead) the connection is marked [AniListConnectionState.NeedsReconnect] and
 * every write stops until the viewer reconnects. Nothing retries in a loop.
 *
 * THE SECRET LIVES IN EXACTLY ONE PLACE: the form body of the exchange POST. It
 * is never in a URL, never logged, never in a diagnostic paste and never in a
 * sync payload, and the failure copy comes from AniList's own response, never from
 * the request.
 */
internal object AniListAuth {

    private const val TAG = "AniListAuth"

    private const val TIMEOUT_MS = 12_000L

    private const val FORM = "application/x-www-form-urlencoded"

    private const val NOT_CONFIGURED_MESSAGE =
        "This build cannot connect AniList: it needs the app id and the app secret."

    private val httpClient = BaseHttpClient.derived {
        callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** What one connect attempt produced. */
    sealed interface ConnectResult {
        data object Connected : ConnectResult

        /**
         * [reason] is for the connect dialog only. It never contains anything the
         * viewer typed or the app's secret: the typed value IS a credential, and
         * the secret is the build's.
         */
        data class Failed(val reason: String) : ConnectResult
    }

    /** One exchange attempt, before AniList's own answer is interpreted. */
    private sealed interface Exchange {
        data class Token(val value: String) : Exchange

        data class Failed(val reason: String) : Exchange
    }

    /**
     * The URL to SHOW - a TV prints it and the QR encodes it - or null when this
     * build cannot connect.
     *
     * No state: the device that approves this link in a browser is the device
     * that receives the code, and it never generated this one's state (see
     * [AniListConnectRules.redirectOutcome]).
     */
    fun authorizeUrl(): String? = AniListConfig.advertisedAuthorizeUrl()

    /**
     * Starts a connect on THIS device: a fresh state, remembered on this profile,
     * and the URL the browser should open.
     *
     * The state is stored BEFORE the URL is handed out. The redirect can arrive in
     * a fresh process (the browser was in front when Android reclaimed this app),
     * and a flow that forgot its state would refuse its own callback.
     */
    fun beginConnect(context: Context): String? {
        if (!AniListConfig.canConnect) return null
        val state = AniListConnectRules.state()
        AniListTokenStore(context).savePendingState(state)
        return AniListConfig.authorizeUrl(state)
    }

    /**
     * Finishes a connect from the redirect this app received.
     *
     * The state is checked against the one this profile generated (when it
     * generated one), then the code is exchanged. A refusal, an unusable link and
     * a failed exchange all leave the connection where it was and say why.
     */
    suspend fun completeConnect(context: Context, rawRedirect: String?): ConnectResult {
        if (!AniListConfig.canConnect) return ConnectResult.Failed(NOT_CONFIGURED_MESSAGE)

        val store = AniListTokenStore(context)
        return when (val outcome = AniListConnectRules.redirectOutcome(rawRedirect, store.pendingState())) {
            is AniListRedirect.Code -> finishConnect(context, outcome.code)
            is AniListRedirect.Refused -> {
                // The viewer said no (or the app registration is wrong): no code
                // is coming, so the pending flow is over.
                store.clearPendingState()
                ConnectResult.Failed("AniList authorization was refused (${outcome.error}).")
            }
            is AniListRedirect.Ignored -> ConnectResult.Failed(
                "AniList sent back a link this app cannot use (${outcome.reason}). " +
                    "Open the link again and approve KBStream."
            )
        }
    }

    /**
     * The connect field's fallback path: the code AniList sent back, pasted by
     * hand, or the whole redirect URL (which is parsed exactly like a callback).
     *
     * A TOKEN is refused with its own sentence: the old flow handed one out, and
     * sending it to the exchange would fail at AniList with a message about a bad
     * code rather than about the stale instruction.
     */
    suspend fun connect(context: Context, pasted: String): ConnectResult {
        if (!AniListConfig.canConnect) return ConnectResult.Failed(NOT_CONFIGURED_MESSAGE)
        return when (val value = AniListConnectRules.pastedValue(pasted)) {
            is AniListPastedValue.Code -> finishConnect(context, value.code)
            is AniListPastedValue.Redirect -> completeConnect(context, value.url)
            AniListPastedValue.Token ->
                ConnectResult.Failed(AniListConnectRules.TOKEN_PASTED_MESSAGE)
            AniListPastedValue.Missing ->
                ConnectResult.Failed(AniListConnectRules.MISSING_CODE_MESSAGE)
        }
    }

    /**
     * Exchanges [code], validates the token, stores it and publishes it.
     *
     * Nothing is written until AniList has both issued a token and accepted it -
     * a half-written session would show Connected for a grant every write refuses.
     */
    private suspend fun finishConnect(context: Context, code: String): ConnectResult {
        val store = AniListTokenStore(context)
        val token = when (val exchange = exchange(code)) {
            is Exchange.Token -> exchange.value
            is Exchange.Failed -> return ConnectResult.Failed(exchange.reason)
        }

        val viewer = AniListClient.viewer(token)
            ?: return ConnectResult.Failed(
                "AniList issued a token but would not accept it. Approve the app again."
            )

        store.save(
            AniListToken(
                accessToken = token,
                expiresAtMs = AniListWriteRules.expiryFromToken(token, System.currentTimeMillis()),
                viewerId = viewer.optInt("id", 0).takeIf { it > 0 },
                viewerName = viewer.optString("name").takeIf { it.isNotBlank() }
            )
        )
        store.clearPendingState()

        // The session travels now, not on the next bulk push: a sibling should
        // adopt it without the viewer approving on every device. There is no
        // refresh half to drive a later publish, so this is the only moment the
        // new grant reaches the account.
        if (store.read() != null) publishAuthToSync(context)

        Log.i(TAG, "AniList connected for this profile")
        return ConnectResult.Connected
    }

    /** The one POST that carries the secret. Null fields never reach a log line. */
    private suspend fun exchange(code: String): Exchange =
        withContext(Dispatchers.IO) {
            val secret = AniListConfig.clientSecret
            if (secret.isBlank()) return@withContext Exchange.Failed(NOT_CONFIGURED_MESSAGE)

            val form = AniListConnectRules.codeExchangeForm(
                clientId = AniListConfig.clientId,
                clientSecret = secret,
                code = code
            )

            val request =
                Request.Builder()
                    .url(ANILIST_TOKEN_URL)
                    .header("User-Agent", AniListClient.USER_AGENT)
                    .header("Accept", "application/json")
                    .post(form.toRequestBody(FORM.toMediaType()))
                    .build()

            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // The status and AniList's own sentence - never the body
                        // that was sent, which is where the secret is.
                        Log.w(TAG, "AniList token endpoint answered ${response.code}")
                        return@use Exchange.Failed(
                            AniListConnectRules.exchangeFailure(body, response.code)
                        )
                    }
                    val token = AniListConnectRules.tokenFromExchange(body)
                    if (token == null) {
                        Exchange.Failed("AniList answered HTTP ${response.code} with no token.")
                    } else {
                        Exchange.Token(token)
                    }
                }
            }.getOrElse { error ->
                Log.w(TAG, "AniList token request failed: ${error.javaClass.simpleName}")
                Exchange.Failed(
                    "Could not reach AniList to exchange the code. Check the connection " +
                        "and try again."
                )
            }
        }

    /**
     * A usable access token, or null.
     *
     * Null means "no session" or "the session is spent". The second case marks the
     * connection needs-reconnect - an expired JWT is not a temporary failure, and
     * retrying it is how a dead leg becomes a loop. This is also the path a write's
     * 401 takes ([markNeedsReconnect]).
     */
    suspend fun accessToken(context: Context): String? {
        val store = AniListTokenStore(context)
        if (store.needsReconnect()) return null

        val current = store.read() ?: return null
        if (!current.isUsable(System.currentTimeMillis())) {
            markNeedsReconnect(context, "token expired")
            return null
        }
        return current.accessToken
    }

    /**
     * The stored token with no expiry verdict, for the taste queue's per-leg
     * "grant" marker: a token from a new connect is a different grant, and that
     * is what re-opens a leg a 401 closed.
     */
    fun accessTokenSnapshot(context: Context): String? =
        AniListTokenStore(context).read()?.accessToken?.takeIf { it.isNotBlank() }

    /** A 401/403 (or an expired JWT) from any AniList call. */
    fun markNeedsReconnect(context: Context, reason: String) {
        AniListTokenStore(context).setNeedsReconnect(true)
        Log.w(TAG, "AniList needs reconnect: $reason")
    }

    /** Settings → Trackers → AniList → Disconnect. Local data is untouched. */
    fun disconnect(context: Context) {
        AniListTokenStore(context).markSignedOut()
        // The tombstone has no token to trip the bulk push's gate, so it is
        // published here - otherwise the sibling keeps writing under a grant
        // the viewer took back.
        publishAuthToSync(context)
        Log.i(TAG, "AniList disconnected for this profile")
    }

    /**
     * Publishes this profile's AniList session immediately, bypassing the bulk
     * push's "nothing to publish" skip (spec 2026-11-13, F3).
     *
     * Wrapped so a sync failure can never fail the connect or the disconnect it
     * follows. There is no refresh call site: AniList issues no refresh grant, so
     * a connect is the only time a token can change (F7).
     */
    private fun publishAuthToSync(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_ANILIST_AUTH,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildAnilistAuth(appContext)
            )
        }
    }

    fun connectionState(context: Context): AniListConnectionState {
        val store = AniListTokenStore(context)
        val session = store.read()
        return when {
            !AniListConfig.isConfigured -> AniListConnectionState.NotConfigured
            store.needsReconnect() -> AniListConnectionState.NeedsReconnect
            session == null -> AniListConnectionState.Disconnected
            !session.isUsable(System.currentTimeMillis()) ->
                AniListConnectionState.NeedsReconnect
            else -> AniListConnectionState.Connected
        }
    }

    /**
     * The same four states without a Context, so the copy cannot drift between
     * the row and the dialog.
     */
    fun connectionState(
        configured: Boolean,
        hasSession: Boolean,
        needsReconnect: Boolean
    ): AniListConnectionState = when {
        !configured -> AniListConnectionState.NotConfigured
        needsReconnect -> AniListConnectionState.NeedsReconnect
        hasSession -> AniListConnectionState.Connected
        else -> AniListConnectionState.Disconnected
    }

    /** The row's second line: who is connected, when a name was learned. */
    fun viewerName(context: Context): String? =
        AniListTokenStore(context).read()?.viewerName?.takeIf { it.isNotBlank() }

    /** The `data` object of an authenticated call, or null. */
    internal fun dataOf(json: JSONObject): JSONObject? = json.optJSONObject("data")
}
