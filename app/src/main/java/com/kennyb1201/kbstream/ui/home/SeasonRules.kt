package com.kennyb1201.kbstream.ui.home

/**
 * The two facts a Home card's badge rests on: whether the episode it points at
 * is its season's FINALE, and whether it is a season's PREMIERE.
 *
 * Both used to be decided inside the card builders themselves, and the finale
 * rule read as "the target is the season's highest listed episode". That is
 * right only once a season's listing is complete — and TMDB does not publish a
 * new season that way. It creates the season when the premiere is announced
 * and adds the rest as they are scheduled, so the day after a premiere the
 * listing for that season holds a single episode, and the premiere is "the
 * last episode of its season". That is what put a SEASON FINALE badge on
 * "Universal Basic Guys" S03E01 — the first episode of a brand-new season —
 * on the card that should have read NEW SEASON.
 *
 * The fix is the floor below: a one-episode listing is a season still
 * arriving, never a one-episode season, so a finale needs at least two. The
 * premiere side of the rule is the same fact the other way round, and lives
 * here so that both card builders (the local next-up walk and the tracker
 * feed) ask a single question rather than each answering it themselves.
 */
internal object SeasonRules {

    /**
     * How short a season's listing may be and still be called a season: two,
     * because a show with one episode in a "season" is a listing nobody has
     * finished writing, not a season anyone made.
     */
    const val MIN_EPISODES_FOR_FINALE = 2

    /**
     * Whether [episodeNumber] is its season's finale, given the season's
     * length — the higher of its last LISTED episode and the count TMDB
     * declares for it (the declared count covers a listing that lags behind
     * what is known about the season).
     *
     * An unmeasurable season ([seasonLength] of 0) is not a finale: nothing
     * can be claimed about a season whose end we could not find.
     */
    fun isSeasonFinale(
        episodeNumber: Int,
        seasonLength: Int
    ): Boolean =
        seasonLength >= MIN_EPISODES_FOR_FINALE &&
            episodeNumber >= seasonLength

    /**
     * Whether [episodeNumber] is a season's first episode — the show coming
     * back rather than one more episode. A tracker's queued position can read
     * 0 for "the start of the season", so anything at or below E01 counts.
     */
    fun isSeasonPremiere(
        episodeNumber: Int
    ): Boolean =
        episodeNumber <= 1
}
