package com.kennyb1201.kbstream.data.iptv

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

class IptvRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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
        val playlistContent = fetchText(playlistUrl)
            .removePrefix("﻿")

        if (!playlistContent.contains("#EXTM3U", ignoreCase = true) &&
            !playlistContent.contains("#EXTINF", ignoreCase = true)
        ) {
            error(
                "Playlist response does not look like M3U. " +
                    "First 200 chars: ${playlistContent.take(200)}"
            )
        }

        val playlist = m3uParser.parse(
            content = playlistContent,
            sourceUrl = playlistUrl,
            playlistName = playlistName
        )

        val epgUrl = epgUrlOverride ?: playlist.epgUrl
        val guide = epgUrl?.let {
            runCatching {
                xmltvParser.parse(
                    fetchText(it).removePrefix("﻿"),
                    sourceUrl = it
                )
            }.getOrNull()
        }

        val mapped = mapChannels(playlist, guide, nowUtcMillis)

        return IptvLineup(
            playlist = playlist,
            guide = guide,
            channels = mapped
        )
    }

    suspend fun fetchText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                error(
                    "Failed to fetch playlist (${response.code} ${response.message}). " +
                        "Preview: ${bodyText.take(300)}"
                )
            }

            if (bodyText.isBlank()) {
                error("Empty response from server.")
            }

            return bodyText
        }
    }

    fun mapChannels(
        playlist: IptvPlaylist,
        guide: XmltvGuide?,
        nowUtcMillis: Long = System.currentTimeMillis()
    ): List<IptvChannelWithEpg> {
        if (guide == null) {
            return playlist.channels.map {
                IptvChannelWithEpg(channel = it, epgChannel = null, now = null, next = null)
            }
        }

        val guideChannelsById = guide.channels.associateBy { it.id.trim() }
        val guideChannelsByName = guide.channels.flatMap { channel ->
            channel.displayNames.map { normalizeName(it) to channel }
        }.toMap()

        val programmesByChannel = guide.programmes.groupBy { it.channelId }

        return playlist.channels.map { channel ->
            val epgChannel = resolveGuideChannel(channel, guideChannelsById, guideChannelsByName)
            val programmes = epgChannel?.let { programmesByChannel[it.id].orEmpty() }.orEmpty()
            val now = programmes.firstOrNull { nowUtcMillis in it.startUtcMillis until it.endUtcMillis }
            val next = programmes.firstOrNull { it.startUtcMillis >= (now?.endUtcMillis ?: nowUtcMillis) }
            val upcoming = programmes.filter { it.endUtcMillis > nowUtcMillis }.take(12)

            IptvChannelWithEpg(
                channel = channel,
                epgChannel = epgChannel,
                now = now,
                next = next,
                upcoming = upcoming
            )
        }
    }

    private fun resolveGuideChannel(
        channel: IptvChannel,
        byId: Map<String, XmltvChannel>,
        byName: Map<String, XmltvChannel>
    ): XmltvChannel? {
        val tvgId = channel.tvgId?.trim()
        if (!tvgId.isNullOrBlank()) {
            byId[tvgId]?.let { return it }
        }

        val candidates = listOfNotNull(
            channel.tvgName,
            channel.displayName,
            channel.name
        ).map(::normalizeName)

        return candidates.firstNotNullOfOrNull { byName[it] }
    }

    private fun normalizeName(value: String): String {
        return value
            .lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9]+"""), "")
            .trim()
    }
}
