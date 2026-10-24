package com.kennyb1201.kbstream.ui.mobile

import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.certification
import com.kennyb1201.kbstream.data.tmdb.displayRuntimeMinutes
import com.kennyb1201.kbstream.data.tmdb.releaseYear
import com.kennyb1201.kbstream.ui.components.formatRuntimeMinutes
import com.kennyb1201.kbstream.ui.detail.formatSeriesYearRange
import com.kennyb1201.kbstream.ui.detail.formatUsd
import java.util.Locale

/**
 * One row of the phone's Movie Details / Show Details block: the label on the
 * left, the value on the right, a hairline under all but the last.
 */
internal data class DetailFact(
    val label: String,
    val value: String
)

/** The block's heading: a film's facts are its Movie Details, a series'. */
internal fun detailFactsTitle(isMovie: Boolean): String =
    if (isMovie) "Movie Details" else "Show Details"

/**
 * The facts the phone's details block prints, in the order it prints them.
 *
 * The values come from the shared `TmdbDetail` helpers the TV meta line already
 * reads (`certification`, `releaseYear`, `displayRuntimeMinutes`) plus the TV's
 * own [formatSeriesYearRange], so the two layouts cannot disagree about what a
 * title's certification or span is - only about how they are laid out. Rows
 * with nothing behind them are dropped rather than shown empty: a title with no
 * certification has no Certification row instead of a dash.
 *
 * Status is a series' row only; a film's status is not something a viewer acts
 * on, and the reference has never shown it.
 *
 * Budget, Revenue and Awards are the television page's own facts, in the order
 * it prints them (see `DetailScreen.kt`'s meta line) and after the rows this
 * block already had: the money is a FILM's
 * fact - TMDB sends no budget or revenue for a series - while awards belong to
 * both, which is why [awards] is read for either type. A zero or missing figure
 * is no row at all, and awards with no text is no row either: the block never
 * prints a placeholder.
 *
 * [type] is the route's own ("movie" / "series"), the same string every other
 * rule on this page branches on. [awards] is the OMDB text the ViewModel's own
 * flow publishes; this module keeps no dependency on it, so the rule stays a
 * pure function of what the screen read.
 */
internal fun detailFacts(
    type: String,
    detail: TmdbDetail?,
    awards: String? = null
): List<DetailFact> {
    detail ?: return emptyList()
    val isMovie = type.equals("movie", ignoreCase = true)
    return listOfNotNull(
        if (isMovie) {
            null
        } else {
            seriesStatusLabel(detail.status)?.let { DetailFact("Status", it) }
        },
        releaseInfo(detail, isMovie)?.let { DetailFact("Release Info", it) },
        detail.displayRuntimeMinutes()
            ?.takeIf { it > 0 }
            ?.let { DetailFact("Runtime", formatRuntimeMinutes(it)) },
        detail.certification(isMovie)?.let { DetailFact("Certification", it) },
        if (isMovie) {
            detail.budget
                ?.takeIf { it > 0L }
                ?.let { DetailFact("Budget", formatUsd(it)) }
        } else {
            null
        },
        if (isMovie) {
            detail.revenue
                ?.takeIf { it > 0L }
                ?.let { DetailFact("Revenue", formatUsd(it)) }
        } else {
            null
        },
        awards
            ?.takeIf { it.isNotBlank() }
            ?.let { DetailFact("Awards", it.trim()) },
        originCountryNames(detail.originCountries)?.let { DetailFact("Origin Country", it) }
    )
}

/**
 * `2022-` while a series still runs, `2019-2023` once it has ended; a film's
 * release year.
 *
 * The TV meta line prefers this same span over a bare `releaseYear()`, so the
 * phone does too, with the same fallback when TMDB sent no first-air date.
 */
internal fun releaseInfo(detail: TmdbDetail, isMovie: Boolean): String? =
    if (isMovie) {
        detail.releaseYear()
    } else {
        formatSeriesYearRange(
            detail.firstAirDate,
            detail.lastEpisodeToAir?.airDate,
            detail.status
        ) ?: detail.releaseYear()
    }

/**
 * TMDB's status, worded the way a viewer reads it: "Returning Series" is what
 * the API calls a show that is still going, not what a person calls it.
 *
 * The TV page's own `seriesStatusTag` prints the same states as an uppercase
 * badge on the hero; this is the rows block's title-case form, and an unknown
 * status is passed through rather than swallowed.
 */
internal fun seriesStatusLabel(status: String?): String? {
    val normalized = status?.trim().orEmpty()
    if (normalized.isEmpty()) return null
    return when {
        normalized.equals("Returning Series", ignoreCase = true) -> "Ongoing"
        normalized.equals("In Production", ignoreCase = true) -> "In production"
        normalized.equals("Planned", ignoreCase = true) -> "Planned"
        normalized.equals("Pilot", ignoreCase = true) -> "Pilot"
        normalized.equals("Canceled", ignoreCase = true) -> "Canceled"
        normalized.equals("Ended", ignoreCase = true) -> "Ended"
        else -> normalized
    }
}

/**
 * The Origin Country row: TMDB's ISO codes as country names, capped at three
 * so the row cannot become a paragraph (`United States of America, Australia,
 * United Kingdom` is already the long case in the reference).
 */
internal fun originCountryNames(codes: List<String>, limit: Int = 3): String? =
    codes.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map(::countryDisplayName)
        .distinct()
        .take(limit)
        .joinToString(", ")
        .takeIf { it.isNotBlank() }

/**
 * `US` -> `United States`. An unparseable or unknown code is printed as it
 * arrived rather than as an empty row: TMDB sends a few three-letter codes, and
 * losing them is worse than echoing them.
 */
internal fun countryDisplayName(code: String): String {
    val normalized = code.trim().uppercase(Locale.ROOT)
    val display = runCatching {
        Locale.Builder().setRegion(normalized).build().displayCountry
    }.getOrDefault("")
    return display.takeIf { it.isNotBlank() } ?: normalized
}
