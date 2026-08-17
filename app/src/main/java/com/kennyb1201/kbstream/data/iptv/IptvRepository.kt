package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.iptv.db.EpgChannelEntity
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import com.kennyb1201.kbstream.data.iptv.db.IptvDatabase
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class IptvRepository(
    context: Context,
    private val client: OkHttpClient = IptvHttpClient.create(),
    private val m3uParser: M3uParser = M3uParser()
) {
    private val db = IptvDatabase.getInstance(context)
    private val dao = db.iptvDao()
    private val xmltvImporter = XmltvImporter(dao)

    suspend fun loadPlaylist(
        playlistUrl: String,
        playlistName: String? = null
    ): IptvPlaylist = withContext(Dispatchers.IO) {
        val playlistContent = IptvHttpClient
            .fetchTextWithRetry(client, playlistUrl)
            .removePrefix("﻿")

        if (
            !playlistContent.contains("#EXTM3U", ignoreCase = true) &&
            !playlistContent.contains("#EXTINF", ignoreCase = true)
        ) {
            error(
                "Playlist response does not look like M3U. " +
                    "First 200 chars: ${playlistContent.take(200)}"
            )
        }

        withContext(Dispatchers.Default) {
            m3uParser.parse(
                content = playlistContent,
                sourceUrl = playlistUrl,
                playlistName = playlistName
            )
        }
    }

    suspend fun importGuide(epgUrl: String) = withContext(Dispatchers.IO) {
        val normalizedUrl = epgUrl.trim()
        require(normalizedUrl.isNotBlank()) { "EPG URL is required" }

        val requestKey = normalizeGuideKey(normalizedUrl)
        val waiter = CompletableDeferred<Result<Unit>>()

        val activeImport = importRequestMutex.withLock {
            activeGuideImports[requestKey]?.also {
                Log.w(TAG, "GUIDE IMPORT JOIN source=$normalizedUrl")
            } ?: waiter.also {
                activeGuideImports[requestKey] = it
                Log.d(TAG, "GUIDE IMPORT START source=$normalizedUrl")
            }
        }

        if (activeImport !== waiter) {
            return@withContext activeImport.await().getOrThrow()
        }

        try {
            val result = runCatching {
                dao.clearGuideBySource(normalizedUrl)

                IptvHttpClient.streamXmltvWithRetry(client, normalizedUrl) { stream ->
                    xmltvImporter.import(normalizedUrl, stream)
                }
            }

            waiter.complete(result)
            result.getOrThrow()
        } finally {
            importRequestMutex.withLock {
                activeGuideImports.remove(requestKey, waiter)
            }

            Log.d(TAG, "GUIDE IMPORT END source=$normalizedUrl")
        }
    }

    suspend fun getGuideChannels(epgUrl: String): List<EpgChannelEntity> =
        withContext(Dispatchers.IO) {
            dao.getChannelsBySource(epgUrl.trim())
        }

    fun observeLineupWithGuide(
        playlist: IptvPlaylist,
        epgUrl: String,
        windowStart: Long,
        windowEnd: Long,
        limit: Int = DEFAULT_PROGRAM_LIMIT
    ): Flow<List<IptvChannelWithEpg>> = flow {
        val normalizedGuideUrl = epgUrl.trim()

        val channelKeysByPlaylistChannel = playlist.channels.map { channel ->
            channel to collectChannelKeys(channel)
        }

        val channelIds = channelKeysByPlaylistChannel
            .asSequence()
            .flatMap { (_, keys) -> keys.asSequence() }
            .distinct()
            .toList()

        Log.d(
            TAG,
            "LINEUP QUERY candidates=${channelIds.size} epgUrl=$normalizedGuideUrl"
        )

        val rows = loadProgramsChunked(
            sourceUrl = normalizedGuideUrl,
            channelIds = channelIds,
            windowStart = windowStart,
            windowEnd = windowEnd,
            totalLimit = limit
        )

        Log.d(
            TAG,
            "LINEUP QUERY rows=${rows.size} epgUrl=$normalizedGuideUrl"
        )

        emit(
            mapChannels(
                channelsWithKeys = channelKeysByPlaylistChannel,
                rows = rows,
                nowUtcMillis = System.currentTimeMillis()
            )
        )
    }.flowOn(Dispatchers.Default)

    private suspend fun loadProgramsChunked(
        sourceUrl: String,
        channelIds: List<String>,
        windowStart: Long,
        windowEnd: Long,
        totalLimit: Int
    ): List<EpgProgramRow> {
        if (sourceUrl.isBlank() || channelIds.isEmpty() || totalLimit <= 0) {
            return emptyList()
        }

        val results = ArrayList<EpgProgramRow>(
            minOf(totalLimit, INITIAL_PROGRAM_CAPACITY)
        )
        var remaining = totalLimit

        for (batch in channelIds.chunked(CHANNEL_QUERY_BATCH_SIZE)) {
            if (remaining <= 0) break

            val batchLimit = minOf(remaining, PROGRAMS_PER_BATCH_LIMIT)

            val batchRows = withContext(Dispatchers.IO) {
                dao.getProgramsForChannelsInWindow(
                    sourceUrl = sourceUrl,
                    channelIds = batch,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    limit = batchLimit
                )
            }

            results.addAll(batchRows)
            remaining = totalLimit - results.size
        }

        val seen = HashSet<ProgramKey>(results.size)

        return results.asSequence()
            .map { row ->
                row.copy(channelId = normalizeLookupKey(row.channelId))
            }
            .filter { row ->
                seen.add(
                    ProgramKey(
                        channelId = row.channelId,
                        startUtcMillis = row.startUtcMillis,
                        endUtcMillis = row.endUtcMillis,
                        title = row.title
                    )
                )
            }
            .sortedBy { it.startUtcMillis }
            .take(totalLimit)
            .toList()
    }

    private fun mapChannels(
        channelsWithKeys: List<Pair<IptvChannel, List<String>>>,
        rows: List<EpgProgramRow>,
        nowUtcMillis: Long
    ): List<IptvChannelWithEpg> {
        val programsByChannel = rows.groupByTo(HashMap()) { it.channelId }

        return channelsWithKeys.map { (channel, ids) ->
            val seen = HashSet<ProgramKey>()

            val matchedPrograms = ids.asSequence()
                .flatMap { programsByChannel[it].orEmpty().asSequence() }
                .filter { row ->
                    seen.add(
                        ProgramKey(
                            channelId = row.channelId,
                            startUtcMillis = row.startUtcMillis,
                            endUtcMillis = row.endUtcMillis,
                            title = row.title
                        )
                    )
                }
                .sortedBy { it.startUtcMillis }
                .toList()

            val now = matchedPrograms.firstOrNull { program ->
                nowUtcMillis >= program.startUtcMillis &&
                    nowUtcMillis < program.endUtcMillis
            }

            val nextStart = now?.endUtcMillis ?: nowUtcMillis

            val next = matchedPrograms.firstOrNull { program ->
                program.startUtcMillis >= nextStart
            }

            val upcoming = matchedPrograms.asSequence()
    .filter { it.startUtcMillis >= nextStart }
    .take(MAX_UPCOMING_PROGRAMS)
    .map(::mapProgramRow)
    .toList()

            IptvChannelWithEpg(
                channel = channel,
                epgChannel = null,
                epgMatchType = if (matchedPrograms.isEmpty()) {
                    EpgMatchType.NO_MATCH
                } else {
                    EpgMatchType.ID_MATCH
                },
                now = now?.let(::mapProgramRow),
                next = next?.let(::mapProgramRow),
                upcoming = upcoming
            )
        }
    }

    private fun collectChannelKeys(channel: IptvChannel): List<String> {
        val keys = LinkedHashSet<String>()

        addCandidateKeys(keys, channel.tvgId.orEmpty())
        addCandidateKeys(keys, channel.providerChannelId.orEmpty())
        addCandidateKeys(keys, channel.tvgName.orEmpty())
        addCandidateKeys(keys, channel.displayName)
        addCandidateKeys(keys, channel.name)

        return keys.toList()
    }

    private fun addCandidateKeys(
        keys: MutableSet<String>,
        value: String
    ) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return

        fun add(candidate: String?) {
            candidate
                ?.let(::normalizeLookupKey)
                ?.takeIf { it.isNotBlank() }
                ?.let(keys::add)
        }

        add(trimmed)
        add(normalizeId(trimmed))
        add(normalizeName(trimmed))
        add(simplifyName(trimmed))
    }

    private fun mapProgramRow(row: EpgProgramRow): XmltvProgram =
        XmltvProgram(
            channelId = row.channelId,
            title = row.title,
            description = row.description,
            category = row.category,
            startUtcMillis = row.startUtcMillis,
            endUtcMillis = row.endUtcMillis
        )

    private fun normalizeId(value: String): String =
        value.trim()
            .lowercase(Locale.US)
            .replace(WHITESPACE, "")

    private fun normalizeName(value: String): String =
        value.lowercase(Locale.US)
            .replace(BRACKETED_TEXT, " ")
            .replace(PARENTHESIZED_TEXT, " ")
            .replace(CHANNEL_QUALIFIERS, " ")
            .replace("+", " plus ")
            .replace(NON_ALPHANUMERIC, "")
            .trim()

    private fun simplifyName(value: String): String? =
        normalizeName(value).ifBlank { null }

    private fun normalizeLookupKey(value: String): String =
        value.trim()
            .lowercase(Locale.US)
            .replace(BRACKETED_TEXT, " ")
            .replace(PARENTHESIZED_TEXT, " ")
            .replace("+", " plus ")
            .replace(NON_LOOKUP_CHARACTERS, "")
            .trim()

    private fun normalizeGuideKey(value: String): String =
        value.trim().lowercase(Locale.US)

    private data class ProgramKey(
        val channelId: String,
        val startUtcMillis: Long,
        val endUtcMillis: Long,
        val title: String
    )

    private companion object {
        const val TAG = "IptvRepository"

        const val CHANNEL_QUERY_BATCH_SIZE = 500
        const val PROGRAMS_PER_BATCH_LIMIT = 2_000
        const val DEFAULT_PROGRAM_LIMIT = 20_000
        const val INITIAL_PROGRAM_CAPACITY = 4_000
        const val MAX_UPCOMING_PROGRAMS = 12

val WHITESPACE = Regex("""\s+""")
        val BRACKETED_TEXT = Regex("""\[[^\]]*]""")
        val PARENTHESIZED_TEXT = Regex("""\([^)]*\)""")
        val CHANNEL_QUALIFIERS = Regex(
            """\b(hd|uhd|fhd|sd|4k|1080p|720p|hevc|h265|h264|hdr|aac|fps|usa|us|uk|ca|au)\b"""
        )
        val NON_ALPHANUMERIC = Regex("""[^a-z0-9]+""")
        val NON_LOOKUP_CHARACTERS = Regex("""[^a-z0-9.]+""")

        val importRequestMutex = Mutex()
        val activeGuideImports = ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()
    }
}
