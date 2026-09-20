package com.kennyb1201.kbstream.ui.player

/**
 * The decisions behind automatic intro/credits skipping, kept pure so the
 * behaviour is unit-testable without a player or a network. The activity only
 * supplies the two prefs and acts on what comes back.
 *
 * Nothing here ever fires for [IntroDbMarkerType.PostCredits] or
 * [IntroDbMarkerType.Preview]: those mark content the viewer is waiting for
 * (the scene after the credits, the next-episode preview), not filler to jump.
 */
internal object AutoSkipRules {

    /**
     * Same ceiling the skip button uses before it offers anything: a "segment"
     * longer than this is a bad crowd-sourced row, not an intro.
     */
    const val MAX_SEGMENT_MS = 10 * 60 * 1000L

    /**
     * A reported confidence below this is a guess. The segment is still offered
     * as a button — it is only never skipped without a press.
     */
    const val MIN_CONFIDENCE = 0.5

    data class Settings(
        val skipIntros: Boolean,
        val skipCredits: Boolean
    )

    /** Whether this kind of segment may be skipped with no press at all. */
    fun autoSkipsType(type: IntroDbMarkerType, settings: Settings): Boolean = when (type) {
        IntroDbMarkerType.Intro, IntroDbMarkerType.Recap -> settings.skipIntros
        IntroDbMarkerType.Outro, IntroDbMarkerType.Credits -> settings.skipCredits
        IntroDbMarkerType.PostCredits, IntroDbMarkerType.Preview -> false
    }

    /** True when [stamp] should be skipped automatically right now. */
    fun shouldAutoSkip(stamp: IntroDbStamp, settings: Settings): Boolean {
        if (!autoSkipsType(stamp.type, settings)) return false
        val confidence = stamp.confidence
        if (confidence != null && confidence < MIN_CONFIDENCE) return false
        return isSkippableSegment(stamp)
    }

    /** Sane length and direction — the guard the skip button also applies. */
    fun isSkippableSegment(stamp: IntroDbStamp): Boolean =
        stamp.endMs > stamp.startMs && stamp.endMs - stamp.startMs <= MAX_SEGMENT_MS

    /**
     * Where a skip of [stamp] should land. When the title has a post-credits
     * scene inside the credits, the skip stops *at* the scene instead of at the
     * end of the file, so skipping credits never eats the scene.
     */
    fun targetMs(stamp: IntroDbStamp, all: List<IntroDbStamp>, durationMs: Long): Long {
        val end = if (durationMs > 0L) stamp.endMs.coerceAtMost(durationMs) else stamp.endMs
        val scene = all
            .filter { it.type == IntroDbMarkerType.PostCredits }
            .filter { it.startMs >= stamp.startMs && it.startMs < end }
            .minByOrNull { it.startMs }
        return scene?.startMs?.coerceAtMost(end) ?: end
    }

    /**
     * Identity of one segment, so it is auto-skipped at most once per session —
     * seeking back into an intro deliberately must not be fought.
     */
    fun key(stamp: IntroDbStamp): String = "${stamp.type.name}:${stamp.startMs}:${stamp.endMs}"
}
