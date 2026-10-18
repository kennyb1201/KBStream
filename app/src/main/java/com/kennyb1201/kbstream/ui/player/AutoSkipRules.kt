package com.kennyb1201.kbstream.ui.player

/**
 * The decisions behind automatic intro/credits skipping, kept pure so the
 * behavior is unit-testable without a player or a network. The activity only
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

    /**
     * The end of [stamp] as a position in the file being played.
     *
     * A row can carry a start with no end: v4 serves the credits that way
     * whenever the submitter marked where they begin and nobody marked where
     * they stop (see [IntroDbStamp.endMs]). The only end such a segment can
     * have is the end of the file, so that is what the duration supplies.
     *
     * Null means "no position can be named yet": the segment is open-ended and
     * the file's duration is not known (a player that is still opening the
     * stream reports 0 or `C.TIME_UNSET`). Callers treat that as nothing to
     * offer rather than inventing an end - the same segment is offered a tick
     * later, once the duration arrives.
     */
    fun segmentEndMs(stamp: IntroDbStamp, durationMs: Long): Long? =
        stamp.endMs ?: durationMs.takeIf { it > 0L }

    /**
     * Whether the playhead is inside [stamp]. An open-ended stamp runs to the
     * end of the file, so "inside" never stops being true once it is reached.
     */
    fun isInside(stamp: IntroDbStamp, positionMs: Long): Boolean =
        positionMs >= stamp.startMs && (stamp.endMs?.let { positionMs < it } ?: true)

    /** True when [stamp] should be skipped automatically right now. */
    fun shouldAutoSkip(
        stamp: IntroDbStamp,
        settings: Settings,
        durationMs: Long
    ): Boolean {
        if (!autoSkipsType(stamp.type, settings)) return false
        val confidence = stamp.confidence
        if (confidence != null && confidence < MIN_CONFIDENCE) return false
        return isSkippableSegment(stamp, durationMs)
    }

    /**
     * Sane length and direction — the guard the skip button also applies.
     *
     * [durationMs] is what an open-ended segment is measured against, so a
     * credits row with no end that would cover hours of the file is still
     * refused, and one whose runtime is not known yet is not offered at all.
     */
    fun isSkippableSegment(stamp: IntroDbStamp, durationMs: Long): Boolean {
        val end = segmentEndMs(stamp, durationMs) ?: return false
        return end > stamp.startMs && end - stamp.startMs <= MAX_SEGMENT_MS
    }

    /**
     * Where a skip of [stamp] should land. When the title has a post-credits
     * scene inside the credits, the skip stops *at* the scene instead of at the
     * end of the file, so skipping credits never eats the scene.
     *
     * An open-ended segment is clamped to the known runtime. With neither an
     * end nor a runtime there is nowhere to go, so the answer is the playhead's
     * own position - a no-op seek - which callers never reach, because they
     * only ever offer a segment [isSkippableSegment] accepted.
     */
    fun targetMs(stamp: IntroDbStamp, all: List<IntroDbStamp>, durationMs: Long): Long {
        val resolved = segmentEndMs(stamp, durationMs) ?: return stamp.startMs
        // A stated end past the runtime is a bad row or an unclamped one: never
        // seek beyond the file.
        val end = if (durationMs > 0L) resolved.coerceAtMost(durationMs) else resolved

        val scene = all
            .filter { it.type == IntroDbMarkerType.PostCredits }
            .filter { it.startMs >= stamp.startMs && it.startMs < end }
            .minByOrNull { it.startMs }
        return scene?.startMs?.coerceAtMost(end) ?: end
    }

    /**
     * How far ahead of the resume point a segment may begin and still be skipped
     * up front by [handoffSkipTarget]. A recap opens the episode at 0:00 and an
     * intro is a few seconds in, so this covers both without reaching across a
     * cold open that is part of the episode itself.
     */
    const val HANDOFF_AHEAD_WINDOW_MS = 30_000L

    /**
     * The segment an external hand-off should start past, or null.
     *
     * ExoPlayer and MPV raise a SKIP button the moment the playhead reaches a
     * segment. The External engine cannot: another app owns the screen, so
     * there is nowhere to draw one and no way to seek once we have let go. The
     * hand-off is therefore the last moment a segment can be skipped at all,
     * and a segment the session is about to run into — or is already inside,
     * which is what resuming into a recap looks like — is skipped up front.
     * That is what turning auto-skip on asked for; with both prefs off this is
     * never consulted.
     *
     * [positionMs] is the resume point, and the earliest qualifying segment
     * wins so a recap never shadows the intro that follows it.
     */
    fun handoffSkipTarget(
        stamps: List<IntroDbStamp>,
        positionMs: Long,
        settings: Settings,
        durationMs: Long
    ): IntroDbStamp? = stamps
        .filter { shouldAutoSkip(it, settings, durationMs) }
        // Not yet reached, or already inside it - never one the resume point is
        // past, which seeking back into would be fighting the viewer.
        .filter { it.startMs > positionMs || isInside(it, positionMs) }
        .filter { it.startMs <= positionMs + HANDOFF_AHEAD_WINDOW_MS }
        .minByOrNull { it.startMs }

    /**
     * Identity of one segment, so it is auto-skipped at most once per session —
     * seeking back into an intro deliberately must not be fought.
     */
    fun key(stamp: IntroDbStamp): String =
        "${stamp.type.name}:${stamp.startMs}:${stamp.endMs ?: "eof"}"
}
