package com.kennyb1201.kbstream.ui.player

/**
 * Where a [IntroDbStamp] came from.
 *
 * The chain asks several services for the same segment kinds (see
 * [fetchIntroDbStamps]), so which one answered is part of what a stamp means:
 * the sources do not share data, confidence or identity, and one of them is not
 * allowed to auto-skip at all. Before this existed a stamp was just times and a
 * kind, and "never auto-skip AniSkip" would have had nothing to test.
 *
 * Ordered as the chain queries them, which is also the precedence within a kind
 * ([mergeIntroDbStamps]).
 */
internal enum class IntroDbSource(val label: String) {
    /** IntroDB.app - the original source, IMDb ids only. */
    IntroDbApp("introdb.app"),

    /** TheIntroDB v4 - human-validated, the larger library. */
    TheIntroDb("theintrodb"),

    /** SkipDB - general movies and TV, duration-aware. */
    SkipDb("skipdb"),

    /** AniSkip - anime openings/endings/recaps, button-only (never auto-skipped). */
    AniSkip("aniskip"),

    /**
     * Anime Skip - anime timestamps as POINTS, button-only for the same reasons
     * as [AniSkip] and one more (see [AutoSkipRules.shouldAutoSkip]): the API
     * states no confidence at all, and the show is identified by an external id
     * or a title match.
     */
    AnimeSkip("anime-skip")
}

internal enum class IntroDbMarkerType(val buttonLabel: String) {
    Intro("SKIP INTRO"), Recap("SKIP RECAP"), Outro("SKIP OUTRO"),
    Credits("SKIP CREDITS"), Preview("SKIP PREVIEW"),

    /**
     * The scene after the end credits. It is content, not filler — the button
     * skips *past* it (to the end of the file), and auto-skip never touches it.
     */
    PostCredits("SKIP TO END")
}

internal data class IntroDbStamp(
    val startMs: Long,
    /**
     * Where the segment stops, or null when the source only says where it
     * starts. v4's credits and outro rows are commonly
     * `{"start_ms": 8300000, "end_ms": null}` - a submitter marked where the
     * credits begin and nobody marked where they stop - and the only end such a
     * row can have is the end of the file. Resolving that against a duration is
     * the player's job, since the player is the only part of the app that knows
     * one: see [AutoSkipRules.segmentEndMs].
     */
    val endMs: Long?,
    val type: IntroDbMarkerType,
    /**
     * Crowd-sourced confidence when the source reports one. Null means "not
     * stated", which is treated as good enough — only a reported value below
     * [AutoSkipRules.MIN_CONFIDENCE] blocks an automatic skip.
     */
    val confidence: Double? = null,
    /**
     * Which service produced this row. Defaults to the chain's first source,
     * which is what every stamp was before the marker existed.
     */
    val source: IntroDbSource = IntroDbSource.IntroDbApp
)
