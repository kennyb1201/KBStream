package com.kennyb1201.kbstream.data.trakt

import android.content.Context
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
 */
internal class TraktTokenStore(private val context: Context?) {

    companion object {
        /** The logical name; [ProfileManager.prefsName] adds the profile scope. */
        const val PREFS_NAME = "trakt_auth"

        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at_ms"
        private const val KEY_ACCOUNT_SLUG = "account_slug"
    }

    private val prefs
        get() = context?.applicationContext?.let { appContext ->
            SecureTokenStore.prefs(
                appContext,
                ProfileStorage.prefsName(appContext, PREFS_NAME),
                legacyPlaintext = false
            )
        }

    fun save(token: TraktToken) {
        prefs?.edit()
            ?.putString(KEY_ACCESS_TOKEN, token.accessToken)
            ?.putString(KEY_REFRESH_TOKEN, token.refreshToken)
            ?.putLong(KEY_EXPIRES_AT, token.expiresAtEpochMillis)
            ?.apply()
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
