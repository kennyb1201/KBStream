package com.kennyb1201.kbstream.ui.player

import android.os.Handler
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.video.DecoderVideoRenderer
import androidx.media3.exoplayer.video.VideoDecoderOutputBufferRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Video renderer for Dolby Vision Profile 5 (ICtCp) content that feeds the
 * GLES color-conversion view with **raw decoded planes**.
 *
 * Why this exists: Profile 5 pixels are ICtCp values coded in a YUV-like
 * planar layout. The stock MediaCodec path renders into a Surface, and the
 * Surface/SurfaceTexture pipeline applies automatic dataspace conversion
 * before the GLES shader can sample anything — the shader never sees real
 * ICtCp samples (green screen / washed-out output). This renderer instead
 * decodes in MediaCodec *buffer mode* (no output Surface), where
 * [MediaCodec.getOutputImage] returns the planes in process memory exactly
 * as coded, and hands them to the attached
 * [VideoDecoderOutputBufferRenderer] (the [P5VideoGlesView]) for upload and
 * the ICtCp → display color conversion in the shader.
 *
 * Track-selection behavior: when [enabled] is set (P5 content + GLES path
 * active), this renderer reports FORMAT_HANDLED for HEVC. The factories in
 * [NativePlayerActivity] prepend it ahead of the stock MediaCodec video
 * renderer, so it wins selection for P5 sessions and stays invisible
 * otherwise.
 */
@UnstableApi
internal class P5PlaneVideoRenderer(
    allowedJoiningTimeMs: Long,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int
) : DecoderVideoRenderer(
    allowedJoiningTimeMs,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify
) {

    companion object {
        /**
         * Session gate set by [NativePlayerActivity.createPlayer] before the
         * player is built: the P5 GLES path is active for this session, so
         * HEVC tracks should be claimed by this renderer.
         */
        @Volatile
        var enabled: Boolean = false
    }

    private var decoder: P5PlaneDecoder? = null

    override fun getName(): String = "P5PlaneVideoRenderer"

    override fun supportsFormat(format: Format): Int {
        if (!enabled) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
        if (format.sampleMimeType != MimeTypes.VIDEO_H265) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
        if (format.drmInitData != null) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
        }
        return RendererCapabilities.create(
            C.FORMAT_HANDLED,
            RendererCapabilities.ADAPTIVE_SEAMLESS,
            RendererCapabilities.TUNNELING_NOT_SUPPORTED
        )
    }

    override fun createDecoder(
        format: Format,
        cryptoConfig: CryptoConfig?
    ): Decoder<DecoderInputBuffer, P5OutputBuffer, DecoderException> {
        P5PlaneDecoder(format).also { decoder = it }
        return checkNotNull(decoder)
    }

    override fun setDecoderOutputMode(outputMode: Int) {
        decoder?.setOutputMode(outputMode)
    }

    override fun renderOutputBufferToSurface(
        outputBuffer: VideoDecoderOutputBuffer,
        surface: Surface
    ) {
        // Never reached: this decoder always produces YUV-mode buffers, and
        // with the GLES view attached the base renderer routes them to
        // setOutputBuffer instead. Release defensively if it ever happens.
        outputBuffer.release()
    }
}
