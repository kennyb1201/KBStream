package com.kennyb1201.kbstream.data.youtube

import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

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
object TrailerPlayerPool {
    @Volatile
    private var player: ExoPlayer? = null

    /**
     * Acquires the pooled player, building it on first use. The builder is
     * provided by the caller so the hero can install its lightly-buffered
     * LoadControl exactly as before.
     */
    @Synchronized
    fun acquire(builder: () -> ExoPlayer): ExoPlayer {
        player?.let { return it }
        return builder()
            .also { player = it }
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

    @Synchronized
    fun release() {
        player?.release()
        player = null
    }
}
