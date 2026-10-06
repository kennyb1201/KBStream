package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.format.DateFormats
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * The date arithmetic behind the up-next and upcoming rails.
 *
 * Extracted from HomeViewModel: these only ever read strings and
 * return dates, timestamps or labels, so they need none of the
 * class's state and can be checked on their own.
 */

internal fun parseTimestampMillis(
    value: String?
): Long {

    return try {

        OffsetDateTime
            .parse(value)
            .toInstant()
            .toEpochMilli()

    } catch (_: Exception) {
        0L
    }
}

internal fun isWithinDays(
    dateStr: String,
    days: Int
): Boolean {

    return try {

        val date =
            LocalDate.parse(
                dateStr
            )

        val today =
            LocalDate.now()

        val diff =
            ChronoUnit.DAYS.between(
                date,
                today
            )

        diff in 0..days.toLong()

    } catch (_: Exception) {
        false
    }
}

/**
 * Whether an episode's air date proves it has already aired.
 *
 * TMDB's episode dates are a CALENDAR DAY with no time of day, and for most
 * series that day is the evening it airs - not midnight. So a date that merely
 * EQUALS today proves nothing about availability: the episode can be fifteen
 * hours away. Treating it as aired is what put a show into Continue Watching at
 * 00:00 on its air date, offering an episode that is not out yet (reported as
 * "shows show up in continue watching at 12am the day they release but they
 * don't actually air til later in the night"). Only a date in the PAST proves
 * an episode has aired.
 *
 * The cost is deliberate and one-sided: an episode that aired late tonight is
 * offered from tomorrow rather than from midnight tonight, because this path
 * has no time of day to test against. An episode that has not aired yet is the
 * Upcoming rail's business, where [formatAirDateLabel] still labels it "Today"
 * - and [AirDateCorrection.nextAiring] counts today as still to come, so the
 * card is there.
 *
 * Blank and unparseable dates stay "aired": failing open must never hide a
 * show whose date is simply missing, and this rule is only ever a reason to
 * WITHHOLD an episode when the date says so.
 *
 * [today] is injectable so the boundary can be tested without a clock; every
 * caller in the app takes the default.
 */
internal fun isAiredOrUnknown(
    airDate: String?,
    today: LocalDate = LocalDate.now()
): Boolean {

    if (
        airDate.isNullOrBlank()
    ) {
        return true
    }

    return try {

        LocalDate
            .parse(airDate)
            .isBefore(today)

    } catch (_: Exception) {
        true
    }
}

internal fun parseReleaseDate(raw: String): LocalDate? {

    return runCatching {
        OffsetDateTime.parse(raw).toLocalDate()
    }.recoverCatching {
        LocalDate.parse(raw)
    }.getOrNull()
}

internal fun parseTmdbAirDate(raw: String?): Long? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return try {
        LocalDate.parse(value)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    } catch (_: Exception) {
        null
    }
}

internal fun formatAirDateLabel(raw: String?): String {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return "Date TBA"
    return try {
        val date = LocalDate.parse(value)
        val today = LocalDate.now(ZoneId.systemDefault())
        val days = ChronoUnit.DAYS.between(today, date)
        when {
            days == 0L -> "Today"
            days == 1L -> "Tomorrow"
            days in 2..6 -> "In $days days"
            else -> date.format(DateFormats.AIR_DATE)
        }
    } catch (_: Exception) {
        "Date TBA"
    }
}
