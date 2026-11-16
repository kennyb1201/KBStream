package com.kennyb1201.kbstream.data.simkl

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Refreshes the Simkl session on a 401 and retries the call ONCE (spec
 * 2026-11-16, F2).
 *
 * V1's tokens lasted five years, so this tree had no refresh path at all; V2's
 * last seven days. The refresh is here rather than at the call sites because a
 * 401 can come back from any of them - and a design that wired "refresh then
 * retry" into forty places would be forty chances to forget one.
 *
 * Three rules, all normative:
 *  - **At most one refresh per grant.** [refresh] is single-flight (see
 *    `SimklRepository.refreshBlocking`): Simkl returns the same refresh token
 *    and kills the old access token, so two concurrent refreshes cut each other
 *    off.
 *  - **Exactly one retry.** A second 401 is returned as-is: that is the
 *    re-authorise prompt, never a loop.
 *  - **Never on the oauth2 endpoints.** The token endpoint is the refresh; re-entering
 *    there on a 401 is the recursion this guard exists to stop.
 */
class SimklAuthInterceptor(
    private val refresh: (failedAccessToken: String?) -> String?
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath.trimStart('/')
        val response = chain.proceed(request)

        if (path.startsWith("oauth2/", ignoreCase = true)) return response
        if (response.code != 401) return response

        val failed = request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val fresh = refresh(failed) ?: return response
        if (fresh == failed) return response

        response.close()
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $fresh")
                .build()
        )
    }
}

/**
 * Reads a 429 before deciding anything (spec 2026-11-16, F2).
 *
 * The body is the difference between three unrelated problems: `rate_limit` is
 * the burst window and answers again in about a second, while
 * `user_limit_exceeded` and `app_limit_exceeded` have spent a DAILY quota and can
 * only be waited out. Retrying the last two here would burn a call per second
 * until midnight, so this interceptor retries the burst once and lets a daily
 * quota through untouched - the write legs already park on `Retry-After`, and a
 * read leg's caller treats the 429 as "no data this time".
 */
class SimklRateLimitInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code != 429) return response

        val error = runCatching {
            org.json.JSONObject(response.peekBody(PEEK_BYTES).string())
                .optString("error")
                .takeIf { it.isNotBlank() }
        }.getOrNull()
        val limit = SimklAuthV2Rules.rateLimit(response.code, error)
        if (SimklAuthV2Rules.isDailyQuota(limit)) return response

        val delayMs = SimklAuthV2Rules.retryDelayMs(
            limit = limit,
            retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
        )
        if (delayMs <= 0L) return response

        response.close()
        runCatching { Thread.sleep(delayMs.coerceAtMost(MAX_BURST_WAIT_MS)) }
        return chain.proceed(request)
    }

    private companion object {
        /** Enough for an error body; never a whole list response. */
        const val PEEK_BYTES = 2_048L

        /**
         * A burst retry waits about a second. Capped anyway: a 429 that carried a
         * long `Retry-After` was classified as a daily quota above and never
         * reaches this sleep.
         */
        const val MAX_BURST_WAIT_MS = 2_000L
    }
}
