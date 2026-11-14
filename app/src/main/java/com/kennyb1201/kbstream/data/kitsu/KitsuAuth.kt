package com.kennyb1201.kbstream.data.kitsu

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
import org.json.JSONObject

/**
 * Kitsu's OAuth client: the resource-owner password grant, and the refresh that
 * keeps a 30-day session alive (spec 2026-11-13 phase 2, F2).
 *
 * WHY THE PASSWORD GRANT: Kitsu has no device flow and no browser-based grant
 * that a TV can drive. The password grant is what every native Kitsu client uses
 * (Mihon, Suwayomi), it is pure text fields - identical on phone and TV, no
 * browser, no redirect, no QR - and Kitsu's own API docs document it.
 *
 * THE PASSWORD IS USED ONCE. It exists in this function's frame for the length of
 * one request and is then dropped: never stored, never logged, never synced,
 * never written anywhere. The [KitsuToken] that replaces it is the only thing
 * that survives.
 *
 * The refresh is SINGLE-FLIGHT. A Home load can have several Kitsu reads in
 * flight when the 30 days turn, and each would otherwise spend the refresh token
 * and strand the session; one caller refreshes and the rest adopt its result.
 * Kitsu rotates the refresh token on use, so a second concurrent refresh is not
 * merely wasteful - it invalidates the first.
 */
internal object KitsuAuth {

    private const val TAG = "KitsuAuth"

    private const val FORM = "application/x-www-form-urlencoded"

    private val httpClient = BaseHttpClient.derived {
        callTimeout(KitsuConfig.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** Serializes refresh: one network exchange, every waiter adopts its result. */
    private val refreshMutex = Mutex()

    /** What one connect attempt produced. */
    sealed interface ConnectResult {
        data object Connected : ConnectResult

        /**
         * [reason] is for the connect dialog only - it is the sentence the viewer
         * reads when an email or password was refused. It never contains either.
         */
        data class Failed(val reason: String) : ConnectResult
    }

    /**
     * Exchanges an email and a password for this profile's session.
     *
     * The viewer id is resolved with the new token immediately, because every
     * library write is addressed by it (`filter[userId]`) and a session without
     * one could not write at all. A failure to read it does not fail the
     * connect: the token is saved first, and the id is resolved lazily by
     * [viewerId] on the first write.
     */
    suspend fun connect(context: Context, email: String, password: String): ConnectResult {
        val user = email.trim()
        if (user.isEmpty() || password.isEmpty()) {
            return ConnectResult.Failed("Enter your Kitsu email and password")
        }

        val token = exchange(
            KitsuWriteRules.passwordForm(user, password, KitsuConfig.clientId)
        ) ?: return ConnectResult.Failed("Kitsu did not accept that email and password")

        val store = KitsuTokenStore(context)
        store.save(token)

        // Best effort, and after the save: a failed identity read costs the
        // viewer nothing, because the first write resolves it again.
        val viewerId = fetchViewerId(token.accessToken)
        if (viewerId != null) store.saveViewerId(viewerId)

        // The session travels now, not on the next bulk push: a sibling should
        // adopt it without waiting for a full sync. The read-back is the
        // "the save actually happened" check - an unwritable store must publish
        // nothing.
        if (store.read() != null) publishAuthToSync(context)

        Log.i(TAG, "Kitsu connected for this profile")
        return ConnectResult.Connected
    }

    /**
     * A usable access token, refreshed when the stored one is spent.
     *
     * Null when there is no session or the grant is dead - and in the last case
     * the connection is marked [needsReconnect], which is the difference between
     * "the token expired" and "keep trying forever". A 401 on a WRITE also comes
     * through here with [forceRefresh] set, so a grant revoked early is
     * refreshed once and then declared dead rather than retried in a loop.
     */
    suspend fun accessToken(context: Context, forceRefresh: Boolean = false): String? {
        val store = KitsuTokenStore(context)
        if (store.needsReconnect()) return null

        val current = store.read() ?: return null
        if (!forceRefresh && current.isUsable(System.currentTimeMillis())) {
            return current.accessToken
        }

        return refreshMutex.withLock {
            // Another waiter may have refreshed while this one queued; Kitsu
            // rotates the refresh token, so adopting theirs is the only correct
            // answer.
            val fresh = KitsuTokenStore(context).read()
            if (
                fresh != null &&
                fresh.accessToken != current.accessToken &&
                fresh.isUsable(System.currentTimeMillis())
            ) {
                return@withLock fresh.accessToken
            }

            val refreshToken = fresh?.refreshToken ?: store.refreshToken()
            if (refreshToken.isNullOrBlank()) {
                markNeedsReconnect(context, "no refresh token")
                return@withLock null
            }

            val refreshed = exchange(
                KitsuWriteRules.refreshForm(refreshToken, KitsuConfig.clientId),
                previousRefresh = refreshToken
            )
            if (refreshed == null) {
                markNeedsReconnect(context, "refresh failed")
                return@withLock null
            }

            KitsuTokenStore(context).save(
                refreshed.copy(
                    viewerId = refreshed.viewerId ?: fresh?.viewerId ?: store.viewerId(),
                    viewerName = refreshed.viewerName ?: fresh?.viewerName
                )
            )
            // A refresh ROTATES the single-use refresh token, so the account
            // copy is stale until this lands: a sibling left holding the
            // consumed token is stranded at its next expiry.
            publishAuthToSync(context)
            refreshed.accessToken
        }
    }

    /**
     * The viewer's Kitsu user id, resolved once and remembered.
     *
     * `GET /users?filter[self]=true` under the profile's own token - the only
     * authenticated identity read Kitsu offers, and the id every library write
     * needs. Null when there is no session or the call failed.
     */
    suspend fun viewerId(context: Context): String? {
        val store = KitsuTokenStore(context)
        store.viewerId()?.let { return it }

        val token = accessToken(context) ?: return null
        val id = fetchViewerId(token) ?: return null
        store.saveViewerId(id)
        return id
    }

    /**
     * The stored access token, with no refresh attempt.
     *
     * The taste queue's per-leg "grant" marker: a rotated token is a different
     * grant, which is what re-opens a leg a 401 closed without asking the viewer
     * to reconnect (see TasteRatingSubmission.currentGrant).
     */
    fun accessTokenSnapshot(context: Context): String? =
        KitsuTokenStore(context).read()?.accessToken?.takeIf { it.isNotBlank() }

    /** Settings → Trackers → Kitsu → Disconnect. Local data is untouched. */
    fun disconnect(context: Context) {
        KitsuTokenStore(context).markSignedOut()
        // The tombstone has no token to trip the bulk push's gate, so it is
        // published here - otherwise the sibling keeps writing under a grant
        // the viewer took back.
        publishAuthToSync(context)
        Log.i(TAG, "Kitsu disconnected for this profile")
    }

    /**
     * Publishes this profile's Kitsu session immediately, bypassing the bulk
     * push's "nothing to publish" skip (spec 2026-11-13, F3).
     *
     * Wrapped so a sync failure can never fail the connect, refresh or
     * disconnect it follows: the local session is already saved, and the next
     * bulk push is the fallback.
     */
    private fun publishAuthToSync(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_KITSU_AUTH,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildKitsuAuth(appContext)
            )
        }
    }

    fun connectionState(context: Context): KitsuConnectionState {
        val store = KitsuTokenStore(context)
        return when {
            !KitsuConfig.isConfigured -> KitsuConnectionState.NotConfigured
            store.needsReconnect() -> KitsuConnectionState.NeedsReconnect
            store.hasSession() -> KitsuConnectionState.Connected
            else -> KitsuConnectionState.Disconnected
        }
    }

    /**
     * Works out the connection state without a Context, for the UI's own text.
     * Kept pure so the four states cannot drift from their copy.
     */
    fun connectionState(hasSession: Boolean, needsReconnect: Boolean, configured: Boolean): KitsuConnectionState =
        when {
            !configured -> KitsuConnectionState.NotConfigured
            needsReconnect -> KitsuConnectionState.NeedsReconnect
            hasSession -> KitsuConnectionState.Connected
            else -> KitsuConnectionState.Disconnected
        }

    private fun markNeedsReconnect(context: Context, reason: String) {
        KitsuTokenStore(context).setNeedsReconnect(true)
        Log.w(TAG, "Kitsu needs reconnect: $reason")
    }

    /** One token-endpoint POST. Null on any failure: the caller decides what it means. */
    private suspend fun exchange(form: String, previousRefresh: String? = null): KitsuToken? =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url(KitsuConfig.TOKEN_URL)
                    .header("User-Agent", KitsuConfig.USER_AGENT)
                    .header("Accept", "application/json")
                    .post(form.toRequestBody(FORM.toMediaType()))
                    .build()

            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // The status only: Kitsu's error body can echo the
                        // request, and that request carries the password.
                        Log.w(TAG, "Kitsu token endpoint answered ${response.code}")
                        return@use null
                    }
                    parseToken(body, System.currentTimeMillis(), previousRefresh)
                }
            }.getOrElse { error ->
                Log.w(TAG, "Kitsu token request failed: ${error.javaClass.simpleName}")
                null
            }
        }

    /**
     * One token response → [KitsuToken].
     *
     * `expires_in` is seconds from `created_at` when Kitsu sends one, and the
     * deadline is skewed back by [KitsuConfig.EXPIRY_SKEW_MS] so a request never
     * races the expiry. A response with no access token is not a session.
     */
    internal fun parseToken(body: String, nowMs: Long, previousRefresh: String?): KitsuToken? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val access = json.optString("access_token").trim()
        if (access.isEmpty()) return null

        val expiresIn = json.optLong("expires_in", 0L)
        val created = json.optLong("created_at", 0L)
        val issuedAtMs = if (created > 0L) created * 1_000L else nowMs
        val expiresAtMs =
            if (expiresIn > 0L) {
                (issuedAtMs + expiresIn * 1_000L - KitsuConfig.EXPIRY_SKEW_MS)
            } else {
                // No stated lifetime: treat it as good for the skew window and
                // let the first 401 ask for a refresh. Never "never expires".
                nowMs + KitsuConfig.EXPIRY_SKEW_MS
            }

        return KitsuToken(
            accessToken = access,
            // A refresh rotation that omits the new refresh token keeps the one
            // it was called with (Kitsu may answer without it).
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }
                ?: previousRefresh,
            expiresAtMs = expiresAtMs
        )
    }

    /** `GET /users?filter[self]=true` under [accessToken]. Null on any failure. */
    private suspend fun fetchViewerId(accessToken: String): String? =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url(KitsuConfig.API_BASE + "/users?filter[self]=true")
                    .header("User-Agent", KitsuConfig.USER_AGENT)
                    .header("Accept", KitsuConfig.JSON_API)
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string().orEmpty())
                    json.optJSONArray("data")
                        ?.optJSONObject(0)
                        ?.optString("id")
                        ?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }
}
