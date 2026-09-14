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

    fun set(channels: List<ZapChannel>) {
        this.channels = channels
    }

    fun indexOfChannel(channelId: String): Int =
        channels.indexOfFirst { it.channelId == channelId }

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
    }
}
