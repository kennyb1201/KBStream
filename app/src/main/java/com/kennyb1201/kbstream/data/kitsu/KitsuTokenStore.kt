package com.kennyb1201.kbstream.data.kitsu

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kennyb1201.kbstream.data.security.SecureTokenStore
import com.kennyb1201.kbstream.data.sync.ProfileStorage

/**
 * This profile's Kitsu session, encrypted at rest (spec 2026-11-13 phase 2, F2).
 *
 * Its own store name (`kitsu_auth` → `<profileId>.kitsu_auth`) for the reason
 * Trakt's, BingeCat's and MAL's have theirs: the sync blob and the diagnostics
 * address a session by store name, and two trackers sharing one file would read
 * each other's tokens.
 *
 * WHAT IS NOT HERE is the point: the viewer's Kitsu PASSWORD. It is used once, in
 * memory, for the token exchange and then dropped. It is never written here, in
 * any pref, in any file, or in any log line - the store holds the two tokens the
 * grant produced, and nothing that can be replayed as a password.
 *
 * [KitsuToken] carries the access token (30 days by default), the refresh token,
 * the absolute deadline and the viewer's Kitsu user id. Plus two markers:
 *
 *  - `signed_out`, the tombstone a DELIBERATE disconnect leaves so it may clear
 *    the session on the account's other devices (a profile that never connected
 *    publishes nothing and must never blank a live session), and
 *  - `needs_reconnect`, Kitsu's own verdict after a refresh failed, which stops
 *    every call until the viewer reconnects rather than looping.
 */
internal class KitsuTokenStore(
    private val context: Context?,
    /** A SPECIFIC profile's session for the cloud applier; null = the active one. */
    private val profileId: String? = null,
    private val openPrefs: (Context, String) -> SharedPreferences = { appContext, name ->
        SecureTokenStore.prefs(appContext, name, legacyPlaintext = false)
    }
) {

    companion object {
        private const val TAG = "KitsuTokenStore"

        /** The logical name; [ProfileStorage.prefsName] adds the profile scope. */
        const val PREFS_NAME = "kitsu_auth"

        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at_ms"
        const val KEY_VIEWER_ID = "viewer_id"
        const val KEY_VIEWER_NAME = "viewer_name"
        const val KEY_SIGNED_OUT = "signed_out"
        const val KEY_NEEDS_RECONNECT = "needs_reconnect"
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
            Log.w(TAG, "kitsu store '$name' would not open (${first.javaClass.simpleName}); rebuilding it")
            SecureTokenStore.discard(appContext, name)
            openPrefs(appContext, name)
        }
    }

    private val prefs: SharedPreferences?
        get() = open()

    fun save(token: KitsuToken) {
        prefs?.edit()
            ?.putString(KEY_ACCESS_TOKEN, token.accessToken)
            ?.putString(KEY_REFRESH_TOKEN, token.refreshToken)
            ?.putLong(KEY_EXPIRES_AT, token.expiresAtMs)
            ?.putString(KEY_VIEWER_ID, token.viewerId)
            ?.putString(KEY_VIEWER_NAME, token.viewerName)
            // A fresh session retires both markers: it is neither a deliberate
            // disconnect nor a dead grant any more.
            ?.putBoolean(KEY_SIGNED_OUT, false)
            ?.putBoolean(KEY_NEEDS_RECONNECT, false)
            ?.apply()
    }

    fun read(): KitsuToken? {
        val p = prefs ?: return null
        val access = p.getString(KEY_ACCESS_TOKEN, "").orEmpty()
        if (access.isBlank()) return null
        return KitsuToken(
            accessToken = access,
            refreshToken = p.getString(KEY_REFRESH_TOKEN, null),
            expiresAtMs = p.getLong(KEY_EXPIRES_AT, 0L),
            viewerId = p.getString(KEY_VIEWER_ID, null)?.takeIf { it.isNotBlank() },
            viewerName = p.getString(KEY_VIEWER_NAME, null)?.takeIf { it.isNotBlank() }
        )
    }

    fun hasSession(): Boolean = read() != null

    fun viewerId(): String? = read()?.viewerId

    fun saveViewerId(viewerId: String?) {
        if (viewerId.isNullOrBlank()) return
        prefs?.edit()?.putString(KEY_VIEWER_ID, viewerId)?.apply()
    }

    fun refreshToken(): String? = read()?.refreshToken?.takeIf { it.isNotBlank() }

    fun needsReconnect(): Boolean = prefs?.getBoolean(KEY_NEEDS_RECONNECT, false) == true

    fun setNeedsReconnect(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY_NEEDS_RECONNECT, value)?.apply()
    }

    /**
     * Disconnect: the tokens go and the tombstone stays.
     *
     * The tombstone is what tells the account's other devices to clear this
     * profile's session too; without it a disconnect on the TV would leave the
     * phone still writing under a grant the viewer took back.
     */
    fun markSignedOut() {
        prefs?.edit()
            ?.remove(KEY_ACCESS_TOKEN)
            ?.remove(KEY_REFRESH_TOKEN)
            ?.remove(KEY_EXPIRES_AT)
            ?.remove(KEY_VIEWER_ID)
            ?.remove(KEY_VIEWER_NAME)
            ?.putBoolean(KEY_SIGNED_OUT, true)
            ?.putBoolean(KEY_NEEDS_RECONNECT, false)
            ?.apply()
    }

    fun signedOut(): Boolean = prefs?.getBoolean(KEY_SIGNED_OUT, false) == true

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
