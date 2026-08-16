package com.kennyb1201.kbstream.data.iptv

import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class IptvRepository(
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
    private val m3uParser: M3uParser = M3uParser(),
    private val xmltvParser: XmltvParser = XmltvParser()
) {

    suspend fun loadLineup(
        playlistUrl: String,
        epgUrlOverride: String? = null,
        playlistName: String? = null,
        nowUtcMillis: Long = System.currentTimeMillis()
    ): IptvLineup {
        val playlistContent = fetchText(playlistUrl).removePrefix("﻿")

        if (!playlistContent.contains("#EXTM3U", ignoreCase = true) &&
            !playlistContent.contains("#EXTINF", ignoreCase = true)
        ) {
            error(
                "Playlist response does not look like M3U. First 200 chars: ${playlistContent.take(200)}"
            )
        }

        val playlist = m3uParser.parse(
            content = playlistContent,
            sourceUrl = playlistUrl,
            playlistName = playlistName
        )

        val epgUrl = epgUrlOverride ?: playlist.epgUrl
        val guide = epgUrl?.let { url ->
            runCatching {
                fetchAndParseGuide(url)
            }.onSuccess { parsedGuide ->
                Log.d(TAG, "EPG loaded: url=$url channels=${parsedGuide.channels.size} programs=${parsedGuide.programs.size}")
            }.onFailure { throwable ->
                Log.e(TAG, "EPG parse/fetch failed for $url", throwable)
            }.getOrNull()
        }

        val mapped = mapChannels(playlist, guide, nowUtcMillis)

        if (guide == null) {
            Log.w(TAG, "Guide is null after loadLineup. playlistChannels=${playlist.channels.size} epgUrl=${epgUrl.orEmpty()}")
        } else {
            val idMatches = mapped.count { it.epgMatchType == EpgMatchType.ID_MATCH }
            val nameMatches = mapped.count { it.epgMatchType == EpgMatchType.NAME_MATCH }
            val noMatches = mapped.count { it.epgMatchType == EpgMatchType.NO_MATCH }
            val channelsWithPrograms = mapped.count { it.upcoming.isNotEmpty() || it.now != null || it.next != null }

            Log.d(
                TAG,
                "EPG mapping complete: playlistChannels=${playlist.channels.size} guideChannels=${guide.channels.size} programs=${guide.programs.size} idMatches=$idMatches nameMatches=$nameMatches noMatches=$noMatches channelsWithPrograms=$channelsWithPrograms"
            )
        }

        return IptvLineup(
            playlist = playlist,
            guide = guide,
            channels = mapped
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

    private suspend fun fetchAndParseGuide(url: String): XmltvGuide = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body ?: error("Empty response body from EPG server.")

            if (!response.isSuccessful) {
                val preview = response.peekBody(4096).string().take(300)
                error("Failed to fetch EPG (${response.code} ${response.message}). Preview: $preview")
            }

            val contentType = response.header("Content-Type").orEmpty()
            val contentEncoding = response.header("Content-Encoding").orEmpty()
            val isGzipByUrl = url.substringAfterLast('/', missingDelimiterValue = "")
                .contains(".gz", ignoreCase = true)
            val isGzipByHeader = contentType.contains("gzip", ignoreCase = true) ||
                contentEncoding.contains("gzip", ignoreCase = true)

            val buffered = BufferedInputStream(responseBody.byteStream(), 64 * 1024)
            buffered.mark(2)
            val magic1 = buffered.read()
            val magic2 = buffered.read()
            buffered.reset()
            val isGzipByMagic = magic1 == 0x1f && magic2 == 0x8b
            val isGzip = isGzipByUrl || isGzipByHeader || isGzipByMagic

            val stream: InputStream = if (isGzip) GZIPInputStream(buffered, 64 * 1024) else buffered

            Log.d(TAG, "EPG fetch streaming: url=$url contentType=$contentType contentEncoding=$contentEncoding gzip=$isGzip")

            stream.use {
                xmltvParser.parse(it, sourceUrl = url)
            }
        }
    }

    fun mapChannels(
        playlist: IptvPlaylist,
        guide: XmltvGuide?,
        nowUtcMillis: Long = System.currentTimeMillis()
    ): List<IptvChannelWithEpg> {
        if (guide == null) {
            return playlist.channels.map {
                IptvChannelWithEpg(
                    channel = it,
                    epgChannel = null,
                    epgMatchType = EpgMatchType.NO_MATCH,
                    now = null,
                    next = null,
                    upcoming = emptyList()
                )
            }
        }

        val guideChannelsById = buildGuideChannelsById(guide)
        val guideChannelsByName = buildGuideChannelsByName(guide)
        val programsByChannel = buildProgramsByChannel(guide)

        return playlist.channels.map { channel ->
            val match = resolveGuideChannel(channel, guideChannelsById, guideChannelsByName)
            val epgChannel = match.channel
            val programs = epgChannel?.let { programsForMatchedChannel(it, programsByChannel) }.orEmpty()
                .sortedBy { it.startUtcMillis }
            val now = programs.firstOrNull { nowUtcMillis in it.startUtcMillis until it.endUtcMillis }
            val next = programs.firstOrNull {
                it.startUtcMillis >= if (now != null) now.endUtcMillis else nowUtcMillis
            }
            val upcoming = programs.filter { it.endUtcMillis > nowUtcMillis }.take(12)

            IptvChannelWithEpg(
                channel = channel,
                epgChannel = epgChannel,
                epgMatchType = match.matchType,
                now = now,
                next = next,
                upcoming = upcoming
            )
        }
    }

    private fun buildProgramsByChannel(guide: XmltvGuide): Map<String, List<XmltvProgram>> {
        val pairs = mutableListOf<Pair<String, XmltvProgram>>()

        guide.programs.forEach { program ->
            val rawId = program.channelId.trim()
            if (rawId.isBlank()) return@forEach

            pairs += rawId to program
            pairs += normalizeId(rawId) to program
            pairs += normalizeName(rawId) to program
            simplifyName(rawId)?.let { pairs += it to program }
        }

        return pairs
            .filter { it.first.isNotBlank() }
            .groupBy({ it.first }, { it.second })
    }

    private fun programsForMatchedChannel(
        channel: XmltvChannel,
        programsByChannel: Map<String, List<XmltvProgram>>
    ): List<XmltvProgram> {
        val keys = buildList {
            val rawId = channel.id.trim()
            if (rawId.isNotBlank()) {
                add(rawId)
                add(normalizeId(rawId))
                add(normalizeName(rawId))
                simplifyName(rawId)?.let { add(it) }
            }

            channel.displayNames.forEach { displayName ->
                val trimmed = displayName.trim()
                if (trimmed.isNotBlank()) {
                    add(trimmed)
                    add(normalizeName(trimmed))
                    simplifyName(trimmed)?.let { add(it) }
                }
            }
        }.distinct()

        return keys
            .flatMap { programsByChannel[it].orEmpty() }
            .distinctBy { "${it.channelId}|${it.startUtcMillis}|${it.endUtcMillis}|${it.title}" }
    }

    private fun buildGuideChannelsById(guide: XmltvGuide): Map<String, XmltvChannel> {
        val pairs = mutableListOf<Pair<String, XmltvChannel>>()

        guide.channels.forEach { channel ->
            val rawId = channel.id.trim()
            if (rawId.isNotBlank()) {
                pairs += rawId to channel
                pairs += normalizeId(rawId) to channel
                pairs += normalizeName(rawId) to channel
                simplifyName(rawId)?.let { pairs += it to channel }
            }

            channel.displayNames.forEach { displayName ->
                val trimmed = displayName.trim()
                if (trimmed.isNotBlank()) {
                    pairs += trimmed to channel
                    pairs += normalizeName(trimmed) to channel
                    simplifyName(trimmed)?.let { pairs += it to channel }
                }
            }
        }

        return pairs
            .filter { it.first.isNotBlank() }
            .associate { it.first to it.second }
    }

    private fun buildGuideChannelsByName(guide: XmltvGuide): Map<String, XmltvChannel> {
        val pairs = mutableListOf<Pair<String, XmltvChannel>>()

        guide.channels.forEach { channel ->
            channel.displayNames.forEach { displayName ->
                val trimmed = displayName.trim()
                if (trimmed.isNotBlank()) {
                    pairs += normalizeName(trimmed) to channel
                    simplifyName(trimmed)?.let { pairs += it to channel }
                }
            }

            val trimmedId = channel.id.trim()
            if (trimmedId.isNotBlank()) {
                pairs += normalizeName(trimmedId) to channel
                simplifyName(trimmedId)?.let { pairs += it to channel }
            }
        }

        return pairs
            .filter { it.first.isNotBlank() }
            .associate { it.first to it.second }
    }

    private fun resolveGuideChannel(
        channel: IptvChannel,
        byId: Map<String, XmltvChannel>,
        byName: Map<String, XmltvChannel>
    ): GuideChannelMatch {
        val strongIdCandidates = listOfNotNull(channel.tvgId, channel.providerChannelId)
            .flatMap { buildCandidateKeys(it) }
            .distinct()

        strongIdCandidates.firstNotNullOfOrNull { byId[it] }?.let {
            return GuideChannelMatch(it, EpgMatchType.ID_MATCH)
        }

        val nameCandidates = listOfNotNull(channel.tvgName, channel.displayName, channel.name)
            .flatMap { buildCandidateKeys(it) }
            .distinct()

        nameCandidates.firstNotNullOfOrNull { byName[it] }?.let {
            return GuideChannelMatch(it, EpgMatchType.NAME_MATCH)
        }

        val weakFallbackCandidates = listOfNotNull(
            channel.tvgId,
            channel.providerChannelId,
            channel.tvgName,
            channel.displayName,
            channel.name
        ).flatMap { buildCandidateKeys(it) }.distinct()

        weakFallbackCandidates.firstNotNullOfOrNull { byId[it] ?: byName[it] }?.let {
            return GuideChannelMatch(it, EpgMatchType.NAME_MATCH)
        }

        return GuideChannelMatch(null, EpgMatchType.NO_MATCH)
    }

    private fun buildCandidateKeys(value: String): List<String> {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return emptyList()

        return buildList {
            add(trimmed)
            add(normalizeId(trimmed))
            add(normalizeName(trimmed))
            simplifyName(trimmed)?.let { add(it) }
        }.distinct().filter { it.isNotBlank() }
    }

    private fun normalizeId(value: String): String {
        return value.trim().lowercase(Locale.US).replace(Regex("""\s+"""), "")
    }

    private fun normalizeName(value: String): String {
        return value
            .lowercase(Locale.US)
            .replace(Regex("""\[[^\]]*]"""), " ")
            .replace(Regex("""\([^\)]*\)"""), " ")
            .replace(Regex("""\b(hd|uhd|fhd|sd|4k|1080p|720p|hevc|h265|h264|hdr|aac|fps|usa|us|uk|ca|au)\b"""), " ")
            .replace("+", " plus ")
            .replace(Regex("""[^a-z0-9]+"""), "")
            .trim()
    }

    private fun simplifyName(value: String): String? {
        val simplified = value
            .lowercase(Locale.US)
            .replace(Regex("""\[[^\]]*]"""), " ")
            .replace(Regex("""\([^\)]*\)"""), " ")
            .replace(Regex("""\b(hd|uhd|fhd|sd|4k|1080p|720p|hevc|h265|h264|hdr|aac|fps)\b"""), " ")
            .replace("+", " plus ")
            .replace(Regex("""[^a-z0-9]+"""), "")
            .trim()

        return simplified.ifBlank { null }
    }

    private data class GuideChannelMatch(
        val channel: XmltvChannel?,
        val matchType: EpgMatchType
    )

    private companion object {
        const val TAG = "IptvRepository"
    }
}
