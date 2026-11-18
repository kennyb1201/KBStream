package com.kennyb1201.kbstream.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * The PIN hashes: the app-level parent PIN and every profile PIN.
 *
 * Spec audit 2026-11-18, Settings L2. Both used to live in ordinary
 * `SharedPreferences` files - the parent hash in `kids_time_guard`, each profile
 * hash inside `profiles_json` - so a copied data directory (a backup, a rooted
 * box, an adb pull) carried salted PBKDF2 hashes of 4-digit PINs. A 4-digit PIN
 * under 100k rounds is minutes-per-PIN work offline, which is not account risk
 * but is exactly the household gate these PINs exist to be. `PinHash`'s own
 * header says so; the storage is what did not agree with it.
 *
 * They are encrypted at rest now, in one store via [SecureTokenStore] - the same
 * master key that holds the bearer tokens - and both legacy locations are
 * DRAINED on the first read, then erased (see the callers).
 *
 * ## The one rule that matters here: a PIN can never be stranded
 *
 * A parent PIN that cannot be read is a parent locked out of their own device
 * with no way back: the editor refuses to set a new one while it believes a PIN
 * exists, and the veil asks for a PIN nobody can produce. So:
 *
 *  - Every write is VERIFIED by reading it back ([write]), and a write that does
 *    not read back is reported as a failure rather than assumed.
 *  - The legacy value is erased only after a verified write of its replacement.
 *  - [SecureTokenStore] itself falls back to a plain store when the KeyStore
 *    cannot provide a master key, so "encrypted" never means "unavailable" -
 *    the fallback is weaker storage, not a lost credential.
 *
 * That is the whole safety story: no path here can erase the only copy of a hash
 * without having stored another one first.
 */
object PinVault {

    private const val TAG = "PinVault"

    /**
     * Store name. Deliberately NOT profile-scoped: the profile list is one
     * account-wide document, so its hashes are keyed by profile id inside one
     * store rather than one store per profile.
     */
    private const val STORE = "kbstream_pin_vault"

    private const val KEY_PARENT = "parent_pin_hash"

    private fun profileKey(profileId: String) = "profile_pin:$profileId"

    private fun prefs(context: Context): SharedPreferences =
        SecureTokenStore.prefs(context, STORE)

    /** The parent PIN's hash, or null when no parent PIN has been set. */
    fun parentHash(context: Context): String? = runCatching {
        prefs(context).getString(KEY_PARENT, null)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Sets (a hash) or clears (null/blank) the parent PIN. True only when the
     * value read back, so a caller can decide whether it is safe to drop a
     * legacy copy.
     */
    fun setParentHash(context: Context, hash: String?): Boolean =
        write(context, KEY_PARENT, hash, expect = hash?.takeIf { it.isNotBlank() })

    /** One profile's PIN hash, or null when that profile has no PIN. */
    fun profileHash(context: Context, profileId: String): String? {
        if (profileId.isBlank()) return null
        return runCatching {
            prefs(context).getString(profileKey(profileId), null)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** Sets or clears one profile's PIN hash; true only when it read back. */
    fun setProfileHash(context: Context, profileId: String, hash: String?): Boolean {
        if (profileId.isBlank()) return false
        return write(
            context,
            profileKey(profileId),
            hash,
            expect = hash?.takeIf { it.isNotBlank() }
        )
    }

    /** Drops one profile's hash (its profile is gone). */
    fun clearProfileHash(context: Context, profileId: String) {
        if (profileId.isBlank()) return
        setProfileHash(context, profileId, null)
    }

    /**
     * Writes [value] to [key] and verifies it, answering whether the store now
     * holds it (or, for a clear, no longer does).
     *
     * A failed write is logged rather than thrown: every caller is on a path a
     * viewer is waiting on - a PIN prompt, a profile read - and none of them can
     * do anything with an exception here.
     */
    private fun write(
        context: Context,
        key: String,
        value: String?,
        expect: String?
    ): Boolean = runCatching {
        val prefs = prefs(context)
        val editor = prefs.edit()
        if (expect == null) editor.remove(key) else editor.putString(key, expect)
        editor.commit()
        val stored = prefs.getString(key, null)?.takeIf { it.isNotBlank() }
        val ok = stored == expect
        if (!ok) Log.w(TAG, "PIN hash write did not read back for '$key'")
        ok
    }.getOrElse { error ->
        Log.w(TAG, "PIN hash write failed for '$key': ${error.javaClass.simpleName}")
        false
    }
}
