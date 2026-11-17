package com.kennyb1201.kbstream.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.data.mdblist.MdbListRatings
import com.kennyb1201.kbstream.ui.components.MdbListRatingChips
import com.kennyb1201.kbstream.ui.components.mdbListRatingSources

/**
 * MDBList's critic/audience ratings for one title (IMDb, Rotten Tomatoes,
 * TMDB, Metacritic, Trakt, Letterboxd, MyAnimeList).
 *
 * These used to render at the very BOTTOM of the detail page, inside the
 * REVIEWS section — below cast, network and production — so in practice they
 * were never seen even though they were fetched and correct. They now sit
 * directly under the overview, where the other title facts are.
 *
 * The chips themselves live in [MdbListRatingChips]; this wrapper adds only its
 * own padding. It used to draw a "RATINGS" label above them, and that label was
 * what pushed the chips and the fact cards so far down the page (spec
 * 2026-11-17, F1/F2): a header naming a row of self-describing chips is a line
 * the viewer pays for in vertical room, and the row under the overview is the
 * one place on this page where that room is scarcest. The shared
 * [mdbListRatingSources] list is the single source of truth, so the detail
 * strip and the Home hero (which folds the same sources into its metadata line
 * as compact tokens rather than chips) name and order the sources identically.
 * The strip is a pure function of its two arguments: no state, no view model.
 *
 * [tmdbFallback] is TMDB's own vote_average, and it is used ONLY when MDBList
 * sent no TMDB figure - which is every title when no MDBList key is set. The
 * detail screen deliberately keeps TMDB's score out of the meta add-on line,
 * where it would be labeled "IMDb x.x" (see DetailViewModel's meta build),
 * so the chip labeled TMDB is where it belongs: a title whose catalog and
 * trackers sent no rating at all still shows its audience score instead of an
 * empty row.
 *
 * [modifier] is the caller's, and the TV page uses it for the row's own focus
 * observer (see DetailScreen's season-row reveal).
 */
@Composable
internal fun MdbListRatingsStrip(
    ratings: MdbListRatings?,
    tmdbFallback: Double? = null,
    modifier: Modifier = Modifier
) {
    val sources = mdbListRatingSources(ratings, tmdbFallback)
    if (sources.isEmpty()) return

    Column(
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 4.dp)
    ) {
        MdbListRatingChips(
            sources = sources,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
