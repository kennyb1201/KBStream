package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.iptv.db.EpgChannelEntity
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import com.kennyb1201.kbstream.data.iptv.db.IptvDatabase
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class IptvRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            chain.proceed(request)
        }
        .build(),
    private val m3uParser: M3uParser = M3uParser()
) {
    private val db = IptvDatabase.getInstance(context)
    private val dao = db.iptvDao()
    private val xmltvImporter = XmltvImporter(dao)

    suspend fun loadPlaylist(
        playlistUrl: String,
        playlistName: String? = null
    ): IptvPlaylist {
        val playlistContent = fetchText(playlistUrl).removePrefix("﻿")

        if (!playlistContent.contains("#EXTM3U", ignoreCase = true) &&
            !playlistContent.contains("#EXTINF", ignoreCase = true)
        ) {
            error("Playlist response does not look like M3U. First 200 chars: ${playlistContent.take(200)}")
        }

        return m3uParser.parse(
            content = playlistContent,
            sourceUrl = playlistUrl,
            playlistName = playlistName
        )
    }

    suspend fun importGuide(epgUrl: String) = withContext(Dispatchers.IO) {
        val normalizedUrl = epgUrl.trim()
        val requestKey = normalizeGuideKey(normalizedUrl)
        val waiter = CompletableDeferred<Result<Unit>>()

        val shouldRun = importRequestMutex.withLock {
            val existing = activeGuideImports[requestKey]
            if (existing != null) {
                Log.w(TAG, "GUIDE IMPORT JOIN source=$normalizedUrl")
                false
            } else {
                activeGuideImports[requestKey] = waiter
                Log.d(TAG, "GUIDE IMPORT START source=$normalizedUrl")
                true
            }
        }

        if (!shouldRun) {
            val existing = activeGuideImports[requestKey]
                ?: error("Guide import state missing for source=$normalizedUrl")
            return@withContext existing.await().getOrThrow()
        }

        try {
            runCatching {
                val request = Request.Builder().url(normalizedUrl).get().build()
                client.newCall(request).execute().use { response ->
                    val body = response.body ?: error("Empty response body from EPG server.")
                    if (!response.isSuccessful) {
                        val preview = response.peekBody(4096).string().take(300)
                        error("Failed to fetch EPG (${response.code} ${response.message}). Preview: $preview")
                    }

                    val contentType = response.header("Content-Type").orEmpty()
                    val contentEncoding = response.header("Content-Encoding").orEmpty()
                    val isGzipByUrl = normalizedUrl.substringAfterLast('/', missingDelimiterValue = "")
                        .contains(".gz", ignoreCase = true)
                    val isGzipByHeader = contentType.contains("gzip", ignoreCase = true) ||
                        contentEncoding.contains("gzip", ignoreCase = true)

                    val buffered = BufferedInputStream(body.byteStream(), 64 * 1024)
                    buffered.mark(2)
                    val magic1 = buffered.read()
                    val magic2 = buffered.read()
                    buffered.reset()
                    val isGzipByMagic = magic1 == 0x1f && magic2 == 0x8b
                    val isGzip = isGzipByUrl || isGzipByHeader || isGzipByMagic

                    val stream: InputStream = if (isGzip) GZIPInputStream(buffered, 64 * 1024) else buffered
                    Log.d(TAG, "Importing guide url=$normalizedUrl gzip=$isGzip")
                    stream.use { xmltvImporter.import(normalizedUrl, it) }
                }
            }.also { result ->
                waiter.complete(result)
                result.getOrThrow()
            }
        } finally {
            importRequestMutex.withLock {
                activeGuideImports.remove(requestKey, waiter)
            }
            Log.d(TAG, "GUIDE IMPORT END source=$normalizedUrl")
        }
    }

    suspend fun getGuideChannels(epgUrl: String): List<EpgChannelEntity> {
        return dao.getChannelsBySource(epgUrl.trim())
    }

    fun observeLineupWithGuide(
        playlist: IptvPlaylist,
        epgUrl: String,
        windowStart: Long,
        windowEnd: Long,
        limit: Int = 20_000
    ): Flow<List<IptvChannelWithEpg>> = flow {
        val normalizedSource = epgUrl.trim()
        val channelIds = playlist.channels
            .flatMap { channel ->
                collectChannelKeys(channel)
            }
            .map { normalizeLookupKey(it) }
            .filter { it.isNotBlank() }
            .distinct()

        Log.d(TAG, "observeLineupWithGuide candidates=${channelIds.size} epgUrl=$normalizedSource")
        if (channelIds.isNotEmpty()) {
            Log.d(TAG, "observeLineupWithGuide sample=${channelIds.take(12)}")
        }

        val rows = if (channelIds.isEmpty()) {
            emptyList()
        } else {
            loadProgramsChunked(
                sourceUrl = normalizedSource,
                channelIds = channelIds,
                windowStart = windowStart,
                windowEnd = windowEnd,
                totalLimit = limit
            )
        }

        Log.d(TAG, "observeLineupWithGuide rows=${rows.size} epgUrl=$normalizedSource")
        emit(mapChannels(playlist, rows, System.currentTimeMillis()))
    }

    private suspend fun loadProgramsChunked(
        sourceUrl: String,
        channelIds: List<String>,
        windowStart: Long,
        windowEnd: Long,
        totalLimit: Int
    ): List<EpgProgramRow> = withContext(Dispatchers.IO) {
        val results = mutableListOf<EpgProgramRow>()
        var remaining = totalLimit

        for (batch in channelIds.chunked(CHANNEL_QUERY_BATCH_SIZE)) {
            if (remaining <= 0) break

            val batchRows = dao.getProgramsForChannelsInWindow(
                sourceUrl = sourceUrl,
                channelIds = batch,
                windowStart = windowStart,
                windowEnd = windowEnd,
                limit = minOf(PROGRAMS_PER_BATCH_LIMIT, remaining)
            )

            results += batchRows
            remaining = totalLimit - results.size
        }

        results
            .map { row -> row.copy(channelId = normalizeLookupKey(row.channelId)) }
            .distinctBy { "${it.channelId}|${it.startUtcMillis}|${it.endUtcMillis}|${it.title}" }
            .sortedBy { it.startUtcMillis }
            .take(totalLimit)
    }

    private fun mapChannels(
        playlist: IptvPlaylist,
        rows: List<EpgProgramRow>,
        nowUtcMillis: Long
    ): List<IptvChannelWithEpg> {
        val programsByChannel = rows.groupBy { normalizeLookupKey(it.channelId) }

        return playlist.channels.map { channel ->
            val ids = collectChannelKeys(channel)
                .map { normalizeLookupKey(it) }
                .filter { it.isNotBlank() }
                .distinct()

            val matchedPrograms = ids
                .flatMap { programsByChannel[it].orEmpty() }
                .distinctBy { "${it.channelId}|${it.startUtcMillis}|${it.endUtcMillis}|${it.title}" }
                .sortedBy { it.startUtcMillis }

            val now = matchedPrograms.firstOrNull { nowUtcMillis in it.startUtcMillis until it.endUtcMillis }
            val next = matchedPrograms.firstOrNull {
                it.startUtcMillis >= if (now != null) now.endUtcMillis else nowUtcMillis
            }
            val upcoming = matchedPrograms
                .filter { it.endUtcMillis > nowUtcMillis }
                .take(12)
                .map {
                    XmltvProgram(
                        channelId = it.channelId,
                        title = it.title,
                        description = it.description,
                        category = it.category,
                        startUtcMillis = it.startUtcMillis,
                        endUtcMillis = it.endUtcMillis
                    )
                }

            IptvChannelWithEpg(
                channel = channel,
                epgChannel = null,
                epgMatchType = if (matchedPrograms.isEmpty()) EpgMatchType.NO_MATCH else EpgMatchType.ID_MATCH,
                now = now?.toXmltvProgram(),
                next = next?.toXmltvProgram(),
                upcoming = upcoming
            )
        }
    }

    private fun collectChannelKeys(channel: IptvChannel): List<String> {
        return buildList {
            addAll(buildCandidateKeys(channel.tvgId.orEmpty()))
            addAll(buildCandidateKeys(channel.providerChannelId.orEmpty()))
            addAll(buildCandidateKeys(channel.tvgName.orEmpty()))
            addAll(buildCandidateKeys(channel.displayName))
            addAll(buildCandidateKeys(channel.name))
        }.map { normalizeLookupKey(it) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun EpgProgramRow.toXmltvProgram(): XmltvProgram {
        return XmltvProgram(
            channelId = channelId,
            title = title,
            description = description,
            category = category,
            startUtcMillis = startUtcMillis,
            endUtcMillis = endUtcMillis
        )
    }

    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Failed to fetch playlist (${response.code} ${response.message}). Preview: ${bodyText.take(300)}")
            }
            if (bodyText.isBlank()) {
                error("Empty response from server.")
            }
            bodyText
        }
    }

    private fun buildCandidateKeys(value: String): List<String> {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return emptyList()
        return buildList {
            add(trimmed)
            add(normalizeId(trimmed))
            add(normalizeName(trimmed))
            simplifyName(trimmed)?.let { add(it) }
        }.map { normalizeLookupKey(it) }
            .distinct()
            .filter { it.isNotBlank() }
    }

    private fun normalizeId(value: String): String {
        return value.trim().lowercase(Locale.US).replace(Regex("""\s+"""), "")
    }

    private fun normalizeName(value: String): String {
        return value
            .lowercase(Locale.US)
            .replace(Regex("""\[[^\]]*\]"""), " ")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\b(hd|uhd|fhd|sd|4k|1080p|720p|hevc|h265|h264|hdr|aac|fps|usa|us|uk|ca|au)\b"""), " ")
            .replace("+", " plus ")
            .replace(Regex("""[^a-z0-9]+"""), "")
            .trim()
    }

    private fun simplifyName(value: String): String? {
        val simplified = value
            .lowercase(Locale.US)
            .replace(Regex("""\[[^\]]*\]"""), " ")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\b(hd|uhd|fhd|sd|4k|1080p|720p|hevc|h265|h264|hdr|aac|fps)\b"""), " ")
            .replace("+", " plus ")
            .replace(Regex("""[^a-z0-9]+"""), "")
            .trim()

        return simplified.ifBlank { null }
    }

    private fun normalizeLookupKey(value: String): String {
        return value
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("""\[[^\]]*\]"""), " ")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace("+", " plus ")
            .replace(Regex("""[^a-z0-9.]+"""), "")
            .trim()
    }

    private fun normalizeGuideKey(value: String): String {
        return value.trim().lowercase(Locale.US)
    }

    private companion object {
        const val TAG = "IptvRepository"
        const val CHANNEL_QUERY_BATCH_SIZE = 500
        const val PROGRAMS_PER_BATCH_LIMIT = 2_000
        val importRequestMutex = Mutex()
        val activeGuideImports = ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()
    }
}
