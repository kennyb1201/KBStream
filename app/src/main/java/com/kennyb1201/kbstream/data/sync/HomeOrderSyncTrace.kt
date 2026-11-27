package com.kennyb1201.kbstream.data.sync

/**
 * One home-order sync touch, and what this device did with it
 * (spec 2026-11-27).
 *
 * @param side [HomeOrderSyncTrace.PUSH] or [HomeOrderSyncTrace.PULL].
 * @param updatedAt the arrangement stamp the push carried / the pull brought,
 *   milliseconds since epoch as the payload carries it. 0 when the payload had
 *   none.
 * @param localStamp this device's `home_order_synced_at` at the time.
 * @param decision one of [HomeOrderSyncTrace.PUBLISHED],
 *   [HomeOrderSyncTrace.ADOPTED], [HomeOrderSyncTrace.MERGED],
 *   [HomeOrderSyncTrace.REJECTED_STALE], [HomeOrderSyncTrace.BLANK_IGNORED].
 * @param profileId the profile the row belongs to; null is the legacy store.
 */
internal data class HomeOrderSyncEvent(
    val side: String,
    val updatedAt: Long,
    val localStamp: Long,
    val decision: String,
    val profileId: String?
)

/**
 * The last few home-order sync touches (spec 2026-11-27).
 *
 * A rail arrangement edited on the phone that never reaches the TV has two
 * failure modes, and every existing line reads the same for both: the phone
 * never published the row, or the TV pulled it and the last-write-wins guard
 * dropped it as older than its own stamp. This is the per-key record that tells
 * them apart - the stamp each side carried, and the decision the guard made.
 *
 * Stamps, decisions and the profile id only: the arrangement's rail keys and
 * names never enter this record, and it is bounded so a paste cannot grow.
 */
internal object HomeOrderSyncTrace {

    const val PUSH = "push"
    const val PULL = "pull"

    const val PUBLISHED = "published"
    const val ADOPTED = "adopted"
    const val MERGED = "merged"
    const val REJECTED_STALE = "rejected-stale"
    const val BLANK_IGNORED = "blank-ignored"

    /** Enough for a couple of edits each way, and small enough to stay a paste. */
    private const val MAX_EVENTS = 24

    private val events = ArrayDeque<HomeOrderSyncEvent>()

    fun record(
        side: String,
        updatedAt: Long,
        localStamp: Long,
        decision: String,
        profileId: String?
    ) {
        synchronized(events) {
            events.addLast(
                HomeOrderSyncEvent(
                    side = side,
                    updatedAt = updatedAt,
                    localStamp = localStamp,
                    decision = decision,
                    profileId = profileId
                )
            )
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    fun events(): List<HomeOrderSyncEvent> = synchronized(events) { events.toList() }

    /** The last pull this process saw, which is the decision the line reports. */
    fun lastPull(): HomeOrderSyncEvent? =
        synchronized(events) { events.lastOrNull { event -> event.side == PULL } }

    /** Test hook: the record is process-wide and must not leak between cases. */
    internal fun reset() {
        synchronized(events) { events.clear() }
    }
}

/**
 * The `sync home-order:` diagnostics line (spec 2026-11-27).
 *
 * Pure so the report's text is testable without a Context. [localStampMs] and
 * [pushedUpdatedAtMs] are the DURABLE stamps - the arrangement's own edit stamp
 * and the stamp the last queued push carried - so a paste taken after a
 * force-stop still answers; [lastPull] is the in-memory decision, because a
 * rejection is a moment, not a stored fact. [time] formats a stamp.
 */
internal fun homeOrderSyncLine(
    localStampMs: Long,
    pushedUpdatedAtMs: Long,
    lastPull: HomeOrderSyncEvent?,
    time: (Long) -> String
): String {
    fun stamp(ms: Long): String = if (ms <= 0L) "never" else time(ms)

    val pulled = lastPull?.let { event ->
        "${event.decision} remote=${stamp(event.updatedAt)} local=${stamp(event.localStamp)}"
    } ?: "none"

    return "sync home-order: local=${stamp(localStampMs)} " +
        "pushed=${stamp(pushedUpdatedAtMs)} pulled=$pulled"
}
