package com.kennyb1201.kbstream.ui.player

/**
 * Whether a Dolby Vision failure on an ExoPlayer session should stop the retry
 * ladder and hand the session to the MPV backup engine instead.
 *
 * This is the one place that decision lives, because the call site
 * (`NativePlayerActivity.onPlayerError`) is a 1,000-line error handler where the
 * rule would otherwise be an unnamed conjunction buried in the middle of the
 * decoder-failure branch.
 *
 * The rule answers a specific, field-observed case, not Dolby Vision in
 * general:
 *
 *  - `dvDecoderRefused` — the failure IS the vendor DV decoder refusing a
 *    passthrough session (TCL/Realtek: `OMX.realtek.video.dvhe.st.decoder`
 *    advertising `video/dolby-vision`, so Media3 reports the format supported,
 *    then returning `OMX_ErrorInsufficientResources` / `0x80001000` on the first
 *    frame). Anything else is a different problem with a different remedy.
 *  - `stripRetrySpent` — the session has already used its one retry with Dolby
 *    Vision stripped to plain HDR10. When the strip REWRITE works, that retry is
 *    what plays the file, so it must never be skipped. When it fails, there is no
 *    ladder step left that can help: the remaining attempts (the MIME-hint-free
 *    raw-extractor probe, then the long backoffs) all ask for the same decoder
 *    for the same format. On the field device that produced a chain of identical
 *    rebuilds ("Attempt 4: probing with raw extractor") without ever reaching the
 *    engine that can play the file.
 *
 * MPV is the remedy because libmpv carries the full FFmpeg decoder set,
 * software included, and software video in ExoPlayer does NOT cover this case:
 * media3 only consults the extension renderer when no MediaCodec decoder claims
 * the format, whereas the vendor decoder here claims it and then fails at
 * runtime, which routes to media3's MediaCodec-to-MediaCodec fallback and never
 * to FFmpeg.
 *
 * Note that this only decides that the handoff is WORTH ATTEMPTING. Whether it
 * actually happens is still `NativePlayerActivity.handOffToMpv`: live TV, a DRM
 * session, a device without libmpv, and an "ExoPlayer only" engine setting all
 * keep the old ladder.
 */
internal object DvEscalation {

    /**
     * True when a DV decoder refusal has already had its stripped-DV retry and
     * should now be handed to the backup engine rather than retried again.
     */
    fun handOffAfterStripRetry(
        stripRetrySpent: Boolean,
        dvDecoderRefused: Boolean
    ): Boolean = stripRetrySpent && dvDecoderRefused
}
