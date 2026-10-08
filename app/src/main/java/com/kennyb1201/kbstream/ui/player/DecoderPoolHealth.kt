package com.kennyb1201.kbstream.ui.player

/**
 * Process-wide decoder-pool health, so a session can tell "this file is too
 * much for the box" apart from "the pool is wedged by our own rebuilds".
 *
 * The TCL/Realtek stack hands out one 4K decode per process and needs ~15s to
 * release it (see PlayerRebuild.kt, [PlaybackRecoveryRules]). A session that
 * keeps rebuilding — the rebuffer downshift switching sources, the retry ladder
 * re-asking — wedges that pool itself: every new attempt needs the same decoder
 * the previous attempt has not finished releasing, and each comes back
 * OMX_ErrorInsufficientResources (0x80001000). A field session turned this into
 * 9 rebuilds and 5 exhaustion events on a file other apps play fine, ending on
 * the "run out of video decoder resources" card; a force-stop (fresh pool)
 * played the same file clean.
 *
 * The two 0x80001000 cases need opposite recoveries:
 * - the pool never produced a frame in this process → a fresh pool refused the
 *   first configure, so the file may genuinely exceed the box (or a concurrent
 *   holder has the decoder) — trying one smaller source is legitimate;
 * - the pool produced frames before → it worked and is now wedged by us — a
 *   smaller source needs the same pool and cannot help, so the source ladder is
 *   skipped and the session goes straight to the MPV software fallback.
 *
 * Only the ExoPlayer engine reports here: it is the one that allocates
 * MediaCodec decoders. MPV decodes through its bundled FFmpeg and never touches
 * this pool, so its frames say nothing about the pool's health.
 */
internal object DecoderPoolHealth {

    /**
     * How long after an exhaustion a stall is still read as pool pressure
     * rather than a slow source. Covers the vendor's ~15s release plus the
     * retry backoffs that would otherwise re-trigger inside it.
     */
    internal const val EXHAUSTION_WINDOW_MS = 120_000L

    /**
     * A fullscreen ExoPlayer session in this process rendered its first frame
     * (the spec's `decoderEverConfiguredOk`).
     *
     * Set from [NativePlayerActivity.markFirstFrameRendered], which only the
     * fullscreen player calls — the Home hero's pooled trailer shares this
     * process but never reports here, so this answers "has the pool handed a
     * fullscreen playback a decoder that worked", not "has any ExoPlayer
     * anywhere".
     */
    @Volatile
    var everRenderedFirstFrame: Boolean = false
        private set

    /** When the pool last reported 0x80001000, 0 when it never has. */
    @Volatile
    var lastExhaustionMs: Long = 0L
        private set

    /** True once the pool has answered 0x80001000 at least once in this process. */
    val everExhausted: Boolean
        get() = lastExhaustionMs > 0L

    /** A decoder rendered a frame: the pool works in this process. */
    fun noteFirstFrame() {
        everRenderedFirstFrame = true
    }

    /** The pool just refused a decoder request (0x80001000). */
    fun noteExhaustion(nowMs: Long = System.currentTimeMillis()) {
        lastExhaustionMs = nowMs
    }

    /**
     * True when the pool failed recently enough that a stall is its doing.
     *
     * A stall inside this window is pool pressure, not a bad source: another
     * rebuild (or a source switch, which is the same rebuild) would ask the
     * wedged pool for the decoder it cannot hand out, so the answer there is to
     * keep buffering and let the pool recover on its own.
     */
    fun exhaustedRecently(nowMs: Long = System.currentTimeMillis()): Boolean =
        lastExhaustionMs > 0L && nowMs - lastExhaustionMs < EXHAUSTION_WINDOW_MS

    /**
     * Clears both facts, for tests only.
     *
     * The health is process-wide by design — nothing in the app may reset it,
     * because a reset would let the next session walk the source ladder into a
     * pool it has already wedged.
     */
    internal fun resetForTest() {
        everRenderedFirstFrame = false
        lastExhaustionMs = 0L
    }
}
