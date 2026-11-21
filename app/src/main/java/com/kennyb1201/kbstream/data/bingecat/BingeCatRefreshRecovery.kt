package com.kennyb1201.kbstream.data.bingecat

/**
 * What to do when BingeCat refuses a refresh, and what to say about it.
 *
 * BingeCat ROTATES the token pair on every refresh: the old refresh token is
 * consumed by the call that used it. Two devices holding the same pair therefore
 * race - both refresh before the sync pull carries the winner's pair to the
 * other - and the loser presents a refresh token that no longer exists. The
 * server answers `invalid_grant`, which says nothing about the GRANT: a
 * perfectly good newer pair was minted seconds earlier by the sibling, and it is
 * either already in the account copy or about to land there.
 *
 * The app used to treat that answer as a lost session: it flagged the profile
 * `needsReconnect` and asked the viewer for a QR code to fix a session that was
 * never actually lost (field report, build 0.9.407: kids profile/3 with
 * `connected=no needs_reconnect=yes last_error=no-token` and its 34,049-title
 * mapping intact).
 *
 * The rules here are pure so the ordering they encode can be pinned without a
 * repository, a network or a device:
 *
 *  - a pair that is the SAME pair is not an answer (nothing to adopt);
 *  - a DIFFERENT pair in the account copy is the sibling's rotated pair, and is
 *    adopted exactly once;
 *  - a deliberate disconnect is never recovered - adoption applies to a refused
 *    pair, never to a tombstone;
 *  - the server's verdict is named, so the next paste says why a reconnect was
 *    (or was not) needed.
 */
internal object BingeCatRefreshRecovery {

    /** The verdict recorded when the adopted pair worked: no reconnect needed. */
    const val RECOVERED = "refresh-recovered-by-sync"

    /**
     * The account pair to adopt in place of the one just refused, or null when
     * there is nothing worth adopting.
     *
     * [held] is the pair that was refused: the exact token a failed refresh was
     * sent with (F1), or this profile's OWN stored copy (F2) - never a fresh
     * `read()`, which answers account-first and would compare the account pair
     * with itself. [account] is the synced copy, read AGAIN at the moment of the
     * refusal, because the sibling's publish can land during the refresh call
     * itself. [refusedAccessToken] is the access token the `needsReconnect`
     * marker names, used only when nothing is held: adopting the very pair that
     * was refused would just refuse again.
     *
     * Precedence is the account's, deliberately: the account copy is the
     * session every profile reads ([BingeCatAccountRules.resolve]), so a
     * different pair there IS the newer grant, whatever this profile's own stale
     * copy still says.
     */
    fun adoptable(
        signedOut: Boolean,
        held: BingeCatToken?,
        account: BingeCatToken?,
        refusedAccessToken: String?
    ): BingeCatToken? = when {
        // A person disconnected. That decision is not a rotation, and no account
        // pair may quietly undo it.
        signedOut -> null
        account == null -> null
        held != null && samePair(held, account) -> null
        held != null -> account
        // Nothing held: the marker names the refused access token, so the account
        // copy is only worth adopting when it is a different one.
        refusedAccessToken != null && account.accessToken == refusedAccessToken -> null
        else -> account
    }

    /**
     * The server's verdict on a refresh, as the diagnostics line names it.
     *
     * `invalid_grant` is the one that matters here - it is what a consumed
     * refresh token looks like, and it is recoverable when a sibling rotated the
     * pair. A 401 is the access token being refused; anything else is carried
     * with its status so a paste can tell a 400 from a 500.
     */
    fun verdict(httpCode: Int, oauthError: String?): String = when {
        oauthError == OAuthErrorInvalidGrant -> OAuthErrorInvalidGrant
        httpCode == 401 -> "401"
        else -> "http:$httpCode"
    }

    /** The OAuth error code BingeCat returns for a consumed refresh token. */
    private const val OAuthErrorInvalidGrant = "invalid_grant"

    private fun samePair(a: BingeCatToken, b: BingeCatToken): Boolean =
        a.accessToken == b.accessToken && a.refreshToken == b.refreshToken
}
