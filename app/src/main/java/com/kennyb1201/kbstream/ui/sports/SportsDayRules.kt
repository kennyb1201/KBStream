package com.kennyb1201.kbstream.ui.sports

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Sports Hub's day navigation (spec audit 2026-11-18, Sports M2).
 *
 * The hub used to be TODAY-ONLY in every direction: `scoreboard()` reads the
 * feed's current day (plus the two carry-over windows a west-coast evening
 * needs), and nothing on either surface could ask for another one. So the
 * obvious question - "is the game tomorrow?" - had no answer in the app at all,
 * which is the whole finding.
 *
 * Three days, not a date picker: yesterday is the score you missed, today is the
 * slate, tomorrow is whether to plan the evening. A calendar would be a second
 * navigation problem (which of forty weeks?) for a hub whose job is one glance.
 *
 * Pure, and free of project imports, so a JVM test can pin the day arithmetic
 * across a DST boundary without a feed, a page or a device.
 *
 * The day is the VIEWER'S calendar day, in [ZoneId.systemDefault]. ESPN's
 * `dates=` parameter groups by the US EASTERN day, which is why the two can
 * disagree by one in the evening west of Eastern - but the viewer asked for
 * "tomorrow", and tomorrow is their own midnight, not the feed's. Both are
 * sent as `BASIC_ISO_DATE`, so the parameter is in the shape the endpoint
 * writes and reads.
 */
internal object SportsDayRules {

    const val YESTERDAY = -1
    const val TODAY = 0
    const val TOMORROW = 1

    /** The three days the hub offers, in the order the chips draw them. */
    val offsets: List<Int> = listOf(YESTERDAY, TODAY, TOMORROW)

    /** Clamps any offset into the three-day window the hub offers. */
    fun clamp(offset: Int): Int = offset.coerceIn(YESTERDAY, TOMORROW)

    /** The chip label, and the word an empty slate names. */
    fun label(offset: Int): String = when (clamp(offset)) {
        YESTERDAY -> "Yesterday"
        TOMORROW -> "Tomorrow"
        else -> "Today"
    }

    /** The viewer's calendar day [offset] days from [nowMs]. */
    fun day(
        offset: Int,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        .plusDays(clamp(offset).toLong())

    /**
     * The `dates=` value for [offset]: ESPN's own `BASIC_ISO_DATE` ("20261118"),
     * which is exactly what [EspnCacheRules][com.kennyb1201.kbstream.data.sports.EspnCacheRules]
     * already writes for the carry-over windows.
     */
    fun dayParam(
        offset: Int,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = day(offset, nowMs, zone).format(DateTimeFormatter.BASIC_ISO_DATE)

    /**
     * Whether [dateMs] falls on that day.
     *
     * For the one source that cannot filter by day server-side: TheSportsDB's
     * fixture read answers a whole round, so a fixture has to be placed on a day
     * here rather than asked for one. An UNDATED fixture is on no day (there is
     * no number to compare), the same rule the cache rules use for an undated
     * item.
     */
    fun containsMs(
        offset: Int,
        nowMs: Long,
        dateMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (dateMs <= 0L) return false
        return Instant.ofEpochMilli(dateMs).atZone(zone).toLocalDate() == day(offset, nowMs, zone)
    }

    /**
     * Whether live scores can be polled on this day.
     *
     * Only today's slate can have a game in play - a poll of a past or future
     * day would re-read a frozen list every 30 seconds for a score that cannot
     * change, so the guard sits in the ViewModel's poll arming rather than in
     * each league read.
     */
    fun pollsLive(offset: Int): Boolean = clamp(offset) == TODAY

    /**
     * The sentence an empty slate shows, e.g. "No NFL games on tomorrow."
     *
     * [subject] is what the tab is about - a league's label, or "your teams" on
     * the Favorites tab. TODAY keeps the sentence the hub has always shown; any
     * other day names the day, because the one thing a viewer must not be left
     * wondering is whether the hub is broken or simply showing a day they may
     * not remember picking.
     */
    fun emptyMessage(offset: Int, subject: String): String = when (clamp(offset)) {
        TODAY -> "No $subject games on the schedule right now."
        else -> "No $subject games on ${label(offset).lowercase()}. " +
            "Pick TODAY to come back."
    }

    /**
     * The Favorites tab's empty sentence, which asks a different question - "is
     * anyone I follow on?" rather than "what is on?" - so it keeps its own
     * wording on today and only names the day when there is one to name.
     */
    fun emptyFavoritesMessage(offset: Int): String = when (clamp(offset)) {
        TODAY -> "None of your teams are playing right now."
        else -> "None of your teams are playing ${label(offset).lowercase()}. " +
            "Pick TODAY to come back."
    }
}
