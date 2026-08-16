package com.kennyb1201.kbstream.data.iptv

import java.util.UUID

class M3uParser {

    fun parse(
        content: String,
        sourceUrl: String? = null,
        playlistName: String? = null
    ): IptvPlaylist {
        val lines = content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

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

                    if (!shouldIncludeEntry(name = rawName, streamUrl = line, attrs = attrs)) {
                        pendingAttrs = null
                        pendingName = null
                        return@forEachIndexed
                    }

                    val tvgName = attrs["tvg-name"]?.trim()?.ifBlank { null }
                    val displayName = cleanupDisplayName(rawName)
                    val tvgId = attrs["tvg-id"]?.trim()?.ifBlank { null }
                    val providerChannelId = attrs["channel-id"]?.trim()?.ifBlank { null }
                    val groupTitle = attrs["group-title"]?.trim()?.ifBlank { null }
                    val logoUrl = attrs["tvg-logo"]?.trim()?.ifBlank { null }
                    val tvgChno = attrs["tvg-chno"]?.trim()?.ifBlank { null }
                        ?: attrs["ch-number"]?.trim()?.ifBlank { null }

                    val headers = buildHeaders(attrs)

                    channels += IptvChannel(
                        id = stableChannelId(
                            streamUrl = line,
                            tvgId = tvgId,
                            name = displayName,
                            providerChannelId = providerChannelId
                        ),
                        name = displayName,
                        displayName = displayName,
                        streamUrl = line,
                        groupTitle = groupTitle,
                        logoUrl = logoUrl,
                        tvgId = tvgId,
                        tvgName = tvgName,
                        tvgChno = tvgChno,
                        catchup = attrs["catchup"]?.trim()?.ifBlank { null },
                        catchupDays = attrs["catchup-days"]?.trim()?.ifBlank { null },
                        catchupSource = attrs["catchup-source"]?.trim()?.ifBlank { null },
                        providerChannelId = providerChannelId,
                        headers = headers
                    )

                    pendingAttrs = null
                    pendingName = null
                }
            }
        }

        return IptvPlaylist(
            name = playlistName?.trim()?.ifBlank { null }
                ?: headerAttrs["x-tvg-name"]
                ?: headerAttrs["name"],
            sourceUrl = sourceUrl,
            epgUrl = headerAttrs["url-tvg"] ?: headerAttrs["x-tvg-url"],
            channels = channels
        )
    }

    private fun parseAttributes(input: String): Map<String, String> {
        val quotedRegex = Regex("""([\w-]+)="([^"]*)"""")
        val attrs = linkedMapOf<String, String>()

        quotedRegex.findAll(input).forEach {
            attrs[it.groupValues[1].lowercase()] = it.groupValues[2].trim()
        }

        val strippedInput = quotedRegex.replace(input, " ")
        val bareRegex = Regex("""([\w-]+)=([^\s",]+)""")
        bareRegex.findAll(strippedInput).forEach {
            attrs.putIfAbsent(it.groupValues[1].lowercase(), it.groupValues[2].trim())
        }

        return attrs
    }

    private fun buildHeaders(attrs: Map<String, String>): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        attrs["user-agent"]?.trim()?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        attrs["referer"]?.trim()?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        attrs["http-referrer"]?.trim()?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        return headers
    }

    private fun shouldIncludeEntry(
        name: String,
        streamUrl: String,
        attrs: Map<String, String>
    ): Boolean {
        val cleanedName = cleanupDisplayName(name)
        if (cleanedName.isBlank() || cleanedName.equals("unknown channel", ignoreCase = true)) return false

        val normalizedName = cleanedName.lowercase()
        val normalizedGroup = attrs["group-title"]?.trim()?.lowercase().orEmpty()

        val separatorLike = cleanedName.matches(Regex("""^[#*\-_=\s|]+$""")) ||
            cleanedName.startsWith("###") ||
            cleanedName.startsWith("---") ||
            cleanedName.startsWith("***")

        val instructionalLike = listOf(
            "play this to learn",
            "how to use",
            "how to install",
            "contact us",
            "trial",
            "subscription",
            "renew",
            "support",
            "telegram",
            "whatsapp"
        ).any { normalizedName.contains(it) }

        val bannerGroupLike = listOf(
            "information",
            "instructions",
            "support",
            "help"
        ).any { normalizedGroup.contains(it) }

        val missingPlayableUrl = !streamUrl.contains("://") &&
            !streamUrl.startsWith("rtmp", ignoreCase = true) &&
            !streamUrl.startsWith("udp://", ignoreCase = true) &&
            !streamUrl.startsWith("rtsp://", ignoreCase = true)

        return !separatorLike && !instructionalLike && !bannerGroupLike && !missingPlayableUrl
    }

    private fun cleanupDisplayName(value: String): String {
        return value
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('-', '#', '*', '|', '_')
            .trim()
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
