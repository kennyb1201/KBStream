package com.kennyb1201.kbstream.data.iptv

/**
 * Registry of the channel lineup the player is currently working from, so
 * the CH+/CH− keys on a remote can zap between live channels without going
 * back through the guide. MainActivity refreshes it every time it launches
 * the player from the guide (single source of truth: the guide's own
 * filtered/ordered channel list).
 */
object LiveChannelZapRegistry {

    /**
     * Minimal channel descriptor — the full IptvChannel is not needed for
     * zapping, only what the player needs to start the next stream and show
     * the channel-info banner (EPG matching + identity).
     */
    data class ZapChannel(
        val channelId: String,
        val name: String,
        val streamUrl: String,
        val logoUrl: String?,
        val headers: Map<String, String> = emptyMap(),
        /** M3U channel number attribute, if the playlist provides one. */
        val chno: String? = null,
        /**
         * Guide channel id this M3U channel matched to — the exact key the
         * EPG program tables are queried with. Null = no guide match.
         */
        val epgChannelId: String? = null,
        /** EPG source URL the guide programs were imported from. */
        val epgUrl: String? = null
    )

    @Volatile
    private var channels: List<ZapChannel> = emptyList()

    /**
     * The guide group the lineup stands for ("All", "Favorites", "Recent", a
     * category…). Shown in the zap banner so it is obvious why UP/DOWN went
     * where it went.
     */
    @Volatile
    private var browsingGroupLabel: String? = null

    /**
     * @param channels the ordered channels to zap through — the guide's own
     *        visible list for the group being browsed, so UP/DOWN stays inside
     *        that group instead of jumping the whole playlist.
     * @param browsingGroup the group [channels] came from, for the banner.
     */
    fun set(channels: List<ZapChannel>, browsingGroup: String? = null) {
        this.channels = channels
        this.browsingGroupLabel = browsingGroup?.trim()?.takeIf { it.isNotBlank() }
    }

    /** True when there is more than one channel to move between. */
    fun zapEnabled(): Boolean = channels.size > 1

    /** Group the current lineup represents, or null when unknown. */
    fun browsingGroup(): String? = browsingGroupLabel

    fun indexOfChannel(channelId: String): Int =
        channels.indexOfFirst { it.channelId == channelId }

    /** Index of the channel a typed number tunes to, or -1 (see [ChannelNumberEntry]). */
    fun indexOfChannelNumber(entry: String): Int =
        ChannelNumberEntry.target(channels.map { it.chno }, entry)

    /** The channel's number as a remote user would type it, for the banner. */
    fun numberFor(index: Int): String? =
        channels.getOrNull(index)?.chno?.trim()?.takeIf { it.isNotBlank() }

    /**
     * First index whose stream URL matches — fallback identity when the
     * player was launched with a parent id the lineup no longer contains.
     */
    fun indexOfStreamUrl(streamUrl: String): Int =
        channels.indexOfFirst { it.streamUrl == streamUrl }

    fun channelAt(index: Int): ZapChannel? =
        channels.getOrNull(index)

    /** Channel [delta] positions from [index], wrapping around both ends. */
    fun offsetChannel(index: Int, delta: Int): ZapChannel? {
        val size = channels.size
        if (size == 0) return null
        val normalized = ((index + delta) % size + size) % size
        return channels.getOrNull(normalized)
    }

    fun size(): Int = channels.size

    fun clear() {
        channels = emptyList()
        browsingGroupLabel = null
    }
}
