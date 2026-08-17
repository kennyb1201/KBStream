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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
    ) = withContext(Dispatchers.Default) {
        IMPORT_MUTEX.withLock {
            importInternal(sourceUrl, input, windowStartMs, windowEndMs)
        }
    }

    private suspend fun importInternal(
    sourceUrl: String,
    input: InputStream,
    windowStartMs: Long,
    windowEndMs: Long
) {
    var xmlInput: InputStream? = null

    try {
        dateParseFailureLogsRemaining = MAX_DATE_PARSE_FAILURE_LOGS

        val startedAt = System.currentTimeMillis()
        Log.i(TAG, "IMPORT START source=$sourceUrl")
        Log.i(TAG, "IMPORT WINDOW start=$windowStartMs end=$windowEndMs")

        dao.clearGuideBySource(sourceUrl)

        val bufferedInput = if (input is BufferedInputStream) input else BufferedInputStream(input)
        bufferedInput.mark(2)
        val b1 = bufferedInput.read()
        val b2 = bufferedInput.read()
        bufferedInput.reset()

        xmlInput = if (b1 == GZIP_MAGIC_1 && b2 == GZIP_MAGIC_2) {
            GZIPInputStream(bufferedInput)
        } else {
            bufferedInput
        }

        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = false
        }
        val parser = factory.newPullParser().apply {
            setInput(InputStreamReader(xmlInput, Charsets.UTF_8))
        }

        val channelBatch = ArrayList<EpgChannelEntity>(CHANNEL_BATCH_SIZE)
        val programBatch = ArrayList<EpgProgramEntity>(PROGRAM_BATCH_SIZE)

        var parsedChannels = 0
        var parsedPrograms = 0
        var keptPrograms = 0

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            currentCoroutineContext().ensureActive()

            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        readChannel(parser, sourceUrl)?.let { channel ->
                            channelBatch.add(channel)
                            parsedChannels++
                        }

                        if (channelBatch.size >= CHANNEL_BATCH_SIZE) {
                            flushChannels(channelBatch)
                        }

                        if (parsedChannels > 0 && parsedChannels % CHANNEL_LOG_INTERVAL == 0) {
                            logProgress(sourceUrl, parsedChannels, parsedPrograms, keptPrograms)
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
                            programBatch.add(program)
                            keptPrograms++

                            if (programBatch.size >= PROGRAM_BATCH_SIZE) {
                                flushPrograms(programBatch)
                            }
                        }

                        if (parsedPrograms % PROGRAM_LOG_INTERVAL == 0) {
                            logProgress(sourceUrl, parsedChannels, parsedPrograms, keptPrograms)
                        }
                    }
                }
            }

            eventType = parser.next()
        }

        flushChannels(channelBatch)
        flushPrograms(programBatch)

        val elapsedMs = System.currentTimeMillis() - startedAt
        Log.i(
            TAG,
            "IMPORT END channels=$parsedChannels parsedPrograms=$parsedPrograms " +
                "keptPrograms=$keptPrograms elapsedMs=$elapsedMs source=$sourceUrl"
        )
    } catch (error: Throwable) {
        Log.e(TAG, "IMPORT FAILED source=$sourceUrl", error)
        throw error
    } finally {
        runCatching { xmlInput?.close() ?: input.close() }
    }
}

    private suspend fun flushChannels(batch: MutableList<EpgChannelEntity>) {
    if (batch.isEmpty()) return
    dao.insertChannels(batch)
    batch.clear()
    currentCoroutineContext().ensureActive()
}

private suspend fun flushPrograms(batch: MutableList<EpgProgramEntity>) {
    if (batch.isEmpty()) return
    dao.insertPrograms(batch)
    batch.clear()
    currentCoroutineContext().ensureActive()
}

    private fun logProgress(
        sourceUrl: String,
        parsedChannels: Int,
        parsedPrograms: Int,
        keptPrograms: Int
    ) {
        Log.i(
            TAG,
            "IMPORT PROGRESS channels=$parsedChannels parsedPrograms=$parsedPrograms " +
                "keptPrograms=$keptPrograms source=$sourceUrl"
        )
    }

    private fun readChannel(parser: XmlPullParser, sourceUrl: String): EpgChannelEntity? {
        parser.require(XmlPullParser.START_TAG, null, "channel")

        val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
        if (id.isBlank()) {
            skip(parser)
            return null
        }

        val displayNames = ArrayList<String>(2)
        var iconUrl: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "channel")) {
            val nextType = parser.next()
            if (nextType != XmlPullParser.START_TAG) continue

            when (parser.name) {
                "display-name" -> {
                    val text = safeNextText(parser).trim()
                    if (text.isNotBlank()) displayNames.add(text)
                }
                "icon" -> {
                    iconUrl = parser.getAttributeValue(null, "src")?.trim()
                    skip(parser)
                }
                else -> skip(parser)
            }
        }

        val aliasKeys = buildAliasKeys(id, displayNames)
        return EpgChannelEntity(
            id = id,
            sourceUrl = sourceUrl,
            primaryDisplayName = displayNames.firstOrNull().orEmpty(),
            allDisplayNames = aliasKeys.joinToString("|"),
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
        val start = parseXmltvDate(parser.getAttributeValue(null, "start"))
        val end = parseXmltvDate(parser.getAttributeValue(null, "stop"))

        if (
            channelId.isBlank() ||
            end <= start ||
            end <= windowStartMs ||
            start >= windowEndMs
        ) {
            skip(parser)
            return null
        }

        var title = ""
        var description: String? = null
        var category: String? = null

        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (parser.name == "programme") break
                XmlPullParser.END_DOCUMENT -> return null
                XmlPullParser.START_TAG -> when (parser.name) {
                    "title" -> title = safeNextText(parser).trim()
                    "desc" -> description = safeNextText(parser).trim().ifBlank { null }
                    "category" -> category = safeNextText(parser).trim().ifBlank { null }
                    else -> skip(parser)
                }
            }
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

    private fun buildAliasKeys(id: String, displayNames: List<String>): List<String> {
        val keys = LinkedHashSet<String>(2 + displayNames.size * 3)

        fun add(value: String?) {
            value?.trim()?.takeIf { it.isNotBlank() }?.let(keys::add)
        }

        add(id)
        add(normalizeChannelKey(id))
        displayNames.forEach { name ->
            add(name)
            add(normalizeChannelKey(name))
            add(simplifyChannelName(name))
        }
        return keys.toList()
    }

    private fun normalizeChannelKey(value: String): String =
        value.trim().lowercase(Locale.US)

    private fun simplifyChannelName(value: String): String? {
        val simplified = value
            .lowercase(Locale.US)
            .replace(BRACKETED_TEXT, " ")
            .replace(PARENTHESIZED_TEXT, " ")
            .replace(CHANNEL_QUALIFIERS, " ")
            .replace("+", " plus ")
            .replace(NON_ALPHANUMERIC, "")
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
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private companion object {
        const val TAG = "XmltvImporter"
        const val CHANNEL_BATCH_SIZE = 500
        const val PROGRAM_BATCH_SIZE = 1000
        const val CHANNEL_LOG_INTERVAL = 5_000
        const val PROGRAM_LOG_INTERVAL = 10_000
        const val MAX_DATE_PARSE_FAILURE_LOGS = 20
        const val GZIP_MAGIC_1 = 0x1f
        const val GZIP_MAGIC_2 = 0x8b

const val DEFAULT_PAST_WINDOW_MS = 2 * 60 * 60 * 1000L
const val DEFAULT_FUTURE_WINDOW_MS = 18 * 60 * 60 * 1000L

        val IMPORT_MUTEX = Mutex()

        val XMLTV_UTC_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        val XMLTV_OFFSET_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")

        val OFFSET_4 = Regex("[+-]\\d{4}")
        val OFFSET_WITH_COLON = Regex("[+-]\\d{2}:\\d{2}")
        val BRACKETED_TEXT = Regex("""\[[^\]]*]""")
val PARENTHESIZED_TEXT = Regex("""\([^)]*\)""")
val CHANNEL_QUALIFIERS = Regex(
    """\b(hd|uhd|fhd|sd|4k|1080p|720p|hevc|h265|h264|hdr|aac|fps|usa|us|uk|ca|au)\b"""
)
        val NON_ALPHANUMERIC = Regex("""[^a-z0-9]+""")

        var dateParseFailureLogsRemaining = MAX_DATE_PARSE_FAILURE_LOGS
    }
}
