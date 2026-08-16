package com.kennyb1201.kbstream.data.iptv

import android.util.Log
import com.kennyb1201.kbstream.data.iptv.db.EpgChannelEntity
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramEntity
import com.kennyb1201.kbstream.data.iptv.db.IptvDao
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class XmltvImporter(
    private val dao: IptvDao
) {

    suspend fun import(sourceUrl: String, input: InputStream) {
        dao.deleteProgramsBySource(sourceUrl)
        dao.deleteChannelsBySource(sourceUrl)

        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(InputStreamReader(input, Charsets.UTF_8))

        val channelBatch = ArrayList<EpgChannelEntity>(500)
        val programBatch = ArrayList<EpgProgramEntity>(1000)
        var parsedChannels = 0
        var parsedPrograms = 0

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        readChannel(parser, sourceUrl)?.let {
                            channelBatch += it
                            parsedChannels++
                        }
                        if (channelBatch.size >= 500) {
                            dao.insertChannels(channelBatch.toList())
                            channelBatch.clear()
                        }
                    }
                    "programme" -> {
                        readProgram(parser, sourceUrl)?.let {
                            programBatch += it
                            parsedPrograms++
                        }
                        if (programBatch.size >= 1000) {
                            dao.insertPrograms(programBatch.toList())
                            programBatch.clear()
                            if (parsedPrograms % 10000 == 0) {
                                Log.d(TAG, "Imported programs=$parsedPrograms channels=$parsedChannels source=$sourceUrl")
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        if (channelBatch.isNotEmpty()) dao.insertChannels(channelBatch.toList())
        if (programBatch.isNotEmpty()) dao.insertPrograms(programBatch.toList())

        Log.d(TAG, "Import complete programs=$parsedPrograms channels=$parsedChannels source=$sourceUrl")
    }

    private fun readChannel(parser: XmlPullParser, sourceUrl: String): EpgChannelEntity? {
        val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
        if (id.isBlank()) {
            skipToEndTag(parser, "channel")
            return null
        }

        val displayNames = mutableListOf<String>()
        var iconUrl: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "channel")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "display-name" -> displayNames += parser.nextText().trim()
                    "icon" -> iconUrl = parser.getAttributeValue(null, "src")?.trim()
                    else -> skip(parser)
                }
            }
            parser.next()
        }

        val cleanNames = displayNames.filter { it.isNotBlank() }.distinct()
        return EpgChannelEntity(
            id = id,
            sourceUrl = sourceUrl,
            primaryDisplayName = cleanNames.firstOrNull().orEmpty(),
            allDisplayNames = cleanNames.joinToString("|"),
            iconUrl = iconUrl?.ifBlank { null }
        )
    }

    private fun readProgram(parser: XmlPullParser, sourceUrl: String): EpgProgramEntity? {
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
                    else -> skip(parser)
                }
            }
            parser.next()
        }

        if (channelId.isBlank() || end <= start) return null

        return EpgProgramEntity(
            sourceUrl = sourceUrl,
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
        val regex = Regex("""^(\d{4})(\d{2})(\d{2})(\d{2})?(\d{2})?(\d{2})?(?:\s?(Z|[+-]\d{4}))?.*$""")
        val match = regex.matchEntire(normalized) ?: return 0L

        val year = match.groupValues[1].toIntOrNull() ?: return 0L
        val month = match.groupValues[2].toIntOrNull() ?: return 0L
        val day = match.groupValues[3].toIntOrNull() ?: return 0L
        val hour = match.groupValues[4].ifBlank { "00" }.toIntOrNull() ?: return 0L
        val minute = match.groupValues[5].ifBlank { "00" }.toIntOrNull() ?: return 0L
        val second = match.groupValues[6].ifBlank { "00" }.toIntOrNull() ?: return 0L
        val offset = match.groupValues.getOrNull(7).orEmpty()

        return runCatching {
            if (offset.isNotBlank()) {
                val zoneOffset = if (offset == "Z") ZoneOffset.UTC
                else ZoneOffset.of(offset.substring(0, 3) + ":" + offset.substring(3, 5))
                OffsetDateTime.of(year, month, day, hour, minute, second, 0, zoneOffset)
                    .toInstant().toEpochMilli()
            } else {
                LocalDateTime.of(year, month, day, hour, minute, second)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            }
        }.getOrDefault(0L)
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
            }
        }
    }

    private fun skipToEndTag(parser: XmlPullParser, tagName: String) {
        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == tagName)) {
            parser.next()
        }
    }

    private companion object {
        const val TAG = "XmltvImporter"
    }
}
