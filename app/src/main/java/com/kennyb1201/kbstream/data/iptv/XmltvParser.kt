package com.kennyb1201.kbstream.data.iptv

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
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
            programmes = programmes.sortedBy { it.startUtcMillis }
        )
    }

    private fun readChannel(parser: XmlPullParser): XmltvChannel {
        val id = parser.getAttributeValue(null, "id") ?: ""
        val displayNames = mutableListOf<String>()
        var iconUrl: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "channel")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "display-name" -> displayNames += parser.nextText().trim()
                    "icon" -> iconUrl = parser.getAttributeValue(null, "src")
                }
            }
            parser.next()
        }

        return XmltvChannel(
            id = id,
            displayNames = displayNames.distinct(),
            iconUrl = iconUrl
        )
    }

    private fun readProgramme(parser: XmlPullParser): XmltvProgram {
        val channelId = parser.getAttributeValue(null, "channel") ?: ""
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

        val normalized = when {
            value.matches(Regex("""d{14} [+-]d{4}""")) -> {
                val base = value.substring(0, 14)
                val offset = value.substring(15)
                val iso = "${base.substring(0, 4)}-${base.substring(4, 6)}-${base.substring(6, 8)}" +
                    "T${base.substring(8, 10)}:${base.substring(10, 12)}:${base.substring(12, 14)}" +
                    offset.substring(0, 3) + ":" + offset.substring(3, 5)
                OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
            }
            value.matches(Regex("""d{14}""")) -> {
                val iso = "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)}" +
                    "T${value.substring(8, 10)}:${value.substring(10, 12)}:${value.substring(12, 14)}Z"
                OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
            }
            else -> 0L
        }
        return normalized
    }
}
