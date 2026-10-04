package com.kennyb1201.kbstream.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.mdblist.MdbListRatings
import com.kennyb1201.kbstream.ui.components.MdbListRatingChips
import com.kennyb1201.kbstream.ui.components.mdbListRatingSources
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * MDBList's critic/audience ratings for one title (IMDb, Rotten Tomatoes,
 * TMDB, Metacritic, Trakt, Letterboxd, MyAnimeList).
 *
 * These used to render at the very BOTTOM of the detail page, inside the
 * REVIEWS section — below cast, network and production — so in practice they
 * were never seen even though they were fetched and correct. They now sit
 * directly under the overview, where the other title facts are.
 *
 * The chips themselves live in [MdbListRatingChips], shared with the Home hero
 * so both surfaces name and color the sources identically; this wrapper only
 * adds the page's "RATINGS" label and its own padding. The strip is a pure
 * function of its two arguments: no state, no view model.
 *
 * [tmdbFallback] is TMDB's own vote_average, and it is used ONLY when MDBList
 * sent no TMDB figure - which is every title when no MDBList key is set. The
 * detail screen deliberately keeps TMDB's score out of the meta add-on line,
 * where it would be labeled "IMDb x.x" (see DetailViewModel's meta build),
 * so the chip labeled TMDB is where it belongs: a title whose catalog and
 * trackers sent no rating at all still shows its audience score instead of an
 * empty row.
 */
@Composable
internal fun MdbListRatingsStrip(
    ratings: MdbListRatings?,
    tmdbFallback: Double? = null
) {
    val sources = mdbListRatingSources(ratings, tmdbFallback)
    if (sources.isEmpty()) return

    Column(
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp)
    ) {
        Text(
            text = "RATINGS",
            style = MaterialTheme.typography.titleSmall,
            color = KBTextLo,
            modifier = Modifier.padding(top = 14.dp, bottom = 7.dp)
        )
        MdbListRatingChips(
            sources = sources,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
