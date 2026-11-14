package com.kennyb1201.kbstream.data.anime

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * AniList's connect and session (spec 2026-11-13 phase 2, F1).
 *
 * THE FLOW IS THE AUTH-PIN IMPLICIT GRANT, deliberately:
 *
 *  1. the app shows an authorize URL (`response_type=token`, the redirect
 *     registered on the app being AniList's own pin page),
 *  2. the viewer opens it on a phone - the TV shows the same URL as text, since
 *     it has no browser - approves on AniList, and lands on a page with an
 *     ACCESS TOKEN,
 *  3. the token is pasted into the app, validated with `Viewer { id name }`, and
 *     stored in this profile's encrypted store.
 *
 * There is no client secret and no token exchange anywhere in it. AniList's
 * Authorization Code grant requires the app's secret in the exchange, and a
 * secret compiled into an APK is extractable from every published build - the
 * rule `SimklClientConfigTest` enforces for every tracker. AniList's own docs
 * route apps "where your client credentials and secret cannot be securely
 * stored" to the implicit grant, which is what a native app is.
 *
 * NO REFRESH. AniList issues no refresh token: a token is a JWT good for about a
 * year, its own `exp` claim is the deadline, and when it passes (or a 401 says it
 * is dead) the connection is marked [AniListConnectionState.NeedsReconnect] and
 * every write stops until the viewer pastes a new one. Nothing retries in a loop.
 */
internal object AniListAuth {

    private const val TAG = "AniListAuth"

    /** What one connect attempt produced. */
    sealed interface ConnectResult {
        data object Connected : ConnectResult

        /**
         * [reason] is for the connect dialog only. It never contains the pasted
         * value: the pasted value IS the credential.
         */
        data class Failed(val reason: String) : ConnectResult
    }

    /** The authorize URL to show, or null when this build has no client id. */
    fun authorizeUrl(): String? = AniListConfig.authorizeUrl()

    /**
     * Validates a pasted token and, when AniList accepts it, stores the session.
     *
     * Validation is a real query with the token (`Viewer`), not a shape check: a
     * token that AniList refuses is a reconnect the viewer would otherwise
     * discover one write at a time. A connection that validates but whose name
     * cannot be read still connects - the identity is decoration on the row.
     */
    suspend fun connect(context: Context, pasted: String): ConnectResult {
        val token = AniListWriteRules.normalizePastedToken(pasted)
            ?: return ConnectResult.Failed(
                "That does not look like an AniList token. Approve the app, then copy " +
                    "the whole token from the page it shows."
            )

        val viewer = AniListClient.viewer(token)
            ?: return ConnectResult.Failed("AniList did not accept that token.")

        val store = AniListTokenStore(context)
        store.save(
            AniListToken(
                accessToken = token,
                expiresAtMs = AniListWriteRules.expiryFromToken(token, System.currentTimeMillis()),
                viewerId = viewer.optInt("id", 0).takeIf { it > 0 },
                viewerName = viewer.optString("name").takeIf { it.isNotBlank() }
            )
        )

        // The session travels now, not on the next bulk push: a sibling should
        // adopt it without the viewer pasting a token on every device. There is
        // no refresh half to drive a later publish, so this is the only moment
        // the new grant reaches the account.
        if (store.read() != null) publishAuthToSync(context)

        Log.i(TAG, "AniList connected for this profile")
        return ConnectResult.Connected
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
     * "grant" marker: a token pasted after a reconnect is a different grant, and
     * that is what re-opens a leg a 401 closed.
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
