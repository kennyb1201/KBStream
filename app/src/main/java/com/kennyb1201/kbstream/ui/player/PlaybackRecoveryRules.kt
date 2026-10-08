package com.kennyb1201.kbstream.ui.player

import android.media.MediaCodec
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.kennyb1201.kbstream.data.reporting.PlaybackEngineTrace

/**
 * The platform's own "no decoder resources available" code
 * (OMX_ErrorInsufficientResources, 0x80001000). Realtek/TCL boxes surface it
 * as a MediaCodec.CodecException when the video decoder cannot be given its
 * buffers. It is not a bad-bitstream error: once one video decoder on the
 * process has failed this way, the box returns it for EVERY later decoder —
 * Dolby Vision and plain HEVC alike — until the process restarts.
 */
private const val OMX_ERROR_INSUFFICIENT_RESOURCES = 0x80001000.toInt()

/**
 * The verdicts that decide what the player does after a failure: retry the
 * same ladder, strip Dolby Vision, or hand the session to the MPV backup engine.
 *
 * These were private methods on [NativePlayerActivity], which meant the ladder
 * that every failed stream walks could not be exercised without a running
 * Activity and a device. They are pure — they read only the error handed to
 * them — so they live here as plain functions with direct unit coverage
 * ([PlaybackRecoveryRulesTest]); the Activity keeps the side effects (logging,
 * the diagnostics line, the actual retry) at the call sites.
 *
 * The Dolby Vision refusal discriminator ([dvPassthroughDecoderRefused]) is a
 * sibling top-level function in this package and follows the same pattern.
 */
internal object PlaybackRecoveryRules {

    /**
     * True when the failure is the extractor refusing the CONTAINER itself,
     * rather than any decoder or network problem.
     *
     * Media3's progressive support is a fixed list (MP4/FMP4, Matroska/WebM,
     * MP3, Ogg, WAV, MPEG-TS, MPEG-PS, FLV, ADTS, FLAC, AMR, AVI). WMV/ASF
     * is the one that is NOT on it, and is why this check exists: it fails
     * here before a single track is created and no decoder can help. AVI IS
     * on that list — media3 ships an AviExtractor — so an AVI that reaches
     * this branch is failing on its codec, not its container.
     * Manifest (HLS/DASH) parse failures are deliberately NOT part of
     * this: a playlist the parser refuses already falls through to the backup
     * engine below, and spending the MIME-hint-dropped probe on a playlist URL
     * would only hand playlist text to the progressive extractors.
     */
    fun isContainerParseFailure(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED

    /**
     * True when the decoder path failed for any reason.
     *
     * This is the broad question — a bad bitstream, a decoder still releasing
     * its OMX component, and a codec the box has no decoder for all answer yes.
     * [isMissingDecoderFailure] is the narrower question the retry ladder
     * silently assumes a yes to.
     */
    fun isDecoderError(errorCode: Int): Boolean =
        errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
            // A box whose DV decoder refuses the profile can report this one.
            // Missing it here sends a declared-DV stream down the plain retry
            // ladder, which rebuilds the identical decoder — the loop this set
            // exists to prevent. Widening is safe: the DV-strip branch still
            // requires a DV codec AND a DV mode other than None.
            errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED

    /**
     * True when this box has NO decoder for the codec that just failed.
     *
     * [isDecoderError] above answers "the decoder path failed", which includes
     * failures a different attempt could fix: a bad bitstream, a decoder still
     * releasing its OMX component. This answers the narrower question the retry
     * ladder silently assumes a yes to: is there any decoder on this device for
     * this codec at all? When there is not, every rebuild asks for the same
     * component and fails the same way, so the only useful move is the other
     * engine.
     *
     * The codec comes from the failure itself (the DecoderInitializationException's
     * mimeType) rather than from the add-on's stream metadata: the metadata
     * names a codec family, while this is the mime Media3 actually asked the
     * platform for. MediaCodecUtil then answers with the device's own decoder
     * list - the same list Media3's selector uses - and a query failure counts
     * as "a decoder exists", because the conservative side is to keep the
     * pre-existing ladder.
     */
    fun isMissingDecoderFailure(error: Throwable?): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is MediaCodecRenderer.DecoderInitializationException) {
                val mime = cause.mimeType
                val decoderExists = if (mime.isNullOrBlank()) {
                    cause.codecInfo != null
                } else {
                    runCatching {
                        MediaCodecUtil.getDecoderInfos(mime, false, false).isNotEmpty()
                    }.getOrDefault(true)
                }
                Log.w(
                    "PLAYER_RETRY",
                    "decoder init failed mime=${mime ?: "unknown"} " +
                        "candidate=${cause.codecInfo?.name ?: "none"} " +
                        "decoderExists=$decoderExists"
                )
                // Only the "no decoder at all" verdict is worth a report line:
                // a decoder that exists but failed init is retried by the
                // ladder, while a missing one is what sends the session
                // to the backup engine.
                if (!decoderExists) {
                    PlaybackEngineTrace.note(
                        PlaybackEngineTrace.describe(
                            cause = "no decoder for this format",
                            detail = "mime=${mime ?: "unknown"}"
                        )
                    )
                }
                return !decoderExists
            }
            cause = cause.cause
        }
        return false
    }

    /**
     * True when the decoder failed because the box could not give it
     * resources, rather than because the bitstream was bad. The distinction
     * matters: a bad bitstream is worth retrying differently, while
     * OMX_ErrorInsufficientResources means no decoder on this process will
     * succeed, so the only useful move is a different (smaller) source.
     */
    fun isDecoderResourceExhausted(error: Throwable?): Boolean {
        var cause = error
        while (cause != null) {
            if (cause is MediaCodec.CodecException &&
                cause.errorCode == OMX_ERROR_INSUFFICIENT_RESOURCES
            ) {
                Log.w(
                    "PLAYER_RETRY",
                    "Decoder resource exhaustion (0x80001000, recoverable=" +
                        "${cause.isRecoverable} transient=${cause.isTransient})"
                )
                // The report line for the unresolved "this TV is out of decoder
                // sources" symptom: without this the diagnostics dump said
                // nothing about decoders at all, so a capture could not tell
                // resource exhaustion from a format verdict (see
                // PlaybackEngineTrace).
                PlaybackEngineTrace.note(
                    PlaybackEngineTrace.describe(
                        cause = "decoder resources exhausted (0x80001000)",
                        detail = "recoverable=${cause.isRecoverable} " +
                            "transient=${cause.isTransient}"
                    )
                )
                return true
            }
            // Media3 wraps the platform error and minified builds can bury the
            // concrete type, so the platform's own code in the message chain
            // is the reliable fallback.
            if (cause.message?.contains("0x80001000", ignoreCase = true) == true) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    /**
     * True when the source never opened at all: the connection could not be
     * established, or the server answered the request for it with a status.
     *
     * Deliberately narrower than the general retryable set. A mid-playback I/O
     * break or a player timeout can clear on its own and keeps the full ladder;
     * these three cannot, so the ladder gives them fewer attempts (see
     * MAX_UNOPENABLE_RETRY_ATTEMPTS at the call site).
     */
    fun isUnopenableSource(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> true
        else -> false
    }

    /**
     * True when the failure is evidence about the LINK itself, as opposed to
     * what this box made of it.
     *
     * This is the gate the played-link cache reads before retiring a cached
     * debrid entry (PB-P2-2). A cached link is only worth forgetting when the
     * failure says the link is dead - the server refused it, or answered with
     * something that is not a stream. A decoder failure, a container the box
     * cannot open, an unsupported track: those say something about THIS DEVICE,
     * and forgetting on them discarded a link that was alive and made the next
     * replay re-resolve for nothing. The TTL remains the backstop for a dead
     * link that fails in some other way.
     *
     * [isUnopenableSource] plus the status/content-type cases: a debrid link
     * past its expiry answers 403/410 (a bad HTTP status) or redirects to an
     * HTML error page (an invalid content type) - the two ways a dead link is
     * actually reported.
     */
    fun isLinkFailure(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> true
        else -> isUnopenableSource(error)
    }

    /**
     * How many ranked sources the MPV engine walks through after a file fails
     * to OPEN before it parks on the error card.
     *
     * Bounded for the same reason the main player's unopenable ladder is
     * ([MAX_UNOPENABLE_RETRY_ATTEMPTS] at its call site): a title whose whole
     * list is dead must end somewhere the viewer can act on, instead of
     * cycling through every remaining source. Three covers the ordinary shape
     * of a bad list - a dead debrid link, its duplicate, and one host that has
     * gone away - while leaving the card as the outcome when nothing works.
     */
    const val MAX_MPV_OPEN_FAILURE_SOURCES = 3

    /**
     * True when the backup engine should open the NEXT ranked source instead
     * of showing the failure card.
     *
     * ExoPlayer already walks past a source that never opened (see the
     * unopenable ladder in [com.kennyb1201.kbstream.ui.player.NativePlayerActivity]),
     * and the MPV engine's failure card was terminal: the viewer got a dead end
     * with a list of 19 sources behind it. That is the worse half to lose,
     * because this engine is where a session lands precisely when its first
     * source was already trouble - a decoder handoff, or a container
     * ExoPlayer's extractor refused. The engine that exists to rescue a stream
     * must not be the one that gives up first.
     *
     * [sourcesTried] counts the sources this SESSION has already failed to
     * open, [hasAnotherSource] whether the ranked list has one left. Both are
     * required: with no source left there is nothing to advance to, and past
     * the bound the card is the honest outcome.
     */
    fun shouldAdvancePastOpenFailure(sourcesTried: Int, hasAnotherSource: Boolean): Boolean =
        hasAnotherSource && sourcesTried <= MAX_MPV_OPEN_FAILURE_SOURCES

    /**
     * A stable identity for a failure, so repeats of the SAME cause share one
     * rebuild budget (see RebuildBudget.kt).
     *
     * Grouped by what a recovery could do about it rather than by the raw code:
     * two different IO codes are different problems with different answers, but
     * every 0x80001000 is the one decoder-pool problem however it is spelled in
     * the codec's own message. Null when there is no failure to attribute
     * (a session that has not failed yet), which callers read as "no budget
     * applies".
     */
    fun failureCauseKey(error: PlaybackException?): String? = error?.let {
        when {
            isDecoderResourceExhausted(it) -> "decoder-resources"
            isMissingDecoderFailure(it) -> "decoder-missing"
            isDecoderError(it.errorCode) -> "decoder"
            isContainerParseFailure(it) -> "container"
            isUnopenableSource(it) -> "unopenable"
            else -> "error:" + it.errorCode
        }
    }
}
