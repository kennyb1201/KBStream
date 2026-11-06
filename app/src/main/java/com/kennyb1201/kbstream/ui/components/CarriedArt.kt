package com.kennyb1201.kbstream.ui.components

/**
 * The art a rail card CARRIED with its own data, and whether it is still art.
 *
 * A pinned Library rail draws its cards from one model ([LibraryItem]'s meta):
 * a BingeCat row carries no art at all and is drawn by its TMDB id, while a
 * Trakt row carries the service's own poster and used to be drawn as-is, with
 * the TMDB resolvers switched off because the card believed it had art. That
 * belief is right about the URL and wrong about the artwork: Trakt's image host
 * can answer a URL that does not load, and the card then sat title-only beside a
 * BingeCat rail of posters (the 2026-11 field report) - the resolvers would have
 * drawn the title, but they never ran.
 *
 * So a carried URL is a first ATTEMPT, not a verdict. It is [effective] until it
 * fails to load, at which point [usable] goes false and the card arms the
 * resolvers as its fallback. A carried poster that loads costs nothing extra:
 * the resolvers are only asked once [failedToLoad] has been reported, so the
 * happy path makes no TMDB call it did not make before.
 *
 * A row whose carried art fails AND whose lookup misses keeps its title: the
 * card's own text fallback is unchanged and no row is ever dropped (see the
 * rails' `fallbackTitle`).
 *
 * The failure is the LOADER's answer (Coil's error callback), not a guess from
 * the URL: a URL that merely looks dead is still tried, and a URL that fails is
 * not drawn a second time.
 */
internal data class CarriedArt(
    /** The URL the row brought with it, blank or null when it brought none. */
    val url: String?,
    /** Whether the loader has already reported that it will not load. */
    val failed: Boolean = false
) {

    /** The carried URL, blank folded to null - what the card would draw. */
    private val carried: String? = url?.takeIf { it.isNotBlank() }

    /**
     * The art to draw, or null when the card has none of its own - either it
     * carried none, or the one it carried did not load.
     */
    val effective: String? = carried?.takeIf { !failed }

    /** Whether the card's own art is what the card should draw. */
    val usable: Boolean = effective != null

    /**
     * The carried art did not load, so the fallback may run.
     *
     * Answers a new state rather than mutating: the caller holds it in Compose
     * state and re-reads it, which is what flips the resolvers on exactly once.
     * A row that carried nothing is unchanged - its fallback was already armed,
     * and a failure of THAT art is the resolver's business, not this card's.
     */
    fun failedToLoad(): CarriedArt =
        if (carried == null || failed) this else copy(failed = true)
}
