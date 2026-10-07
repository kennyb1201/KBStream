package com.kennyb1201.kbstream.data.iptv

/**
 * Registry of the channel lineup the player is currently working from, so
 * the CH+/CH− keys on a remote can zap between live channels without going
 * back through the guide. MainActivity refreshes it every time it launches
 * the player from the guide (single source of truth: the guide's own
 * filtered/ordered channel list).
 *
 * It carries every browsable GROUP as well as the one being browsed: the
 * in-player guide switches groups with LEFT/RIGHT (see
 * [offsetGroup]), and the group it lands on is then what UP/DOWN zaps
 * through - the same "the group you are browsing is what changes channels"
 * rule the guide screen's own chips follow.
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
        /**
         * The guide source this channel's programs are stored under, when a
         * single one is known. Prefer [epgUrls] — a channel may be matched in
         * any configured source, and the player has to be able to ask about
         * all of them (see [epgUrls]).
         */
        val epgUrl: String? = null,
        /**
         * Every configured guide source URL, in the order the guide screen
         * resolves them (primary first, then extras).
         *
         * The player queries programs per source — the DAO resolves a source
         * by URL — so a channel whose guide lives in a SECONDARY source used
         * to read as "No guide data" in the player while the guide screen was
         * fully populated: the screen reads every source and merges, the
         * player asked only the primary. Empty means "no guide sources
         * published"; [epgUrl] is then used on its own.
         */
        val epgUrls: List<String> = emptyList(),
        /**
         * The channel's own guide identity, so a reader that holds no resolved
         * match yet (the in-player guide, opened before the guide screen's
         * matching finished) can resolve one itself against the imported
         * guide. The matching rules are the guide screen's; these are the same
         * candidates it feeds them - [providerChannelId] included, because a
         * playlist whose `tvg-id` is blank keeps its id in `channel-id`/`id`,
         * and the guide screen matches on it while a player that only had
         * `tvg-id` could not.
         */
        val tvgId: String? = null,
        val tvgName: String? = null,
        val providerChannelId: String? = null
    )

    /**
     * One browsable group of the guide ("All", "Favorites", "Recent", a
     * category…), in the guide screen's own order.
     */
    data class ZapGroup(
        val label: String,
        val channels: List<ZapChannel>
    )

    @Volatile
    private var channels: List<ZapChannel> = emptyList()

    @Volatile
    private var groups: List<ZapGroup> = emptyList()

    @Volatile
    private var activeGroupIndex: Int = 0

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
        setGroups(
            groups = listOf(ZapGroup(browsingGroup?.trim().orEmpty(), channels)),
            selected = browsingGroup
        )
    }

    /**
     * Publishes every browsable group, and which one to browse.
     *
     * Groups with no channels are dropped: the guide screen only offers a
     * group it has channels for, and an empty one would be a LEFT/RIGHT stop
     * that shows nothing. [selected] names the group to land on; an unknown or
     * blank name falls back to the first group.
     */
    fun setGroups(groups: List<ZapGroup>, selected: String? = null) {
        val usable = groups.filter { it.channels.isNotEmpty() }
        this.groups = usable
        if (usable.isEmpty()) {
            channels = emptyList()
            browsingGroupLabel = null
            activeGroupIndex = 0
            return
        }
        val wanted = selected?.trim()?.takeIf { it.isNotBlank() }
        activeGroupIndex =
            usable.indexOfFirst { it.label.trim().equals(wanted, ignoreCase = true) }
                .takeIf { it >= 0 }
                ?: 0
        applyActiveGroup()
    }

    private fun applyActiveGroup() {
        val group = groups.getOrNull(activeGroupIndex) ?: return
        channels = group.channels
        browsingGroupLabel = group.label.trim().takeIf { it.isNotBlank() }
    }

    /**
     * Moves the browsed group by [delta], CLAMPED at either end.
     *
     * Clamped rather than wrapped so the two surfaces agree: the guide
     * screen's group chips stop at the ends (see its moveSelectedGroup), and a
     * wrap here would silently jump from the last category back to "All",
     * which reads as a mis-press rather than a group change.
     *
     * Returns true only when the browsed group actually changed, so a press at
     * an end does not repaint the overlay or re-read the guide.
     */
    fun offsetGroup(delta: Int): Boolean {
        if (groups.size <= 1) return false
        val next = (activeGroupIndex + delta).coerceIn(0, groups.lastIndex)
        if (next == activeGroupIndex) return false
        activeGroupIndex = next
        applyActiveGroup()
        return true
    }

    /** Browsable group labels, in the order they are offered. */
    fun groupLabels(): List<String> = groups.map { it.label }

    /** What the banner prints for the browsed group, or null when unknown. */
    fun browsingGroup(): String? = browsingGroupLabel

    /** 1-based position of the browsed group and the total, or null. */
    fun groupPosition(): Pair<Int, Int>? =
        if (groups.isEmpty()) null else (activeGroupIndex + 1) to groups.size

    /** True when there is more than one channel to move between. */
    fun zapEnabled(): Boolean = channels.size > 1

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
        groups = emptyList()
        activeGroupIndex = 0
        browsingGroupLabel = null
    }
}
