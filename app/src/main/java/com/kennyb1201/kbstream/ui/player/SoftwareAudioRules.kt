package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks

/**
 * What the bundled SOFTWARE audio decoder may be handed, and what is left when
 * nothing on this device can take a track at all.
 *
 * Why this exists — the field crash (Sentry ANDROID-Z, kbstream@0.6.89, arm32,
 * Android 14):
 *
 * ```
 * SIGSEGV  swri_oldapi_conv_fltp_to_s16_nch_neon
 *          module = the app's own libffmpegJNI.so
 * ```
 *
 * That library is the video-enabled media3 FFmpeg decoder extension this repo
 * builds itself (`scripts/build_ffmpeg_video.sh`), which statically links
 * libavcodec + libswresample. The symbol is libswresample's ARM NEON converter
 * for **planar float to interleaved 16-bit**, and FFmpeg 6.0 only selects it
 * for a **channel count greater than two** (a stereo frame takes the two-channel
 * path, a mono one is not planar):
 *
 * ```c
 * // libswresample/arm/audio_convert_init.c
 * if (out_fmt == AV_SAMPLE_FMT_S16 && in_fmt == AV_SAMPLE_FMT_FLTP && channels > 2)
 *     ac->simd_f = conv_fltp_to_s16_nch_neon;
 * ```
 *
 * So the frame being converted was multichannel FLTP, converted to S16 because
 * the sink supports 16-bit PCM ([androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer.shouldOutputFloat]
 * asks for float only when S16 is unavailable). Media3 1.11.1 then hands
 * libswresample the input planes like this:
 *
 * ```c
 * // libraries/decoder_ffmpeg/src/main/jni/ffmpeg_jni.cc
 * swr_convert(resampleContext, &outputBuffer, bufferOutSize,
 *             (const uint8_t**)frame->data, frame->nb_samples);
 * ```
 *
 * and `frame->data` is an **array of AV_NUM_DATA_POINTERS (8) pointers**.
 * `frame->extended_data` is the array that has one pointer per channel;
 * FFmpeg only makes `extended_data` a separate allocation for more than eight
 * channels, and that is exactly the case this function must not be handed
 * `data` for (see `av_frame_get_buffer` in FFmpeg's `frame.c`). With a wider
 * frame the planar converter walks `in[8..n-1]`, which are the frame's own
 * `linesize[]` values read as pointers — a wild read that segfaults inside the
 * converter, which is the crash above, on the frame's first block.
 *
 * The AAR's own copy of that call is fixed at the source: `scripts/build_ffmpeg_video.sh`
 * patches `frame->data` to `frame->extended_data` in the media3 checkout it
 * builds from (anchored, hard-failing, idempotent), so every AAR built from
 * now on hands libswresample the full per-channel plane array.
 *
 * This guard is not redundant with that. It is what protects the AARs already
 * in the field — the ones this crash came from — and any build where the AAR
 * came from somewhere else (a previous CI artifact dropped into `libs/`, or a
 * patch that could not be applied), where the native defect is still present.
 *
 * What it can do about it, and what it deliberately does NOT do:
 *
 *  - It cannot patch that C in a shipped APK, and [FFMPEG_PLANE_LIMIT] is the
 *    only width the JNI is provably wrong for. So rather than disabling the
 *    software audio decoder (the DTS / TrueHD / E-AC-3 tracks this app bundles
 *    it for), the decoder is simply never *offered* a track wider than
 *    [FFMPEG_PLANE_LIMIT] — see [refusesSoftwareDecode], which the sink
 *    implements so media3's own capability question answers for us.
 *  - A track that no renderer claims produces no `PlaybackException`: media3
 *    leaves the audio renderer disabled and plays the file in SILENCE. That is
 *    worse than a crash only in that it is invisible, so
 *    [usesBackupEngine] answers the other half — the session belongs to the
 *    engine that can decode it.
 *
 * The limit is a ceiling on the *conversion*, not a claim about what a viewer
 * can hear: this app's own downmix folds to what the output carries
 * ([resolveDeviceMaxChannels] caps at 8), so a wider track has no correct path
 * through this chain in either engine — only a shorter one that does not
 * segfault.
 *
 * Pure, so it is covered directly by [SoftwareAudioRulesTest] rather than
 * through a player.
 */
internal object SoftwareAudioRules {

    /**
     * `AV_NUM_DATA_POINTERS` — the number of plane pointers an `AVFrame`'s
     * `data` array holds, and therefore the widest planar frame media3 1.11.1's
     * `swr_convert` call can be given safely.
     */
    const val FFMPEG_PLANE_LIMIT = 8

    /**
     * The reason the bundled software audio decoder must not be handed this
     * track, or null when it may.
     *
     * [format] is the PCM format media3 asks the sink about — both for the
     * software renderer's `supportsFormat` and for its "output float?" choice
     * (`Util.getPcmFormat`, i.e. `audio/raw`) — so the answer here is what
     * decides whether the FFmpeg decoder ever gets the track.
     *
     * Only `audio/raw` is judged: a compressed format is the passthrough
     * question, and passthrough never reaches libswresample at all.
     */
    fun refusesSoftwareDecode(format: Format): String? {
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW) return null
        if (format.channelCount <= FFMPEG_PLANE_LIMIT) return null
        return "audio ${format.channelCount}ch > $FFMPEG_PLANE_LIMIT planar planes " +
            "(libswresample would read past AVFrame.data)"
    }

    /**
     * Whether the bundled FFmpeg audio renderer must decline this compressed
     * format even in "prefer app" mode: the device has a platform decoder for
     * it, so MediaCodec takes the track and FFmpeg stays reserved for what the
     * box cannot decode itself (DTS / DTS-HD / TrueHD). [deviceDecoderPresent]
     * is the MediaCodecSelector answer, kept out of the pure function so the
     * rule is unit-testable.
     *
     * Why it exists (field, Onn 4K Plus, 2026-10-18): "Prefer app decoders" runs
     * the extension renderer in `EXTENSION_RENDERER_MODE_PREFER`, which puts it
     * AHEAD of MediaCodec, and a renderer that supports a format is simply
     * given it - so the bundled FFmpeg claimed AAC 2.0 stereo too and played it
     * as "robotic computer noises", on a box whose MediaCodec decodes AAC
     * perfectly. The setting exists for the codecs the sticks cannot decode,
     * not for the ones they can.
     *
     * A blank or null mime is never a decline: with no type stated there is
     * nothing for a platform decoder to match, and answering "decline" would
     * take the track away from FFmpeg with nothing to hand it to.
     */
    fun ffmpegDeclinesForDeviceDecoder(
        sampleMimeType: String?,
        deviceDecoderPresent: Boolean
    ): Boolean = !sampleMimeType.isNullOrBlank() && deviceDecoderPresent

    /**
     * The reason this session belongs to the backup engine, or null when the
     * player is fine as it is.
     *
     * True only for the shape [refusesSoftwareDecode] creates: the file has
     * audio, nothing on the device supports any of it, and the picture is
     * supported — so the session is playing in silence rather than failing, and
     * moving it to the MPV engine is the only thing that can put sound to the
     * picture. Live TV and DRM are refused by [NativePlayerActivity.handOffToMpv]
     * itself, so they are not repeated here.
     *
     * The video half matters: with no supported video group either, the session
     * is broken in the way the ordinary error ladder already answers, and a
     * handoff from here would take it away from that ladder.
     */
    fun usesBackupEngine(tracks: Tracks): String? {
        var audioGroups = 0
        var audioSupported = false
        var videoSupported = false
        for (group in tracks.groups) {
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    audioGroups++
                    if (group.isSupported) audioSupported = true
                }
                C.TRACK_TYPE_VIDEO -> if (group.isSupported) videoSupported = true
            }
        }
        // "None supported", not "some unsupported": one playable group among
        // several (a file carrying both a 16-channel TrueHD and a 5.1 AC-3,
        // say) is a session WITH sound, which media3 has already switched to by
        // the time this runs. Rescuing it would take a working session away
        // from the engine it is playing in.
        if (audioGroups == 0 || audioSupported) return null
        if (!videoSupported) return null
        return "$audioGroups audio track group(s), none supported"
    }
}
