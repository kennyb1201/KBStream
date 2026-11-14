package com.kennyb1201.kbstream.data.anime

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kennyb1201.kbstream.data.security.SecureTokenStore
import com.kennyb1201.kbstream.data.sync.ProfileStorage

/**
 * This profile's AniList session, encrypted at rest (spec 2026-11-13 phase 2, F1).
 *
 * Its own store name (`anilist_auth` → `<profileId>.anilist_auth`) for the reason
 * Trakt's, BingeCat's, MAL's and Kitsu's have theirs: two trackers sharing one
 * file would read each other's tokens, and the diagnostics address a session by
 * store name.
 *
 * WHAT IS NOT HERE: the pasted connect value. The PIN page's token goes straight
 * into the exchange that validates it and then into this store; nothing typed on
 * the connect screen is logged or written anywhere else. (For AniList that value
 * IS the credential, so storing it here is storing the session - there is no
 * password-shaped intermediate like Kitsu's.)
 *
 * The token has no refresh sibling, because AniList issues none: `expires_at_ms`
 * comes from the JWT's own `exp` claim, and when it passes the row says
 * needs-reconnect and every write stops until the viewer pastes a new token.
 * `signed_out` is the deliberate-disconnect tombstone, so a disconnect here is
 * visible to the account's other devices rather than silently re-connecting.
 */
internal class AniListTokenStore(
    private val context: Context?,
    /** A SPECIFIC profile's session for the cloud applier; null = the active one. */
    private val profileId: String? = null,
    private val openPrefs: (Context, String) -> SharedPreferences = { appContext, name ->
        SecureTokenStore.prefs(appContext, name, legacyPlaintext = false)
    }
) {

    companion object {
        private const val TAG = "AniListTokenStore"

        /** The logical name; [ProfileStorage.prefsName] adds the profile scope. */
        const val PREFS_NAME = "anilist_auth"

        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_EXPIRES_AT = "expires_at_ms"
        const val KEY_VIEWER_ID = "viewer_id"
        const val KEY_VIEWER_NAME = "viewer_name"
        const val KEY_SIGNED_OUT = "signed_out"
        const val KEY_NEEDS_RECONNECT = "needs_reconnect"

        /**
         * The `state` of the code-grant flow this profile started, and nothing
         * else (spec 2026-11-14, F3).
         *
         * A CSRF token, not a credential: no verifier exists because AniList's
         * code grant takes no PKCE. Stored rather than kept in memory for the same
         * reason MAL stores its verifier - the redirect can arrive in a fresh
         * process after the browser was in front.
         */
        const val KEY_PENDING_STATE = "pending_state"
    }

    private fun storeName(appContext: Context): String =
        profileId?.let { ProfileStorage.prefsName(it, PREFS_NAME) }
            ?: ProfileStorage.prefsName(appContext, PREFS_NAME)

    /** One rebuild allowed when the ciphertext outlives its KeyStore entry. */
    private fun open(): SharedPreferences? {
        val appContext = context?.applicationContext ?: return null
        val name = storeName(appContext)
        return try {
            openPrefs(appContext, name)
        } catch (first: Exception) {
            Log.w(TAG, "anilist store '$name' would not open (${first.javaClass.simpleName}); rebuilding it")
            SecureTokenStore.discard(appContext, name)
            openPrefs(appContext, name)
        }
    }

    private val prefs: SharedPreferences?
        get() = open()

    fun save(token: AniListToken) {
        prefs?.edit()
            ?.putString(KEY_ACCESS_TOKEN, token.accessToken)
            ?.putLong(KEY_EXPIRES_AT, token.expiresAtMs)
            ?.putInt(KEY_VIEWER_ID, token.viewerId ?: 0)
            ?.putString(KEY_VIEWER_NAME, token.viewerName)
            // A fresh session retires both markers: it is neither a deliberate
            // disconnect nor a dead grant any more.
            ?.putBoolean(KEY_SIGNED_OUT, false)
            ?.putBoolean(KEY_NEEDS_RECONNECT, false)
            ?.apply()
    }

    fun read(): AniListToken? {
        val p = prefs ?: return null
        val access = p.getString(KEY_ACCESS_TOKEN, "").orEmpty()
        if (access.isBlank()) return null
        return AniListToken(
            accessToken = access,
            expiresAtMs = p.getLong(KEY_EXPIRES_AT, 0L),
            viewerId = p.getInt(KEY_VIEWER_ID, 0).takeIf { it > 0 },
            viewerName = p.getString(KEY_VIEWER_NAME, null)?.takeIf { it.isNotBlank() }
        )
    }

    fun hasSession(): Boolean = read() != null

    fun needsReconnect(): Boolean = prefs?.getBoolean(KEY_NEEDS_RECONNECT, false) == true

    fun setNeedsReconnect(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY_NEEDS_RECONNECT, value)?.apply()
    }

    /**
     * Disconnect: the token goes and the tombstone stays.
     *
     * The tombstone is what tells the account's other devices that this profile
     * signed out; without it a disconnect on the TV would leave the phone writing
     * under a grant the viewer took back. Local watch data is never touched.
     */
    fun markSignedOut() {
        prefs?.edit()
            ?.remove(KEY_ACCESS_TOKEN)
            ?.remove(KEY_EXPIRES_AT)
            ?.remove(KEY_VIEWER_ID)
            ?.remove(KEY_VIEWER_NAME)
            ?.putBoolean(KEY_SIGNED_OUT, true)
            ?.putBoolean(KEY_NEEDS_RECONNECT, false)
            ?.apply()
    }

    fun signedOut(): Boolean = prefs?.getBoolean(KEY_SIGNED_OUT, false) == true

    /** Remembers the state a connect started here must see echoed back. */
    fun savePendingState(state: String) {
        prefs?.edit()?.putString(KEY_PENDING_STATE, state)?.apply()
    }

    /** The state this profile is waiting for, or null when no flow is pending. */
    fun pendingState(): String? = prefs?.getString(KEY_PENDING_STATE, null)?.takeIf { it.isNotBlank() }

    fun clearPendingState() {
        prefs?.edit()?.remove(KEY_PENDING_STATE)?.apply()
    }

    /**
     * Retires a lingering disconnect marker without touching the session.
     *
     * The cloud applier's same-session path: a pulled blob that equals what this
     * device already holds must not leave a `signed_out` marker beside a live
     * token, or the next bulk push republishes the marker as a disconnect and
     * signs the account out everywhere.
     */
    fun clearSignedOutMarker() {
        if (signedOut()) prefs?.edit()?.putBoolean(KEY_SIGNED_OUT, false)?.apply()
    }
}
