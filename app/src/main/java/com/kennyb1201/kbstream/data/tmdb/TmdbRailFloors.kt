package com.kennyb1201.kbstream.data.tmdb

/**
 * The RECENT rails' floors, split by media type (spec 2026-11-14).
 *
 * Pure, and separate from [TmdbRepository] for one reason: these are the
 * decisions Kenny tuned, and a plain JVM test can hold them without an Android
 * context. The repository reads them and passes them to the discover calls.
 *
 * WHAT WAS WRONG. The movie RECENT rails behind the Search browse chips (genre,
 * keyword, network, studio, service, decade, cross-genre) sort purely
 * newest-first with no vote floor. On any given day there are dozens of minor
 * releases - shorts, teasers, nobody-has-rated entries - for every wide release,
 * so the blockbusters sat pages deep and the rail read as a wall of noise.
 *
 * THE SPLIT. Movies now need [RECENT_MOVIE_VOTE_FLOOR] votes and
 * [RECENT_MOVIE_MIN_RUNTIME_MINUTES] minutes of runtime; TV keeps the zero
 * floor and no runtime cut - "shows are fine" is the requirement, not a
 * preference. A series' runtime is per EPISODE, so the 40-minute feature line
 * would wipe out every sitcom; [recentRuntimeGte] answers null for TV and no TV
 * discover call has a runtime parameter to send.
 *
 * WHY 5, NOT 10. Five is the floor the rails used before the zero-floor era, and
 * it strips only the nobody-has-rated tail: a wide release clears five votes
 * within hours, so it appears the same day or the next - hours of delay, not
 * days. Ten would starve a thin catalog (a niche keyword, a mid-size studio) to
 * empty, which is why POPULAR and TOP RATED keep 10 and RECENT does not.
 *
 * A vote COUNT, not a rating: a badly-rated blockbuster with 500 votes still
 * shows; a ten-star short with three votes does not.
 */
internal object TmdbRailFloors {

    /** TV RECENT keeps no floor: a premiere is a real arrival the hour it airs. */
    const val RECENT_TV_VOTE_FLOOR = 0

    /** Movie RECENT's floor (see the file comment for why 5). */
    const val RECENT_MOVIE_VOTE_FLOOR = 5

    /** The feature/short line, in minutes. Movies only, always. */
    const val RECENT_MOVIE_MIN_RUNTIME_MINUTES = 40

    /** POPULAR's floor - unchanged by the movie RECENT fix. */
    const val POPULAR_VOTE_FLOOR = 10

    /** TOP RATED / "Most Voted" floor - unchanged too. */
    const val TOP_RATED_VOTE_FLOOR = 10

    /** The RECENT floor for a rail of [isTv]: shows none, movies five. */
    fun recentVoteFloor(isTv: Boolean): Int =
        if (isTv) RECENT_TV_VOTE_FLOOR else RECENT_MOVIE_VOTE_FLOOR

    /**
     * The runtime cut a RECENT rail sends: 40 for movies, null for TV.
     *
     * THE ONE GATE that keeps shorts out of movie rails and the filter off every
     * series rail. Derived here rather than at each call site, so a new TV rail
     * cannot pick it up by copy-paste.
     */
    fun recentRuntimeGte(isTv: Boolean): Int? =
        if (isTv) null else RECENT_MOVIE_MIN_RUNTIME_MINUTES
}
