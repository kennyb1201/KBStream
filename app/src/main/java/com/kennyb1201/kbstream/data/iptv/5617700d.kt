package com.kennyb1201.kbstream.data.iptv

enum class EpgMatchType {
    ID_MATCH,
    NAME_MATCH,
    NO_MATCH
}

data class IptvPlaylist(
    val name: String? = null,
    val sourceUrl: String? = null,
    val epgUrl: String? = null,
    val channels: List<IptvChannel> = emptyList()
)

data class IptvChannel(
    val id: String,
    val name: String,
    val displayName: String,
    val streamUrl: String,
    val groupTitle: String?,
    val logoUrl: String?,
    val tvgId: String?,
    val tvgName: String?,
    val tvgChno: String?,
    val catchup: String?,
    val catchupDays: String?,
    val catchupSource: String?,
    val providerChannelId: String?,
    val headers: Map<String, String> = emptyMap()
) {
    val favoriteKey: String
        get() = when {
            !tvgId.isNullOrBlank() -> "tvg:${tvgId.trim()}"
            streamUrl.isNotBlank() -> "url:${streamUrl.trim()}"
            else -> "name:${displayName.ifBlank { name }.trim().lowercase()}"
        }
}

data class XmltvGuide(
    val sourceUrl: String? = null,
    val channels: List<XmltvChannel> = emptyList(),
    val programs: List<XmltvProgram> = emptyList()
)

data class XmltvChannel(
    val id: String,
    val displayNames: List<String> = emptyList(),
    val iconUrl: String? = null
)

data class XmltvProgram(
    val channelId: String,
    val title: String,
    val description: String?,
    val category: String?,
    val startUtcMillis: Long,
    val endUtcMillis: Long
)

data class IptvChannelWithEpg(
    val channel: IptvChannel,
    val epgChannel: XmltvChannel?,
    val epgMatchType: EpgMatchType = EpgMatchType.NO_MATCH,
    val isFavorite: Boolean = false,
    val isRecent: Boolean = false,
    val now: XmltvProgram?,
    val next: XmltvProgram?,
    val upcoming: List<XmltvProgram> = emptyList()
)

data class IptvLineup(
    val playlist: IptvPlaylist,
    val guide: XmltvGuide?,
    val channels: List<IptvChannelWithEpg>
) {
    val favoriteChannels: List<IptvChannelWithEpg>
        get() = channels.filter { it.isFavorite }

    val recentChannels: List<IptvChannelWithEpg>
        get() = channels.filter { it.isRecent }

    val matchedChannels: List<IptvChannelWithEpg>
        get() = channels.filter { it.epgMatchType != EpgMatchType.NO_MATCH }

    val unmatchedChannels: List<IptvChannelWithEpg>
        get() = channels.filter { it.epgMatchType == EpgMatchType.NO_MATCH }
}
