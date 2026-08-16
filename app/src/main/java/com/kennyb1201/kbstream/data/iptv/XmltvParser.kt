package com.kennyb1201.kbstream.data.iptv

import android.util.Log
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class XmltvParser {

    fun parse(content: String, sourceUrl: String? = null): XmltvGuide {
        return parse(StringReader(sanitizeMalformedXml(content)), sourceUrl)
    }

    fun parse(input: InputStream, sourceUrl: String? = null): XmltvGuide {
        return parse(InputStreamReader(input, Charsets.UTF_8), sourceUrl)
    }

    fun parse(reader: Reader, sourceUrl: String? = null): XmltvGuide {
        val now = System.currentTimeMillis()
        return parse(
            reader = reader,
            sourceUrl = sourceUrl,
            windowStartMs = now - DEFAULT_PAST_WINDOW_MS,
            windowEndMs = now + DEFAULT_FUTURE_WINDOW_MS
        )
    }

    fun parse(
        reader: Reader,
        sourceUrl: String? = null,
        windowStartMs: Long,
        windowEndMs: Long
    ): XmltvGuide {
        val channels = mutableListOf<XmltvChannel>()
        val programs = mutableListOf<XmltvProgram>()

        parseStreaming(
            reader = reader,
            sourceUrl = sourceUrl,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            onChannel = { channels += it },
            onProgramBatch = { batch -> programs += batch }
        )

        return XmltvGuide(
            sourceUrl = sourceUrl,
            channels = channels,
            programs = programs
        )
    }

    fun parseStreaming(
        input: InputStream,
        sourceUrl: String? = null,
        windowStartMs: Long,
        windowEndMs: Long,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        onChannel: (XmltvChannel) -> Unit = {},
        onProgramBatch: (List<XmltvProgram>) -> Unit
    ) {
        parseStreaming(
            reader = InputStreamReader(input, Charsets.UTF_8),
            sourceUrl = sourceUrl,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            batchSize = batchSize,
            onChannel = onChannel,
            onProgramBatch = onProgramBatch
        )
    }

    fun parseStreaming(
        reader: Reader,
        sourceUrl: String? = null,
        windowStartMs: Long,
        windowEndMs: Long,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        onChannel: (XmltvChannel) -> Unit = {},
        onProgramBatch: (List<XmltvProgram>) -> Unit
    ) {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(reader)

        val acceptedPrograms = ArrayList<XmltvProgram>(batchSize)
        var channelCount = 0
        var programmeCount = 0
        var keptProgrammeCount = 0

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        val channel = readChannel(parser)
                        onChannel(channel)
                        channelCount++

                        if (channelCount % 1000 == 0) {
                            Log.d(
                                TAG,
                                "XMLTV progress: parsedChannels=$channelCount parsedProgrammes=$programmeCount keptProgrammes=$keptProgrammeCount source=${sourceUrl.orEmpty()}"
                            )
                        }
                    }

                    "programme" -> {
                        val program = readProgram(parser)
                        programmeCount++

                        if (
                            program.channelId.isNotBlank() &&
                            program.endUtcMillis > program.startUtcMillis &&
                            program.endUtcMillis > windowStartMs &&
                            program.startUtcMillis < windowEndMs
                        ) {
                            acceptedPrograms += program
                            keptProgrammeCount++

                            if (acceptedPrograms.size >= batchSize) {
                                onProgramBatch(acceptedPrograms.toList())
                                acceptedPrograms.clear()
                            }
                        }

                        if (programmeCount % 5000 == 0) {
                            Log.d(
                                TAG,
                                "XMLTV progress: parsedChannels=$channelCount parsedProgrammes=$programmeCount keptProgrammes=$keptProgrammeCount source=${sourceUrl.orEmpty()}"
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        if (acceptedPrograms.isNotEmpty()) {
            onProgramBatch(acceptedPrograms.toList())
            acceptedPrograms.clear()
        }

        Log.d(
            TAG,
            "XMLTV completed: parsedChannels=$channelCount parsedProgrammes=$programmeCount keptProgrammes=$keptProgrammeCount source=${sourceUrl.orEmpty()} windowStartMs=$windowStartMs windowEndMs=$windowEndMs"
        )
    }

    private fun readChannel(parser: XmlPullParser): XmltvChannel {
        val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
        val displayNames = mutableListOf<String>()
        var iconUrl: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "channel")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "display-name" -> displayNames += safeNextText(parser).trim()
                    "icon" -> iconUrl = parser.getAttributeValue(null, "src")?.trim()
                    else -> skip(parser)
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
                    "title" -> title = safeNextText(parser).trim()
                    "desc" -> description = safeNextText(parser).trim().ifBlank { null }
                    "category" -> category = safeNextText(parser).trim().ifBlank { null }
                    else -> skip(parser)
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

    private fun safeNextText(parser: XmlPullParser): String {
        val result = runCatching { parser.nextText() }.getOrDefault("")
        if (parser.eventType != XmlPullParser.END_TAG) {
            runCatching { parser.nextTag() }
        }
        return result
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

    private fun sanitizeMalformedXml(content: String): String {
        return content.replace(
            Regex("&(?!(amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)"),
            "&amp;"
        )
    }

    private companion object {
        const val TAG = "XmltvParser"
        const val DEFAULT_BATCH_SIZE = 1000
        const val DEFAULT_PAST_WINDOW_MS = 4 * 60 * 60 * 1000L
        const val DEFAULT_FUTURE_WINDOW_MS = 12 * 60 * 60 * 1000L
    }
}
