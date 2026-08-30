package com.kennyb1201.kbstream.ui.player

import android.content.Context
import androidx.media3.common.audio.AudioSink
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * A [RenderersFactory] that creates audio renderers with a tunneled
 * [DefaultAudioSink].  Tunnel mode routes compressed video/audio
 * through a single hardware tunnel session, giving tighter A/V sync
 * on devices that support it (Fire TV, Shield, most Android TV SoCs).
 *
 * This is needed because media3 1.9.0 removed the old
 * [DefaultRenderersFactory.setTunnelingAudioSessionId] API.
 */
class TunneledRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventListener: AudioRendererEventListener,
        calledByMainThread: Boolean,
        allowedVideoPlayingTimeMs: Long
    ): Array<androidx.media3.exoplayer.Renderer> {
        // Build a tunneled audio sink instead of the default one
        val tunneledSink = DefaultAudioSink.Builder()
            .setTunneling(true)
            .build()

        return arrayOf(
            MediaCodecAudioRenderer(
                context,
                mediaCodecSelector,
                extensionRendererMode,
                enableDecoderFallback,
                tunneledSink,
                eventListener,
                allowedVideoPlayingTimeMs
            )
        )
    }
}
