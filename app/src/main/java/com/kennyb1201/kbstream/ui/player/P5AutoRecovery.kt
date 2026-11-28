package com.kennyb1201.kbstream.ui.player

/**
 * Auto-mode Profile 5: the rules behind "play it as native Dolby Vision, and
 * recover when the platform does not engage it".
 *
 * The field evidence this encodes (TCL / Realtek, DV mode Auto, 2026-11-27):
 *
 *  - P5 is NOT deterministically broken on the Realtek DV decoder. After the
 *    first play engaged Dolby Vision, every replay — a warm one, a full exit +
 *    replay, even a round trip through MPV — played correctly, with no setting
 *    changed. So a static "this decoder cannot do P5" blocklist would throw
 *    away working native Dolby Vision, and there is no such list here.
 *  - What fails is the FIRST play in a process: the decoder accepts the ICtCp
 *    stream and renders it as if it were Rec.2020 PQ (green), with no error.
 *    No exception, no refused format, no zero-frame stall — the app is told
 *    every frame rendered, and it did.
 *
 * Which leaves three decisions, kept here as pure functions so they can be
 * tested and read without an 18,000-line Activity:
 *
 *  1. [stripP5ForGpuPath] — when P5 cannot go to the vendor decoder at all, it
 *     takes the strip + GPU color path (the same path the "P5 → HDR10" toggle
 *     engages). This is the F1 fallback: a suppressed passthrough (a recorded
 *     decoder refusal, 14-day TTL), a box with no DV decoder, or a DV decoder
 *     with no DV display. It is NOT applied while passthrough is available —
 *     native Dolby Vision is the preferred path, and Auto must not strip it.
 *  2. [glesPathWantedForP5] — the GPU shader is mandatory whenever P5 is
 *     stripped (ICtCp planes with no conversion are the same green), and it is
 *     also how a sniffed P5 gets corrected: a remux that declared `hvc1` never
 *     involves the platform's Dolby Vision pipeline, so the shader is the only
 *     thing that can render its planes right, on any box.
 *  3. [needsFirstPlayPrime] — the bounded first-play recovery.
 *
 * THE SIGNAL THAT DOES NOT EXIST. The spec asks for the first frame to be held
 * until Dolby Vision engagement is *confirmed*, and for the recovery to run
 * only when it is not. There is no such confirmation available to an app:
 *
 *  - no decoder output-format hook on the passthrough path — the vendor
 *    MediaCodec is media3's, and its output MediaFormat is not exposed;
 *  - no display Dolby Vision mode callback: `Display.getHdrCapabilities()` and
 *    the active `Display.Mode` are static capability reads, identical before
 *    and after the panel latches;
 *  - and the failure renders frames, so neither the black-video watchdog nor
 *    the decoder-failure ladder (all of which require an error or no output)
 *    can see it.
 *
 * So [needsFirstPlayPrime] implements the spec's mandated fallback instead of
 * inventing a green-pixel detector: on the cold first P5 passthrough play of a
 * process, the session spends one bounded re-init — the "replay it from the
 * beginning" that Kenny's retest proved works — and marks the process as
 * primed, so every later P5 play takes the ordinary passthrough path with no
 * extra wait. If even that does not engage, the platform's own refusal (F4,
 * a DV decoder error) feeds the learned failure and [stripP5ForGpuPath] takes
 * over; the user's toggle remains the manual path.
 *
 * The re-init is deliberately AFTER the first frame, not before it: the
 * evidence is that a *replay* fixes it, and a replay only fixes it because the
 * first play put frames through the panel and latched its Dolby Vision mode.
 * Rebuilding before any frame would be the same cold attempt again — the
 * reason this is a "prime" and not a "pre-build".
 */
internal object P5AutoRecovery {

    /**
     * How long the priming attempt is left running after its first frame
     * before the one re-init.
     *
     * Frames must have flowed for the display to latch its DV mode, so zero is
     * wrong; long is a viewer staring at a splash that has already painted
     * behind it. Under a second covers a couple of GOPs at the frame rates
     * these files carry, and the splash is still covering the picture for the
     * whole window (`markFirstFrameRendered` holds it while the prime is armed).
     */
    const val PRIME_GRACE_AFTER_FIRST_FRAME_MS = 800L

    /**
     * Whether a declared P5 track takes the strip + GPU color path.
     *
     * Never while passthrough is available: this is the recovery, not the
     * default, and Auto's contract is that a DV display gets native Dolby
     * Vision. [convertAllProfiles] is left out for the same reason it has its
     * own arm — "Strip All" is the user saying the display has no Dolby
     * Vision, and it already rewrites every profile including P5.
     */
    fun stripP5ForGpuPath(
        declaredP5: Boolean,
        convertAllProfiles: Boolean,
        dvRewriteEnabled: Boolean,
        convertP5To81: Boolean,
        nativeDvPassthrough: Boolean
    ): Boolean {
        if (!declaredP5) return false
        // Strip All rewrites P5 on its own arm; "None" is the explicit
        // pass-through the user asked for and must stay byte-exact.
        if (convertAllProfiles || !dvRewriteEnabled) return false
        if (convertP5To81) return true
        return !nativeDvPassthrough
    }

    /**
     * Whether this session's P5 content needs the raw-plane GPU color path.
     *
     * [sniffedP5] is the remux case: the container declared `hvc1`, so the
     * platform never treats the track as Dolby Vision — whatever the box can
     * do natively, this track is not going there, and stripping it without the
     * shader is the same green.
     */
    fun glesPathWantedForP5(
        hasGles3: Boolean,
        forceStripForSession: Boolean,
        stripAllMode: Boolean,
        p5Toggle: Boolean,
        sniffedP5: Boolean,
        nativeDvPassthrough: Boolean,
        dvModeOff: Boolean
    ): Boolean {
        if (!hasGles3) return false
        if (forceStripForSession) return true
        if (sniffedP5) return true
        if (stripAllMode || p5Toggle) return true
        // F1: the extractor strips P5 exactly when there is no passthrough, so
        // the shader is the only thing that can render the planes correctly.
        return !nativeDvPassthrough && !dvModeOff
    }

    /**
     * Whether this attempt is the cold first P5 passthrough play of the process
     * and should spend the one bounded re-init.
     *
     * Every term is a precondition of the observed failure, so a box that never
     * showed it pays nothing: Auto (the mode that prefers native DV), passthrough
     * actually active, a DV display to show it, P5 content, and no GPU path in
     * play (a stripped P5 is not passthrough and has nothing to engage).
     */
    fun needsFirstPlayPrime(
        autoMode: Boolean,
        nativeDvPassthrough: Boolean,
        displaySupportsDv: Boolean,
        p5Content: Boolean,
        glesPathWanted: Boolean,
        primedThisProcess: Boolean
    ): Boolean = autoMode && nativeDvPassthrough && displaySupportsDv && p5Content &&
        !glesPathWanted && !primedThisProcess
}
