package com.kennyb1201.kbstream.data.notifications

/**
 * When does a live-TV program reminder deserve an alert?
 *
 * The guide writes reminders keyed by channel + program start. While the
 * app is in the background nothing else watches the clock, so delivery runs
 * on scheduled work — this is the decision logic that work uses. Pure, so the
 * rules are unit tested rather than discovered on the TV.
 */
internal object ReminderRules {

    /** How long after a program ends its reminder is still worth keeping. */
    const val STALE_GRACE_MS = 10 * 60_000L

    /**
     * How late a "starting now" alert may still fire.
     *
     * There was NO upper bound: the worker only had to see a reminder after its
     * start time, so a deferred job / device-off catch-up could post "<title> is
     * starting now" hours after the program began.
     */
    const val DUE_LATENESS_MS = 30 * 60_000L

    /**
     * Running time assumed for a reminder whose guide entry carries no end, so
     * an end-less reminder is eventually pruned instead of living forever.
     */
    const val DEFAULT_PROGRAM_MS = 4 * 60 * 60_000L

    /** Stable key for one reminder — same format the store persists. */
    fun reminderKey(channelId: String, startUtcMillis: Long): String =
        "$channelId|$startUtcMillis"

    /**
     * Due = the program has started and has not been announced yet.
     * A start of 0 means the guide gave no schedule, which can never be due.
     */
    fun isDue(startUtcMillis: Long, nowMs: Long, alreadyNotified: Boolean): Boolean =
        !alreadyNotified &&
            startUtcMillis > 0 &&
            startUtcMillis <= nowMs &&
            nowMs - startUtcMillis <= DUE_LATENESS_MS

    /** Ended long enough ago that keeping the reminder only wastes work. */
    fun isStale(endUtcMillis: Long, nowMs: Long): Boolean =
        endUtcMillis > 0 && endUtcMillis + STALE_GRACE_MS <= nowMs

    /**
     * [isStale] for a reminder whose end may be unknown.
     *
     * endUtcMillis == 0 means the guide gave no end; [DEFAULT_PROGRAM_MS] from
     * the start stands in, so such a reminder no longer survives forever.
     */
    fun isStale(startUtcMillis: Long, endUtcMillis: Long, nowMs: Long): Boolean {
        val end = if (endUtcMillis > 0) endUtcMillis else startUtcMillis + DEFAULT_PROGRAM_MS
        return end > 0 && end + STALE_GRACE_MS <= nowMs
    }

    /**
     * Delay before a not-yet-started reminder should fire. Zero when the
     * program has already started, which the caller treats as "do not arm".
     */
    fun delayUntilStart(startUtcMillis: Long, nowMs: Long): Long =
        (startUtcMillis - nowMs).coerceAtLeast(0L)

    /**
     * Stable notification id per reminder, so re-arming the same reminder
     * replaces its alert instead of stacking a second one.
     *
     * Masked into the live-reminder band (0x20000000..0x3FFFFFFF) of the shared
     * notification id space, so it cannot collide with a new-episode alert
     * (0x00000000..0x1FFFFFFF, [NewEpisodeRules.notificationId]) or a game
     * reminder - a collision makes one alert replace the other and its tap open
     * the wrong target.
     */
    fun notificationId(key: String): Int = 0x20000000 or (key.hashCode() and 0x1FFFFFFF)
}
