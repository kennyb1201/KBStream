package com.kennyb1201.kbstream.data.iptv

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * XMLTV date parsing, lifted out of [XmltvImporter] so it can be unit tested.
 *
 * This is the messiest input the guide receives and the importer itself says
 * so: it keeps a budgeted "DATE PARSE FAILED" log because real guides emit
 * dates no single format matches. Getting it wrong is silent — a program lands
 * at epoch 0 (and is dropped as `end <= start`) or spans the wrong hours — so
 * the accepted spellings are pinned by tests rather than by reading the parser.
 *
 * Accepted forms (all UTC unless the value carries an offset):
 *  - compact `yyyyMMddHHmmss`, and the 12/10/8-digit truncations, optionally
 *    followed by a whitespace-separated offset (`+0200`, `+02:00`, `Z`, `UTC`)
 *  - ISO-like `2026-09-21T12:00:00Z` / `2026-09-21 12:00:00Z`, with or without
 *    an offset, with or without a `Z`.
 *
 * @return epoch millis, or null when nothing matched (callers decide whether
 * that is worth logging).
 */
internal fun parseXmltvDateMillis(value: String?): Long? {
    val normalized = value?.trim().orEmpty()
    if (normalized.isBlank()) return null

    parseCompactXmltvDate(normalized)?.let { return it }
    parseIsoLikeXmltvDate(normalized)?.let { return it }
    return null
}

private fun parseCompactXmltvDate(value: String): Long? {
    val firstWhitespace = value.indexOfFirst { it.isWhitespace() }
    val datePart = if (firstWhitespace == -1) value else value.substring(0, firstWhitespace)
    val tzPart = if (firstWhitespace == -1) {
        null
    } else {
        value.substring(firstWhitespace).trim().takeIf { it.isNotEmpty() }
    }

    val normalizedDate = when (datePart.length) {
        14 -> datePart
        12 -> datePart + "00"
        10 -> datePart + "0000"
        8 -> datePart + "000000"
        else -> return null
    }

    return runCatching {
        if (!tzPart.isNullOrBlank()) {
            val normalizedTz = normalizeXmltvOffset(tzPart) ?: return null
            OffsetDateTime.parse("$normalizedDate $normalizedTz", XMLTV_OFFSET_FORMATTER)
                .toInstant()
                .toEpochMilli()
        } else {
            LocalDateTime.parse(normalizedDate, XMLTV_UTC_FORMATTER)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }
    }.getOrNull()
}

private fun normalizeXmltvOffset(value: String): String? {
    val offset = value.trim()
    return when {
        offset.equals("Z", ignoreCase = true) -> "+0000"
        offset.equals("UTC", ignoreCase = true) -> "+0000"
        OFFSET_4.matches(offset) -> offset
        OFFSET_WITH_COLON.matches(offset) -> offset.replace(":", "")
        else -> null
    }
}

private fun parseIsoLikeXmltvDate(value: String): Long? {
    val withT = value.replace(' ', 'T')
    val withZ = if (value.endsWith("Z")) value else "${value}Z"

    return parseIsoCandidate(value)
        ?: parseIsoCandidate(withT)
        ?: if (withZ != value) parseIsoCandidate(withZ) else null
}

private fun parseIsoCandidate(candidate: String): Long? {
    runCatching {
        return OffsetDateTime.parse(candidate, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            .toInstant()
            .toEpochMilli()
    }
    runCatching {
        return LocalDateTime.parse(candidate.removeSuffix("Z"))
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
    }
    return null
}

private val XMLTV_UTC_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

private val XMLTV_OFFSET_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")

private val OFFSET_4 = Regex("[+-]\\d{4}")
private val OFFSET_WITH_COLON = Regex("[+-]\\d{2}:\\d{2}")
