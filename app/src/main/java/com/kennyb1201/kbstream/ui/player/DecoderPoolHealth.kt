package com.kennyb1201.kbstream.ui.player

/**
 * Process-wide decoder-pool health, so a session can tell "this file is too
 * much for the box" apart from "the pool is wedged by our own rebuilds".
 *
 * The TCL/Realtek stack hands out one 4K decode per process and needs ~15s to
 * release it (see PlaybackRecoveryRules, PlayerRebuild.kt). A session that
 * keeps rebuilding — the rebuffer downshift switching sources, the retry
 * ladder re-asking — wedges that pool itself: every new attempt needs the
 * same decoder the previous attempt has not finished releasing, and each
 * comes back OMX_ErrorInsufficientResources (0x80001000). A field session
 * turned this into 9 rebuilds and 5 exhaustion events on a file other apps
 * play fine, ending on the "run out of video decoder resources" card; a
 * force-stop (fresh pool) played the same file clean.
 *
 * The two 0x80001000 cases need opposite recoveries:
 * - the pool never produced a frame in this process → a fresh pool refused
 *   the first configure, so the file may genuinely exceed the box (or a
 *   concurrent holder has the decoder) — trying one smaller source is
 *   legitimate;
 * - the pool produced frames before → it worked and is now wedged by us — a
 *   smaller source needs the same pool and cannot help, so the source ladder
 *   is skipped and the session goes straight to the MPV software fallback.
 *
 * Only the ExoPlayer engine reports here: it is the one that allocates
 * MediaCodec decoders. MPV decodes through its bundled FFmpeg and never
 * touches this pool, so its frames say nothing about the pool's health.
 */
internal object DecoderPoolHealth {

    /**
     * How long after an exhaustion a stall is still read as pool pressure
     * rather than a slow source. Covers the vendor's ~15s release plus the
     * retry backoffs that would otherwise re-trigger inside it.
     */
    private const val EXHAUSTION_WINDOW_MS = 120_000L

    /** A MediaCodec decoder rendered a frame at least once in this process. */
    @Volatile
    var everRenderedFirstFrame: Boolean = false
        private set

    /** When the pool last reported 0x80001000, 0 when it never has. */
    @Volatile
    var lastExhaustionMs: Long = 0L
        private set

    fun noteFirstFrame() {
        everRenderedFirstFrame = true
    }

    fun noteExhaustion(nowMs: Long = System.currentTimeMillis()) {
        lastExhaustionMs = nowMs
    }

    /** True when the pool failed recently enough that a stall is its doing. */
    fun exhaustedRecently(nowMs: Long = System.currentTimeMillis()): Boolean =
        lastExhaustionMs > 0L && nowMs - lastExhaustionMs < EXHAUSTION_WINDOW_MS
}
