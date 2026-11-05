package com.kennyb1201.kbstream.data.bingecat

/**
 * The device-code flow as a state machine, with no network in it.
 *
 * BingeCat is a real OAuth device flow (RFC 8628), so unlike Trakt it answers
 * with an `error` CODE in a 400 body rather than a status code per state. The
 * whole table lives here so the poll loop stays a `when` over its results, and
 * so every rule is pinned by a test instead of by a TV.
 *
 * | answer | meaning |
 * |---|---|
 * | 200 with a token | approved - terminal, the code is consumed |
 * | 400 `authorization_pending` | still waiting - the normal case |
 * | 400 `slow_down` | poll slower; the spec says +5 s, and it stays widened |
 * | 400 `expired_token` | the code is dead |
 * | 400 `access_denied` | the viewer refused the app |
 * | 400 `invalid_scope` | the APP is not allowed the scopes asked for |
 * | 401 `invalid_client` | the client id is wrong - retrying cannot help |
 * | anything else | one retry, not a state |
 *
 * The elapsed/expiry check runs FIRST: a `authorization_pending` that arrives
 * after `expires_in` is not "keep waiting", it is a code the server will never
 * accept, and a screen that keeps spinning would be lying.
 */
object BingeCatDeviceFlowRules {

    /** What one poll means. */
    enum class Poll {
        /** Approved: the body carries the token pair. Terminal. */
        APPROVED,

        /** Not approved yet; keep polling at the current interval. */
        PENDING,

        /** Polling too fast; widen the interval and keep polling. */
        SLOW_DOWN,

        /** The code is dead (expired, unknown or already consumed). */
        EXPIRED,

        /** The viewer explicitly denied the app. */
        DENIED,

        /**
         * The developer app is not allowed the scopes v1 asks for.
         *
         * Separate from [FAILED] because retrying is pointless and the fix is
         * an app setting the viewer can change: the screen names the missing
         * scope instead of printing `invalid_scope`.
         */
        SCOPE_REJECTED,

        /**
         * The client id was rejected (`invalid_client`).
         *
         * Retrying cannot help and "waiting for approval…" would be a lie: the
         * request never had a chance. A public client ships no secret, so this
         * is the answer to surface if BingeCat ever stops accepting a
         * secret-less device token.
         */
        MISCONFIGURED,

        /** Anything else: a transport or server failure worth one retry. */
        FAILED
    }

    /** How much longer the code lives, measured from the code request. */
    fun isExpired(elapsedSeconds: Long, expiresInSeconds: Int): Boolean =
        elapsedSeconds >= expiresInSeconds.coerceAtLeast(0)

    /**
     * Reads one poll.
     *
     * @param statusCode the HTTP status of the poll
     * @param hasToken whether the parsed body actually carried an access token
     * @param elapsedSeconds seconds since the device code was issued
     * @param expiresInSeconds the code's own lifetime, from the code response
     * @param error the OAuth `error` code from a non-2xx body
     */
    fun poll(
        statusCode: Int,
        hasToken: Boolean,
        elapsedSeconds: Long,
        expiresInSeconds: Int,
        error: String? = null
    ): Poll {
        if (statusCode in 200..299 && hasToken) return Poll.APPROVED
        if (isExpired(elapsedSeconds, expiresInSeconds)) return Poll.EXPIRED
        return when (error?.trim()?.lowercase()) {
            "authorization_pending" -> Poll.PENDING
            "slow_down" -> Poll.SLOW_DOWN
            "access_denied" -> Poll.DENIED
            "expired_token", "invalid_grant" -> Poll.EXPIRED
            "invalid_scope" -> Poll.SCOPE_REJECTED
            "invalid_client", "unauthorized_client" -> Poll.MISCONFIGURED
            else -> when (statusCode) {
                400, 428 -> Poll.PENDING
                401 -> Poll.MISCONFIGURED
                404, 410 -> Poll.EXPIRED
                403 -> Poll.DENIED
                429 -> Poll.SLOW_DOWN
                else -> Poll.FAILED
            }
        }
    }

    /** When the granted token stops being usable, from the grant itself. */
    fun expiresAtEpochMillis(
        createdAtEpochSeconds: Long?,
        expiresInSeconds: Long,
        nowMs: Long
    ): Long {
        val issuedMs = createdAtEpochSeconds?.takeIf { it > 0 }?.times(1_000L) ?: nowMs
        return issuedMs + expiresInSeconds.coerceAtLeast(0L) * 1_000L
    }

    /** The widened interval after a `slow_down`, as RFC 8628 prescribes. */
    fun slowedIntervalSeconds(currentSeconds: Int): Int =
        (currentSeconds + 5).coerceAtLeast(5)

    /**
     * What a rejected scope means in the viewer's language.
     *
     * `invalid_scope` is an APP-level fault: the `bca_…` application on
     * BingeCat has not been granted these scopes, so it is not the viewer's
     * account to fix. Saying which one is missing is the difference between a
     * dead end and a two-tap fix in the app's own settings.
     */
    fun scopeRejectionMessage(scopeLine: String = BingeCatConfig.SCOPES): String =
        "This BingeCat app is not allowed the scopes v1 needs ($scopeLine). " +
            "Enable ${BingeCatConfig.REQUIRED_LISTS_SCOPE} on the app in BingeCat's " +
            "developer settings, then try again."
}
