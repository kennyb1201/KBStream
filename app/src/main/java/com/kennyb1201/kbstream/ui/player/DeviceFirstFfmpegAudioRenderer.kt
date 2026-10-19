package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * The bundled FFmpeg audio renderer, with ONE question answered differently:
 * whether it supports a format the device can decode itself.
 *
 * Why (field, Onn 4K Plus, 2026-10-18): Settings → Audio → "Prefer app decoders
 * (FFmpeg)" sets `EXTENSION_RENDERER_MODE_PREFER`, which places the extension
 * renderer AHEAD of MediaCodec in the renderer list, and a renderer that
 * supports a format is simply given it. So FFmpeg claimed an AAC 2.0 stereo
 * track the box's MediaCodec decodes flawlessly and played it as "robotic
 * computer noises". The setting is there for DTS / DTS-HD / TrueHD — which the
 * sticks' MediaCodec cannot decode — not for codecs it can.
 *
 * The policy is the same one [SplitModeRenderersFactory] already applies to
 * video (software video is a fallback behind MediaCodec): device first for
 * everything a platform decoder claims, FFmpeg for the rest, in every extension
 * mode.
 *
 * Two implementation notes, both deliberate:
 *
 *  - **A delegating wrapper, not a subclass of `FfmpegAudioRenderer`.** The
 *    extension is a file dependency this project only has when
 *    `libs/media3-ffmpeg-decoder.aar` exists ([androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer]
 *    is not on the compile classpath otherwise — a build without the extension
 *    is a supported configuration here, `app/build.gradle.kts` warns and
 *    continues), so a subclass would not compile in those builds. Wrapping the
 *    instance `DefaultRenderersFactory` already built needs no extension types
 *    at compile time at all, and it keeps that instance's own audio sink (this
 *    factory's [LiveDownmixAudioSink] chain) instead of building a second one.
 *  - **The gate is on `getCapabilities()`.** media3 1.11's [Renderer] has no
 *    `supportsFormat`; the per-format question IS
 *    [RendererCapabilities.supportsFormat], which is what track selection asks
 *    (and the only per-format door into a renderer), so that is where the
 *    decline has to be answered.
 *
 * Passthrough is untouched: an encoded track never reaches this renderer's
 * decode path, and the decline only fires for a format whose mime a platform
 * decoder claims.
 */
internal class DeviceFirstFfmpegAudioRenderer(
    private val delegate: Renderer,
    mediaCodecSelector: MediaCodecSelector = MediaCodecSelector.DEFAULT
) : Renderer by delegate {

    private val capabilities: RendererCapabilities = DeviceFirstAudioCapabilities(
        delegate = delegate.capabilities,
        deviceDecoderPresent = { sampleMimeType ->
            platformDecoderPresent(mediaCodecSelector, sampleMimeType)
        }
    )

    override fun getCapabilities(): RendererCapabilities = capabilities
}

/**
 * [delegate]'s capabilities with [SoftwareAudioRules.ffmpegDeclinesForDeviceDecoder]
 * applied to the one per-format question, and every other member passed
 * straight through (track type, name, mixed-mime adaptation, the capability
 * listener a MediaCodec renderer uses for dynamic format support).
 *
 * Split out from the wrapper so the gate can be exercised on the JVM: both
 * inputs are interfaces, so a test supplies both answers with no device.
 */
internal class DeviceFirstAudioCapabilities(
    private val delegate: RendererCapabilities,
    private val deviceDecoderPresent: (String?) -> Boolean
) : RendererCapabilities by delegate {

    override fun supportsFormat(format: Format): Int {
        if (
            SoftwareAudioRules.ffmpegDeclinesForDeviceDecoder(
                sampleMimeType = format.sampleMimeType,
                deviceDecoderPresent = deviceDecoderPresent(format.sampleMimeType)
            )
        ) {
            return C.FORMAT_UNSUPPORTED_TYPE
        }
        return delegate.supportsFormat(format)
    }
}

/**
 * Whether this device's MediaCodec list has a decoder for [sampleMimeType],
 * asked exactly the way media3's own MediaCodec audio renderer asks it
 * ([MediaCodecSelector.getDecoderInfos] with no secure/tunneling requirement),
 * so the two renderers cannot disagree about who can take the track.
 *
 * Anything unanswerable — a blank mime, a `DecoderQueryException` from a broken
 * vendor codec list — answers false, which leaves the track with FFmpeg. That is
 * the pre-fix behavior, which is the right way to fail here: a wrong "decline"
 * would take audio away from the only renderer that could play it.
 */
private fun platformDecoderPresent(
    mediaCodecSelector: MediaCodecSelector,
    sampleMimeType: String?
): Boolean {
    if (sampleMimeType.isNullOrBlank()) return false
    return runCatching {
        mediaCodecSelector.getDecoderInfos(sampleMimeType, false, false).isNotEmpty()
    }.getOrDefault(false)
}
