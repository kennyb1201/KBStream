package com.kennyb1201.kbstream.data.iptv

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class XmltvParser {

    fun parse(content: String, sourceUrl: String? = null): XmltvGuide {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(content))

        val channels = mutableListOf<XmltvChannel>()
        val programmes = mutableListOf<XmltvProgram>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> channels += readChannel(parser)
                    "programme" -> programmes += readProgramme(parser)
                }
            }
            eventType = parser.next()
        }

        return XmltvGuide(
            sourceUrl = sourceUrl,
            channels = channels,
            programmes = programmes
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

    private fun readProgramme(parser: XmlPullParser): XmltvProgram {
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
        val basePattern = Regex("""^(\d{14})(?:\s([+-]\d{4}))?.*$""")
        val match = basePattern.matchEntire(normalized) ?: return 0L

        val base = match.groupValues[1]
        val offset = match.groupValues.getOrNull(2).orEmpty()

        return runCatching {
            val year = base.substring(0, 4).toInt()
            val month = base.substring(4, 6).toInt()
            val day = base.substring(6, 8).toInt()
            val hour = base.substring(8, 10).toInt()
            val minute = base.substring(10, 12).toInt()
            val second = base.substring(12, 14).toInt()

            if (offset.isNotBlank()) {
                val isoOffset = offset.substring(0, 3) + ":" + offset.substring(3, 5)
                OffsetDateTime.parse(
                    "%04d-%02d-%02dT%02d:%02d:%02d%s".format(
                        year, month, day, hour, minute, second, isoOffset
                    ),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME
                ).toInstant().toEpochMilli()
            } else {
                LocalDateTime.of(year, month, day, hour, minute, second)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            }
        }.getOrDefault(0L)
    }
}
