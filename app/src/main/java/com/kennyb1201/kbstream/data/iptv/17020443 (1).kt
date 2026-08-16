package com.kennyb1201.kbstream.data.iptv

import android.util.Log
import com.kennyb1201.kbstream.data.iptv.db.EpgChannelEntity
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramEntity
import com.kennyb1201.kbstream.data.iptv.db.IptvDao
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class XmltvImporter(
    private val dao: IptvDao
) {

    suspend fun import(sourceUrl: String, input: InputStream) {
        val now = System.currentTimeMillis()
        import(
            sourceUrl = sourceUrl,
            input = input,
            windowStartMs = now - DEFAULT_PAST_WINDOW_MS,
            windowEndMs = now + DEFAULT_FUTURE_WINDOW_MS
        )
    }

    suspend fun import(
        sourceUrl: String,
        input: InputStream,
        windowStartMs: Long,
        windowEndMs: Long
    ) {
        Log.e(TAG, "IMPORT START source=$sourceUrl")
        Log.e(TAG, "IMPORT WINDOW start=$windowStartMs end=$windowEndMs")

        Log.e(TAG, "DELETE PROGRAMS START")
        dao.deleteProgramsBySource(sourceUrl)
        Log.e(TAG, "DELETE PROGRAMS END")

        Log.e(TAG, "DELETE CHANNELS START")
        dao.deleteChannelsBySource(sourceUrl)
        Log.e(TAG, "DELETE CHANNELS END")

        val bufferedInput = if (input is BufferedInputStream) input else BufferedInputStream(input)
        bufferedInput.mark(2)
        val b1 = bufferedInput.read()
        val b2 = bufferedInput.read()
        bufferedInput.reset()

        val urlEndsGz = sourceUrl.substringBefore("?").endsWith(".gz", ignoreCase = true)
        val isActuallyGzip = b1 == 0x1f && b2 == 0x8b
        Log.e(TAG, "STREAM TYPE urlEndsGz=$urlEndsGz actualGzip=$isActuallyGzip")

        val xmlInput = if (isActuallyGzip) {
            GZIPInputStream(bufferedInput)
        } else {
            bufferedInput
        }
        Log.e(TAG, "STREAM READY")

        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        Log.e(TAG, "FACTORY READY")

        val parser = factory.newPullParser()
        Log.e(TAG, "PARSER CREATED")

        parser.setInput(InputStreamReader(xmlInput, Charsets.UTF_8))
        Log.e(TAG, "PARSER INPUT SET")

        val channelBatch = ArrayList<EpgChannelEntity>(CHANNEL_BATCH_SIZE)
        val programBatch = ArrayList<EpgProgramEntity>(PROGRAM_BATCH_SIZE)

        var parsedChannels = 0
        var parsedPrograms = 0
        var keptPrograms = 0

        var eventType = parser.eventType
        Log.e(TAG, "PARSER EVENT START type=$eventType")

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val tagName = parser.name.orEmpty()

                if (parsedChannels < 3 || parsedPrograms < 3) {
                    Log.e(TAG, "START TAG name=$tagName")
                }

                when (tagName) {
                    "channel" -> {
                        Log.e(TAG, "READ CHANNEL START")
                        readChannel(parser, sourceUrl)?.let { channel ->
                            channelBatch += channel
                            parsedChannels++
                        }
                        Log.e(TAG, "READ CHANNEL END parsedChannels=$parsedChannels")

                        if (channelBatch.size >= CHANNEL_BATCH_SIZE) {
                            dao.insertChannels(channelBatch.toList())
                            channelBatch.clear()
                        }

                        if (parsedChannels % 1000 == 0 && parsedChannels > 0) {
                            Log.d(
                                TAG,
                                "Import progress channels=$parsedChannels parsedPrograms=$parsedPrograms keptPrograms=$keptPrograms source=$sourceUrl"
                            )
                        }
                    }

                    "programme" -> {
                        parsedPrograms++
                        if (parsedPrograms <= 3) {
                            Log.e(TAG, "READ PROGRAM START parsedPrograms=$parsedPrograms")
                        }

                        readProgram(
                            parser = parser,
                            sourceUrl = sourceUrl,
                            windowStartMs = windowStartMs,
                            windowEndMs = windowEndMs
                        )?.let { program ->
                            programBatch += program
                            keptPrograms++

                            if (programBatch.size >= PROGRAM_BATCH_SIZE) {
                                dao.insertPrograms(programBatch.toList())
                                programBatch.clear()
                            }
                        }

                        if (parsedPrograms <= 3) {
                            Log.e(TAG, "READ PROGRAM END parsedPrograms=$parsedPrograms keptPrograms=$keptPrograms")
                        }

                        if (parsedPrograms % 5000 == 0) {
                            Log.d(
                                TAG,
                                "Import progress channels=$parsedChannels parsedPrograms=$parsedPrograms keptPrograms=$keptPrograms source=$sourceUrl"
                            )
                        }
                    }
                }
            }

            eventType = parser.next()

            if (parsedChannels < 3 || parsedPrograms < 3) {
                Log.e(TAG, "PARSER NEXT type=$eventType")
            }
        }

        if (channelBatch.isNotEmpty()) {
            dao.insertChannels(channelBatch.toList())
        }
        if (programBatch.isNotEmpty()) {
            dao.insertPrograms(programBatch.toList())
        }

        Log.e(
            TAG,
            "IMPORT END channels=$parsedChannels parsedPrograms=$parsedPrograms keptPrograms=$keptPrograms source=$sourceUrl"
        )

        Log.d(
            TAG,
            "Import complete channels=$parsedChannels parsedPrograms=$parsedPrograms keptPrograms=$keptPrograms source=$sourceUrl windowStartMs=$windowStartMs windowEndMs=$windowEndMs"
        )
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
                    "display-name" -> displayNames += safeNextText(parser).trim()
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

    private fun readProgram(
        parser: XmlPullParser,
        sourceUrl: String,
        windowStartMs: Long,
        windowEndMs: Long
    ): EpgProgramEntity? {
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

        if (channelId.isBlank()) return null
        if (end <= start) return null
        if (end <= windowStartMs) return null
        if (start >= windowEndMs) return null

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

    private fun safeNextText(parser: XmlPullParser): String {
        val result = runCatching { parser.nextText() }.getOrDefault("")
        if (parser.eventType != XmlPullParser.END_TAG) {
            runCatching { parser.nextTag() }
        }
        return result
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
        const val CHANNEL_BATCH_SIZE = 500
        const val PROGRAM_BATCH_SIZE = 1000
        const val DEFAULT_PAST_WINDOW_MS = 60 * 60 * 1000L
        const val DEFAULT_FUTURE_WINDOW_MS = 12 * 60 * 60 * 1000L
    }
}
