package com.kennyb1201.kbstream.ui.player

/**
 * Shared state used to communicate "next episode" navigation from
 * NativePlayerActivity back to MainActivity without relying on
 * activity results (which can be unreliable when onStop blocks).
 */
object NextEpisodeResult {
    @Volatile var pendingNextEpisode: PendingNext? = null

    data class PendingNext(
        val season: Int,
        val episode: Int,
        val title: String,
        val streamId: String
    )

    fun consume(): PendingNext? = synchronized(this) {
        val p = pendingNextEpisode
        pendingNextEpisode = null
        p
    }
}
