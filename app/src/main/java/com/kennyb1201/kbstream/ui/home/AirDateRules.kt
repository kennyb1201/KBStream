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

internal fun isAiredOrUnknown(
    airDate: String?
): Boolean {

    if (
        airDate.isNullOrBlank()
    ) {
        return true
    }

    return try {

        !LocalDate
            .parse(airDate)
            .isAfter(
                LocalDate.now()
            )

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
