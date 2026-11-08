package com.kennyb1201.kbstream.data.simkl

/**
 * How long a show-library blob minted AFTER a watched write is still treated as
 * unsettled.
 *
 * The window is a FALLBACK, not the rail's gate (upcoming-caught-up spec F3c).
 * It decides only what this Simkl leg may serve for the shows this leg is the
 * only evidence for; a show another source proves caught up is on the rail
 * whatever this leg's blob says, so the window can be wrong about Simkl's apply
 * lag without hiding it. That is the only honest role left for a settle guess:
 * the field proved the 60s assumption wrong (a refresh minted two minutes after
 * the finish still carried the pre-finish counts), while a union of sources does
 * not need the guess to be right at all.
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
 * - No watched write on this profile ([lastWatchedWriteAt] 0 or less): trusted
 *   FOR THIS LEG. The stamp is device-local, so a zero says only "this device
 *   has not written", never "nothing was watched" - a finish made on another
 *   device leaves it at zero (R1). Trusting the blob here is therefore only
 *   safe because the rail no longer depends on this answer: another source
 *   proves such a show itself, and the leg that proves it builds the card.
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
