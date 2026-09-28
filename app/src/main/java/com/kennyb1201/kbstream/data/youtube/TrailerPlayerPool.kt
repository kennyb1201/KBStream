package com.kennyb1201.kbstream.data.youtube

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.kennyb1201.kbstream.data.iptv.EpgWriteGate
import com.kennyb1201.kbstream.data.memory.MemoryPressure

/**
 * Reuses one lightly buffered player for short inline hero trailers.
 *
 * Building an ExoPlayer is expensive (renderer initialization dominates
 * trailer startup), so the hero keeps a single instance for the whole
 * screen: every new trailer swaps the MediaSource on the same player instead
 * of building a new one. The pool deliberately does NOT own a media source
 * factory — the hero builds per-source factories because the signed URL's
 * User-Agent hint differs per resolved source, and callers route every
 * media item through that factory.
 */
object TrailerPlayerPool : MemoryPressure.CacheOwner {
    @Volatile
    private var player: ExoPlayer? = null

    /**
     * Playback state of the pooled instance, mirrored so [cacheStats] can answer
     * "is a hero decoder open?" on the diagnostics thread without touching the
     * player (a Player method called off its own thread throws, which would drop
     * the whole report line).
     */
    @Volatile
    private var pooledState = Player.STATE_IDLE

    /** True while the pooled instance is playing rather than merely open. */
    @Volatile
    private var pooledPlaying = false

    /** Registered with [MemoryPressure] once, on the build that creates it. */
    private var observed = false

    /**
     * Acquires the pooled player, building it on first use. The builder is
     * provided by the caller so the hero can install its lightly-buffered
     * LoadControl exactly as before.
     */
    @Synchronized
    fun acquire(builder: () -> ExoPlayer): ExoPlayer {
        player?.let { return it }
        return builder()
            .also { built ->
                // The pool is the single point every inline trailer goes
                // through, so this is where the guide-write gate learns that
                // the hero is playing: a per-composable listener would be
                // clobbered by the outgoing instance of a hero crossfade
                // reporting "stopped" while the incoming one plays on the
                // same shared player.
                built.addListener(
                    object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            pooledPlaying = isPlaying
                            EpgWriteGate.setInlinePlaybackActive(isPlaying)
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            pooledState = playbackState
                        }
                    }
                )
                player = built
                if (!observed) {
                    observed = true
                    MemoryPressure.register(this)
                }
            }
    }

    /**
     * Stops playback and drops queued media without destroying the player —
     * used between trailers and when the hero returns to the backdrop, so
     * the next acquire gets a quiet, idle player with renderer state warm.
     */
    @Synchronized
    fun releaseForReuse() {
        player?.let { p ->
            p.stop()
            p.clearMediaItems()
        }
        // stop() delivers onIsPlayingChanged, but do not depend on it: the
        // gate must never be left holding guide writes because a callback was
        // missed on a released instance.
        EpgWriteGate.setInlinePlaybackActive(false)
    }

    /**
     * Pauses the currently playing trailer without dropping its media.
     * Used on ON_STOP (app backgrounded): audio must stop, but the player
     * stays alive so the resume epoch can re-prep and restart it cheaply.
     */
    @Synchronized
    fun pauseCurrent() {
        player?.pause()
    }

    /**
     * Reported, never released — the same trade the trailer source cache makes
     * (see [MemoryPressure]): dropping this instance would cost the renderer
     * initialization the pool exists to pay once, and the scarce thing it holds,
     * the video decoder, is already handed back by [releaseForReuse], which is
     * what the fullscreen players call on the way in. What is left worth
     * reporting is whether an instance is alive and whether it is holding a
     * decoder — the first question a "this TV has run out of video decoder
     * resources" report needs answered.
     */
    override fun cacheStats(): String {
        if (player == null) return "hero trailer player: none"
        val state = when (pooledState) {
            Player.STATE_IDLE -> "idle, no decoder"
            Player.STATE_BUFFERING -> "buffering, decoder open"
            Player.STATE_READY ->
                if (pooledPlaying) "playing, decoder open" else "paused, decoder open"
            Player.STATE_ENDED -> "ended, decoder open"
            else -> "state=$pooledState"
        }
        return "hero trailer player: 1 pooled instance ($state)"
    }

    @Synchronized
    fun release() {
        player?.release()
        player = null
        EpgWriteGate.setInlinePlaybackActive(false)
    }
}
