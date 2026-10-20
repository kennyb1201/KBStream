package com.kennyb1201.kbstream.data.trakt

/**
 * The device-code flow as a state machine, with no network in it.
 *
 * Trakt communicates the flow's state with HTTP status codes, and the one that
 * matters most is the one that looks like a failure: **400 while the viewer has
 * not approved yet is normal**. Treating it as an error is what turns polling
 * into either a crash loop or an instant "couldn't connect" on a screen the
 * viewer is still reading. The whole code-to-state table lives here so the poll
 * loop is a `when` over four lines and the table is pinned by a test instead of
 * by a device.
 *
 * Codes, per Trakt's device-token endpoint:
 *
 * | status | meaning |
 * |---|---|
 * | 200 | approved - the body carries the token pair |
 * | 400 | still waiting for the viewer |
 * | 404 | device code unknown |
 * | 409 | code already used (another poll won the race) |
 * | 410 | code expired |
 * | 418 | viewer denied the app |
 * | 429 | polling too fast - slow down (device-flow spec: +5 s) |
 *
 * The elapsed/expiry check comes first on purpose: a 400 that arrives after
 * `expires_in` has passed is not "keep waiting", it is a code Trakt will never
 * accept, and the screen has to say so.
 */
object TraktDeviceFlowRules {

    /** What one poll means. */
    enum class Poll {
        /** Approved: the response body holds the token pair. */
        APPROVED,

        /** Not yet approved; keep polling at the current interval. */
        PENDING,

        /** Trakt says the interval is too short; widen it and keep polling. */
        SLOW_DOWN,

        /** The code is dead (expired, unknown, or already consumed). */
        EXPIRED,

        /** The viewer explicitly denied the app. */
        DENIED,

        /** Anything else: a transport or server failure worth one retry, not a state. */
        FAILED,

        /**
         * Trakt rejected this app's credentials rather than the code.
         *
         * A separate state from [FAILED] because retrying cannot help and
         * "waiting for approval…" forever would be a lie: the request never had
         * a chance. The client is a public client and ships no secret, so this
         * is the answer to surface if Trakt ever stops accepting a secret-less
         * device-token request.
         */
        MISCONFIGURED
    }

    /** How much longer the code lives, measured from the code request. */
    fun isExpired(elapsedSeconds: Long, expiresInSeconds: Int): Boolean =
        elapsedSeconds >= expiresInSeconds.coerceAtLeast(0)

    /**
     * Reads one poll.
     *
     * @param statusCode the HTTP status of the poll
     * @param hasToken whether the (parsed) body actually carried an access token
     * @param elapsedSeconds seconds since the device code was issued
     * @param expiresInSeconds the code's own lifetime, from the code response
     */
    fun poll(
        statusCode: Int,
        hasToken: Boolean,
        elapsedSeconds: Long,
        expiresInSeconds: Int,
        error: String? = null
    ): Poll {
        if (error != null && error in CREDENTIAL_ERRORS) return Poll.MISCONFIGURED
        if (isExpired(elapsedSeconds, expiresInSeconds)) return Poll.EXPIRED
        return when (statusCode) {
            200 -> if (hasToken) Poll.APPROVED else Poll.FAILED
            400 -> Poll.PENDING
            429 -> Poll.SLOW_DOWN
            418 -> Poll.DENIED
            404, 409, 410 -> Poll.EXPIRED
            else -> Poll.FAILED
        }
    }

    /**
     * What an `EXPIRED` read really is, given the whole flow so far.
     *
     * A 404 means "that code expired" on its own, and it stays that way when
     * every poll in the flow produced a status. After a poll THREW, it does not:
     * the request that threw may have been the 200 that traded the code for a
     * token (Trakt deletes the code on exchange), so the 404 is that spent code
     * being read again and the fault is this app's, not the viewer's timeout.
     * Reported as [Poll.FAILED] so the screen can be honest about it.
     *
     * Only 404 moves: 409/410 are Trakt stating the code is gone, which a local
     * throw cannot explain away.
     */
    fun expiredOutcome(sawPollError: Boolean, statusCode: Int?): Poll =
        if (sawPollError && statusCode == 404) Poll.FAILED else Poll.EXPIRED

    /** OAuth errors that mean "this client is not accepted", not "not yet". */
    private val CREDENTIAL_ERRORS = setOf(
        "invalid_client",
        "invalid_grant",
        "unauthorized_client"
    )

    /** The device-flow spec's answer to 429: five seconds slower than before. */
    fun nextIntervalSeconds(currentSeconds: Int): Int =
        currentSeconds.coerceAtLeast(1) + 5

    /** The delay between polls, in milliseconds. */
    fun intervalMillis(intervalSeconds: Int): Long =
        intervalSeconds.coerceAtLeast(1) * 1_000L

    /**
     * When the granted token stops being valid.
     *
     * Derived from Trakt's own `created_at` when it is there, so a device whose
     * clock drifted during the flow cannot shift the refresh window; when the
     * field is absent, the grant's own `expires_in` is measured from now. A
     * response with no `expires_in` gets "already due", which refreshes rather
     * than trusting a token of unknown age.
     */
    fun expiresAtEpochMillis(
        createdAtEpochSeconds: Long?,
        expiresInSeconds: Long,
        nowMs: Long
    ): Long {
        if (expiresInSeconds <= 0L) return nowMs
        val createdMs = createdAtEpochSeconds?.times(1_000L) ?: nowMs
        return createdMs + expiresInSeconds * 1_000L
    }

    /**
     * Whether the token should be refreshed before use.
     *
     * Proactive, not on-401: the access token lasts seven days, and a refresh at
     * play time would be a network round-trip in front of the first scrobble.
     * One day of skew also covers a device that slept through the boundary.
     */
    fun refreshDue(
        expiresAtEpochMillis: Long,
        nowMs: Long,
        skewMs: Long = 24L * 60L * 60L * 1_000L
    ): Boolean {
        if (expiresAtEpochMillis <= 0L) return true
        return nowMs >= expiresAtEpochMillis - skewMs
    }
}

/**
 * The two facts about one device flow's polls that its ending depends on.
 *
 * A single poll cannot answer "did this flow already go wrong?". The loop used
 * to decide from the last read alone, so `[400, throw, 404]` read as a timeout:
 * the throw was counted as one more failed poll, the retry hit a code that the
 * throwing poll had already spent, and the screen blamed the viewer's clock.
 * The loop records both facts here instead, and [TraktDeviceFlowRules.expiredOutcome]
 * turns them into the outcome it reports. Pure, so the sequence is a test rather
 * than a field report.
 */
class TraktFlowEvidence {

    private var sawPollError = false

    private var lastStatusCode: Int? = null

    /** One completed read: the status is what Trakt answered (null for none). */
    fun recordPoll(statusCode: Int?) {
        lastStatusCode = statusCode
    }

    /** A poll that threw before any status could be read. */
    fun recordThrow() {
        sawPollError = true
    }

    /** What the flow's `EXPIRED` read may be reported as. */
    fun expiredOutcome(): TraktDeviceFlowRules.Poll =
        TraktDeviceFlowRules.expiredOutcome(
            sawPollError = sawPollError,
            statusCode = lastStatusCode
        )
}
