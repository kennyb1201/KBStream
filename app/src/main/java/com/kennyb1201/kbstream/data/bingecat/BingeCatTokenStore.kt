package com.kennyb1201.kbstream.data.bingecat

import android.content.Context
import android.content.SharedPreferences
import com.kennyb1201.kbstream.data.security.SecureTokenStore
import com.kennyb1201.kbstream.data.sync.ProfileStorage

/**
 * This profile's BingeCat session, encrypted at rest.
 *
 * The same three rules as the other trackers' stores, because the hazards are
 * identical: its **own** store name (`bingecat_auth` resolves to
 * `<profileId>.bingecat_auth`, so a BingeCat token can never be read as a Trakt
 * or Simkl session), the token goes through [SecureTokenStore] so the file is
 * ciphertext, and disconnect removes this store's keys and nothing else - the
 * local watch history, resume rows and My List are the viewer's.
 *
 * Deliberately absent: any sync of this session. v1 keeps BingeCat
 * device-local, exactly as the spec asks, so there is no sign-out tombstone
 * either - nothing else can overwrite it.
 */
internal class BingeCatTokenStore(
    private val context: Context?,
    private val openPrefs: (Context, String) -> SharedPreferences = { appContext, name ->
        SecureTokenStore.prefs(appContext, name, legacyPlaintext = false)
    }
) {

    companion object {
        const val PREFS_NAME = "bingecat_auth"

        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at_ms"
        const val KEY_SCOPE = "scope"
    }

    private fun storeName(appContext: Context): String =
        ProfileStorage.prefsName(appContext, PREFS_NAME)

    /**
     * This store, with ONE rebuild allowed when it will not open.
     *
     * A store can outlive the KeyStore entry that decrypts it (a restored
     * backup, a re-flash), and from then on every open throws. A session is
     * worth less than a usable integration: the account can hand out another
     * one, so the second attempt discards the file. One retry, never a loop.
     */
    private fun open(): SharedPreferences? {
        val appContext = context?.applicationContext ?: return null
        val name = storeName(appContext)
        return try {
            openPrefs(appContext, name)
        } catch (first: Exception) {
            SecureTokenStore.discard(appContext, name)
            openPrefs(appContext, name)
        }
    }

    private val prefs: SharedPreferences?
        get() = open()

    /** Throws when the store cannot be written even after [open]'s rebuild. */
    fun save(token: BingeCatToken) {
        val editor = prefs?.edit() ?: throw IllegalStateException("bingecat store unavailable")
        editor.putString(KEY_ACCESS_TOKEN, token.accessToken)
            .putString(KEY_REFRESH_TOKEN, token.refreshToken)
            .putLong(KEY_EXPIRES_AT, token.expiresAtEpochMillis)
            .putString(KEY_SCOPE, token.scope)
            .apply()
    }

    fun read(): BingeCatToken? {
        val access = prefs?.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: return null
        return BingeCatToken(
            accessToken = access,
            refreshToken = prefs?.getString(KEY_REFRESH_TOKEN, null),
            expiresAtEpochMillis = prefs?.getLong(KEY_EXPIRES_AT, 0L) ?: 0L,
            scope = prefs?.getString(KEY_SCOPE, null)
        )
    }

    fun accessToken(): String? = read()?.accessToken

    fun scope(): String? = prefs?.getString(KEY_SCOPE, null)

    /** Drops the session. Local library and watch state are not ours to touch. */
    fun clear() {
        prefs?.edit()
            ?.remove(KEY_ACCESS_TOKEN)
            ?.remove(KEY_REFRESH_TOKEN)
            ?.remove(KEY_EXPIRES_AT)
            ?.remove(KEY_SCOPE)
            ?.apply()
    }
}

/**
 * Which BingeCat profile this KBStream profile reads.
 *
 * Stored per KBStream profile, on this device, and deliberately **not** in the
 * sync payload: the mapping is a choice about *this* profile's device, and a
 * mapping arriving from another device would point at a BingeCat id that may
 * not even exist for this account.
 *
 * "Unmapped" is a real state, not an error: a connected profile with no
 * mapping makes no data request at all, which is what keeps a kids profile
 * from inheriting Profile 1's BingeCat rows (spec F2).
 */
internal class BingeCatProfileMap(private val context: Context?) {

    companion object {
        const val PREFS_NAME = "bingecat_map"
        const val KEY_PROFILE_ID = "bingecat_profile_id"
        const val KEY_PROFILE_NAME = "bingecat_profile_name"
    }

    private fun prefs(): SharedPreferences? {
        val appContext = context?.applicationContext ?: return null
        return appContext.getSharedPreferences(
            ProfileStorage.prefsName(appContext, PREFS_NAME),
            Context.MODE_PRIVATE
        )
    }

    fun mappedProfileId(): Long? =
        prefs()?.getLong(KEY_PROFILE_ID, 0L)?.takeIf { it > 0L }

    fun mappedProfileName(): String? =
        prefs()?.getString(KEY_PROFILE_NAME, null)?.takeIf { it.isNotBlank() }

    fun set(profile: BingeCatProfile) {
        val id = profile.id?.takeIf { it > 0L } ?: return
        prefs()?.edit()
            ?.putLong(KEY_PROFILE_ID, id)
            ?.putString(KEY_PROFILE_NAME, profile.name)
            ?.apply()
    }

    fun clear() {
        prefs()?.edit()
            ?.remove(KEY_PROFILE_ID)
            ?.remove(KEY_PROFILE_NAME)
            ?.apply()
    }
}
