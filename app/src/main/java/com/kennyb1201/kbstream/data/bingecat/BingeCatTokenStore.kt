package com.kennyb1201.kbstream.data.bingecat

import android.content.Context
import android.content.SharedPreferences
import com.kennyb1201.kbstream.data.security.SecureTokenStore
import com.kennyb1201.kbstream.data.sync.ProfileStorage

/**
 * A KBStream profile's view of the BingeCat session, encrypted at rest.
 *
 * TWO layers since spec 2026-11-13 (F1): the session itself belongs to the
 * ACCOUNT and lives in [BingeCatAccountStore] (device-wide), while this store
 * keeps the profile's own copy plus the two facts that are genuinely this
 * profile's - the deliberate-disconnect tombstone and the refused-grant marker.
 * [read] answers from the account first, so one QR serves every profile; the
 * per-profile pair remains as the bootstrap an install that connected before the
 * account store existed is adopted from.
 *
 * The same three rules as the other trackers' stores, because the hazards are
 * identical: its **own** store name (`bingecat_auth` resolves to
 * `<profileId>.bingecat_auth`, so a BingeCat token can never be read as a Trakt
 * or Simkl session), the token goes through [SecureTokenStore] so the file is
 * ciphertext, and disconnect removes this store's keys and nothing else - the
 * local watch history, resume rows and My List are the viewer's.
 *
 * And one rule the earlier v1 spec drew the other way: the session now SYNCs
 * (see [com.kennyb1201.kbstream.data.sync.BingeCatAuthRules]). It was
 * device-local because the spec said so, and the price was signing in again on
 * every TV and on every profile - while every other integration the viewer
 * connects once follows the account. The session travels encrypted on both
 * sides (the account copy is the same trust level the Simkl and Trakt sessions
 * already have), and a deliberate disconnect leaves a tombstone, because a
 * blank store also means "this profile never connected BingeCat" and must not
 * sign a sibling out.
 */
internal class BingeCatTokenStore(
    private val context: Context?,
    /**
     * A SPECIFIC profile's session, for the cloud applier. Null reads the
     * active profile, which is what every in-app caller wants.
     */
    private val profileId: String? = null,
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

        /**
         * Tombstone for a DELIBERATE disconnect. Only this marker may end a
         * live session on another device; a merely empty store (a profile that
         * never connected) must never do it.
         */
        const val KEY_SIGNED_OUT = "signed_out"

        /**
         * LOCAL marker: the server refused this grant (or its refresh), so the
         * viewer has to run the device flow again.
         *
         * Deliberately NOT published: it names a fault of THIS device's
         * credential, and a sibling with a working pair must not adopt it - nor
         * may it be confused with [KEY_SIGNED_OUT], which is a person's
         * decision and signs the account out everywhere.
         */
        const val KEY_NEEDS_RECONNECT = "needs_reconnect"

        /**
         * The access token the refusal in [KEY_NEEDS_RECONNECT] was recorded
         * against.
         *
         * The session is the account's now, so a reconnect on ANY profile replaces
         * the pair every profile reads. Without this value a sibling would keep its
         * own marker and ask for a second QR over a token that had already been
         * replaced (settler S4), so the marker names its credential and stops
         * applying once the account holds a different one.
         */
        const val KEY_REFUSED_ACCESS = "refused_access"
    }

    /** The account's session, shared by every profile on this device. */
    private val account = BingeCatAccountStore(context)

    private fun storeName(appContext: Context): String =
        profileId?.let { ProfileStorage.prefsName(it, PREFS_NAME) }
            ?: ProfileStorage.prefsName(appContext, PREFS_NAME)

    /**
     * This store, with ONE rebuild allowed when it will not open.
     *
     * A store can outlive the KeyStore entry that decrypts it (a restored
     * backup, a re-flash), and from then on every open throws. A session is
     * worth less than a usable integration: the account can hand out another
     * one, so a store that is genuinely unreadable is discarded and reopened.
     *
     * The retry before the discard is the discriminator, not politeness. A
     * KeyStore that is momentarily unavailable - a locked key, a provider
     * re-keying - throws ONE open and answers the next, and this used to treat
     * that as corruption and delete a live session during a routine read. Only
     * an open that fails twice in a row is the corruption this exists for.
     */
    private fun open(): SharedPreferences? {
        val appContext = context?.applicationContext ?: return null
        val name = storeName(appContext)
        try {
            return openPrefs(appContext, name)
        } catch (first: Exception) {
            // Falls through to the discard below only if this second attempt
            // throws too.
            try {
                return openPrefs(appContext, name)
            } catch (second: Exception) {
                second.addSuppressed(first)
            }
        }
        SecureTokenStore.discard(appContext, name)
        return openPrefs(appContext, name)
    }

    private val prefs: SharedPreferences?
        get() = open()

    /**
     * Throws when the store cannot be written even after [open]'s rebuild.
     *
     * A saved session retires a lingering disconnect marker: a token that is
     * present must never be published alongside a tombstone (see
     * [com.kennyb1201.kbstream.data.sync.BingeCatAuthRules]).
     */
    fun save(token: BingeCatToken) {
        val editor = prefs?.edit() ?: throw IllegalStateException("bingecat store unavailable")
        editor.putString(KEY_ACCESS_TOKEN, token.accessToken)
            .putString(KEY_REFRESH_TOKEN, token.refreshToken)
            .putLong(KEY_EXPIRES_AT, token.expiresAtEpochMillis)
            .putString(KEY_SCOPE, token.scope)
            .putBoolean(KEY_SIGNED_OUT, false)
            // A live pair retires a refused one: connect, a successful refresh
            // and an adopted account session all land here, and the first three
            // are exactly the ways back out of "needs reconnect".
            .putBoolean(KEY_NEEDS_RECONNECT, false)
            .remove(KEY_REFUSED_ACCESS)
            .apply()

        // The account's copy, which is the one every OTHER profile reads. It is
        // written after this profile's own, and its failure is not this save's:
        // the profile that just connected works either way, and failing the
        // connect over a second copy would report "could not keep the grant"
        // about a grant that was kept.
        runCatching { account.save(token) }
    }

    /**
     * Records that the stored grant was REFUSED, without deleting it.
     *
     * A 401 says the ACCESS token was rejected; it says nothing about the
     * refresh token, and deleting the pair is how a recoverable expiry became a
     * permanent `no-token` with the profile mapping still in place (the field
     * report, builds 242-253). Keeping the pair also keeps the honest answer
     * reachable: a refresh, or an account session for this same login, can
     * still revive it, and the connect flow's own prompt is driven off
     * [needsReconnect].
     */
    fun markNeedsReconnect() {
        // Which pair was refused, so the answer stops applying once the account's
        // session is replaced by a fresh grant on any profile (see
        // [KEY_REFUSED_ACCESS]). The value comes through [read], because the
        // refused pair is whichever one was actually sent.
        val refused = read()?.accessToken ?: readLocal()?.accessToken
        prefs?.edit()
            ?.putBoolean(KEY_NEEDS_RECONNECT, true)
            ?.putString(KEY_REFUSED_ACCESS, refused)
            ?.apply()
    }

    /**
     * True when this device holds a grant the server has refused.
     *
     * A marker whose pair has been REPLACED no longer applies: one reconnect on
     * one profile mints the account's new pair for every profile, and a sibling
     * still reading its own old marker would ask for the QR the first profile just
     * answered (S4). A marker that named no pair (nothing was stored when the 401
     * landed) stays until a live session retires it.
     */
    fun needsReconnect(): Boolean {
        if (prefs?.getBoolean(KEY_NEEDS_RECONNECT, false) != true) return false
        val refused = prefs?.getString(KEY_REFUSED_ACCESS, null)?.takeIf { it.isNotBlank() }
            ?: return true
        val current = account.read()?.accessToken ?: readLocal()?.accessToken
        return current == null || current == refused
    }

    fun isSignedOut(): Boolean = prefs?.getBoolean(KEY_SIGNED_OUT, false) == true

    /**
     * Drops the session and leaves the sign-out tombstone for the sync blob.
     *
     * Returns whether the tombstone was actually written, and THROWS when the
     * store cannot be opened at all: a disconnect that published anyway would
     * sign the account out on every OTHER device while this one kept its
     * session. Local library and watch state are not ours to touch.
     */
    fun markSignedOut(): Boolean {
        val editor = prefs?.edit() ?: return false
        editor.remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_SCOPE)
            .putBoolean(KEY_SIGNED_OUT, true)
            // A deliberate disconnect is the answer a "needs reconnect" state
            // was asking for, so the marker goes with the session - otherwise a
            // reconnect prompt would survive the disconnect that resolved it.
            .putBoolean(KEY_NEEDS_RECONNECT, false)
            .remove(KEY_REFUSED_ACCESS)
            .apply()
        return true
    }

    /** Retires a tombstone once a live session is present again. */
    fun clearSignedOutMarker() {
        if (isSignedOut()) prefs?.edit()?.putBoolean(KEY_SIGNED_OUT, false)?.apply()
    }

    /**
     * The session this profile reads with: the ACCOUNT's, else its own.
     *
     * A deliberate disconnect still ends that profile's answer even though the
     * account's session is still present ([BingeCatAccountRules.resolve]) - the
     * tombstone is the person's decision, and reading around it would make a
     * disconnect look like it did nothing (F4). A sibling's working session is
     * never touched by it.
     *
     * With no session anywhere, this also performs the MIGRATION: an install that
     * connected before the account store existed has a live per-profile pair and
     * an empty account store, and the first profile to ask adopts it, so nobody
     * is signed out by this fix (F1). The scan of sibling stores runs once per
     * device, because the adoption fills the account store.
     */
    fun read(): BingeCatToken? {
        val signedOut = isSignedOut()
        val accountToken = account.read()
        val local = readLocal()
        BingeCatAccountRules.resolve(signedOut, local, accountToken)?.let { return it }
        if (signedOut) return null
        // No account session and this profile has none: adopt one.
        local?.let { session ->
            if (runCatching { account.save(session) }.isSuccess) return session
        }
        return adoptFromSiblingProfile()
    }

    /** This profile's OWN stored pair, with no account fallback - the adoption source. */
    internal fun readLocal(): BingeCatToken? {
        val access = prefs?.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: return null
        return BingeCatToken(
            accessToken = access,
            refreshToken = prefs?.getString(KEY_REFRESH_TOKEN, null),
            expiresAtEpochMillis = prefs?.getLong(KEY_EXPIRES_AT, 0L) ?: 0L,
            scope = prefs?.getString(KEY_SCOPE, null)
        )
    }

    /**
     * The migration's last resort: another profile on this device already holds a
     * session this one can adopt.
     *
     * Read off the profile-scoped stores that actually exist on disk, not off an
     * in-memory profile list: the first BingeCat screen opened after an update may
     * be a profile that has never connected, with no profile switch behind it.
     * A sibling that deliberately disconnected is skipped - its tombstone must not
     * be resurrected through the account.
     */
    private fun adoptFromSiblingProfile(): BingeCatToken? {
        val appContext = context?.applicationContext ?: return null
        val suffix = ".$PREFS_NAME"
        val profileIds = runCatching {
            ProfileStorage.namespaces(appContext)
                .asSequence()
                .filter { it.endsWith(suffix) }
                .map { it.removeSuffix(suffix) }
                .filter { it.isNotBlank() }
                .toList()
        }.getOrDefault(emptyList())

        profileIds.forEach { siblingId ->
            val sibling = BingeCatTokenStore(context, siblingId)
            if (sibling.isSignedOut()) return@forEach
            val session = sibling.readLocal() ?: return@forEach
            if (runCatching { account.save(session) }.isSuccess) return session
        }
        return null
    }

    fun accessToken(): String? = read()?.accessToken

    /**
     * The scope of the session this profile actually reads with.
     *
     * Read through [read], not off this profile's own copy: a profile serving the
     * ACCOUNT's session must be judged by THAT grant's scopes, or a sibling whose
     * own duplicate predates a write scope would report "writing is off" while its
     * requests carry the account's fully-scoped token.
     */
    fun scope(): String? = read()?.scope

    /**
     * Drops THIS profile's copy of the session. Local library and watch state are
     * not ours to touch, and neither is the account's session: that one outlives a
     * single profile's clear and is dropped only by a disconnect with no mapping
     * left ([BingeCatAccountRules.shouldClearAccountToken]).
     */
    fun clear() {
        prefs?.edit()
            ?.remove(KEY_ACCESS_TOKEN)
            ?.remove(KEY_REFRESH_TOKEN)
            ?.remove(KEY_EXPIRES_AT)
            ?.remove(KEY_SCOPE)
            ?.remove(KEY_NEEDS_RECONNECT)
            ?.remove(KEY_REFUSED_ACCESS)
            ?.apply()
    }
}

/**
 * Which BingeCat profile this KBStream profile reads.
 *
 * Stored per KBStream profile, and this store stays the store of record on the
 * device. It used to be deliberately **not** in the sync payload, on the
 * argument that "a mapping arriving from another device would point at a
 * BingeCat id that may not even exist for this account" - and that argument
 * died with the session: the token follows the account now
 * ([com.kennyb1201.kbstream.data.sync.BingeCatAuthRules]), so the sibling is
 * signed into the same account and the id does exist there. The mapping travels
 * as its own per-profile row, a transport on top of this store rather than a
 * replacement for it, and the one case the old comment was right about - an id
 * whose profile was deleted after the mapping was written - is answered by
 * validating the id against the account's own profile list
 * (`BingeCatRepository.profiles`).
 *
 * The row is per KBStream profile, which is what keeps the kids protection: a
 * kids profile has no mapping and publishes none, so Profile 1's BingeCat
 * profile can never become the kids profile's, whether or not sync is on.
 *
 * "Unmapped" is a real state, not an error: a connected profile with no
 * mapping makes no data request at all (spec F2).
 */
internal class BingeCatProfileMap(
    private val context: Context?,
    /**
     * A SPECIFIC profile's mapping. Null reads the active profile, which is what
     * every in-app caller wants; a disconnect pins the profile it started on so
     * a switch mid-call cannot tombstone the incoming profile's mapping.
     */
    private val profileId: String? = null
) {

    companion object {
        const val PREFS_NAME = "bingecat_map"
        const val KEY_PROFILE_ID = "bingecat_profile_id"
        const val KEY_PROFILE_NAME = "bingecat_profile_name"
    }

    private fun prefs(): SharedPreferences? {
        val appContext = context?.applicationContext ?: return null
        val name = profileId?.let { ProfileStorage.prefsName(it, PREFS_NAME) }
            ?: ProfileStorage.prefsName(appContext, PREFS_NAME)
        return appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    fun mappedProfileId(): Long? =
        prefs()?.getLong(KEY_PROFILE_ID, 0L)?.takeIf { it > 0L }

    fun mappedProfileName(): String? =
        prefs()?.getString(KEY_PROFILE_NAME, null)?.takeIf { it.isNotBlank() }

    /**
     * Files the mapping, or answers false when nothing was written (no usable id,
     * or no store to write to).
     *
     * The answer is not cosmetic: the caller publishes the mapping to the
     * account, and doing that over a no-op re-asserts the PREVIOUS id as if the
     * new choice had landed.
     */
    fun set(profile: BingeCatProfile): Boolean {
        val id = profile.id?.takeIf { it > 0L } ?: return false
        val prefs = prefs() ?: return false
        prefs.edit()
            .putLong(KEY_PROFILE_ID, id)
            .putString(KEY_PROFILE_NAME, profile.name)
            .apply()
        return true
    }

    fun clear() {
        prefs()?.edit()
            ?.remove(KEY_PROFILE_ID)
            ?.remove(KEY_PROFILE_NAME)
            ?.apply()
    }
}
