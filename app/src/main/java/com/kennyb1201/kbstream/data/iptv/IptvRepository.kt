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

/** A fully-aired program with a resolved catch-up (DVR) playback URL. */
data class CatchupProgram(
    val title: String,
    val description: String?,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val url: String
)

class IptvRepository(
    context: Context,
    private val client: OkHttpClient = IptvHttpClient.create(),
    private val m3uParser: M3uParser = M3uParser()
) {
    // Capture the app context: constructor params are not in scope inside
    // custom getter accessors.
    private val appContext: Context = context.applicationContext

    // DB/DAO rebind per access: IptvDatabase.getInstance resolves the ACTIVE
    // profile's scoped DB file. Capturing the DAO once pinned this repository
    // to whatever profile was active at construction - after a switch, reads
    // and imports would land in the previous profile's EPG database.
    private val db: IptvDatabase get() = IptvDatabase.getInstance(appContext)
    private val dao: com.kennyb1201.kbstream.data.iptv.db.IptvDao get() = db.iptvDao()
    private val xmltvImporter: XmltvImporter get() = XmltvImporter(dao)

    private val guideSnapshotMutex = Mutex()
    private val guideSnapshots = ConcurrentHashMap<String, GuideSnapshot>()

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
            invalidateGuideSnapshot(normalizedUrl)
        } finally {
            importRequestMutex.withLock {
                activeGuideImports.remove(requestKey, waiter)
            }

            Log.d(TAG, "GUIDE IMPORT END source=$normalizedUrl")
        }
    }

    suspend fun getGuideChannels(epgUrl: String): List<EpgChannelEntity> =
        withContext(Dispatchers.IO) {
            getOrCreateGuideSnapshot(epgUrl.trim()).guideChannels
        }

    fun observeLineupWithGuides(
        playlist: IptvPlaylist,
        epgUrls: List<String>,
        windowStart: Long,
        windowEnd: Long,
        limit: Int = Int.MAX_VALUE
    ): Flow<List<IptvChannelWithEpg>> = flow {
        val normalizedGuideUrls = epgUrls.map(String::trim).filter(String::isNotEmpty)
        val normalizedPlaylistUrl = playlist.sourceUrl.orEmpty().trim()

        if (
            normalizedGuideUrls.isEmpty() ||
            normalizedPlaylistUrl.isBlank() ||
            playlist.channels.isEmpty()
        ) {
            emit(playlist.channels.map(::unmatchedItem))
            return@flow
        }

        // Snapshots for every configured source; a source whose guide has
        // never been imported contributes nothing instead of failing the
        // whole lineup.
        val snapshots = normalizedGuideUrls.mapNotNull { url ->
            val snapshot = getOrCreateGuideSnapshot(url)
            if (snapshot.guideChannels.isEmpty()) {
                Log.w(TAG, "LINEUP QUERY no guide channels epgUrl=$url")
                null
            } else {
                snapshot
            }
        }

        if (snapshots.isEmpty()) {
            emit(playlist.channels.map(::unmatchedItem))
            return@flow
        }

        val channelIds = playlist.channels.map { it.id }

        // First source with a cached match for a channel wins; ties keep
        // the earlier (primary) source's match.
        val cachedMatches = HashMap<String, PlaylistEpgMatchEntity>(channelIds.size)
        for (guideUrl in normalizedGuideUrls) {
            val perSource = loadCachedMatches(
                playlistUrl = normalizedPlaylistUrl,
                epgUrl = guideUrl,
                playlistChannelIds = channelIds.filterNot { it in cachedMatches }
            )
            perSource.forEach { (channelId, match) ->
                cachedMatches.putIfAbsent(channelId, match)
            }
        }

        val resolvedMatches = resolveMatchesMulti(
            playlistUrl = normalizedPlaylistUrl,
            epgUrls = normalizedGuideUrls,
            snapshots = snapshots,
            playlistChannels = playlist.channels,
            cachedMatches = cachedMatches
        )

        val matchedGuideIds = resolvedMatches.values
            .mapNotNull { it.epgChannel?.id }
            .distinct()

        Log.w(
            TAG,
            "LINEUP QUERY playlist=${playlist.channels.size} matched=${matchedGuideIds.size} sources=${snapshots.size}"
        )

        // Programs may live under any source (a channel matched from the
        // second guide has its rows keyed by that guide's URL), so query
        // every snapshot and merge — dedupe keeps a program imported by two
        // overlapping sources from rendering twice.
        val rows = ArrayList<EpgProgramRow>()
        var remainingLimit = limit
        for (snapshot in snapshots) {
            if (remainingLimit <= 0) break
            val sourceRows = loadProgramsChunked(
                sourceUrl = snapshot.sourceUrl,
                channelIds = matchedGuideIds,
                windowStart = windowStart,
                windowEnd = windowEnd,
                totalLimit = remainingLimit
            )
            rows.addAll(sourceRows)
            remainingLimit -= sourceRows.size
        }

        val dedupedRows = rows
            .groupBy { ProgramKey(it.channelId, it.startUtcMillis, it.endUtcMillis, it.title) }
            .map { it.value.first() }

        Log.w(
            TAG,
            "LINEUP QUERY rows=${dedupedRows.size} sources=${snapshots.size}"
        )

        emit(
            mapChannels(
                channels = playlist.channels,
                resolvedMatches = resolvedMatches,
                rows = dedupedRows,
                nowUtcMillis = System.currentTimeMillis()
            )
        )
    }.flowOn(Dispatchers.Default)

    private suspend fun getOrCreateGuideSnapshot(epgUrl: String): GuideSnapshot {
        val normalizedGuideUrl = epgUrl.trim()
        if (normalizedGuideUrl.isBlank()) {
            return GuideSnapshot(
                sourceUrl = normalizedGuideUrl,
                guideChannels = emptyList(),
                guideById = emptyMap(),
                guideByDisplayName = emptyMap()
            )
        }

        guideSnapshots[normalizedGuideUrl]?.let { return it }

        return guideSnapshotMutex.withLock {
            guideSnapshots[normalizedGuideUrl]?.let { return@withLock it }

            val guideChannels = withContext(Dispatchers.IO) {
                dao.getChannelsBySource(normalizedGuideUrl)
            }

            val guideById = guideChannels.associateBy { normalizeLookupKey(it.id) }
            val guideByDisplayName = HashMap<String, EpgChannelEntity>(guideChannels.size * 2)

            guideChannels.forEach { guideChannel ->
                guideChannel.displayNames().forEach { displayName ->
                    guideByDisplayName.putIfAbsent(
                        normalizeLookupKey(displayName),
                        guideChannel
                    )
                }
            }

            GuideSnapshot(
                sourceUrl = normalizedGuideUrl,
                guideChannels = guideChannels,
                guideById = guideById,
                guideByDisplayName = guideByDisplayName
            ).also { snapshot ->
                guideSnapshots[normalizedGuideUrl] = snapshot
                Log.w(
                    TAG,
                    "GUIDE SNAPSHOT BUILT channels=${guideChannels.size} epgUrl=$normalizedGuideUrl"
                )
            }
        }
    }

    private suspend fun invalidateGuideSnapshot(epgUrl: String) {
        val normalizedGuideUrl = epgUrl.trim()
        if (normalizedGuideUrl.isBlank()) return

        guideSnapshotMutex.withLock {
            guideSnapshots.remove(normalizedGuideUrl)
        }
    }

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

    /**
     * Match every playlist channel against the FIRST source that resolves
     * it, in [epgUrls] order (primary first). A channel can end up matched
     * in source A while its neighbor matches in source B — per-source
     * caching keys everything by epgUrl so both coexist.
     */
    private suspend fun resolveMatchesMulti(
        playlistUrl: String,
        epgUrls: List<String>,
        snapshots: List<GuideSnapshot>,
        playlistChannels: List<IptvChannel>,
        cachedMatches: Map<String, PlaylistEpgMatchEntity>
    ): Map<String, ResolvedEpgMatch> {
        val resolved = LinkedHashMap<String, ResolvedEpgMatch>(playlistChannels.size)
        val recordsToSave = ArrayList<PlaylistEpgMatchEntity>()
        val updatedAt = System.currentTimeMillis()
        val snapshotByUrl = snapshots.associateBy { it.sourceUrl }

        playlistChannels.forEach { channel ->
            val cached = cachedMatches[channel.id]
            val cachedSnapshot = cached?.epgUrl
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { raw -> snapshotByUrl.entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } }
                ?.value
            val cachedGuideChannel = cached?.epgChannelId
                ?.let(::normalizeLookupKey)
                ?.let { key -> cachedSnapshot?.guideById?.get(key) }

            val match = if (
                cached != null &&
                cachedSnapshot != null &&
                (cached.epgChannelId == null || cachedGuideChannel != null)
            ) {
                ResolvedEpgMatch(
                    epgChannel = cachedGuideChannel?.toXmltvChannel(),
                    matchType = cached.matchType.toEpgMatchType()
                )
            } else {
                findBestMatchMulti(
                    channel = channel,
                    epgUrls = epgUrls,
                    snapshots = snapshots
                ).also { resolvedMatch ->
                    if (resolvedMatch.epgUrl != null) {
                        recordsToSave += PlaylistEpgMatchEntity(
                            playlistUrl = playlistUrl,
                            playlistChannelId = channel.id,
                            epgUrl = resolvedMatch.epgUrl,
                            epgChannelId = resolvedMatch.epgChannel?.id,
                            matchType = resolvedMatch.matchType.name,
                            updatedAtUtcMillis = updatedAt
                        )
                    }
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

    private fun findBestMatchMulti(
        channel: IptvChannel,
        epgUrls: List<String>,
        snapshots: List<GuideSnapshot>
    ): ResolvedEpgMatch {
        val idCandidates = listOf(channel.tvgId, channel.providerChannelId)
        val nameCandidates = listOf(channel.tvgName, channel.displayName, channel.name)

        epgUrls.forEach { epgUrl ->
            val snapshot = snapshots.firstOrNull { it.sourceUrl == epgUrl }
                ?: return@forEach

            idCandidates.firstNotNullOfOrNull { candidate ->
                candidate?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::normalizeLookupKey)
                    ?.let(snapshot.guideById::get)
            }?.let { guideChannel ->
                return ResolvedEpgMatch(
                    epgChannel = guideChannel.toXmltvChannel(),
                    matchType = EpgMatchType.ID_MATCH,
                    epgUrl = epgUrl
                )
            }

            nameCandidates.firstNotNullOfOrNull { candidate ->
                candidate?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::normalizeLookupKey)
                    ?.let(snapshot.guideByDisplayName::get)
            }?.let { guideChannel ->
                return ResolvedEpgMatch(
                    epgChannel = guideChannel.toXmltvChannel(),
                    matchType = EpgMatchType.NAME_MATCH,
                    epgUrl = epgUrl
                )
            }
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
        if (sourceUrl.isBlank() || channelIds.isEmpty() || totalLimit <= 0) {
            return emptyList()
        }

        val results = ArrayList<EpgProgramRow>()
        var remainingLimit = totalLimit

        for (batch in channelIds.chunked(PROGRAM_QUERY_BATCH_SIZE)) {
            if (remainingLimit <= 0) break

            val perBatchTarget = (batch.size * PROGRAMS_PER_CHANNEL_TARGET)
                .coerceAtMost(MAX_PROGRAM_ROWS_PER_BATCH)
            val batchLimit = remainingLimit.coerceAtMost(perBatchTarget)

            val batchRows = withContext(Dispatchers.IO) {
                dao.getProgramsForChannelsInWindowLite(
    sourceUrl = sourceUrl,
    channelIds = batch,
    windowStart = windowStart,
    windowEnd = windowEnd,
    perChannelLimit = PROGRAMS_PER_CHANNEL_TARGET
)
            }
                .sortedBy { it.startUtcMillis }
                .take(batchLimit)

            Log.w(
                TAG,
                "PROGRAM BATCH channels=${batch.size} rows=${batchRows.size} remaining=$remainingLimit target=$batchLimit"
            )

            results.addAll(batchRows)
            remainingLimit -= batchRows.size
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
            .sortedWith(compareBy<EpgProgramRow> { it.channelId }.thenBy { it.startUtcMillis })
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

            val next = matchedPrograms.firstOrNull { program ->
                program.startUtcMillis >= nowUtcMillis
            }

            val upcoming = matchedPrograms.asSequence()
                .filter { program ->
                    next != null && program.startUtcMillis > next.startUtcMillis
                }
                .take(MAX_UPCOMING_PROGRAMS)
                .map(::mapProgramRow)
                .toList()

            val item = IptvChannelWithEpg(
                channel = channel,
                epgChannel = match.epgChannel,
                epgMatchType = match.matchType,
                now = now?.let(::mapProgramRow),
                next = next?.let(::mapProgramRow),
                upcoming = upcoming
            )

            

            item
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
            description = row.description?.takeIf { it.isNotBlank() },
            category = row.category?.takeIf { it.isNotBlank() },
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

    /**
     * Guide-wide program search ("what's on with X tonight"): title match
     * across every channel for programs that have not finished yet.
     *
     * Channel visibility is applied by the caller — the guide owns the
     * hidden-channel rules, and a channel the user hid must not resurface
     * through a program match. Short queries return nothing (a one-letter
     * query matches most of the guide and would look like a freeze).
     */
    suspend fun searchPrograms(query: String, limit: Int = 60): List<EpgProgramRow> {
        val q = query.trim()
        if (q.length < MIN_PROGRAM_SEARCH_LENGTH) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                dao.searchProgramsByTitle(q, System.currentTimeMillis(), limit)
            }.getOrElse { t ->
                Log.w(TAG, "program search failed: ${t.message}")
                emptyList()
            }
        }
    }

    /**
     * Catch-up entries for one channel: the channel's last programs that
     * have fully aired (newest first), each carrying a resolved DVR URL
     * template built from the channel's M3U catch-up attributes. Channels
     * without catch-up attributes resolve to an empty list — the guide then
     * renders the section as unavailable instead of silently dead entries.
     */
    suspend fun getRecentCatchupPrograms(
        channel: IptvChannel,
        epgUrl: String,
        count: Int = 12,
        windowDays: Int = 7
    ): List<CatchupProgram> = withContext(Dispatchers.IO) {
        val template = channel.catchupSource
            ?: channel.catchup
            ?: return@withContext emptyList()

        val guideUrl = epgUrl.trim()
        if (guideUrl.isBlank()) return@withContext emptyList()

        val now = System.currentTimeMillis()
        val epgChannelId = channel.tvgId?.trim().orEmpty()
        if (epgChannelId.isBlank()) return@withContext emptyList()

        val rows = dao.getRecentProgramsForChannels(
            sourceUrl = guideUrl,
            channelIds = listOf(epgChannelId),
            nowMillis = now,
            windowStart = now - windowDays * 86_400_000L,
            perChannelLimit = count
        )

        rows.mapNotNull { row ->
            // Only fully-aired programs are seekable on provider DVR.
            if (row.endUtcMillis > now) return@mapNotNull null
            if (!CatchupUrls.withinDvrWindow(channel.catchupDays, row.startUtcMillis, now)) {
                return@mapNotNull null
            }
            val url = CatchupUrls.build(
                template = template,
                startUtcMillis = row.startUtcMillis,
                endUtcMillis = row.endUtcMillis,
                nowUtcMillis = now
            ) ?: return@mapNotNull null
            CatchupProgram(
                title = row.title,
                description = row.description?.takeIf { it.isNotBlank() },
                startUtcMillis = row.startUtcMillis,
                endUtcMillis = row.endUtcMillis,
                url = url
            )
        }
    }

    private data class ResolvedEpgMatch(
        val epgChannel: XmltvChannel?,
        val matchType: EpgMatchType,
        /** Source URL the match came from — null for NO_MATCH. */
        val epgUrl: String? = null
    )

    private data class ProgramKey(
        val channelId: String,
        val startUtcMillis: Long,
        val endUtcMillis: Long,
        val title: String
    )

    private data class GuideSnapshot(
        val sourceUrl: String,
        val guideChannels: List<EpgChannelEntity>,
        val guideById: Map<String, EpgChannelEntity>,
        val guideByDisplayName: Map<String, EpgChannelEntity>
    )

    private companion object {
        const val TAG = "IptvRepository"
        const val CACHE_PAGE_SIZE = 500
        const val CHANNEL_QUERY_BATCH_SIZE = 400
        const val PROGRAM_QUERY_BATCH_SIZE = 8
        const val PROGRAMS_PER_CHANNEL_TARGET = 12
        const val MAX_PROGRAM_ROWS_PER_BATCH = 192
        const val MAX_UPCOMING_PROGRAMS = 12

        // Program search needs at least this many characters: one letter
        // matches most of the guide and would block the query thread for no
        // useful result.
        const val MIN_PROGRAM_SEARCH_LENGTH = 2

        val BRACKETED_TEXT = Regex("""\[[^]]*]""")
        val PARENTHESIZED_TEXT = Regex("""\([^)]*\)""")
        val NON_LOOKUP_CHARACTERS = Regex("""[^a-z0-9.]+""")

        val importRequestMutex = Mutex()
        val activeGuideImports =
            ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()
    }
}
