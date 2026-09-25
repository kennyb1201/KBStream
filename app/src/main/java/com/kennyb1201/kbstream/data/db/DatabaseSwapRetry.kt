package com.kennyb1201.kbstream.data.db

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/**
 * Retrying work that a profile-scoped database swap interrupted.
 *
 * The guide and history databases are per-profile FILES with one live Room
 * instance each, and that instance is retired when the active profile changes
 * ([IptvDatabase.closeScopedInstanceForSwitch] and its history twin). The
 * retirement is deferred by a grace period so that work already in flight can
 * finish, but the grace is bounded: an EPG import runs for minutes, and any
 * caller that asks for a different profile's file inside the window builds
 * that instance instead — so the import's next batch can still land on a
 * closed handle. Room reports that as
 *
 *   IllegalStateException: The connection pool has been closed.
 *
 * and the process-wide symptom is a guide that never appears (Sentry
 * 7736349317). The same error is what a `SQLiteDatabaseLockedException` /
 * `SQLITE_BUSY` means when the instance being replaced still holds the schema
 * lock.
 *
 * Retrying is the right answer because the database layer settles by itself:
 * a failed attempt re-resolves the DAO through
 * [com.kennyb1201.kbstream.data.iptv.db.IptvDatabase.getInstance], which
 * either revives the instance that was pending close or builds a fresh one on
 * the settled file. What it must not do is treat a swap as a real failure —
 * that turns a momentary race into an error banner the user has to clear by
 * hand, or (worse, on the read side) into a lineup flow that stays dead and
 * empty for the rest of the screen's life.
 *
 * Nothing here retries a real failure: the guide import still fails loudly on
 * a network error or a malformed guide, and only the symptoms below count.
 *
 * Kept free of Android types so the rule can be unit tested.
 */

/** Depth cap on the cause walk: a wrapped error is still recognised, a cycle is not walked forever. */
private const val CAUSE_DEPTH_LIMIT = 8

/**
 * True when [t] is the scoped database being closed or swapped under a running
 * operation rather than a real failure.
 */
internal fun isTransientDatabaseError(t: Throwable): Boolean {
    var current: Throwable? = t
    var depth = 0
    while (current != null && depth < CAUSE_DEPTH_LIMIT) {
        // Matched by name: the SQLite exception type lives on the Android
        // classpath, which these JVM unit tests do not have.
        if (current::class.java.simpleName == "SQLiteDatabaseLockedException") return true
        val message = current.message.orEmpty().lowercase()
        if (
            message.contains("already-closed object") ||
            message.contains("connection pool has been closed") ||
            message.contains("database is locked") ||
            message.contains("sqlite_busy")
        ) {
            return true
        }
        current = current.cause
        depth++
    }
    return false
}

/** How many times a swap-interrupted operation is re-run before it fails. */
internal const val DB_SWAP_RETRY_ATTEMPTS = 3

/** First pause between attempts; doubles per attempt, capped. */
internal const val DB_SWAP_RETRY_DELAY_MS = 250L

internal const val DB_SWAP_RETRY_MAX_DELAY_MS = 2_000L

/**
 * Runs [block], re-running it while it fails with a [isTransientDatabaseError]
 * symptom, up to [attempts] times with a doubling pause. [onRetry] is called
 * before each wait so the swap is visible in logcat.
 *
 * A [CancellationException] always propagates: a cancelled guide read must not
 * be retried back to life.
 */
internal suspend fun <T> withDatabaseSwapRetry(
    attempts: Int = DB_SWAP_RETRY_ATTEMPTS,
    delayMs: Long = DB_SWAP_RETRY_DELAY_MS,
    onRetry: (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
    block: suspend () -> T
): T {
    var wait = delayMs
    for (attempt in 1..attempts) {
        try {
            return block()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (attempt == attempts || !isTransientDatabaseError(t)) throw t
            onRetry(attempt, t)
            delay(wait)
            wait = minOf(wait * 2, DB_SWAP_RETRY_MAX_DELAY_MS)
        }
    }
    // Unreachable: the loop either returns or throws on its last attempt.
    throw IllegalStateException("database swap retry loop fell through")
}

/**
 * The [Flow] form: a source that fails with a swap symptom is re-collected, so
 * the retry re-runs the query against the instance the database layer settled
 * on instead of tearing the collector's flow down.
 */
internal fun <T> Flow<T>.retryOnDatabaseSwap(
    attempts: Int = DB_SWAP_RETRY_ATTEMPTS,
    delayMs: Long = DB_SWAP_RETRY_DELAY_MS,
    onRetry: (attempt: Int, error: Throwable) -> Unit = { _, _ -> }
): Flow<T> = retryWhen { cause, attempt ->
    val attemptNumber = attempt.toInt() + 1
    val retryable = isTransientDatabaseError(cause) && attemptNumber < attempts
    if (retryable) {
        onRetry(attemptNumber, cause)
        delay(minOf(delayMs * attemptNumber, DB_SWAP_RETRY_MAX_DELAY_MS))
    }
    retryable
}
