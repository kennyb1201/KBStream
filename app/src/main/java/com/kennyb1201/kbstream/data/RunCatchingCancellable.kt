package com.kennyb1201.kbstream.data

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] for a block that SUSPENDS — same shape, same [Result], but a
 * [CancellationException] is rethrown instead of being captured.
 *
 * Why this exists: `runCatching` catches `Throwable`, and cancellation is
 * delivered as a `CancellationException`, so `runCatching` around suspending
 * work silently converts "this coroutine was cancelled" into "this operation
 * failed". Two things follow, and both are bugs:
 *
 *  - the coroutine keeps running. Kotlin cancellation is cooperative: swallowing
 *    the exception means the next statement executes, so a screen the user has
 *    left (or a worker Android has stopped, or a switched-away profile) carries
 *    on doing work — and every subsequent suspend call has to throw the
 *    cancellation again before it can stop.
 *  - the cancellation is reported as a failure. A cancelled fetch reaches
 *    `.onFailure { Log.w(...) }`, `CrashReporter.recordNonFatal` and the error
 *    snackbar, which is how "fetch failed" noise gets into the logs and into
 *    Sentry with no underlying fault, and how a real failure gets buried in it.
 *
 * Inline, like the stdlib's own [runCatching], so a suspending call is allowed
 * inside [block] (this is why it cannot be a normal function).
 *
 * Not for every block: converting one means the statements AFTER it in the
 * enclosing lambda are now skipped when the coroutine is cancelled. That is the
 * point for a fetch feeding UI state, but it is wrong for bookkeeping that must
 * happen either way — a sign-out that has to clear its own prefs, a resource
 * that has to be released. Those stay on plain `runCatching` (they are already
 * only safe by accident), or move the must-run part into `finally`/
 * `withContext(NonCancellable)`.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }
