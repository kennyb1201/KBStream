package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.iptv.db.CachedPlaylistChannelEntity
import com.kennyb1201.kbstream.data.iptv.db.CachedPlaylistChannelRow
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
import org.json.JSONObject

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
        val normalizedUrl = playlistUrl.trim()

        val playlistContent = IptvHttpClient
            .fetchTextWithRetry(client, normalizedUrl)
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

        val playlist = withContext(Dispatchers.Default) {
            m3uParser.parse(
                content = playlistContent,
                sourceUrl = normalizedUrl,
                playlistName = playlistName
            )
        }

        Log.d(
            TAG,
            "PLAYLIST CACHE WRITE START channels=${playlist.channels.size} source=$normalizedUrl"
        )

        dao.replaceCachedPlaylistChannels(
            playlistUrl = normalizedUrl,
            channels = playlist.channels.mapIndexed { index, channel ->
                channel.toCachedEntity(
                    playlistUrl = normalizedUrl,
                    position = index
                )
            }
        )

        Log.d(
            TAG,
            "PLAYLIST CACHE WRITE END channels=${playlist.channels.size} source=$normalizedUrl"
        )

        playlist
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
        limit: Int = Int.MAX_VALUE
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

        Log.w(
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

        Log.w(
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
    if (sourceUrl.isBlank() || channelIds.isEmpty()) {
        return emptyList()
    }

    val results = ArrayList<EpgProgramRow>()

    for (batch in channelIds.chunked(CHANNEL_QUERY_BATCH_SIZE)) {
        val batchRows = withContext(Dispatchers.IO) {
            dao.getProgramsForChannelsInWindow(
                sourceUrl = sourceUrl,
                channelIds = batch,
                windowStart = windowStart,
                windowEnd = windowEnd
            )
        }

        results.addAll(batchRows)
    }

    val seen = HashSet<ProgramKey>(results.size)

    return results.asSequence()
        .map { row -> row.copy(channelId = normalizeLookupKey(row.channelId)) }
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

    private fun collectChannelKeys(channel: IptvChannel): List<String> =
        listOfNotNull(
            channel.tvgId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::normalizeLookupKey),

            channel.providerChannelId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::normalizeLookupKey)
        ).distinct()

    private fun mapProgramRow(row: EpgProgramRow): XmltvProgram =
        XmltvProgram(
            channelId = row.channelId,
            title = row.title,
            description = row.description,
            category = row.category,
            startUtcMillis = row.startUtcMillis,
            endUtcMillis = row.endUtcMillis
        )

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

    suspend fun loadCachedPlaylist(
        playlistUrl: String,
        playlistName: String? = null
    ): IptvPlaylist? = withContext(Dispatchers.IO) {
        val normalizedUrl = playlistUrl.trim()

        if (normalizedUrl.isBlank()) {
            Log.d(TAG, "PLAYLIST CACHE READ SKIPPED: blank URL")
            return@withContext null
        }

        Log.w(TAG, "PLAYLIST CACHE READ START source=$normalizedUrl")

        val channels = ArrayList<IptvChannel>()
        var offset = 0

        while (true) {
            val page = dao.getCachedPlaylistChannelPage(
                playlistUrl = normalizedUrl,
                limit = CACHE_PAGE_SIZE,
                offset = offset
            )

            if (page.isEmpty()) {
                break
            }

            channels.addAll(page.map(::cachedRowToChannel))
            offset += page.size

            if (page.size < CACHE_PAGE_SIZE) {
                break
            }
        }

        Log.w(
            TAG,
            "PLAYLIST CACHE READ END channels=${channels.size} source=$normalizedUrl"
        )

        if (channels.isEmpty()) {
            null
        } else {
            IptvPlaylist(
                name = playlistName,
                sourceUrl = normalizedUrl,
                channels = channels
            )
        }
    }

    private fun IptvChannel.toCachedEntity(
        playlistUrl: String,
        position: Int
    ): CachedPlaylistChannelEntity =
        CachedPlaylistChannelEntity(
            playlistUrl = playlistUrl,
            position = position,
            id = id,
            name = name,
            displayName = displayName,
            streamUrl = streamUrl,
            groupTitle = groupTitle,
            logoUrl = logoUrl,
            tvgId = tvgId,
            tvgName = tvgName,
            tvgChno = tvgChno,
            catchup = catchup,
            catchupDays = catchupDays,
            catchupSource = catchupSource,
            providerChannelId = providerChannelId,
            headersText = JSONObject(headers).toString()
        )

    private fun cachedRowToChannel(
        row: CachedPlaylistChannelRow
    ): IptvChannel =
        IptvChannel(
            id = row.id,
            name = row.name,
            displayName = row.displayName,
            streamUrl = row.streamUrl,
            groupTitle = row.groupTitle,
            logoUrl = row.logoUrl,
            tvgId = row.tvgId,
            tvgName = row.tvgName,
            tvgChno = row.tvgChno,
            catchup = row.catchup,
            catchupDays = row.catchupDays,
            catchupSource = row.catchupSource,
            providerChannelId = row.providerChannelId,
            headers = emptyMap()
        )

    private data class ProgramKey(
        val channelId: String,
        val startUtcMillis: Long,
        val endUtcMillis: Long,
        val title: String
    )

    private companion object {
        const val TAG = "IptvRepository"

        const val CACHE_PAGE_SIZE = 500
        const val CHANNEL_QUERY_BATCH_SIZE = 400
        const val MAX_UPCOMING_PROGRAMS = 12

        val BRACKETED_TEXT = Regex("""\[[^]]*]""")
val PARENTHESIZED_TEXT = Regex("""\([^)]*\)""")
val NON_LOOKUP_CHARACTERS = Regex("""[^a-z0-9.]+""")

        val importRequestMutex = Mutex()
        val activeGuideImports =
            ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()
    }
}
