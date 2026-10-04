package com.kennyb1201.kbstream.data.iptv

/**
 * The A/B pair a live channel's previous-channel key toggles between: the
 * channel playing now, and the one that was playing before it.
 *
 * The zap lineup ([LiveChannelZapRegistry]) is ordered, so "the channel before
 * this one" there means the previous *position* - not what a viewer means by
 * "last channel". They mean the channel they were watching a moment ago,
 * wherever it sits in the lineup, and they expect to bounce between the two:
 * pick A, pick B, then toggle A <-> B as long as they like.
 *
 * That pair has to outlive a player session, because the second channel is
 * normally opened from the guide - which closes the first player and starts a
 * new one. Process scope is what carries A across that gap: [onTuned] keeps
 * the channel now playing alongside the one it replaced, and [target] is the
 * channel to go back to.
 *
 * Deliberately NOT cleared when a session ends - the pair only means anything
 * across the gap. Tests clear it explicitly.
 */
object ChannelRecall {

    /** The channel playing now, as last reported by a player. */
    @Volatile
    private var lastWatchedId: String? = null

    /** The channel [target] names, if one is armed. */
    @Volatile
    private var targetId: String? = null

    /**
     * Reports that [channelId] is the channel now playing.
     *
     * The channel being replaced becomes the recall target, so the first tune
     * of a session arms the toggle from whatever was watched before it.
     * Tuning to the channel already playing changes nothing - the pair is left
     * alone rather than collapsing onto one channel - and neither does a blank
     * id, which is not a channel at all.
     */
    fun onTuned(channelId: String?) {
        val tuned = channelId?.trim()?.takeIf { it.isNotBlank() } ?: return
        val previous = lastWatchedId
        if (previous != null && previous != tuned) {
            targetId = previous
        }
        lastWatchedId = tuned
    }

    /** The channel a previous-channel key should go back to, or null. */
    fun target(): String? = targetId

    /**
     * Drops the armed target, for the case where the channel it names has left
     * the lineup: there is nothing to go back to, and leaving it armed would
     * make every press re-resolve a channel that is no longer there.
     */
    fun clearTarget() {
        targetId = null
    }

    /** Resets both channels. For tests. */
    fun clear() {
        lastWatchedId = null
        targetId = null
    }
}
