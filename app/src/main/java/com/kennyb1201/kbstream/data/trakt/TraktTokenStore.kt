package com.kennyb1201.kbstream.data.trakt

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kennyb1201.kbstream.data.security.SecureTokenStore
import com.kennyb1201.kbstream.data.sync.ProfileStorage

/**
 * This profile's Trakt session, encrypted at rest.
 *
 * Three things are deliberate:
 *
 *  1. **Its own store name.** `trakt_auth` resolves to
 *     `<profileId>.trakt_auth`, which is a different file from Simkl's
 *     `<profileId>.simkl_auth`. Diagnostics and the sync/cloud readers address
 *     these stores by name, so sharing one would make a Trakt token read as a
 *     Simkl session - the exact collision the plan calls out.
 *  2. **The token is a live credential.** It goes through
 *     [SecureTokenStore], so the on-disk file is ciphertext keyed from the
 *     AndroidKeyStore. There is no plaintext copy and no `legacyPlaintext`
 *     migration: Trakt never existed before this store.
 *  3. **Disconnect removes this store's keys and nothing else.** The local watch
 *     history, resume rows and watched markers are the viewer's, not Trakt's;
 *     Simkl's disconnect wiping them was the bug this store is shaped to avoid
 *     (see [TraktRepository.revokeAndClear]).
 *  4. **The whole session syncs, not just the access token.** Trakt's refresh
 *     tokens are single-use and its access tokens expire, so a sibling that
 *     adopted only the access token would work for a while and then strand the
 *     viewer. The sync blob carries all of it (see
 *     [com.kennyb1201.kbstream.data.sync.TraktAuthRules]), and this store is
 *     the one place the field names are spelled.
 */
internal class TraktTokenStore(
    private val context: Context?,
    /**
     * A SPECIFIC profile's session, for the cloud applier. Null reads the
     * active profile, which is what every in-app caller wants.
     */
    private val profileId: String? = null,
    /**
     * How this store's encrypted file is opened. A parameter rather than a
     * direct call so [open]'s rebuild path can be pinned with a store that
     * throws on its first open - a corrupt KeyStore cannot be reproduced on
     * demand on a real device.
     */
    private val openPrefs: (Context, String) -> SharedPreferences = { appContext, name ->
        SecureTokenStore.prefs(appContext, name, legacyPlaintext = false)
    }
) {

    companion object {
        private const val TAG = "TraktTokenStore"

        /** The logical name; [ProfileManager.prefsName] adds the profile scope. */
        const val PREFS_NAME = "trakt_auth"

        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at_ms"
        const val KEY_ACCOUNT_SLUG = "account_slug"

        /**
         * Tombstone for a DELIBERATE disconnect. Only this marker may clear a
         * live session on another device; a blank store (a profile that never
         * connected) must never do it.
         */
        const val KEY_SIGNED_OUT = "signed_out"
    }

    /** The file name this profile's session lives in. */
    private fun storeName(appContext: Context): String =
        profileId?.let { ProfileStorage.prefsName(it, PREFS_NAME) }
            ?: ProfileStorage.prefsName(appContext, PREFS_NAME)

    /**
     * This store, with ONE rebuild allowed when it will not open.
     *
     * A store can be left unreadable by an earlier build or a restored backup:
     * the ciphertext outlives the KeyStore entry that decrypts it, and from then
     * on every open throws. A Trakt session is worth less than a usable Trakt:
     * the file holds nothing the account cannot hand out again, so the second
     * attempt deletes it and starts a fresh store. One retry, never a loop - a
     * device where even a fresh file will not open reports the failure to its
     * caller instead of spinning.
     *
     * Scoped to this store's own file: Simkl's session lives in a different one
     * and is not touched by a Trakt fault.
     */
    private fun open(): SharedPreferences? {
        val appContext = context?.applicationContext ?: return null
        val name = storeName(appContext)
        return try {
            openPrefs(appContext, name)
        } catch (first: Exception) {
            Log.w(
                TAG,
                "trakt store '$name' would not open " +
                    "(${first.javaClass.simpleName}: ${first.message}); rebuilding it"
            )
            SecureTokenStore.discard(appContext, name)
            openPrefs(appContext, name)
        }
    }

    private val prefs: SharedPreferences?
        get() = open()

    /**
     * Writes a granted session.
     *
     * Throws when the store cannot be written even after [open]'s single
     * rebuild: the caller ([TraktPollExchange]) turns that into "approved, but
     * the token could not be saved" rather than polling a spent code again.
     */
    fun save(token: TraktToken) {
        prefs?.edit()
            ?.putString(KEY_ACCESS_TOKEN, token.accessToken)
            ?.putString(KEY_REFRESH_TOKEN, token.refreshToken)
            ?.putLong(KEY_EXPIRES_AT, token.expiresAtEpochMillis)
            // A fresh session retires a lingering disconnect marker, so it
            // cannot be republished over the live token on the next push.
            ?.putBoolean(KEY_SIGNED_OUT, false)
            ?.apply()
    }

    /** True when the last thing this profile did was deliberately disconnect. */
    fun isSignedOut(): Boolean = prefs?.getBoolean(KEY_SIGNED_OUT, false) == true

    /**
     * Drops the session and leaves the sign-out tombstone for the sync blob.
     * Local watch data is not this store's to touch.
     */
    fun markSignedOut() {
        prefs?.edit()
            ?.remove(KEY_ACCESS_TOKEN)
            ?.remove(KEY_REFRESH_TOKEN)
            ?.remove(KEY_EXPIRES_AT)
            ?.remove(KEY_ACCOUNT_SLUG)
            ?.putBoolean(KEY_SIGNED_OUT, true)
            ?.apply()
    }

    /** Retires a tombstone once a live session is present again. */
    fun clearSignedOutMarker() {
        if (isSignedOut()) prefs?.edit()?.putBoolean(KEY_SIGNED_OUT, false)?.apply()
    }

    fun read(): TraktToken? {
        val access = prefs?.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: return null
        return TraktToken(
            accessToken = access,
            refreshToken = prefs?.getString(KEY_REFRESH_TOKEN, null),
            expiresAtEpochMillis = prefs?.getLong(KEY_EXPIRES_AT, 0L) ?: 0L
        )
    }

    fun accessToken(): String? = read()?.accessToken

    fun expiresAtEpochMillis(): Long = prefs?.getLong(KEY_EXPIRES_AT, 0L) ?: 0L

    /**
     * The account this profile's token belongs to (Trakt's user slug).
     *
     * Recorded so a screen can name the account without a request, and so a
     * future library read can refuse a token that answers as a different user
     * than the one the profile was connected to.
     */
    fun accountSlug(): String? =
        prefs?.getString(KEY_ACCOUNT_SLUG, null)?.takeIf { it.isNotBlank() }

    fun setAccountSlug(slug: String?) {
        val editor = prefs?.edit() ?: return
        if (slug.isNullOrBlank()) {
            editor.remove(KEY_ACCOUNT_SLUG)
        } else {
            editor.putString(KEY_ACCOUNT_SLUG, slug)
        }
        editor.apply()
    }

    /** Drop the session. Local watch data is not this store's to touch. */
    fun clear() {
        prefs?.edit()
            ?.remove(KEY_ACCESS_TOKEN)
            ?.remove(KEY_REFRESH_TOKEN)
            ?.remove(KEY_EXPIRES_AT)
            ?.remove(KEY_ACCOUNT_SLUG)
            ?.apply()
    }
}
