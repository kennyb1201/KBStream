package com.kennyb1201.kbstream.data.simkl

/**
 * How long a show-library blob minted AFTER a watched write is still treated as
 * unsettled.
 *
 * The write path already drops the cached library
 * (`SimklRepository.invalidateWatchedSnapshots`), but the fetch that follows it
 * can land before Simkl's own read API has applied the write: the blob then
 * still carries the PRE-write tally - "17 of 18 aired watched" for a show whose
 * last aired episode was just finished - and, being newer than the write, it
 * would otherwise be trusted for the next twelve hours. A blob minted inside
 * this window is therefore not evidence of anything yet, and the caught-up leg
 * refreshes once more; that is what turns a single raced fetch into one more
 * read instead of a half-day stale rail.
 *
 * Bounded on purpose, and by the data rather than by a counter: the retry only
 * repeats while the newest blob is inside the window, so a Home load with no
 * watched change behind it never refetches, and once a blob minted after the
 * window exists the extra reads stop.
 */
internal const val SHOW_LIBRARY_WRITE_SETTLE_MS =
    60_000L

/**
 * Whether the show library minted at [blobUpdatedAt] may answer the caught-up
 * decision, given the last watched write this device made ([lastWatchedWriteAt],
 * see `WatchedWriteStamp`).
 *
 * Three answers, each deliberate:
 *
 * - No blob at all ([blobUpdatedAt] null): untrusted - i.e. the caller passes
 *   `forceRefresh = true`, which changes nothing, because a read is happening
 *   anyway. Saying "trusted" here would only be a way to forget.
 * - No watched write on this profile ([lastWatchedWriteAt] 0 or less): trusted.
 *   There is no write the blob could have missed, and an untouched library is
 *   the cold-start case the cache exists for.
 * - Otherwise: trusted only when it was minted at least [settleMs] after that
 *   write - before it is the reported miss (the blob predates the finish), and
 *   just after it is a fetch that may have raced Simkl's own apply (see
 *   [SHOW_LIBRARY_WRITE_SETTLE_MS]).
 *
 * Pure arithmetic on two timestamps, in a file with no Android or Compose
 * dependency, because this is the whole of the fix and a contract test asserts
 * every branch exactly.
 */
internal fun isShowLibraryBlobTrustworthy(
    blobUpdatedAt: Long?,
    lastWatchedWriteAt: Long,
    settleMs: Long = SHOW_LIBRARY_WRITE_SETTLE_MS
): Boolean {
    if (blobUpdatedAt == null) return false
    if (lastWatchedWriteAt <= 0L) return true
    return blobUpdatedAt >= lastWatchedWriteAt + settleMs
}
