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
            channelDetailLogsRemaining = if (VERBOSE_DEBUG_LOGS) 3 else 0
            programSkipLogsRemaining = if (VERBOSE_DEBUG_LOGS) 20 else 0
            programDetailLogsRemaining = if (VERBOSE_DEBUG_LOGS) 3 else 0
            dateParseFailureLogsRemaining = 20

            try {
                Log.d(TAG, "IMPORT START source=$sourceUrl")
                Log.d(TAG, "IMPORT WINDOW start=$windowStartMs end=$windowEndMs")

                dao.deleteProgramsBySource(sourceUrl)
                dao.deleteChannelsBySource(sourceUrl)

                val bufferedInput = if (input is BufferedInputStream) input else BufferedInputStream(input, IO_BUFFER_SIZE)
                bufferedInput.mark(2)
                val b1 = bufferedInput.read()
                val b2 = bufferedInput.read()
                bufferedInput.reset()

                val isActuallyGzip = b1 == 0x1f && b2 == 0x8b
                Log.d(TAG, "STREAM TYPE actualGzip=$isActuallyGzip")

                val xmlInput = if (isActuallyGzip) {
                    GZIPInputStream(bufferedInput, IO_BUFFER_SIZE)
                } else {
                    bufferedInput
                }

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

                                if (parsedChannels % CHANNEL_PROGRESS_INTERVAL == 0 && parsedChannels > 0) {
                                    Log.d(
                                        TAG,
                                        "Import progress channels=$parsedChannels parsedPrograms=$parsedPrograms keptPrograms=$keptPrograms source=$sourceUrl"
                                    )
                                }
                            }

                            "programme" -> {
                                parsedPrograms++

                                readProgram(
                                    parser = parser,
                                    sourceUrl = sourceUrl,
                                    windowStartMs = windowStartMs,
                                    windowEndMs = windowEndMs,
                                    programIndex = parsedPrograms
                                )?.let { program ->
                                    programBatch += program
                                    keptPrograms++

                                    if (programBatch.size >= PROGRAM_BATCH_SIZE) {
                                        dao.insertPrograms(programBatch.toList())
                                        programBatch.clear()
                                    }
                                }

                                if (parsedPrograms % PROGRAM_PROGRESS_INTERVAL == 0) {
                                    Log.d(
                                        TAG,
                                        "Import progress channels=$parsedChannels parsedPrograms=$parsedPrograms keptPrograms=$keptPrograms source=$sourceUrl"
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

                Log.d(
                    TAG,
                    "IMPORT END channels=$parsedChannels parsedPrograms=$parsedPrograms keptPrograms=$keptPrograms source=$sourceUrl"
                )
            } catch (error: Throwable) {
                Log.e(TAG, "IMPORT FAILED source=$sourceUrl", error)
                throw error
            } finally {
                runCatching { input.close() }
                Log.d(TAG, "IMPORT LOCK RELEASED source=$sourceUrl")
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
                    displayNames += text
                }
                "icon" -> {
                    iconUrl = parser.getAttributeValue(null, "src")?.trim()
                    skip(parser)
                }
                else -> skip(parser)
            }
        }

        val cleanNames = displayNames.filter { it.isNotBlank() }.distinct()
        val aliasKeys = buildAliasKeys(id, cleanNames)
        val primaryName = cleanNames.firstOrNull().orEmpty()
        val allNames = aliasKeys.joinToString("|")

        if (channelDetailLogsRemaining > 0) {
            Log.d(TAG, "READ CHANNEL BUILD id=$id names=${cleanNames.size} aliases=${aliasKeys.size} icon=${iconUrl != null}")
            channelDetailLogsRemaining--
        }

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
        windowEndMs: Long,
        programIndex: Int
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
        var steps = 0

        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> {
                    if (parser.name == "programme") break
                }
                XmlPullParser.END_DOCUMENT -> {
                    return null
                }
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "title" -> title = safeNextText(parser).trim()
                        "desc" -> description = safeNextText(parser).trim().ifBlank { null }
                        "category" -> category = safeNextText(parser).trim().ifBlank { null }
                        else -> skip(parser)
                    }
                }
            }

            steps++

            if (programDetailLogsRemaining > 0 && steps % PROGRAM_STEP_LOG_INTERVAL == 0) {
                Log.d(
                    TAG,
                    "READ PROGRAM STEP index=$programIndex steps=$steps eventType=${parser.eventType} name=${parser.name.orEmpty()}"
                )
            }
        }

        if (programDetailLogsRemaining > 0) {
            Log.d(
                TAG,
                "READ PROGRAM FINISH index=$programIndex title=${title.ifBlank { "<blank>" }} channelId=${channelId.ifBlank { "<blank>" }} start=$start end=$end steps=$steps"
            )
            programDetailLogsRemaining--
        }

        if (channelId.isBlank()) {
            logProgramSkip("blank-channel", title, channelId, start, end, windowStartMs, windowEndMs)
            return null
        }
        if (end <= start) {
            logProgramSkip("end-before-start", title, channelId, start, end, windowStartMs, windowEndMs)
            return null
        }
        if (end <= windowStartMs) {
            logProgramSkip("before-window", title, channelId, start, end, windowStartMs, windowEndMs)
            return null
        }
        if (start >= windowEndMs) {
            logProgramSkip("after-window", title, channelId, start, end, windowStartMs, windowEndMs)
            return null
        }

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

    private fun logProgramSkip(
        reason: String,
        title: String,
        channelId: String,
        start: Long,
        end: Long,
        windowStartMs: Long,
        windowEndMs: Long
    ) {
        if (programSkipLogsRemaining <= 0) return
        programSkipLogsRemaining--
        Log.w(
            TAG,
            "PROGRAM SKIP reason=$reason title=${title.ifBlank { "<blank>" }} channelId=${channelId.ifBlank { "<blank>" }} start=$start end=$end windowStart=$windowStartMs windowEnd=$windowEndMs remaining=$programSkipLogsRemaining"
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
        val parts = value.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
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
                OffsetDateTime.parse("$normalizedDate $normalizedTz", COMPACT_WITH_OFFSET_FORMATTER)
                    .toInstant()
                    .toEpochMilli()
            } else {
                LocalDateTime.parse(normalizedDate, COMPACT_LOCAL_FORMATTER)
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
            OFFSET_PLAIN_REGEX.matches(offset) -> offset
            OFFSET_COLON_REGEX.matches(offset) -> offset.replace(":", "")
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
            .replace(BRACKET_CONTENT_REGEX, " ")
            .replace(PAREN_CONTENT_REGEX, " ")
            .replace(NOISE_WORD_REGEX, " ")
            .replace("+", " plus ")
            .replace(NON_ALNUM_REGEX, "")
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
        const val CHANNEL_BATCH_SIZE = 1_000
        const val PROGRAM_BATCH_SIZE = 3_000
        const val PROGRAM_STEP_LOG_INTERVAL = 200
        const val CHANNEL_PROGRESS_INTERVAL = 1_000
        const val PROGRAM_PROGRESS_INTERVAL = 5_000
        const val IO_BUFFER_SIZE = 64 * 1024
        const val DEFAULT_PAST_WINDOW_MS = 24 * 60 * 60 * 1000L
        const val DEFAULT_FUTURE_WINDOW_MS = 48 * 60 * 60 * 1000L
        const val VERBOSE_DEBUG_LOGS = false

        val IMPORT_MUTEX = Mutex()
        var channelDetailLogsRemaining = 3
        var programSkipLogsRemaining = 20
        var programDetailLogsRemaining = 3
        var dateParseFailureLogsRemaining = 20

        val WHITESPACE_REGEX = Regex("\\s+")
        val OFFSET_PLAIN_REGEX = Regex("[+-]\\d{4}")
        val OFFSET_COLON_REGEX = Regex("[+-]\\d{2}:\\d{2}")
        val BRACKET_CONTENT_REGEX = Regex("""\[[^\]]*\]""")
        val PAREN_CONTENT_REGEX = Regex("""\([^)]*\)""")
        val NOISE_WORD_REGEX = Regex("""\b(hd|uhd|fhd|sd|4k|1080p|720p|hevc|h265|h264|hdr|aac|fps|usa|us|uk|ca|au)\b""")
        val NON_ALNUM_REGEX = Regex("""[^a-z0-9]+""")
        val COMPACT_LOCAL_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        val COMPACT_WITH_OFFSET_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
    }
}
