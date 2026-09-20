package com.kennyb1201.kbstream.data.notifications

/**
 * When does a live-TV programme reminder deserve an alert?
 *
 * The guide writes reminders keyed by channel + programme start. While the
 * app is in the background nothing else watches the clock, so delivery runs
 * on scheduled work — this is the decision logic that work uses. Pure, so the
 * rules are unit tested rather than discovered on the TV.
 */
internal object ReminderRules {

    /** How long after a programme ends its reminder is still worth keeping. */
    const val STALE_GRACE_MS = 10 * 60_000L

    /** Stable key for one reminder — same format the store persists. */
    fun reminderKey(channelId: String, startUtcMillis: Long): String =
        "$channelId|$startUtcMillis"

    /**
     * Due = the programme has started and has not been announced yet.
     * A start of 0 means the guide gave no schedule, which can never be due.
     */
    fun isDue(startUtcMillis: Long, nowMs: Long, alreadyNotified: Boolean): Boolean =
        !alreadyNotified && startUtcMillis > 0 && startUtcMillis <= nowMs

    /** Ended long enough ago that keeping the reminder only wastes work. */
    fun isStale(endUtcMillis: Long, nowMs: Long): Boolean =
        endUtcMillis > 0 && endUtcMillis + STALE_GRACE_MS <= nowMs

    /**
     * Delay before a not-yet-started reminder should fire. Zero when the
     * programme has already started, which the caller treats as "do not arm".
     */
    fun delayUntilStart(startUtcMillis: Long, nowMs: Long): Long =
        (startUtcMillis - nowMs).coerceAtLeast(0L)

    /**
     * Stable notification id per reminder, so re-arming the same reminder
     * replaces its alert instead of stacking a second one.
     */
    fun notificationId(key: String): Int = key.hashCode() and 0x7FFFFFFF
}
