package com.kennyb1201.kbstream.data.mdblist

/**
 * The watched-snapshot retry storm, as rules: a spent MDBList budget must be
 * told apart from a transient failure, and backing off must expire.
 *
 * When the daily allowance is gone, `MdbListClient`'s budget interceptor
 * refuses the request locally and answers with its own 429 instead of calling
 * the API — no real quota is burned, but `getWatchedSnapshot` used to read
 * that as just another failure: log it, return an empty snapshot, cache
 * nothing (the `!result.isEmpty` guard is deliberate, so a transient failure
 * cannot blank badges). With neither the TTL nor the cache engaged, every
 * later caller missed and tried again, and the field log caught ~40 attempts
 * in 30 seconds, each logging `sync/watched failed code=429`.
 *
 * The distinction has to be made on the interceptor's exact message, not on
 * the status code: a real MDBList 429 (a daily-limit or hourly-limit response)
 * is genuinely transient and keeps the old behavior. Both halves — "is this
 * block?" and "is the block still fresh?" — live here as plain functions so
 * they are testable without a live client.
 */

/**
 * The status line the local budget interceptor stamps on a request it refuses
 * to send. See `MdbListClient.budgetInterceptor`.
 */
internal const val MDBLIST_LOCAL_BUDGET_MESSAGE = "KBStream local MDBList budget"

/** The status code that carries [MDBLIST_LOCAL_BUDGET_MESSAGE]. */
internal const val MDBLIST_LOCAL_BUDGET_CODE = 429

/**
 * True only for the interceptor's synthetic 429.
 *
 * A real 429 from MDBList says "you have spent your allowance" too, but it
 * went out over the network and its body is the API's own error text, so it
 * must not be mistaken for the local block: a transient failure is allowed to
 * be retried, and only the local block is what the day will not give back.
 */
internal fun isLocalBudgetBlock(code: Int, message: String?): Boolean =
    code == MDBLIST_LOCAL_BUDGET_CODE && message == MDBLIST_LOCAL_BUDGET_MESSAGE

/**
 * Whether a budget-block stamp still holds callers off the download path.
 *
 * A stamp of zero means "never blocked", and it is checked explicitly rather
 * than left to the arithmetic: `now - 0` is the current epoch time, so on a
 * clock that is early enough (a boot with no network time yet) an absent stamp
 * would otherwise read as a fresh block and suppress a download that has no
 * reason to be suppressed.
 */
internal fun isBudgetBlockActive(blockedAtMs: Long, nowMs: Long, ttlMs: Long): Boolean =
    blockedAtMs > 0L && nowMs - blockedAtMs < ttlMs
