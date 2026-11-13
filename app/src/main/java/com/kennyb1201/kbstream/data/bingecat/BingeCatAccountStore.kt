package com.kennyb1201.kbstream.data.bingecat

import android.content.Context
import android.content.SharedPreferences
import com.kennyb1201.kbstream.data.security.SecureTokenStore
import com.kennyb1201.kbstream.data.sync.ProfileStorage

/**
 * The BingeCat session as the ACCOUNT has it (spec 2026-11-13, F1).
 *
 * BingeCat's grant is account-level: one approval authorizes the app for the
 * account, and every v1 call then names the profile in the path
 * (`/api/v1/profiles/{profile_id}/...`). The session used to live only in the
 * per-KBStream-profile store, which meant profile 3 could not reach the account's
 * profile list without running its own device flow - the QR-per-profile report.
 *
 * So the session has a second home, and this one is NOT profile-scoped: one
 * device-wide encrypted store (`bingecat_account`), shared by every profile.
 * [BingeCatTokenStore] reads it first and writes every session it saves into it,
 * so a connect on any profile serves all of them.
 *
 * ## The mapping is still per profile
 *
 * Nothing here decides WHICH BingeCat profile a KBStream profile reads - that is
 * [BingeCatProfileMap], unchanged and still per profile, still synced as its own
 * row. A session without a mapping reads nothing
 * (`BingeCatRepository.mappedProfileId() ?: return null`), so a profile that
 * never picked a BingeCat profile stays out of the account's data even though it
 * can see the account's session.
 *
 * ## Disconnect
 *
 * The `signed_out` tombstone stays where it was, in the per-profile store: a
 * blank store still means "this profile never connected" and never signs a
 * sibling out. This store is dropped only when the LAST KBStream profile mapped
 * to the account disconnects ([BingeCatAccountRules.shouldClearAccountToken]).
 *
 * ## One pair per account
 *
 * The v1 API exposes no account id - `GET /api/v1/profiles` names profiles, not
 * the account behind them - so this store holds one pair. A household with two
 * BingeCat accounts still needs one QR per account, and connecting the second
 * replaces the first account's pair (the profiles still mapped to the first are
 * then asked for one reconnect, for THAT account). What it never needs again is
 * a QR per KBStream profile.
 */
internal class BingeCatAccountStore(
    private val context: Context?,
    private val openPrefs: (Context, String) -> SharedPreferences = { appContext, name ->
        SecureTokenStore.prefs(appContext, name, legacyPlaintext = false)
    }
) {

    companion object {
        /** Device-wide on purpose: this is the account's session, not a profile's. */
        const val PREFS_NAME = "bingecat_account"
    }

    /**
     * This store, with ONE rebuild allowed when it will not open - the same
     * recovery [BingeCatTokenStore] gives its own store, for the same reasons
     * (a ciphertext that outlived its KeyStore entry, a keyset an earlier build
     * left half-written). A session is worth less than a usable integration.
     */
    private fun open(): SharedPreferences? {
        val appContext = context?.applicationContext ?: return null
        // The retry before the discard is the discriminator, not politeness: a
        // KeyStore that is momentarily unavailable throws ONE open and answers the
        // next, and discarding on that would throw away the account's session - the
        // exact token the QR was spent on.
        try {
            return openPrefs(appContext, PREFS_NAME)
        } catch (first: Exception) {
            try {
                return openPrefs(appContext, PREFS_NAME)
            } catch (second: Exception) {
                second.addSuppressed(first)
            }
        }
        SecureTokenStore.discard(appContext, PREFS_NAME)
        return runCatching { openPrefs(appContext, PREFS_NAME) }.getOrNull()
    }

    private val prefs: SharedPreferences?
        get() = open()

    fun read(): BingeCatToken? {
        val access = prefs?.getString(BingeCatTokenStore.KEY_ACCESS_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return BingeCatToken(
            accessToken = access,
            refreshToken = prefs?.getString(BingeCatTokenStore.KEY_REFRESH_TOKEN, null),
            expiresAtEpochMillis = prefs?.getLong(BingeCatTokenStore.KEY_EXPIRES_AT, 0L) ?: 0L,
            scope = prefs?.getString(BingeCatTokenStore.KEY_SCOPE, null)
        )
    }

    /**
     * Files the account's session, replacing any earlier one.
     *
     * Throws when the store cannot be written, exactly as
     * [BingeCatTokenStore.save] does: that exception is what the connect flow
     * reports as "approved, but this device could not keep the grant", and a
     * silent no-op here would read as a connection that does not exist.
     */
    fun save(token: BingeCatToken) {
        val editor = prefs?.edit() ?: throw IllegalStateException("bingecat account store unavailable")
        editor.putString(BingeCatTokenStore.KEY_ACCESS_TOKEN, token.accessToken)
            .putString(BingeCatTokenStore.KEY_REFRESH_TOKEN, token.refreshToken)
            .putLong(BingeCatTokenStore.KEY_EXPIRES_AT, token.expiresAtEpochMillis)
            .putString(BingeCatTokenStore.KEY_SCOPE, token.scope)
            .apply()
    }

    /** Drops the account's session. The per-profile mappings and tombstones are not this store's. */
    fun clear() {
        prefs?.edit()
            ?.remove(BingeCatTokenStore.KEY_ACCESS_TOKEN)
            ?.remove(BingeCatTokenStore.KEY_REFRESH_TOKEN)
            ?.remove(BingeCatTokenStore.KEY_EXPIRES_AT)
            ?.remove(BingeCatTokenStore.KEY_SCOPE)
            ?.apply()
    }
}

/**
 * True when any OTHER KBStream profile on this device still maps to a BingeCat
 * profile.
 *
 * Read from the profile-scoped mapping stores that exist on disk
 * ([ProfileStorage.namespaces]), not from an in-memory profile list: a disconnect
 * must be correct even when no profile switch has happened in this process. A
 * store file that exists but holds no id is "mapped then cleared", which is not a
 * mapping.
 */
internal fun bingecatOtherProfilesKeepMapping(
    context: Context?,
    exceptProfileId: String?
): Boolean {
    val appContext = context?.applicationContext ?: return false
    val suffix = ".${BingeCatProfileMap.PREFS_NAME}"
    return runCatching {
        ProfileStorage.namespaces(appContext)
            .asSequence()
            .filter { it.endsWith(suffix) }
            .map { it.removeSuffix(suffix) }
            .filter { it.isNotBlank() && it != exceptProfileId }
            .any { profileId ->
                runCatching { BingeCatProfileMap(appContext, profileId).mappedProfileId() }
                    .getOrNull() != null
            }
    }.getOrDefault(false)
}
