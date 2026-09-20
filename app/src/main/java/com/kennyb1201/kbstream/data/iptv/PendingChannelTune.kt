package com.kennyb1201.kbstream.data.iptv

/**
 * One-shot: a notification tap asked to watch a specific channel, and the
 * guide is where that gets resolved.
 *
 * The reminder store keeps channel identity but no stream URL (the guide
 * resolves the live URL from the playlist), so the tap cannot build a player
 * intent by itself. MainActivity drops the channel id here and opens the
 * guide; [consume] hands it over exactly once, as soon as the guide has a
 * lineup to match it against — and clears it even then, so a channel that no
 * longer exists in the playlist leaves you on the guide rather than springing
 * a tune-in later.
 */
object PendingChannelTune {

    @Volatile
    private var channelId: String? = null

    fun set(channelId: String?) {
        this.channelId = channelId?.trim()?.takeIf { it.isNotBlank() }
    }

    fun peek(): String? = channelId

    fun consume(): String? = channelId.also { channelId = null }
}
