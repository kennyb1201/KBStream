package com.kennyb1201.kbstream.data.iptv

import java.util.Locale
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
        // Tracks every id assigned so far in this parse, mapped to the stream
        // URL it was assigned for -> lets us tell "same channel repeated" (a
        // true duplicate, safe to drop) apart from "different channel that
        // collided on a reused tvg-id" (disambiguate, don't drop). Keyed by
        // stream URL so the disambiguation is stable regardless of list order.
        val assignedIds = HashMap<String, String>()

        lines.forEach { line ->
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    headerAttrs = parseAttributes(
                        line.substringAfter("#EXTM3U", missingDelimiterValue = "").trim()
                    )
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val extinfBody = line.substringAfter(":", "")
                    val commaIndex = findExtinfNameDelimiter(extinfBody)
                    val attrsPart = if (commaIndex >= 0) {
                        extinfBody.substring(0, commaIndex)
                    } else {
                        extinfBody
                    }
                    val namePart = if (commaIndex >= 0) {
                        extinfBody.substring(commaIndex + 1).trim()
                    } else {
                        ""
                    }

                    pendingAttrs = parseAttributes(attrsPart)
                    pendingName = namePart
                }

                !line.startsWith("#") -> {
                    val attrs = pendingAttrs.orEmpty()
                    val rawName = pendingName?.takeIf { it.isNotBlank() }
                        ?: attrs["tvg-name"]
                        ?: attrs["channel-name"]
                        ?: attrs["name"]
                        ?: "Unknown Channel"

                    if (!shouldIncludeEntry(rawName, line, attrs)) {
                        pendingAttrs = null
                        pendingName = null
                        return@forEach
                    }

                    val tvgName = firstNonBlank(
                        attrs["tvg-name"],
                        attrs["channel-name"],
                        pendingName
                    )?.let(::cleanupDisplayName)

                    val displayName = cleanupDisplayName(rawName)
                    val tvgId = firstNonBlank(
                        attrs["tvg-id"],
                        attrs["channel-id"],
                        attrs["tvg-channel-id"],
                        attrs["catchup-id"]
                    )?.let(::normalizeIdentifier)

                    val providerChannelId = firstNonBlank(
                        attrs["channel-id"],
                        attrs["channelid"],
                        attrs["id"],
                        attrs["cuid"]
                    )?.let(::normalizeIdentifier)

                    val groupTitle = firstNonBlank(
                        attrs["group-title"],
                        attrs["group_name"],
                        attrs["group"]
                    )

                    val logoUrl = firstNonBlank(
                        attrs["tvg-logo"],
                        attrs["logo"]
                    )

                    val tvgChno = firstNonBlank(
                        attrs["tvg-chno"],
                        attrs["ch-number"],
                        attrs["channel-number"]
                    )

                    val baseId = stableChannelId(
                        streamUrl = line,
                        tvgId = tvgId,
                        name = displayName,
                        providerChannelId = providerChannelId
                    )
                    val resolvedId = resolveUniqueId(assignedIds, baseId, streamUrl = line)
                    if (resolvedId == null) {
                        // Exact duplicate: same base id AND same stream URL as
                        // an entry we already emitted. Not a different channel,
                        // just the same line repeated in the source -> drop it
                        // instead of assigning it a second, position-based id.
                        pendingAttrs = null
                        pendingName = null
                        return@forEach
                    }

                    val channel = IptvChannel(
                        id = resolvedId,
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
                        headers = buildHeaders(attrs)
                    )

                    channels += channel
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

    private fun findExtinfNameDelimiter(value: String): Int {
        var quote: Char? = null

        value.forEachIndexed { index, char ->
            when {
                 char == '"' && quote == null -> quote = char
            char == '\'' && quote == null -> quote = char
            char == quote -> quote = null
            char == ',' && quote == null -> return index
            }
        }

        return -1
    }

    private fun parseAttributes(input: String): Map<String, String> {
        val quotedRegex = Regex("""([\w-]+)=(?:\"([^\"]*)\"|'([^']*)')""")
        val attrs = linkedMapOf<String, String>()

        quotedRegex.findAll(input).forEach { match ->
            val key = match.groupValues[1].lowercase(Locale.US)
            val value = match.groupValues[2].ifBlank { match.groupValues[3] }.trim()
            attrs[key] = value
        }

        val strippedInput = quotedRegex.replace(input, " ")
        val bareRegex = Regex("""([\w-]+)=([^\s,]+)""")
        bareRegex.findAll(strippedInput).forEach { match ->
            attrs.putIfAbsent(
                match.groupValues[1].lowercase(Locale.US),
                match.groupValues[2].trim()
            )
        }

        return attrs
    }

    private fun buildHeaders(attrs: Map<String, String>): Map<String, String> {
        val headers = linkedMapOf<String, String>()

        attrs["user-agent"]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { headers["User-Agent"] = it }

        attrs["referer"]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { headers["Referer"] = it }

        attrs["http-referrer"]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { headers["Referer"] = it }

        attrs["origin"]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { headers["Origin"] = it }

        return headers
    }

    private fun shouldIncludeEntry(
        name: String,
        streamUrl: String,
        attrs: Map<String, String>
    ): Boolean {
        val cleanedName = cleanupDisplayName(name)

        if (
            cleanedName.isBlank() ||
            cleanedName.equals("unknown channel", ignoreCase = true)
        ) {
            return false
        }

        val normalizedName = cleanedName.lowercase(Locale.US)
        val normalizedGroup = attrs["group-title"]
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()

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

        return !separatorLike &&
            !instructionalLike &&
            !bannerGroupLike &&
            !missingPlayableUrl
    }

    private fun cleanupDisplayName(value: String): String = value
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trim('-', '#', '*', '|', '_')
        .trim()

    private fun normalizeIdentifier(value: String): String = value.trim()

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?.ifBlank { null }

    private fun stableChannelId(
        streamUrl: String,
        tvgId: String?,
        name: String,
        providerChannelId: String?
    ): String = when {
        !tvgId.isNullOrBlank() -> "tvg:$tvgId"
        !providerChannelId.isNullOrBlank() -> "provider:$providerChannelId"
        else -> "url:${UUID.nameUUIDFromBytes("$name|$streamUrl".toByteArray())}"
    }

    /**
     * Guarantees every channel id emitted by this parse is unique, without
     * ever basing that uniqueness on list position/index (a position-based
     * suffix breaks Compose's key-based item tracking the moment the list
     * reorders, which is what caused channels to flicker out while scrolling).
     *
     * - New id -> record it against this stream URL, return as-is.
     * - Id collides but the earlier entry had a DIFFERENT stream URL -> this
     *   is a distinct channel that happens to share a reused tvg-id/provider-id
     *   upstream (common with sloppy M3U sources). Disambiguate by folding the
     *   stream URL itself into the id. That's stable forever for this URL,
     *   regardless of parse order or list position.
     * - Id collides AND the stream URL is identical to the earlier entry ->
     *   this is the same channel listed twice. Return null so the caller drops
     *   the duplicate entry entirely rather than manufacturing a second id
     *   for the same stream.
     */
    private fun resolveUniqueId(
        assignedIds: MutableMap<String, String>,
        baseId: String,
        streamUrl: String
    ): String? {
        val existingUrlForBaseId = assignedIds[baseId]
        if (existingUrlForBaseId == null) {
            assignedIds[baseId] = streamUrl
            return baseId
        }
        if (existingUrlForBaseId == streamUrl) return null

        val disambiguatedId = "$baseId#${UUID.nameUUIDFromBytes(streamUrl.toByteArray())}"
        // Vanishingly unlikely, but keep this correct even if the
        // disambiguated id itself somehow collides with a different URL.
        return resolveUniqueId(assignedIds, disambiguatedId, streamUrl)
    }
}
