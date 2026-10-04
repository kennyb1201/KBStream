package com.kennyb1201.kbstream.data.debrid

import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.badges.StreamBadge

/**
 * Turns "TorBox holds this hash" into a badge on the source card.
 *
 * Attached after the badge pack is applied, not folded into it: a pack replaces
 * a stream's badge list wholesale (see `StreamBadgeEngine.apply`), so a cached
 * chip written before that step would be thrown away. Here it is appended, and
 * only when the same chip is not already present, so a pack that also happens to
 * mark a copy cached does not double up.
 *
 * Pure — it is handed the set [TorBoxClient] already resolved — so the matching
 * is unit tested without a network.
 */
internal object TorBoxCachedBadges {

    /** The chip's label, in the picker and anywhere else badges render. */
    const val BADGE_NAME = "Cached"

    /**
     * A filled green chip. Colours are the same "AARRGGBB"/"RRGGBB" strings a
     * badge pack uses, so the picker's chip renderer needs no special case.
     */
    private val CACHED_BADGE = StreamBadge(
        name = BADGE_NAME,
        tagStyle = "filled",
        tagColor = "#1E7F4F",
        textColor = "#FFFFFFFF",
        borderColor = "#1E7F4F"
    )

    /**
     * Returns [streams] with a "Cached" badge appended to every source whose
     * infohash is in [cachedHashes]. Sources without an infohash (a plain direct
     * link) are left alone: there is nothing to have checked.
     */
    fun mark(streams: List<Stream>, cachedHashes: Set<String>): List<Stream> {
        if (cachedHashes.isEmpty() || streams.isEmpty()) return streams
        return streams.map { stream ->
            val hash = stream.infoHash?.trim()?.lowercase()
            if (hash == null || hash !in cachedHashes || hasCachedBadge(stream)) {
                stream
            } else {
                stream.copy(badges = stream.badges + CACHED_BADGE)
            }
        }
    }

    private fun hasCachedBadge(stream: Stream): Boolean =
        stream.badges.any { it.name.equals(BADGE_NAME, ignoreCase = true) }
}
