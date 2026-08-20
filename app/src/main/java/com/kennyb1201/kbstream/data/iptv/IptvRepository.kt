package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.iptv.db.CachedPlaylistChannelEntity
import com.kennyb1201.kbstream.data.iptv.db.CachedPlaylistChannelRow
import com.kennyb1201.kbstream.data.iptv.db.EpgChannelEntity
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import com.kennyb1201.kbstream.data.iptv.db.IptvDatabase
import com.kennyb1201.kbstream.data.iptv.db.PlaylistEpgMatchEntity
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
        val normalizedPlaylistUrl = playlist.sourceUrl.orEmpty().trim()

        if (
            normalizedGuideUrl.isBlank() ||
            normalizedPlaylistUrl.isBlank() ||
            playlist.channels.isEmpty()
        ) {
            emit(playlist.channels.map(::unmatchedItem))
            return@flow
        }

        val guideChannels = withContext(Dispatchers.IO) {
            dao.getChannelsBySource(normalizedGuideUrl)
        }

        if (guideChannels.isEmpty()) {
            Log.w(TAG, "LINEUP QUERY no guide channels epgUrl=$normalizedGuideUrl")
            emit(playlist.channels.map(::unmatchedItem))
            return@flow
        }

        val channelIds = playlist.channels.map { it.id }
        val cachedMatches = loadCachedMatches(
            playlistUrl = normalizedPlaylistUrl,
            epgUrl = normalizedGuideUrl,
            playlistChannelIds = channelIds
        )

        val resolvedMatches = resolveMatches(
            playlistUrl = normalizedPlaylistUrl,
            epgUrl = normalizedGuideUrl,
            playlistChannels = playlist.channels,
            guideChannels = guideChannels,
            cachedMatches = cachedMatches
        )

        val matchedGuideIds = resolvedMatches.values
            .mapNotNull { it.epgChannel?.id }
            .distinct()

        Log.w(
            TAG,
            "LINEUP QUERY playlist=${playlist.channels.size} " +
                "matched=${matchedGuideIds.size} epgUrl=$normalizedGuideUrl"
        )

        val rows = loadProgramsChunked(
            sourceUrl = normalizedGuideUrl,
            channelIds = matchedGuideIds,
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
                channels = playlist.channels,
                resolvedMatches = resolvedMatches,
                rows = rows,
                nowUtcMillis = System.currentTimeMillis()
            )
        )
    }.flowOn(Dispatchers.Default)

    private suspend fun loadCachedMatches(
        playlistUrl: String,
        epgUrl: String,
        playlistChannelIds: List<String>
    ): Map<String, PlaylistEpgMatchEntity> {
        if (playlistChannelIds.isEmpty()) return emptyMap()

        return playlistChannelIds
            .distinct()
            .chunked(CHANNEL_QUERY_BATCH_SIZE)
            .flatMap { batch ->
                withContext(Dispatchers.IO) {
                    dao.getPlaylistEpgMatches(
                        playlistUrl = playlistUrl,
                        epgUrl = epgUrl,
                        playlistChannelIds = batch
                    )
                }
            }
            .associateBy { it.playlistChannelId }
    }

    private suspend fun resolveMatches(
        playlistUrl: String,
        epgUrl: String,
        playlistChannels: List<IptvChannel>,
        guideChannels: List<EpgChannelEntity>,
        cachedMatches: Map<String, PlaylistEpgMatchEntity>
    ): Map<String, ResolvedEpgMatch> {
        val guideById = guideChannels.associateBy { normalizeLookupKey(it.id) }
        val guideByDisplayName = HashMap<String, EpgChannelEntity>()

        guideChannels.forEach { guideChannel ->
            guideChannel.displayNames().forEach { displayName ->
                guideByDisplayName.putIfAbsent(
                    normalizeLookupKey(displayName),
                    guideChannel
                )
            }
        }

        val resolved = LinkedHashMap<String, ResolvedEpgMatch>(playlistChannels.size)
        val recordsToSave = ArrayList<PlaylistEpgMatchEntity>()
        val updatedAt = System.currentTimeMillis()

        playlistChannels.forEach { channel ->
            val cached = cachedMatches[channel.id]
            val cachedGuideChannel = cached?.epgChannelId
                ?.let(::normalizeLookupKey)
                ?.let(guideById::get)

            val match = if (cached != null &&
                (cached.epgChannelId == null || cachedGuideChannel != null)
            ) {
                ResolvedEpgMatch(
                    epgChannel = cachedGuideChannel?.toXmltvChannel(),
                    matchType = cached.matchType.toEpgMatchType()
                )
            } else {
                findBestMatch(
                    channel = channel,
                    guideById = guideById,
                    guideByDisplayName = guideByDisplayName
                ).also { resolvedMatch ->
                    recordsToSave += PlaylistEpgMatchEntity(
                        playlistUrl = playlistUrl,
                        playlistChannelId = channel.id,
                        epgUrl = epgUrl,
                        epgChannelId = resolvedMatch.epgChannel?.id,
                        matchType = resolvedMatch.matchType.name,
                        updatedAtUtcMillis = updatedAt
                    )
                }
            }

            resolved[channel.id] = match
        }

        if (recordsToSave.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                dao.insertPlaylistEpgMatches(recordsToSave)
            }
        }

        return resolved
    }

    private fun findBestMatch(
        channel: IptvChannel,
        guideById: Map<String, EpgChannelEntity>,
        guideByDisplayName: Map<String, EpgChannelEntity>
    ): ResolvedEpgMatch {
        val idCandidates = listOf(channel.tvgId, channel.providerChannelId)

        idCandidates.firstNotNullOfOrNull { candidate ->
            candidate?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::normalizeLookupKey)
                ?.let(guideById::get)
        }?.let { guideChannel ->
            return ResolvedEpgMatch(
                epgChannel = guideChannel.toXmltvChannel(),
                matchType = EpgMatchType.ID_MATCH
            )
        }

        val nameCandidates = listOf(
            channel.tvgName,
            channel.displayName,
            channel.name
        )

        nameCandidates.firstNotNullOfOrNull { candidate ->
            candidate?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::normalizeLookupKey)
                ?.let(guideByDisplayName::get)
        }?.let { guideChannel ->
            return ResolvedEpgMatch(
                epgChannel = guideChannel.toXmltvChannel(),
                matchType = EpgMatchType.NAME_MATCH
            )
        }

        return ResolvedEpgMatch(
            epgChannel = null,
            matchType = EpgMatchType.NO_MATCH
        )
    }

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
            .take(totalLimit)
            .toList()
    }

    private fun mapChannels(
        channels: List<IptvChannel>,
        resolvedMatches: Map<String, ResolvedEpgMatch>,
        rows: List<EpgProgramRow>,
        nowUtcMillis: Long
    ): List<IptvChannelWithEpg> {
        val programsByChannel = rows.groupByTo(HashMap()) { it.channelId }

        return channels.map { channel ->
            val match = resolvedMatches[channel.id] ?: ResolvedEpgMatch(
                epgChannel = null,
                matchType = EpgMatchType.NO_MATCH
            )
            val guideChannelId = match.epgChannel?.id?.let(::normalizeLookupKey)
            val matchedPrograms = guideChannelId
                ?.let(programsByChannel::get)
                .orEmpty()
                .sortedBy { it.startUtcMillis }

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

            Log.w(
                TAG,
                "EPG MAP channel=${channel.displayName} " +
                    "match=${match.matchType} " +
                    "matched=${matchedPrograms.size} " +
                    "now=${now?.title} " +
                    "next=${next?.title} " +
                    "upcoming=${upcoming.size}"
            )

            IptvChannelWithEpg(
                channel = channel,
                epgChannel = match.epgChannel,
                epgMatchType = match.matchType,
                now = now?.let(::mapProgramRow),
                next = next?.let(::mapProgramRow),
                upcoming = upcoming
            )
        }
    }

    private fun unmatchedItem(channel: IptvChannel): IptvChannelWithEpg =
        IptvChannelWithEpg(
            channel = channel,
            epgChannel = null,
            epgMatchType = EpgMatchType.NO_MATCH,
            now = null,
            next = null,
            upcoming = emptyList()
        )

    private fun EpgChannelEntity.displayNames(): List<String> =
        buildList {
            add(id)
            add(primaryDisplayName)
            allDisplayNames
                .split("\n", "\r", "|")
                .map(String::trim)
                .filter(String::isNotBlank)
                .forEach(::add)
        }.distinct()

    private fun EpgChannelEntity.toXmltvChannel(): XmltvChannel =
        XmltvChannel(
            id = id,
            displayNames = displayNames().filterNot { it == id },
            iconUrl = iconUrl
        )

    private fun String.toEpgMatchType(): EpgMatchType =
        runCatching { EpgMatchType.valueOf(this) }
            .getOrDefault(EpgMatchType.NO_MATCH)

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
            headers = parseHeaders(row.headersText)
        )

    private fun parseHeaders(headersText: String?): Map<String, String> =
        runCatching {
            if (headersText.isNullOrBlank()) {
                return@runCatching emptyMap()
            }

            val json = JSONObject(headersText)
            buildMap {
                json.keys().forEach { key ->
                    json.optString(key)
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?.let { value -> put(key, value) }
                }
            }
        }.getOrDefault(emptyMap())

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
            .replace("&", " and ")
            .replace("+", " plus ")
            .replace(NON_LOOKUP_CHARACTERS, "")
            .trim()

    private fun normalizeGuideKey(value: String): String =
        value.trim().lowercase(Locale.US)

    private data class ResolvedEpgMatch(
        val epgChannel: XmltvChannel?,
        val matchType: EpgMatchType
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
