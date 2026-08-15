package com.kennyb1201.kbstream.data.iptv

import java.util.UUID

class M3uParser {

    fun parse(
        content: String,
        sourceUrl: String? = null,
        playlistName: String? = null
    ): IptvPlaylist {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }

        var headerAttrs: Map<String, String> = emptyMap()
        val channels = mutableListOf<IptvChannel>()

        var pendingAttrs: Map<String, String>? = null
        var pendingName: String? = null

        lines.forEachIndexed { index, line ->
            when {
                index == 0 && line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    headerAttrs = parseAttributes(line.removePrefix("#EXTM3U").trim())
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val extinfBody = line.substringAfter(":", "")
                    val commaIndex = extinfBody.indexOf(',')
                    val attrsPart = if (commaIndex >= 0) extinfBody.substring(0, commaIndex) else extinfBody
                    val namePart = if (commaIndex >= 0) extinfBody.substring(commaIndex + 1).trim() else ""

                    pendingAttrs = parseAttributes(attrsPart)
                    pendingName = namePart
                }

                !line.startsWith("#") -> {
                    val attrs = pendingAttrs.orEmpty()
                    val rawName = pendingName?.takeIf { it.isNotBlank() }
                        ?: attrs["tvg-name"]
                        ?: attrs["channel-name"]
                        ?: "Unknown Channel"

                    val tvgName = attrs["tvg-name"]
                    val displayName = rawName
                    val tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
                    val providerChannelId = attrs["channel-id"]?.takeIf { it.isNotBlank() }
                    val groupTitle = attrs["group-title"]?.takeIf { it.isNotBlank() }
                    val logoUrl = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                    val tvgChno = attrs["tvg-chno"]?.takeIf { it.isNotBlank() }
                        ?: attrs["ch-number"]?.takeIf { it.isNotBlank() }

                    val headers = buildHeaders(attrs)

                    channels += IptvChannel(
                        id = stableChannelId(
                            streamUrl = line,
                            tvgId = tvgId,
                            name = rawName,
                            providerChannelId = providerChannelId
                        ),
                        name = rawName,
                        displayName = displayName,
                        streamUrl = line,
                        groupTitle = groupTitle,
                        logoUrl = logoUrl,
                        tvgId = tvgId,
                        tvgName = tvgName,
                        tvgChno = tvgChno,
                        catchup = attrs["catchup"],
                        catchupDays = attrs["catchup-days"],
                        catchupSource = attrs["catchup-source"],
                        providerChannelId = providerChannelId,
                        headers = headers
                    )

                    pendingAttrs = null
                    pendingName = null
                }
            }
        }

        return IptvPlaylist(
            name = playlistName ?: headerAttrs["x-tvg-name"] ?: headerAttrs["name"],
            sourceUrl = sourceUrl,
            epgUrl = headerAttrs["url-tvg"] ?: headerAttrs["x-tvg-url"],
            channels = channels
        )
    }

    private fun parseAttributes(input: String): Map<String, String> {
        val regex = Regex("""([\w-]+)="(.*?)"""")
        return regex.findAll(input)
            .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
    }

    private fun buildHeaders(attrs: Map<String, String>): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        attrs["user-agent"]?.let { headers["User-Agent"] = it }
        attrs["referer"]?.let { headers["Referer"] = it }
        attrs["http-referrer"]?.let { headers["Referer"] = it }
        return headers
    }

    private fun stableChannelId(
        streamUrl: String,
        tvgId: String?,
        name: String,
        providerChannelId: String?
    ): String {
        return when {
            !tvgId.isNullOrBlank() -> "tvg:$tvgId"
            !providerChannelId.isNullOrBlank() -> "provider:$providerChannelId"
            else -> "url:${UUID.nameUUIDFromBytes("$name|$streamUrl".toByteArray())}"
        }
    }
}
