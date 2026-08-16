package com.kennyb1201.kbstream.data.iptv

import java.io.StringReader
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class XmltvParser {

    fun parse(content: String, sourceUrl: String? = null): XmltvGuide {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(content))

        val channels = mutableListOf<XmltvChannel>()
        val programs = mutableListOf<XmltvProgram>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> channels += readChannel(parser)
                    "programme" -> programs += readProgram(parser)
                }
            }
            eventType = parser.next()
        }

        return XmltvGuide(
            sourceUrl = sourceUrl,
            channels = channels,
            programs = programs
                .filter { it.channelId.isNotBlank() && it.endUtcMillis > it.startUtcMillis }
                .sortedBy { it.startUtcMillis }
        )
    }

    private fun readChannel(parser: XmlPullParser): XmltvChannel {
        val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
        val displayNames = mutableListOf<String>()
        var iconUrl: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "channel")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "display-name" -> displayNames += parser.nextText().trim()
                    "icon" -> iconUrl = parser.getAttributeValue(null, "src")?.trim()
                }
            }
            parser.next()
        }

        return XmltvChannel(
            id = id,
            displayNames = displayNames.filter { it.isNotBlank() }.distinct(),
            iconUrl = iconUrl?.ifBlank { null }
        )
    }

    private fun readProgram(parser: XmlPullParser): XmltvProgram {
        val channelId = parser.getAttributeValue(null, "channel")?.trim().orEmpty()
        val start = parseXmltvDate(parser.getAttributeValue(null, "start"))
        val end = parseXmltvDate(parser.getAttributeValue(null, "stop"))

        var title = ""
        var description: String? = null
        var category: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "programme")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> title = parser.nextText().trim()
                    "desc" -> description = parser.nextText().trim().ifBlank { null }
                    "category" -> category = parser.nextText().trim().ifBlank { null }
                }
            }
            parser.next()
        }

        return XmltvProgram(
            channelId = channelId,
            title = title.ifBlank { "Untitled Program" },
            description = description,
            category = category,
            startUtcMillis = start,
            endUtcMillis = end
        )
    }

    private fun parseXmltvDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L

        val normalized = value.trim().replace(Regex("\\s+"), " ")

        parseCompactXmltvDate(normalized)?.let { return it }
        parseIsoLikeXmltvDate(normalized)?.let { return it }

        return 0L
    }

    private fun parseCompactXmltvDate(value: String): Long? {
        val regex = Regex("""^(\d{4})(\d{2})(\d{2})(\d{2})?(\d{2})?(\d{2})?(?:\s?(Z|[+-]\d{4}))?.*$""")
        val match = regex.matchEntire(value) ?: return null

        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        val hour = match.groupValues[4].ifBlank { "00" }.toIntOrNull() ?: return null
        val minute = match.groupValues[5].ifBlank { "00" }.toIntOrNull() ?: return null
        val second = match.groupValues[6].ifBlank { "00" }.toIntOrNull() ?: return null
        val offset = match.groupValues.getOrNull(7).orEmpty()

        return runCatching {
            if (offset.isNotBlank()) {
                val zoneOffset = if (offset == "Z") {
                    ZoneOffset.UTC
                } else {
                    ZoneOffset.of(offset.substring(0, 3) + ":" + offset.substring(3, 5))
                }

                OffsetDateTime.of(
                    year,
                    month,
                    day,
                    hour,
                    minute,
                    second,
                    0,
                    zoneOffset
                ).toInstant().toEpochMilli()
            } else {
                LocalDateTime.of(year, month, day, hour, minute, second)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            }
        }.getOrNull()
    }

    private fun parseIsoLikeXmltvDate(value: String): Long? {
        val candidates = listOf(
            value,
            value.replace(" ", "T"),
            if (value.endsWith("Z")) value else "${value}Z"
        ).distinct()

        candidates.forEach { candidate ->
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
        }

        return null
    }
}
