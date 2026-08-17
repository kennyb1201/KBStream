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
import com.kennyb1201.kbstream.data.iptv.db.CachedPlaylistChannelEntity
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

        val batchLimit = minOf(
            remaining,
            batch.size * ROWS_PER_CHANNEL_TARGET
        )

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

    suspend fun loadCachedPlaylist(
    playlistUrl: String,
    playlistName: String? = null
): IptvPlaylist? = withContext(Dispatchers.IO) {
    val normalizedUrl = playlistUrl.trim()

    if (normalizedUrl.isBlank()) {
        Log.d(TAG, "PLAYLIST CACHE READ SKIPPED: blank URL")
        return@withContext null
    }

    Log.d(TAG, "PLAYLIST CACHE READ START source=$normalizedUrl")

    val channels = dao.getCachedPlaylistChannels(normalizedUrl)
        .map(::cachedEntityToChannel)

    Log.d(
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

private fun cachedEntityToChannel(
    entity: CachedPlaylistChannelEntity
): IptvChannel =
    IptvChannel(
        id = entity.id,
        name = entity.name,
        displayName = entity.displayName,
        streamUrl = entity.streamUrl,
        groupTitle = entity.groupTitle,
        logoUrl = entity.logoUrl,
        tvgId = entity.tvgId,
        tvgName = entity.tvgName,
        tvgChno = entity.tvgChno,
        catchup = entity.catchup,
        catchupDays = entity.catchupDays,
        catchupSource = entity.catchupSource,
        providerChannelId = entity.providerChannelId,
        headers = entity.headersText.toHeadersMap()
    )

private fun String.toHeadersMap(): Map<String, String> = runCatching {
    val json = JSONObject(this)
    buildMap {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, json.optString(key))
        }
    }
}.getOrDefault(emptyMap())

    private companion object {
        const val TAG = "IptvRepository"

        const val CHANNEL_QUERY_BATCH_SIZE = 50
        const val ROWS_PER_CHANNEL_TARGET = 20
        const val DEFAULT_PROGRAM_LIMIT = 200_000
        const val INITIAL_PROGRAM_CAPACITY = 10_000
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
