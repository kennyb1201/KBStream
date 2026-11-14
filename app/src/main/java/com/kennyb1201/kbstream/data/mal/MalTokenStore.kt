package com.kennyb1201.kbstream.data.mal

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kennyb1201.kbstream.data.security.SecureTokenStore
import com.kennyb1201.kbstream.data.sync.ProfileStorage

/**
 * This profile's MyAnimeList session, encrypted at rest (spec 2026-11-13, F1).
 *
 * Its own store name (`mal_auth` → `<profileId>.mal_auth`) for the same reason
 * Trakt's and BingeCat's have theirs: the sync blob and the diagnostics address
 * a session by store name, and two trackers sharing one file would read each
 * other's tokens.
 *
 * The token goes through [SecureTokenStore] (AndroidKeyStore-keyed ciphertext, no
 * plaintext copy). Everything MAL needs to stay connected is here - the access
 * token (one hour), the refresh token (one month) and the absolute deadline -
 * plus two markers:
 *
 *  - `signed_out`, the tombstone a DELIBERATE disconnect leaves so it may clear
 *    the session on the account's other devices (a profile that simply never
 *    connected publishes nothing and must never blank a live session), and
 *  - `needs_reconnect`, MAL's own verdict after a refresh failed, which stops
 *    every call until the viewer reconnects rather than looping.
 */
internal class MalTokenStore(
    private val context: Context?,
    /** A SPECIFIC profile's session for the cloud applier; null = the active one. */
    private val profileId: String? = null,
    private val openPrefs: (Context, String) -> SharedPreferences = { appContext, name ->
        SecureTokenStore.prefs(appContext, name, legacyPlaintext = false)
    }
) {

    companion object {
        private const val TAG = "MalTokenStore"

        /** The logical name; [ProfileStorage.prefsName] adds the profile scope. */
        const val PREFS_NAME = "mal_auth"

        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at_ms"
        const val KEY_SIGNED_OUT = "signed_out"
        const val KEY_NEEDS_RECONNECT = "needs_reconnect"

        /**
         * The connected account's own name, for Settings' "connected as"
         * (spec 2026-11-13, MAL row F1).
         *
         * A display fact, not a credential: it is written by the `/users/@me`
         * read the first time that answers, so the row does not ask MAL again on
         * every compose. It is NOT part of the sync blob (`buildMalAuth` copies
         * an explicit list) - each device reads it from MAL under its own
         * session, and a stale name can never outlive a disconnect because
         * [markSignedOut] drops it.
         */
        const val KEY_VIEWER_NAME = "viewer_name"

        /** The in-flight PKCE values: written before the browser opens. */
        const val KEY_PENDING_VERIFIER = "pending_verifier"
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
            Log.w(TAG, "mal store '$name' would not open (${first.javaClass.simpleName}); rebuilding it")
            SecureTokenStore.discard(appContext, name)
            openPrefs(appContext, name)
        }
    }

    private val prefs: SharedPreferences?
        get() = open()

    fun save(token: MalToken) {
        prefs?.edit()
            ?.putString(KEY_ACCESS_TOKEN, token.accessToken)
            ?.putString(KEY_REFRESH_TOKEN, token.refreshToken)
            ?.putLong(KEY_EXPIRES_AT, token.expiresAtMs)
            // A fresh session retires both markers: it is neither a deliberate
            // disconnect nor a dead grant any more.
            ?.putBoolean(KEY_SIGNED_OUT, false)
            ?.putBoolean(KEY_NEEDS_RECONNECT, false)
            ?.apply()
    }

    fun savePendingFlow(verifier: String, state: String) {
        prefs?.edit()
            ?.putString(KEY_PENDING_VERIFIER, verifier)
            ?.putString(KEY_PENDING_STATE, state)
            ?.apply()
    }

    fun pendingVerifier(): String? =
        prefs?.getString(KEY_PENDING_VERIFIER, null)?.takeIf { it.isNotBlank() }

    fun pendingState(): String? =
        prefs?.getString(KEY_PENDING_STATE, null)?.takeIf { it.isNotBlank() }

    fun clearPendingFlow() {
        prefs?.edit()
            ?.remove(KEY_PENDING_VERIFIER)
            ?.remove(KEY_PENDING_STATE)
            ?.apply()
    }

    fun accessToken(): String? =
        prefs?.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun refreshToken(): String? =
        prefs?.getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun expiresAtMs(): Long = prefs?.getLong(KEY_EXPIRES_AT, 0L) ?: 0L

    fun read(): MalToken? {
        val access = accessToken() ?: return null
        return MalToken(access, refreshToken(), expiresAtMs())
    }

    fun hasSession(): Boolean = accessToken() != null

    /** The cached display name of the connected account, if it was ever read. */
    fun viewerName(): String? =
        prefs?.getString(KEY_VIEWER_NAME, null)?.takeIf { it.isNotBlank() }

    fun saveViewerName(name: String) {
        if (name.isBlank()) return
        prefs?.edit()?.putString(KEY_VIEWER_NAME, name)?.apply()
    }

    /**
     * Drops the cached display name without touching the session.
     *
     * Used when a DIFFERENT session is adopted on this profile: it may be a
     * different MAL account, and a stale name under a healthy token would make
     * the Settings row name the wrong account.
     */
    fun clearViewerName() {
        prefs?.edit()?.remove(KEY_VIEWER_NAME)?.apply()
    }

    fun isSignedOut(): Boolean = prefs?.getBoolean(KEY_SIGNED_OUT, false) == true

    fun needsReconnect(): Boolean = prefs?.getBoolean(KEY_NEEDS_RECONNECT, false) == true

    fun setNeedsReconnect(needsReconnect: Boolean) {
        prefs?.edit()?.putBoolean(KEY_NEEDS_RECONNECT, needsReconnect)?.apply()
    }

    /**
     * Drops the session and leaves the disconnect tombstone for the sync blob.
     *
     * Local watch data is not this store's to touch: disconnecting a tracker has
     * never deleted a viewer's history, resume points or watched markers, and
     * MAL's row going dormant is the whole effect.
     */
    fun markSignedOut(): Boolean {
        val editor = prefs?.edit() ?: return false
        editor.remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_VIEWER_NAME)
            .remove(KEY_PENDING_VERIFIER)
            .remove(KEY_PENDING_STATE)
            .putBoolean(KEY_SIGNED_OUT, true)
            .putBoolean(KEY_NEEDS_RECONNECT, false)
            .apply()
        return true
    }

    fun clearSignedOutMarker() {
        if (isSignedOut()) prefs?.edit()?.putBoolean(KEY_SIGNED_OUT, false)?.apply()
    }
}
