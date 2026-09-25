package com.kennyb1201.kbstream.ui.detail

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.data.mdblist.MdbListRatings
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/** One rating source: the value MDBList returned, its brand mark and colour. */
private data class RatingSource(
    val name: String,
    val value: String,
    @DrawableRes val icon: Int,
    val tint: Color
)

// Brand colours. MDBList's own badges are colour-coded per source, so the
// icon carries the recognition and the value stays the accent-coloured number.
// MyAnimeList is lifted from its #2E51A2, which is nearly invisible on the
// dark chip surface; the rest are the brands' own colours.
private val ImdbTint = Color(0xFFF5C518)
private val RottenTomatoesTint = Color(0xFFFA320A)
private val TmdbTint = Color(0xFF01B4E4)
private val MetacriticTint = Color(0xFFFFCC34)
private val TraktTint = Color(0xFFED1C24)
private val LetterboxdTint = Color(0xFF00E054)
private val MyAnimeListTint = Color(0xFF4E7BF0)

/**
 * MDBList's critic/audience ratings for one title (IMDb, Rotten Tomatoes,
 * TMDB, Metacritic, Trakt, Letterboxd, MyAnimeList).
 *
 * These used to render at the very BOTTOM of the detail page, inside the
 * REVIEWS section — below cast, network and production — so in practice they
 * were never seen even though they were fetched and correct. They now sit
 * directly under the overview, where the other title facts are.
 *
 * Each chip shows the source's BRAND MARK next to its score instead of a
 * spelled-out name: seven chips of text made a row too wide to read at a
 * glance, and the icons are what identify the source. The full name stays as
 * the icon's content description for TalkBack.
 *
 * The chips WRAP (FlowRow) rather than sitting in one long row, and they are
 * plain boxes: a Box can never take focus, so D-pad navigation walks straight
 * past the strip instead of stopping on seven pieces of read-only data. Every
 * source present on the title fits on screen — no clipping, no scrolling to
 * reach a rating.
 *
 * Split into its own file because DetailScreen.kt is past the size where a
 * single file stays editable (the same reason [DetailRatingEnrichment] lives
 * apart from it). It is a pure function of [ratings]: no state, no view model.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MdbListRatingsStrip(ratings: MdbListRatings) {
    val sources = listOfNotNull(
        ratings.imdb?.let {
            RatingSource("IMDb", it, R.drawable.ic_rating_imdb, ImdbTint)
        },
        ratings.rottenTomatoes?.let {
            RatingSource("Rotten Tomatoes", it, R.drawable.ic_rating_rt, RottenTomatoesTint)
        },
        ratings.tmdb?.let {
            RatingSource("TMDB", it, R.drawable.ic_rating_tmdb, TmdbTint)
        },
        ratings.metacritic?.let {
            RatingSource("Metacritic", it, R.drawable.ic_rating_metacritic, MetacriticTint)
        },
        ratings.trakt?.let {
            RatingSource("Trakt", it, R.drawable.ic_rating_trakt, TraktTint)
        },
        ratings.letterboxd?.let {
            RatingSource("Letterboxd", it, R.drawable.ic_rating_letterboxd, LetterboxdTint)
        },
        ratings.myAnimeList?.let {
            RatingSource("MyAnimeList", it, R.drawable.ic_rating_mal, MyAnimeListTint)
        }
    )
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            sources.forEach { source ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(KBShapeChip)
                        .background(KBSurfaceRaised)
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Icon(
                        painter = painterResource(id = source.icon),
                        contentDescription = "${source.name} rating",
                        tint = source.tint,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = source.value,
                        color = KBAccent,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
