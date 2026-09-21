package com.kennyb1201.kbstream.data.iptv

import android.util.Log
import com.kennyb1201.kbstream.data.iptv.db.EpgChannelEntity
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramEntity
import com.kennyb1201.kbstream.data.iptv.db.IptvDao
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
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

    // Rows are parsed into a STAGING source key first and only promoted to
    // the real source URL once the whole document parsed successfully.
    // Previously the live guide was cleared BEFORE parsing, so a truncated
    // download / malformed XML mid-stream left the user with an EMPTY guide
    // until the next successful refresh.
    val stagingUrl = "$sourceUrl@importing"

    try {
        dateParseFailureLogsRemaining = MAX_DATE_PARSE_FAILURE_LOGS

        val startedAt = System.currentTimeMillis()
        Log.i(TAG, "IMPORT START source=$sourceUrl")
        Log.i(TAG, "IMPORT WINDOW start=$windowStartMs end=$windowEndMs")

        // Clear any stale rows left by a previously failed attempt, and
        // remember whether the live guide still has rows at all (a first
        // import has nothing to protect).
        dao.clearGuideBySource(stagingUrl)
        val hadLiveGuide = dao.hasChannelsForSource(sourceUrl)

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
            // XmlPullParser throws on an unescaped '&' anywhere in the document
            // (routine in real description text), and that one exception fails
            // the whole import — the provider's guide then never updates, with
            // nothing on screen explaining why. Escape invalid entity
            // references as the stream is consumed instead; well-formed ones
            // pass through byte-for-byte.
            setInput(
                XmltvEntitySanitizingReader(
                    InputStreamReader(xmlInput, Charsets.UTF_8)
                )
            )
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
                        readChannel(parser, stagingUrl)?.let { channel ->
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
                            sourceUrl = stagingUrl,
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

        // Full document parsed: atomically swap the staged rows into the
        // live guide. @Transaction — the old guide is never briefly absent
        // while reads land between the delete and the re-key.
        //
        // This is the single heaviest guide write (re-keying every imported
        // row), so it is the one most worth deferring past playback.
        EpgWriteGate.holdWhilePlaying()
        val swapped = try {
            dao.swapStagedGuideIntoLive(
                sourceUrl = sourceUrl,
                stagingUrl = stagingUrl
            )
            true
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // Never swallow structured cancellation: rethrow so the caller's
            // scope teardown still cancels cleanly.
            throw cancellation
        } catch (swapError: Throwable) {
            Log.e(TAG, "IMPORT SWAP FAILED source=$sourceUrl", swapError)
            false
        }

        val elapsedMs = System.currentTimeMillis() - startedAt
        Log.i(
            TAG,
            "IMPORT END channels=$parsedChannels parsedPrograms=$parsedPrograms " +
                "keptPrograms=$keptPrograms swapped=$swapped " +
                "hadLiveGuide=$hadLiveGuide elapsedMs=$elapsedMs source=$sourceUrl"
        )

        if (!swapped) {
            // Nothing was promoted; surface the failure so the caller's retry
            // logic still sees the import as unsuccessful.
            error("guide swap failed for source=$sourceUrl")
        }
    } catch (error: Throwable) {
        // The staged rows may be half-written; they live under the staging
        // key and are swept at the start of the next attempt, so the live
        // guide (if any) is untouched by a failed import.
        Log.e(TAG, "IMPORT FAILED source=$sourceUrl", error)
        throw error
    } finally {
        runCatching { xmlInput?.close() ?: input.close() }
    }
}

    private suspend fun flushChannels(batch: MutableList<EpgChannelEntity>) {
    if (batch.isEmpty()) return
    // Holding inside the flush also parks the parse loop above it, so a
    // running import stops pulling the (possibly 60 MB) guide off the
    // network while the player is up - not just the row write.
    EpgWriteGate.holdWhilePlaying()
    dao.insertChannels(batch)
    batch.clear()
    currentCoroutineContext().ensureActive()
}

private suspend fun flushPrograms(batch: MutableList<EpgProgramEntity>) {
    if (batch.isEmpty()) return
    EpgWriteGate.holdWhilePlaying()
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

        val aliasKeys = epgAliasKeys(id, displayNames)
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
        parseXmltvDateMillis(value)?.let { return it }

        // Unparseable (not merely blank): this is the case the budgeted log
        // exists for. See parseXmltvDateMillis for the accepted spellings.
        if (!value.isNullOrBlank() && dateParseFailureLogsRemaining > 0) {
            dateParseFailureLogsRemaining--
            Log.w(
                TAG,
                "DATE PARSE FAILED raw=${value.trim()} remaining=$dateParseFailureLogsRemaining"
            )
        }
        return 0L
    }

    private fun normalizeChannelKey(value: String): String =
        value.trim().lowercase(Locale.US)

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

        var dateParseFailureLogsRemaining = MAX_DATE_PARSE_FAILURE_LOGS
    }
}
