package com.kennyb1201.kbstream.data.sync

/**
 * Publish/apply rules for the Simkl session blob.
 *
 * The blob is account-wide state written as a FULL REPLACE, and the applier
 * treats a blank token as "the user signed out". Those two halves were not
 * aware of each other: EVERY device published the blob, so a device that had
 * simply never connected Simkl published an empty token — and the moment its
 * push won the race (pressing "Sync now" pushes before it pulls), it erased
 * the account's Simkl session on every device. That is the "Simkl sign-in
 * didn't sync" report: the other TV wiped it instead of adopting it.
 *
 * The fix is one rule per side:
 *  - a device only PUBLISHES the blob when it has a token to share, or a
 *    deliberate disconnect to propagate (a fresh device publishes nothing),
 *  - a blank token only CLEARS the local session when it carries the explicit
 *    sign-out tombstone, so a blank row can never wipe a live session.
 *
 * AUTH V2 (spec 2026-11-16, F3) adds the third: a session carrying a refresh
 * token is DEVICE-LOCAL and is published not at all. Its tokens are per device,
 * so two devices sharing one refresh token cut each other off - the row has
 * nothing to say about a V2 session, not even "connected", because a marker
 * would claim a connection on a device whose own grant was revoked.
 *
 * What a V2 session DOES have to say travels on its own row instead: the link
 * state (see `SimklLinkRules` and `PrefsPayloadBuilder.KEY_SIMKL_LINK`), which
 * names the account and nothing else. Keeping that out of this blob is what
 * leaves every rule above - and every tombstone below - exactly as they were.
 *
 * This object is pure and lives in its own file so the rule can be exercised in
 * a plain JVM test, beside its V1/V2 sibling
 * [com.kennyb1201.kbstream.data.simkl.SimklAuthV2Rules].
 */
internal object SimklAuthRules {

    /** Payload field marking a blank token as a deliberate sign-out. */
    const val SIGNED_OUT_FIELD = "signed_out"

    fun shouldPublish(accessToken: String?, signedOut: Boolean): Boolean =
        !accessToken.isNullOrBlank() || signedOut

    /**
     * The AUTH V2 shape (spec 2026-11-16, F3): a session WITH a refresh token is
     * device-local and is never published - not the token, not a marker, not a
     * tombstone.
     *
     * The default keeps every V1 call site (and its tests) reading exactly as
     * before: a row with no refresh token is a legacy session, which keeps
     * travelling until Simkl retires AUTH V1.
     */
    fun shouldPublish(
        accessToken: String?,
        signedOut: Boolean,
        refreshToken: String? = null
    ): Boolean {
        if (com.kennyb1201.kbstream.data.simkl.SimklAuthV2Rules.isV2Session(refreshToken)) {
            return false
        }
        return !accessToken.isNullOrBlank() || signedOut
    }

    fun clearsSession(accessToken: String?, signedOut: Boolean): Boolean =
        accessToken.isNullOrBlank() && signedOut
}
