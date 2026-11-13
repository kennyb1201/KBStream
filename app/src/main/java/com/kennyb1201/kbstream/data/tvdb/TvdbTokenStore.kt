package com.kennyb1201.kbstream.data.tvdb

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kennyb1201.kbstream.data.security.SecureTokenStore
import com.kennyb1201.kbstream.data.sync.ProfileStorage

/**
 * The TVDB bearer token, encrypted at rest (spec 2026-11-13, F1).
 *
 * `/v4/login` mints a JWT from the project key and it is valid for one month
 * (the docs say so outright; the same sentence as the PIN rule), so the token is
 * cached rather than minted per screen: a login per Detail open would be exactly
 * the "hammer it" behaviour F1 forbids.
 *
 * It goes through [SecureTokenStore] for the same reason Trakt's and MAL's do -
 * a bearer token is a live credential, and `shared_prefs` is plain XML on a
 * rooted box. Its own store name (`tvdb_auth` -> `<profileId>.tvdb_auth`) so no
 * two sources ever read each other's session, and the name is profile-scoped
 * because the rest of the app's credential stores are.
 *
 * It deliberately does NOT sync. The token is the APP's, minted from the
 * project's key with no account behind it, so every device can mint its own in
 * one request; carrying a month-long JWT over the profile sync blob would ship
 * a credential between devices to save a request that costs nothing and would
 * still be valid only until the same date. The PIN that CAN personalise a login
 * is the user-level value, and that one lives in the synced API-key slot (see
 * AppPreferences.getTvdbPin).
 */
internal class TvdbTokenStore(
    private val context: Context?,
    private val profileId: String? = null,
    private val openPrefs: (Context, String) -> SharedPreferences = { appContext, name ->
        SecureTokenStore.prefs(appContext, name, legacyPlaintext = false)
    }
) {

    companion object {
        private const val TAG = "TvdbTokenStore"

        /** The logical name; [ProfileStorage.prefsName] adds the profile scope. */
        const val PREFS_NAME = "tvdb_auth"

        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_EXPIRES_AT = "expires_at_ms"

        /**
         * How long a minted token is assumed to live.
         *
         * The docs promise one month. This takes 25 days, a week inside the
         * promise: a token that dies on a clock skew, a re-issued key or a
         * server-side rotation is re-minted by a plain read rather than by a
         * 401 the viewer sees as a failed screen.
         */
        const val TOKEN_LIFETIME_MS = 25L * 24L * 60L * 60L * 1000L
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
            Log.w(TAG, "tvdb store '$name' would not open (${first.javaClass.simpleName}); rebuilding it")
            SecureTokenStore.discard(appContext, name)
            openPrefs(appContext, name)
        }
    }

    private val prefs: SharedPreferences?
        get() = open()

    fun save(token: String, expiresAtMs: Long) {
        prefs?.edit()
            ?.putString(KEY_ACCESS_TOKEN, token)
            ?.putLong(KEY_EXPIRES_AT, expiresAtMs)
            ?.apply()
    }

    /**
     * A token that can be sent now, or null when there is none, it has expired,
     * or it is close enough to expiry that a long fetch could cross it.
     */
    fun token(): String? {
        val value = prefs?.getString(KEY_ACCESS_TOKEN, null)?.trim().orEmpty()
        if (value.isEmpty()) return null
        val expiresAt = prefs?.getLong(KEY_EXPIRES_AT, 0L) ?: 0L
        if (expiresAt <= System.currentTimeMillis()) return null
        return value
    }

    fun clear() {
        prefs?.edit()?.remove(KEY_ACCESS_TOKEN)?.remove(KEY_EXPIRES_AT)?.apply()
    }
}
