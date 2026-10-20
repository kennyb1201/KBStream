package com.kennyb1201.kbstream.data.trakt

import android.util.Log
import com.kennyb1201.kbstream.BuildConfig
import kotlinx.coroutines.CancellationException

/**
 * One device-token request's answer, split from Retrofit so a poll can be
 * exercised with a stubbed socket.
 *
 * [retryAfterSeconds] is Trakt's `Retry-After`, when it sent one; [oauthError]
 * is the OAuth error code from a non-2xx body, which Retrofit cannot parse for
 * us.
 */
internal data class TraktPollHttp(
    val statusCode: Int,
    val retryAfterSeconds: Long? = null,
    val body: TraktTokenResponse? = null,
    val oauthError: String? = null
)

/**
 * One poll of the device code, and the persistence of what it grants.
 *
 * **A 200-with-token is terminal.** Trakt deletes the code at the moment of
 * exchange, so a code that has been answered 200 can never be polled again: the
 * next request reads 404, the flow reports "that code expired", and a grant
 * that actually succeeded looks like a failure. The old loop did exactly that
 * whenever anything below the HTTP call threw - the grant was consumed, the
 * exception was counted as "one more failed poll", and the retry was a request
 * for a code that no longer existed.
 *
 * So this class does the two things that stop it:
 *
 *  1. the poll is recorded in [TraktPollTrace] the instant the response is in,
 *     BEFORE any storage work, so a 200 is never missing from the evidence;
 *  2. a granted token is saved first and, if saving throws, the result is
 *     [TraktDeviceFlowRules.Poll.APPROVED] with a [TraktPollResult.saveError] -
 *     a separate outcome, never a retry. The viewer is told the account was
 *     approved but the token could not be kept, which is actionable; "the code
 *     expired" is not.
 *
 * Extracted from [TraktRepository] rather than inlined there so both rules can
 * be tested without a network, a device or a SharedPreferences file.
 */
internal class TraktPollExchange(
    /** Performs the one HTTP request for a device code. */
    private val fetch: suspend (String) -> TraktPollHttp,
    /** Persists a granted token; may throw, which is a supported outcome. */
    private val save: (TraktToken) -> Unit,
    /** The clock the expiry is derived from; a parameter so a test can fix it. */
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    suspend fun pollOnce(
        deviceCode: String,
        elapsedSeconds: Long,
        expiresInSeconds: Int
    ): TraktPollResult {
        val http = fetch(deviceCode)
        val state = TraktDeviceFlowRules.poll(
            statusCode = http.statusCode,
            hasToken = !http.body?.accessToken.isNullOrBlank(),
            elapsedSeconds = elapsedSeconds,
            expiresInSeconds = expiresInSeconds,
            error = http.oauthError
        )
        // Evidence first: the in-app report is written from the response alone,
        // so no later throw can erase the poll that decided the outcome. A
        // viewer's TV runs the signed release APK, where the debug log below is
        // compiled out and logcat is unreachable.
        TraktPollTrace.record(
            statusCode = http.statusCode,
            elapsedSeconds = elapsedSeconds,
            expiresInSeconds = expiresInSeconds,
            state = state
        )
        // Debug builds only: one failed connect has to say whether Trakt
        // answered 404/409/410 or the local math is wrong. Never the device
        // code, the user code or a token - those are live credentials.
        if (BuildConfig.DEBUG) {
            Log.d(
                "TraktPoll",
                "status=${http.statusCode} elapsed=${elapsedSeconds}s " +
                    "expiresIn=${expiresInSeconds}s state=$state"
            )
        }
        if (state != TraktDeviceFlowRules.Poll.APPROVED) {
            return TraktPollResult(
                state,
                retryAfterSeconds = http.retryAfterSeconds,
                statusCode = http.statusCode
            )
        }
        val granted = http.body ?: return TraktPollResult(
            TraktDeviceFlowRules.Poll.FAILED,
            statusCode = http.statusCode
        )
        val token = TraktToken(
            accessToken = granted.accessToken.orEmpty(),
            refreshToken = granted.refreshToken,
            expiresAtEpochMillis = TraktDeviceFlowRules.expiresAtEpochMillis(
                createdAtEpochSeconds = granted.createdAtEpochSeconds,
                expiresInSeconds = granted.expiresInSeconds,
                nowMs = nowMs()
            )
        )
        // Persist FIRST, and nothing else before it: the code is already spent,
        // so there is no second chance to read this token pair.
        return try {
            save(token)
            TraktPollResult(
                TraktDeviceFlowRules.Poll.APPROVED,
                token,
                statusCode = http.statusCode
            )
        } catch (cancellation: CancellationException) {
            // The screen is leaving; this is not a storage fault to report and
            // not a poll worth repeating either.
            throw cancellation
        } catch (error: Exception) {
            TraktPollTrace.recordSaveFailure(error)
            TraktPollResult(
                state = TraktDeviceFlowRules.Poll.APPROVED,
                saveError = TraktPollTrace.describe(error),
                statusCode = http.statusCode
            )
        }
    }
}
