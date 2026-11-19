package com.kennyb1201.kbstream.data.tmdb

/**
 * When a catalog's own release year is enough to answer the digital-release
 * question without asking TMDB.
 *
 * The filter behind [TmdbRepository.filterByHomeAvailabilityById] exists to hide
 * one thing: a movie that is still inside its theatrical window, or not out at
 * all, with no home release. `isAvailableAtHome` decides that from TMDB's
 * release_dates payload, which costs one enriched-detail fetch per movie - and
 * a Home screen is made of them. The 0.9.393 paste measured what that costs:
 * `detailSrc net:436` in a cold load, against a 150-entry detail cache that
 * cannot hold a rail, let alone six, so ~440 movies were re-asked about on a
 * load that had asked about the same ones minutes before. That fetch was the
 * whole 28-51 s verdict pass.
 *
 * The catalog also ships a release year, and for most of a Home screen it
 * already answers the question: a movie released in an earlier calendar year is
 * a year or more past its release, which is past any theatrical window the
 * verdict distinguishes. Asking TMDB about it can only confirm what the row
 * already implies, so [isDefinitelyReleased] lets the caller keep the title
 * without a request.
 *
 * What this is NOT: a claim that the year is a home-release date. A movie can
 * sit theatrical-only for a long time in a slow market, and such a title is now
 * KEPT rather than hidden. That is the direction the filter already fails in -
 * an unknown or unresolvable verdict keeps the title - and it is the only
 * direction worth failing in here: a hidden row is a title the viewer cannot
 * reach at all, while a kept one is a card whose play sources the add-ons
 * answer for themselves.
 *
 * The year is a calendar year, so the rule is deliberately coarse: it never
 * claims more precision than the input has. Pure and free of Android, so the
 * arithmetic is pinned by a plain-JVM test rather than by source text.
 */
internal object ReleaseAgeGate {

    /**
     * Nothing this app can be asked to show was released before the first
     * public film screenings, so a smaller year is junk rather than evidence -
     * an add-on that ships "1" or "0" in `releaseInfo` must not be read as
     * "released in year 1" and skip the fetch.
     */
    private const val EARLIEST_PLAUSIBLE_YEAR = 1870

    /**
     * Whether [year] alone proves the title is past its release window in
     * [currentYear].
     *
     * A null or implausible year answers false, i.e. the caller asks TMDB as it
     * always did: the gate may only ever REMOVE a request, never keep something
     * on the strength of a year it could not read.
     */
    fun isDefinitelyReleased(year: Int?, currentYear: Int): Boolean {
        if (year == null) return false
        if (year < EARLIEST_PLAUSIBLE_YEAR) return false
        return year < currentYear
    }
}
