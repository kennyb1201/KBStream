package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Decouples the video and audio extension policies, which stock
 * DefaultRenderersFactory ties to a single extensionRendererMode. Audio gets
 * the FFmpeg audio extension at the position set by the audio decoder
 * priority (OFF / fallback / preferred) — that extension is the reason the
 * FFmpeg decoder package is bundled at all (DTS / DTS-HD / TrueHD tracks,
 * which the Fire TV stick's MediaCodec can't decode, play with no sound
 * without it).
 *
 * Video gets the FFmpeg software video renderer as a FALLBACK behind
 * MediaCodec (mode ON): hardware is tried first and wins for every codec the
 * box can decode; software only catches the codecs it cannot (AVI's MPEG-4
 * ASP, VC-1/WMV, 10-bit AVC, ...). The bundled FFmpeg is the video-enabled
 * build now: scripts/build_ffmpeg_video.sh enables h264 hevc mpeg2video
 * mpeg1video mpeg4 msmpeg4v3 wmv3 vc1 vp8 vp9 av1 theora h263 alongside the
 * audio set, so FfmpegLibrary.supportsFormat() answers SUPPORTED for those and
 * this renderer does claim a track for them.
 *
 * It is a fallback in the media3 sense only: the extension renderer is
 * consulted when NO MediaCodec decoder claims the format. A vendor decoder
 * that claims the format and then fails at RUNTIME (the TCL/Realtek DV case,
 * OMX_ErrorInsufficientResources on the first frame) goes to media3's own
 * MediaCodec-to-MediaCodec fallback, not here - see [DvEscalation] for how
 * that case is handled.
 */
internal class SplitModeRenderersFactory(
    context: Context,
    audioExtMode: Int,
    /**
     * Builds the libass text renderer for this player, or null when this build
     * has no libassjni.so. Supplied by the activity so the renderer feeds the
     * same shared libass instance the overlay tick draws from.
     */
    private val buildLibassTextRenderer: (() -> LibassSubtitleRenderer?)? = null
) : DefaultRenderersFactory(context) {
    init {
        setExtensionRendererMode(audioExtMode)
        setEnableDecoderFallback(true)
    }

    /**
     * This player's OWN audio processors (see [PlayerAudioProcessors]).
     * Deliberately not a process-wide singleton: Home's hero trailer builds one
     * of these factories too, and a hand-off leaves both players alive for a
     * moment, so a shared pair would answer for the new stream's first buffers
     * with the old stream's state - a stale offset still to shift, and the
     * downmix's channel counts from the trailer's own configure.
     */
    internal val audioProcessors = PlayerAudioProcessors()

    /**
     * Prepends the libass text renderer so an embedded ASS/SSA track is claimed
     * by it rather than by media3's default text renderer (which flattens the
     * script into plain cues). With no libass in this build the lambda is null
     * and the stock text renderers are the whole list, exactly as before.
     */
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        buildLibassTextRenderer?.invoke()?.let { renderer ->
            out.add(0, renderer)
            Log.i("PLAYER_ASS", "libass text renderer prepended (embedded ASS/SSA)")
        }
    }

    private companion object {
        /**
         * media3's own FFmpeg audio renderer class, as a NAME: see
         * [buildAudioRenderers] for why this build cannot name it as a type.
         */
        const val FFMPEG_AUDIO_RENDERER_CLASS =
            "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer"
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        // P5 (ICtCp) sessions: prepend the raw-plane renderer AHEAD of the
        // stock MediaCodec video renderer. It reports FORMAT_HANDLED for HEVC
        // only while enabled, so non-P5 sessions are unaffected. Winning the
        // tie-break requires being earlier in the renderer list.
        if (P5PlaneVideoRenderer.enabled) {
            out.add(
                P5PlaneVideoRenderer(
                    allowedVideoJoiningTimeMs,
                    eventHandler,
                    eventListener,
                    DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                )
            )
            Log.i("PLAYER_DV", "P5 plane renderer prepended (raw-plane ICtCp path)")
        }
        // Video: hardware first, FFmpeg software video BEHIND it. Mode ON
        // (not OFF) so the FFmpeg extension is picked up as the fallback for
        // a codec MediaCodec has no decoder for. With no extension on the
        // classpath (libs/media3-ffmpeg-decoder.aar absent) the renderer is
        // never created at all and hardware behavior is unchanged.
        super.buildVideoRenderers(
            context,
            EXTENSION_RENDERER_MODE_ON,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out
        )
    }

    /**
     * Audio: device-first, on top of whatever mode the setting asked for.
     *
     * [buildAudioRenderers] runs the stock policy and the extension renderer it
     * built (the one `EXTENSION_RENDERER_MODE_PREFER` puts AHEAD of MediaCodec)
     * is then re-wrapped as a [DeviceFirstFfmpegAudioRenderer], which declines
     * every format the device has its own decoder for. That is what stops
     * "Prefer app decoders (FFmpeg)" from handing an AAC stereo track to
     * FFmpeg on a box whose MediaCodec decodes it fine — the artifact this
     * exists for — while DTS / DTS-HD / TrueHD, which no platform decoder
     * claims, still reach FFmpeg (and still reach it FIRST in prefer mode, the
     * setting's whole point).
     *
     * The extension renderer is matched by CLASS NAME rather than by type: a
     * build without `libs/media3-ffmpeg-decoder.aar` has no such class to
     * reference (see [DeviceFirstFfmpegAudioRenderer]), and the list simply has
     * no entry to replace. Nothing else in the list is touched, so audio with
     * the extension absent (or set to OFF) is exactly what it was.
     */
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
        val index = out.indexOfFirst { it.javaClass.name == FFMPEG_AUDIO_RENDERER_CLASS }
        if (index == -1) return
        // The instance the factory just built is not initialized yet (the player
        // has not seen the list), so swapping it out here is safe — and unlike a
        // drop, the wrapped instance keeps the sink chain built above.
        out[index] = DeviceFirstFfmpegAudioRenderer(
            delegate = out[index],
            mediaCodecSelector = mediaCodecSelector
        )
        Log.i(
            "PLAYER_AUDIO",
            "FFmpeg audio renderer wrapped device-first (mode $extensionRendererMode); " +
                "formats with a platform decoder go to MediaCodec"
        )
    }

    /**
     * Installs the A/V sync offset processor and the downmix / dialogue
     * enhancer. Media3 has no audio offset API, so the shift rides in the PCM
     * stream (see [AudioDelayProcessor]). Both are attached unconditionally — at
     * their defaults they are pure pass-throughs — so moving a slider or picking
     * a downmix layout takes effect without rebuilding the player, and so no
     * audio path can ever silently lose the correction.
     *
     * Order matters: the delay runs first (it only inserts or drops leading
     * silence), then [AudioDownmixProcessor] folds the channels, applies the
     * dialogue/volume gain and limits the result.
     *
     * The sink is wrapped in [LiveDownmixAudioSink] so a *layout* change (the one
     * knob the processors cannot apply from inside the sample stream, because the
     * channel count is baked into the AudioTrack at configure time) is heard
     * while the film keeps playing instead of on the next stream start.
     */
    // setEnableAudioTrackPlaybackParams is deprecated in media3 1.9 with no
    // replacement offered; revisit when media3 is upgraded.
    @Suppress("DEPRECATION")
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        // "Auto" folds a multichannel stream down to what this output can
        // actually carry, so the fold needs to know the answer. Resolved per
        // sink (one player build) and logged, because it is the one input to
        // the downmix that comes from the device rather than the user.
        PlayerAudioTuning.deviceMaxChannels = resolveDeviceMaxChannels(context)
        Log.i(
            "PLAYER_DOWNMIX",
            "output carries up to ${PlayerAudioTuning.deviceMaxChannels} channels; " +
                "Auto folds multichannel down to it"
        )

        return LiveDownmixAudioSink(
            DefaultAudioSink.Builder(context)
                .setAudioProcessors(
                    arrayOf(
                        audioProcessors.delay,
                        audioProcessors.downmix
                    )
                )
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build(),
            // The sink drives the layout switch through the SAME pair it wears,
            // which is this player's alone.
            audioProcessors
        )
    }
}

/**
 * The audio processors ONE player runs in its sink chain.
 *
 * Per factory, never shared. [AudioDelayProcessor] carries the bytes still to
 * shift and whether it is inside a live in-place reconfigure; the downmix
 * carries the channel counts its last configure committed to (and re-pins them
 * when a layout change is refused). Two players - the full one and Home's hero
 * trailer, which overlap for a moment during a hand-off - sharing one pair means
 * one stream's state is applied to the other's buffers.
 *
 * The settings themselves stay process-wide where they always were
 * ([PlayerAudioTuning], [AppPreferences]): only the state that APPLIES them is
 * per-player.
 */
internal class PlayerAudioProcessors {
    val delay = AudioDelayProcessor()
    val downmix = AudioDownmixProcessor()
}

/**
 * How many channels this device's audio output can carry — the number the
 * downmix's "Auto" setting folds down to (see
 * [AudioDownmix.desiredOutputChannels]): stereo folds a 5.1/7.1 film down, which
 * is the TV-speaker case the dialogue lift exists for, while a six-channel
 * output keeps 5.1 and only trims 7.1 to it.
 *
 * Read from the HDMI audio-plug broadcast, whose extra is the sink's own EDID
 * channel count — the number the platform itself consults before handing an app
 * surround audio. [androidx.media3.exoplayer.audio.AudioCapabilities] reports
 * the same thing but falls back to a placeholder of 10 when the device says
 * nothing, and 10 folds nothing down, so it cannot answer this question.
 *
 * Anything absent or implausible falls back to stereo on purpose: a viewer who
 * really has six channels is one press from the 5.1 pill, while the opposite
 * mistake (keeping 5.1 on a stereo output) silently leaves dialogue under the
 * score — exactly what this feature is here to fix.
 */
@Suppress("DEPRECATION") // Sticky-broadcast query; the flags overload takes no null receiver.
internal fun resolveDeviceMaxChannels(context: Context): Int {
    val fromSink = runCatching {
        context
            .registerReceiver(
                null,
                android.content.IntentFilter(
                    android.media.AudioManager.ACTION_HDMI_AUDIO_PLUG
                )
            )
            ?.getIntExtra(
                android.media.AudioManager.EXTRA_MAX_CHANNEL_COUNT,
                0
            )
            ?: 0
    }.getOrDefault(0)

    return if (fromSink in 3..8) fromSink else 2
}
