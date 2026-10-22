package com.kennyb1201.kbstream.ui.player

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/**
 * Media3's own audio sink ([androidx.media3.exoplayer.audio.DefaultAudioSink],
 * which [NativePlayerActivity] hands us) is what carries the PCM to the
 * speakers. Everything the app's audio panel does rides in the PCM stream and
 * is therefore live, with one exception: the *number of channels*. That is
 * fixed when the sink is configured — the sink builds its `AudioTrack` from the
 * channel count [AudioDownmixProcessor] declares as its output format, and a
 * processor cannot change it from inside the sample stream.
 *
 * So the layout switch is driven from here instead. `AudioSink` is an interface
 * with a decorator-shaped contract ("call configure() whenever the input format
 * changes — the sink is reinitialized on the next handleBuffer"), and Media3
 * itself reconfigures a running sink that way on any format change: it drains
 * the audio already queued, then tears down and rebuilds the output if the
 * channel layout no longer matches, and re-bases its media clock on the current
 * buffer so the position stays continuous. Nothing about that path requires the
 * *input* format to have actually changed, so when the setting changes we simply
 * re-run it with the format we were configured with. The result is what the user
 * asked for: pick Stereo/5.1/Auto while a film is playing and hear it refold,
 * with no player rebuild, no seek and no re-open of the stream.
 *
 * Everything else is delegated straight through (`AudioSink by inner`), so offload
 * mode, tunneling, the audio session and the buffer-size accounting keep
 * behaving exactly as they did — with one more exception, the capability
 * question at [supportsFormat], which is answered here so the bundled software
 * audio decoder is never handed a track wider than its conversion can address
 * (see [SoftwareAudioRules]).
 */
internal class LiveDownmixAudioSink(
    private val inner: AudioSink,
    /**
     * The pair this sink wears, owned by the factory that built it. Read here
     * rather than taken from a process-wide singleton: the processors' state
     * belongs to this player's stream (see [PlayerAudioProcessors]).
     */
    private val processors: PlayerAudioProcessors
) : AudioSink by inner {

    private companion object {
        const val TAG = "PLAYER_DOWNMIX_SINK"
    }

    /** Input format of the stream the sink is carrying, or null before configure(). */
    private var inputFormat: Format? = null

    /** Whether that input format is PCM — i.e. whether our processors are in the chain at all. */
    private var pcmInput = false

    /** The `configure()` arguments, kept so a reconfigure can repeat the exact call. */
    private var specifiedBufferSize = 0
    private var channelMap: IntArray? = null

    /**
     * The layout setting the sink last refused, so a device that cannot carry a
     * width is asked exactly once instead of on every buffer. Cleared for any
     * other setting, so moving the pill back and forth re-tries it.
     */
    private var rejectedTarget = Int.MIN_VALUE

    /**
     * The width this sink last answered "unsupported" to for the software
     * decoder (see [supportsFormat]), so one file cannot log it per query.
     */
    private var refusedWidth = Int.MIN_VALUE

    /**
     * The software decoder's questions, answered here because this is the only
     * object media3 asks them of.
     *
     * `FfmpegAudioRenderer.supportsFormatInternal` decides whether the bundled
     * FFmpeg decoder may take a track from `sinkSupportsFormat(format, 16-bit)`
     * and `sinkSupportsFormat(format, float)` — both of which reach this sink as
     * a PCM format built at the input track's channel count — and
     * `shouldOutputFloat` asks through `getFormatSupport` too. So these two
     * answers ARE the decoder's routing decision, and they are where the one
     * width its libswresample conversion is provably wrong for is turned away
     * before any decode (see [SoftwareAudioRules] for the crash and the source
     * behind it).
     *
     * Both are overridden because `DecoderAudioRenderer` has a method for each
     * (`sinkSupportsFormat` → `supportsFormat`, `getSinkFormatSupport` →
     * `getFormatSupport`) and answers "does the software decoder fit this
     * track" from either.
     *
     * Everything else is the inner sink's answer, unchanged: a compressed
     * format here is the passthrough question, and passthrough never reaches
     * libswresample.
     */
    override fun supportsFormat(format: Format): Boolean {
        val refusal = refuseWidth(format)
        return refusal == null && inner.supportsFormat(format)
    }

    override fun getFormatSupport(format: Format): Int {
        val refusal = refuseWidth(format)
        if (refusal != null) return AudioSink.SINK_FORMAT_UNSUPPORTED
        return inner.getFormatSupport(format)
    }

    /**
     * The shared answer behind the two overrides above: non-null and logged
     * once per width when this track is past what the software decoder's
     * conversion can address, null when it is not this question's business.
     */
    private fun refuseWidth(format: Format): String? {
        val refusal = SoftwareAudioRules.refusesSoftwareDecode(format) ?: return null
        if (refusedWidth != format.channelCount) {
            refusedWidth = format.channelCount
            Log.w(TAG, "software decoder refused this track: $refusal")
        }
        return refusal
    }

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        this.inputFormat = inputFormat
        this.pcmInput = MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType
        this.specifiedBufferSize = specifiedBufferSize
        // Defensive copy: the renderer hands us an array it may reuse.
        this.channelMap = outputChannels?.copyOf()

        inner.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        applyPendingLayoutChange()
        return inner.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun reset() {
        inputFormat = null
        pcmInput = false
        channelMap = null
        rejectedTarget = Int.MIN_VALUE
        refusedWidth = Int.MIN_VALUE
        inner.reset()
    }

    override fun release() {
        inputFormat = null
        pcmInput = false
        channelMap = null
        refusedWidth = Int.MIN_VALUE
        inner.release()
    }

    /**
     * Runs the sink's reconfigure path when the downmix setting now asks for a
     * different number of channels than the one the sink is carrying.
     *
     * Checked here, per buffer, rather than from the panel: the settings live in
     * [PlayerAudioTuning] and are read by the audio thread, so the change is
     * picked up on the next buffer whatever wrote it — the player's own panel,
     * the global settings screen, or a per-title override as a session starts.
     * Doing it on the audio thread also means the sink can never be reconfigured
     * underneath a buffer it is in the middle of writing.
     *
     * Cost when nothing changed: two volatile int reads.
     */
    private fun applyPendingLayoutChange() {
        if (!pcmInput) return
        val format = inputFormat ?: return

        val processor = processors.downmix
        // The processor stands aside for encodings and layouts it does not
        // model (passthrough/offload, or a channel count with no speaker map);
        // guessing at a fold for those would corrupt the stream.
        if (!processor.isFoldingChannels) return

        val decoded = processor.inputChannelCount
        val carrying = processor.outputChannelCount
        if (decoded <= 0 || carrying <= 0) return
        if (!AudioDownmix.layoutChangeNeedsReconfigure(decoded, carrying)) return
        val target = PlayerAudioTuning.downmixTarget
        if (target == rejectedTarget) return

        // The reconfigure replays the sink's configure/flush hooks, and those are
        // where AudioDelayProcessor re-inserts the A/V offset — but the audio
        // already carries its shift, so that one re-insertion must be skipped.
        processors.delay.beginInPlaceReconfigure()

        try {
            inner.configure(format, specifiedBufferSize, channelMap)
            Log.i(
                TAG,
                "live downmix: layout rebuilt, $carrying -> " +
                    "${processor.outputChannelCount} channels " +
                    "(target=${PlayerAudioTuning.downmixTarget})"
            )
        } catch (rejected: AudioSink.ConfigurationException) {
            // The re-configure happens inside the pipeline's configure(), so the
            // processors have already committed to the new width by the time the
            // output config is rejected. Put them back on the width the sink is
            // still carrying, or the two would feed each other buffers of
            // different frame sizes.
            Log.w(
                TAG,
                "live downmix: sink refused the new layout, keeping $carrying channels",
                rejected
            )
            processors.downmix.rePinOutputChannels(carrying)
            rejectedTarget = target
            // ...and put the SINK back where it was. A DefaultAudioSink that
            // threw out of configure() may be left unconfigured, and every
            // later handleBuffer would then throw or drop audio for the rest of
            // the live session - a half-applied layout change taking the sound
            // with it. reset() clears whatever the failure left behind, and the
            // repeat configure() re-establishes the layout the sink was
            // actually carrying. If even that fails there is no usable output
            // left, so the failure is rethrown and the renderer rebuilds, which
            // is what a real format change would have done anyway.
            try {
                inner.reset()
                inner.configure(format, specifiedBufferSize, channelMap)
            } catch (restoreFailure: Exception) {
                Log.e(
                    TAG,
                    "live downmix: could not restore the carried layout",
                    restoreFailure
                )
                throw rejected
            }
        }
    }
}
