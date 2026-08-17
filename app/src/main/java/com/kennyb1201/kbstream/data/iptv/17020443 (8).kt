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
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        IMPORT_MUTEX.withLock {
            dateParseFailureLogsRemaining = 20

            try {
                val startedAt = System.currentTimeMillis()
                Log.i(TAG, "IMPORT START source=$sourceUrl")
                Log.i(TAG, "IMPORT WINDOW start=$windowStartMs end=$windowEndMs")

                val bufferedInput = if (input is BufferedInputStream) input else BufferedInputStream(input)
                bufferedInput.mark(2)
                val b1 = bufferedInput.read()
                val b2 = bufferedInput.read()
                bufferedInput.reset()

                val isActuallyGzip = b1 == 0x1f && b2 == 0x8b
                val xmlInput = if (isActuallyGzip) GZIPInputStream(bufferedInput) else bufferedInput

                val factory = XmlPullParserFactory.newInstance()
                factory.isNamespaceAware = false
                val parser = factory.newPullParser()
                parser.setInput(InputStreamReader(xmlInput, Charsets.UTF_8))

                val channelBatch = ArrayList<EpgChannelEntity>(CHANNEL_BATCH_SIZE)
                val programBatch = ArrayList<EpgProgramEntity>(PROGRAM_BATCH_SIZE)

                var parsedChannels = 0
                var parsedPrograms = 0
                var keptPrograms = 0

                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        when (parser.name.orEmpty()) {
                            "channel" -> {
                                readChannel(parser, sourceUrl)?.let { channel ->
                                    channelBatch += channel
                                    parsedChannels++
                                }

                                if (channelBatch.size >= CHANNEL_BATCH_SIZE) {
                                    dao.insertChannels(channelBatch.toList())
                                    channelBatch.clear()
                                }

                                if (parsedChannels % 5000 == 0 && parsedChannels > 0) {
                                    Log.i(
                                        TAG,
                                        "IMPORT PROGRESS channels=$parsedChannels parsedPrograms=$parsedPrograms keptPrograms=$keptPrograms source=$sourceUrl"
                                    )
                                }
                            }

                            "programme" -> {
                                parsedPrograms++

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

                                if (parsedPrograms % 10000 == 0) {
                                    Log.i(
                                        TAG,
                                        "IMPORT PROGRESS channels=$parsedChannels parsedPrograms=$parsedPrograms keptPrograms=$keptPrograms source=$sourceUrl"
                                    )
                                }
                            }
                        }
                    }

                    eventType = parser.next()
                }

                if (channelBatch.isNotEmpty()) {
                    dao.insertChannels(channelBatch.toList())
                }
                if (programBatch.isNotEmpty()) {
                    dao.insertPrograms(programBatch.toList())
                }

                val elapsedMs = System.currentTimeMillis() - startedAt
                Log.i(
                    TAG,
                    "IMPORT END channels=$parsedChannels parsedPrograms=$parsedPrograms keptPrograms=$keptPrograms elapsedMs=$elapsedMs source=$sourceUrl"
                )
            } catch (error: Throwable) {
                Log.e(TAG, "IMPORT FAILED source=$sourceUrl", error)
                throw error
            } finally {
                runCatching { input.close() }
            }
        }
    }

    private fun readChannel(parser: XmlPullParser, sourceUrl: String): EpgChannelEntity? {
        parser.require(XmlPullParser.START_TAG, null, "channel")

        val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
        if (id.isBlank()) {
            skip(parser)
            return null
        }

        val displayNames = mutableListOf<String>()
        var iconUrl: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "channel")) {
            val nextType = parser.next()
            val nextName = parser.name.orEmpty()

            if (nextType != XmlPullParser.START_TAG) {
                continue
            }

            when (nextName) {
                "display-name" -> {
                    val text = safeNextText(parser).trim()
                    if (text.isNotBlank()) displayNames += text
                }
                "icon" -> {
                    iconUrl = parser.getAttributeValue(null, "src")?.trim()
                    skip(parser)
                }
                else -> skip(parser)
            }
        }

        val cleanNames = displayNames.distinct()
        val aliasKeys = buildAliasKeys(id, cleanNames)
        val primaryName = cleanNames.firstOrNull().orEmpty()
        val allNames = aliasKeys.joinToString("|")

        return EpgChannelEntity(
            id = id,
            sourceUrl = sourceUrl,
            primaryDisplayName = primaryName,
            allDisplayNames = allNames,
            iconUrl = iconUrl?.ifBlank { null }
        )
    }

    private fun readProgram(
        parser: XmlPullParser,
        sourceUrl: String,
        windowStartMs: Long,
        windowEndMs: Long
    ): EpgProgramEntity? {
        parser.require(XmlPullParser.START_TAG, null, "programme")

        val channelId = parser.getAttributeValue(null, "channel")?.trim().orEmpty()
        val rawStart = parser.getAttributeValue(null, "start")
        val rawEnd = parser.getAttributeValue(null, "stop")
        val start = parseXmltvDate(rawStart)
        val end = parseXmltvDate(rawEnd)

        var title = ""
        var description: String? = null
        var category: String? = null

        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> {
                    if (parser.name == "programme") break
                }
                XmlPullParser.END_DOCUMENT -> return null
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "title" -> title = safeNextText(parser).trim()
                        "desc" -> description = safeNextText(parser).trim().ifBlank { null }
                        "category" -> category = safeNextText(parser).trim().ifBlank { null }
                        else -> skip(parser)
                    }
                }
            }
        }

        if (channelId.isBlank()) return null
        if (end <= start) return null
        if (end <= windowStartMs) return null
        if (start >= windowEndMs) return null

        return EpgProgramEntity(
            sourceUrl = sourceUrl,
            channelId = normalizeChannelKey(channelId),
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
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) return 0L

        parseCompactXmltvDate(normalized)?.let { return it }
        parseIsoLikeXmltvDate(normalized)?.let { return it }

        if (dateParseFailureLogsRemaining > 0) {
            dateParseFailureLogsRemaining--
            Log.w(TAG, "DATE PARSE FAILED raw=$normalized remaining=$dateParseFailureLogsRemaining")
        }

        return 0L
    }

    private fun parseCompactXmltvDate(value: String): Long? {
        val parts = value.split(Regex("\\s+")).filter { it.isNotBlank() }
        val datePart = parts.getOrNull(0) ?: return null
        val tzPart = parts.getOrNull(1)

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
                val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
                OffsetDateTime.parse("$normalizedDate $normalizedTz", formatter)
                    .toInstant()
                    .toEpochMilli()
            } else {
                LocalDateTime.parse(normalizedDate, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
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
            Regex("[+-]\\d{4}").matches(offset) -> offset
            Regex("[+-]\\d{2}:\\d{2}").matches(offset) -> offset.replace(":", "")
            else -> null
        }
    }

    private fun parseIsoLikeXmltvDate(value: String): Long? {
        val candidates = listOf(
            value,
            value.replace(' ', 'T'),
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

    private fun buildAliasKeys(id: String, displayNames: List<String>): List<String> {
        return buildList {
            add(id)
            add(normalizeChannelKey(id))
            displayNames.forEach { name ->
                add(name)
                add(normalizeChannelKey(name))
                simplifyChannelName(name)?.let { add(it) }
            }
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun normalizeChannelKey(value: String): String {
        return value.trim().lowercase(Locale.US)
    }

    private fun simplifyChannelName(value: String): String? {
        val simplified = value
            .lowercase(Locale.US)
            .replace(Regex("""\[[^\]]*\]"""), " ")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\b(hd|uhd|fhd|sd|4k|1080p|720p|hevc|h265|h264|hdr|aac|fps|usa|us|uk|ca|au)\b"""), " ")
            .replace("+", " plus ")
            .replace(Regex("""[^a-z0-9]+"""), "")
            .trim()
        return simplified.ifBlank { null }
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

    private companion object {
        const val TAG = "XmltvImporter"
        const val CHANNEL_BATCH_SIZE = 500
        const val PROGRAM_BATCH_SIZE = 1000
        const val DEFAULT_PAST_WINDOW_MS = 24 * 60 * 60 * 1000L
        const val DEFAULT_FUTURE_WINDOW_MS = 48 * 60 * 60 * 1000L
        val IMPORT_MUTEX = Mutex()
        var dateParseFailureLogsRemaining = 20
    }
}
