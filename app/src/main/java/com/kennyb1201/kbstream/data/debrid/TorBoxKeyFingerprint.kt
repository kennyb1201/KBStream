package com.kennyb1201.kbstream.data.debrid

import java.security.MessageDigest

/**
 * The marker a settings row shows when this device verified the key earlier.
 *
 * Shared by the TV and phone cards so the same fact cannot be spelled two ways:
 * it is not an account detail, it is "this device already asked" (see
 * AppPreferences.isTorboxVerified), and the live probe's own answer carries the
 * account's count instead.
 */
internal const val TORBOX_VERIFIED_MARKER = "verified"

/**
 * The fingerprint a TorBox key's verification is remembered against.
 *
 * A verification is a fact about ONE key: "this account answered" says nothing
 * about whatever is pasted next. The answer therefore has to be filed under
 * something derived from the key it was made for - the same key matches, a
 * different key does not - and it must not be a second copy of the credential.
 * A digest is both: the stored value can never be read back as a key.
 *
 * Pure, so the rule is pinned by a unit test rather than by a device.
 */
internal fun torboxKeyFingerprint(key: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(key.trim().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
