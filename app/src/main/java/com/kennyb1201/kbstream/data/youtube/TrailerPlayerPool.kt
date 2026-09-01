package com.kennyb1201.kbstream.data.youtube

import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/** Reuses one lightly buffered player for short inline trailers. */
object TrailerPlayerPool {
    @Volatile
    private var player: ExoPlayer? = null

    @Synchronized
    fun acquire(context: Context): ExoPlayer {
        return player ?: ExoPlayer.Builder(context.applicationContext)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2_000, 12_000, 500, 1_000)
                    .build()
            )
            .build()
            .also { player = it }
    }

    @Synchronized
    fun releaseForReuse() {
        player?.stop()
        player?.clearMediaItems()
    }

    @Synchronized
    fun release() {
        player?.release()
        player = null
    }
}
