package com.kennyb1201.kbstream.data.simkl

/**
 * One stored session, as AUTH V2 keeps it (spec 2026-11-16, F2).
 *
 * [refreshToken] is the discriminator between the two registrations this app is
 * holding at once: V2 issued one on every grant, V1 never had one. A V1 row is
 * therefore the same data class with a null refresh token, which is what makes
 * "connected (legacy)" answerable offline.
 */
internal data class SimklSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMillis: Long = 0L,
    val scope: String? = null
) {
    val isV2: Boolean get() = SimklAuthV2Rules.isV2Session(refreshToken)

    val hasWriteScope: Boolean
        get() = SimklAuthV2Rules.scopeGrants(scope, SimklAuthV2Rules.WRITE_SCOPE)
}

/** What `POST /oauth2/device` hands back: the code to show and where to send the viewer. */
internal data class SimklDeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int
)

/** What one poll of the device code means, plus what the screen should say. */
internal data class SimklPollOutcome(
    val state: SimklAuthV2Rules.Poll,
    val message: String? = null,
    /** The widened wait after a `slow_down`, or null to keep the current one. */
    val intervalSeconds: Int? = null
) {
    val approved: Boolean get() = state == SimklAuthV2Rules.Poll.APPROVED
}

/**
 * Simkl AUTH V1 -> V2 (spec 2026-11-16): every decision the migration makes
 * that a JVM can make on its own.
 *
 * V2 retires the PIN flow this app shipped with and replaces it with the OAuth 2
 * device grant: `POST /oauth2/device` -> an 8-character code the viewer enters on
 * simkl.com/pin -> `POST /oauth2/token` polls until it is approved. The price is
 * a 7-day access token and a 180-day refresh token, so a whole lifecycle (F2)
 * comes with it, and the token becomes PER DEVICE, so the cross-device session
 * blob has to stop carrying it (F3).
 *
 * The rules live here rather than in the repository because each one has been a
 * shipped bug somewhere in this tree already: a case-sensitive `bearer`
 * comparison, a scope that cannot grow after the grant, a refresh that retries
 * forever, a 429 read as one undifferentiated failure. They are pinned by
 * [com.kennyb1201.kbstream.data.simkl.SimklAuthV2ContractTest].
 */
internal object SimklAuthV2Rules {

    // ── F1: the grant ────────────────────────────────────────────────────

    /**
     * The scopes the device request ASKS FOR (normative, spec F1).
     *
     * `media:write` is the half that matters: KBStream marks watched, rates,
     * adds to the watchlist and scrobbles, and a grant can never grow its scope
     * afterwards - the viewer would have to authorise again. Asking for read
     * only would be a silent downgrade that shows up months later as writes
     * failing on a token that never had permission to make them.
     */
    const val SCOPE = "media:read media:write"

    /** The write scope the tracker's own gates check on a stored grant. */
    const val WRITE_SCOPE = "media:write"

    /** Where the viewer enters the code, when the response names no address. */
    const val VERIFICATION_URL = "https://simkl.com/pin"

    /** RFC 8628's device-code grant type. */
    const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

    /** `grant_type` for a refresh. */
    const val REFRESH_GRANT_TYPE = "refresh_token"

    /**
     * The key V2's refresh token is stored under, in the device-local `simkl_auth`
     * preferences (see `SimklRepository`).
     *
     * Spelled once, here, because two files need it for opposite reasons: the
     * repository writes the token, and the sync payload reads for its PRESENCE to
     * know the row is device-local and must not travel. Two copies of the string
     * would let a rename quietly start syncing a token.
     */
    const val PREFS_REFRESH_TOKEN_KEY = "refresh_token"

    /** V2's access-token lifetime, as a fallback when a response omits it. */
    const val ACCESS_TOKEN_TTL_SECONDS = 604_800L

    /** V2's code lifetime, as a fallback when a response omits it. */
    const val DEVICE_CODE_TTL_SECONDS = 900L

    /** V2's poll interval, as a fallback when a response omits it. */
    const val POLL_INTERVAL_SECONDS = 5L

    /**
     * Refresh this long before the access token actually expires.
     *
     * A 7-day token refreshed exactly at the boundary still has to survive the
     * trip; refreshing a minute early costs one call per week and removes the
     * class of failure where a request is sent with a token that dies in flight.
     */
    const val REFRESH_SKEW_MS = 60_000L

    /**
     * Whether a granted `scope` string contains [needed].
     *
     * Space-delimited per RFC 6749 §3.3. A grant with no scope recorded is
     * treated as granting it: V2's device grant returns the scope it approved,
     * and refusing to write on a session whose scope was simply not stored would
     * be a downgrade this app invented.
     */
    fun scopeGrants(scope: String?, needed: String): Boolean {
        val recorded = scope?.trim()?.takeIf { it.isNotBlank() } ?: return true
        return recorded.split(' ', '\t')
            .any { it.equals(needed, ignoreCase = true) }
    }

    /** The code as the viewer types it: `XXXX-YYYY`, from either the raw or this form. */
    fun formatUserCode(raw: String?): String {
        val letters = normalizeUserCode(raw)
        if (letters.length <= 4) return letters
        return letters.substring(0, 4) + "-" + letters.substring(4)
    }

    /**
     * A code with its formatting removed, so entry is forgiving.
     *
     * The app never rejects a code for its shape: V2's codes are 8 characters
     * shown as `XXXX-YYYY`, and a viewer reading theirs off a TV will type it
     * with or without the hyphen, in either case, sometimes with a space. All of
     * those are the same code.
     */
    fun normalizeUserCode(raw: String?): String =
        raw.orEmpty().filter { !it.isWhitespace() && it != '-' && it != '_' }.uppercase()

    /**
     * Whether a `token_type` names a bearer token.
     *
     * V1 answered `bearer`; V2 answers `Bearer`. The migration guide warns about
     * it, because a case-sensitive comparison ships as a bug that only appears
     * on the new grant - and then every request is made with a header the server
     * reads as a different scheme.
     */
    fun isBearerTokenType(tokenType: String?): Boolean =
        tokenType?.trim()?.equals("Bearer", ignoreCase = true) == true

    /** When a grant stops being usable, measured from this device's own clock. */
    fun expiresAtEpochMillis(nowMs: Long, expiresInSeconds: Long?): Long =
        nowMs + (expiresInSeconds?.takeIf { it > 0L } ?: ACCESS_TOKEN_TTL_SECONDS) * 1_000L

    /** Whether the stored access token is due for a refresh. */
    fun isExpired(
        expiresAtMs: Long,
        nowMs: Long,
        skewMs: Long = REFRESH_SKEW_MS
    ): Boolean {
        // No expiry recorded (a V1 row, or a grant that omitted it): not expired,
        // because the refresh path is driven by the server's own 401 as well.
        if (expiresAtMs <= 0L) return false
        return nowMs + skewMs >= expiresAtMs
    }

    /** What one poll of the device code means. */
    enum class Poll {
        /** Approved: the body carries the token pair. Terminal. */
        APPROVED,

        /** Not approved yet; keep polling at the current interval. */
        PENDING,

        /** Polling too fast; widen the interval by five seconds and keep polling. */
        SLOW_DOWN,

        /** The code is dead. A new device authorisation is the only way on. */
        EXPIRED,

        /** The viewer declined on simkl.com. */
        DENIED,

        /** `invalid_client`: this build's client id is wrong. Retrying cannot help. */
        MISCONFIGURED,

        /** Anything else: a transport or server failure worth one more poll. */
        FAILED
    }

    /**
     * Reads one poll (spec F1's polling discipline).
     *
     * `authorization_pending` is the ordinary answer while the viewer types
     * their code, and it is ALSO what a decline looks like - Simkl writes
     * nothing on a decline - so the caller's own `expires_in` timeout is the
     * only signal the attempt ever gets that nobody approved it.
     */
    fun poll(
        statusCode: Int,
        hasToken: Boolean,
        elapsedSeconds: Long,
        expiresInSeconds: Int,
        error: String? = null
    ): Poll {
        if (statusCode in 200..299 && hasToken) return Poll.APPROVED
        if (isCodeExpired(elapsedSeconds, expiresInSeconds)) return Poll.EXPIRED
        return when (error?.trim()?.lowercase()) {
            "authorization_pending" -> Poll.PENDING
            "slow_down" -> Poll.SLOW_DOWN
            "access_denied" -> Poll.DENIED
            "expired_token", "invalid_grant" -> Poll.EXPIRED
            "invalid_client", "unauthorized_client" -> Poll.MISCONFIGURED
            else -> when (statusCode) {
                400, 428 -> Poll.PENDING
                401 -> Poll.MISCONFIGURED
                403 -> Poll.DENIED
                404, 410 -> Poll.EXPIRED
                429 -> Poll.SLOW_DOWN
                else -> Poll.FAILED
            }
        }
    }

    /** Whether the code's own lifetime has run out. */
    fun isCodeExpired(elapsedSeconds: Long, expiresInSeconds: Int): Boolean =
        elapsedSeconds >= expiresInSeconds.coerceAtLeast(0)

    /** The widened interval after a `slow_down`, as RFC 8628 prescribes. */
    fun slowedIntervalSeconds(currentSeconds: Int): Int = (currentSeconds + 5).coerceAtLeast(5)

    /** Seconds left on the code, for the countdown the screen shows. */
    fun secondsRemaining(elapsedSeconds: Long, expiresInSeconds: Int): Int =
        (expiresInSeconds - elapsedSeconds).coerceAtLeast(0L).toInt()

    // ── F3/F4: which session this device holds ───────────────────────────

    /**
     * Whether a stored session is a V2 one.
     *
     * The refresh token is the discriminator, not the token's shape: V2 issues
     * one on every grant, V1 never issued one at all, and a build that predates
     * this migration has a row with an access token and nothing else. That makes
     * "does this device need to reconnect" answerable from the store, offline,
     * with no prefix parsing and no second marker to keep in step.
     */
    fun isV2Session(refreshToken: String?): Boolean =
        !refreshToken.isNullOrBlank()

    /**
     * Whether the settings pane should offer the reconnect row (spec F4).
     *
     * Connected on a legacy session only: a V2 session has nothing to reconnect
     * for, and a disconnected profile's row already says "Simkl" and opens the
     * connect flow.
     */
    fun reconnectVisible(isConnected: Boolean, isV2Session: Boolean): Boolean =
        isConnected && !isV2Session

    /** The settings row's label: the legacy one names its state, the V2 one does not. */
    fun settingsRowLabel(isConnected: Boolean, isV2Session: Boolean): String =
        if (reconnectVisible(isConnected, isV2Session)) {
            "Simkl: connected (legacy)"
        } else {
            "Simkl"
        }

    /** The settings row's description under the legacy label. */
    const val LEGACY_DESCRIPTION =
        "Reconnect to keep Simkl syncing after the old sign-in is retired"

    /**
     * Which app registration a request identifies itself with.
     *
     * F4 keeps BOTH registrations live: the V1 app for the tokens already out
     * there, the V2 app for every new grant. The oauth2 endpoints exist only
     * in V2, so they always send the V2 id; every other request sends the id of
     * the registration that issued the session it is carrying, which is the V2 id
     * exactly when the stored session has a refresh token.
     *
     * A blank id falls back to the other one, so a build handed only one of the
     * two still works: a V2-only build can keep its own old data calls going, and
     * a V1-only build simply cannot start a new grant.
     */
    fun clientIdFor(
        path: String?,
        legacyId: String,
        v2Id: String,
        isV2Session: Boolean
    ): String {
        val wantsV2 = path?.trim()?.startsWith("oauth2/", ignoreCase = true) == true ||
            isV2Session
        val first = if (wantsV2) v2Id else legacyId
        if (first.isNotBlank()) return first
        return if (wantsV2) legacyId else v2Id
    }

    // ── F2: the server's own answers ─────────────────────────────────────

    /**
     * What a 429 body says the limit is (spec F2).
     *
     * The three are different problems and only one of them is worth waiting
     * for: `rate_limit` is the burst window and answers again in about a second,
     * `user_limit_exceeded` has spent THIS account's daily quota (wait
     * `Retry-After`), and `app_limit_exceeded` has spent the V1 app's. Reading
     * the body is the difference between retrying in a second and sleeping
     * through the afternoon.
     */
    enum class RateLimit {
        NONE,

        /** The burst window: ~1 s, and `Retry-After` is not to be trusted here. */
        BURST,

        /** This user's V2 daily quota: wait `Retry-After`. */
        USER_DAILY,

        /** The V1 app's daily quota: every user is affected. */
        APP_DAILY
    }

    fun rateLimit(statusCode: Int, bodyError: String?): RateLimit {
        if (statusCode != 429) return RateLimit.NONE
        return when (bodyError?.trim()?.lowercase()) {
            "rate_limit" -> RateLimit.BURST
            "user_limit_exceeded" -> RateLimit.USER_DAILY
            "app_limit_exceeded" -> RateLimit.APP_DAILY
            // An unrecognised body is a burst until it proves otherwise: a
            // second's wait is cheap, an hour's is not.
            else -> RateLimit.BURST
        }
    }

    /** How long to wait before trying again, per limit. */
    fun retryDelayMs(limit: RateLimit, retryAfterSeconds: Long?): Long = when (limit) {
        RateLimit.NONE -> 0L
        // Deliberately ignoring `Retry-After` here: Simkl's burst header is not
        // meaningful, and the guide says to retry in about a second.
        RateLimit.BURST -> 1_000L
        RateLimit.USER_DAILY, RateLimit.APP_DAILY ->
            retryAfterSeconds?.takeIf { it > 0L }?.times(1_000L) ?: 0L
    }

    /**
     * The daily quota is exhausted: waiting is the only thing that helps, and a
     * request made before the reset can only fail again.
     */
    fun isDailyQuota(limit: RateLimit): Boolean =
        limit == RateLimit.USER_DAILY || limit == RateLimit.APP_DAILY

    /**
     * A V1 token on a V2-only endpoint (spec F2's last paragraph).
     *
     * `403 oauth2_token_required` names the wrong GRANT, not a missing
     * permission: re-authorising the V1 app cannot clear it, and retrying it is
     * the one failure in this API that a refresh can never fix.
     */
    fun isV2GrantRequired(statusCode: Int, bodyError: String?): Boolean =
        statusCode == 403 &&
            bodyError?.trim()?.lowercase() == "oauth2_token_required"

    /** A 401 that names the missing session rather than a bad token. */
    fun isMissingSession(statusCode: Int, bodyError: String?): Boolean =
        statusCode == 401 &&
            bodyError?.trim()?.lowercase() == "user_token_required"
}
