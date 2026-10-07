package com.kennyb1201.kbstream.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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

/**
 * One rating source: the value MDBList returned, its brand mark and color.
 *
 * The chips identify the source by its mark rather than a spelled-out name -
 * seven labelled chips make a row too wide to read at a glance - so the values
 * are what the eye lands on. The full [name] stays as the icon's content
 * description for TalkBack.
 */
internal data class RatingChipSource(
    val name: String,
    val value: String,
    @DrawableRes val icon: Int,
    val tint: Color
)

// Brand colors. MDBList's own badges are color-coded per source, so the
// icon carries the recognition and the value stays the accent-colored number.
// MyAnimeList is lifted from its #2E51A2, which is nearly invisible on the
// dark chip surface; the rest are the brands' own colors.
// The brand's own yellow. Shared rather than retyped: the detail screen's IMDb
// rating chip paints the same plate, and the two had drifted into two
// literals of the same value in two files (see DetailScreen).
internal val KBImdbTint = Color(0xFFF5C518)
private val RottenTomatoesTint = Color(0xFFFA320A)
private val TmdbTint = Color(0xFF01B4E4)
private val MetacriticTint = Color(0xFFFFCC34)
private val TraktTint = Color(0xFFED1C24)
private val LetterboxdTint = Color(0xFF00E054)
private val MyAnimeListTint = Color(0xFF4E7BF0)

/**
 * The rating sources present on a title, in MDBList's own order, each with its
 * brand mark and color.
 *
 * [tmdbFallback] is TMDB's own vote_average, used ONLY when MDBList sent no
 * TMDB figure - which is every title when no MDBList key is set. A title whose
 * catalog and trackers sent no rating at all still shows its audience score
 * instead of an empty row.
 */
internal fun mdbListRatingSources(
    ratings: MdbListRatings?,
    tmdbFallback: Double? = null
): List<RatingChipSource> = listOfNotNull(
    ratings?.imdb?.let {
        RatingChipSource("IMDb", it, R.drawable.ic_rating_imdb, KBImdbTint)
    },
    ratings?.rottenTomatoes?.let {
        RatingChipSource("Rotten Tomatoes", it, R.drawable.ic_rating_rt, RottenTomatoesTint)
    },
    (ratings?.tmdb ?: tmdbFallback?.takeIf { it > 0.0 }?.let { "%.1f".format(it) })?.let {
        RatingChipSource("TMDB", it, R.drawable.ic_rating_tmdb, TmdbTint)
    },
    ratings?.metacritic?.let {
        RatingChipSource("Metacritic", it, R.drawable.ic_rating_metacritic, MetacriticTint)
    },
    ratings?.trakt?.let {
        RatingChipSource("Trakt", it, R.drawable.ic_rating_trakt, TraktTint)
    },
    ratings?.letterboxd?.let {
        RatingChipSource("Letterboxd", it, R.drawable.ic_rating_letterboxd, LetterboxdTint)
    },
    ratings?.myAnimeList?.let {
        RatingChipSource("MyAnimeList", it, R.drawable.ic_rating_mal, MyAnimeListTint)
    }
)

/**
 * The source marks as a wrapping row of chips, used by the detail page's
 * RATINGS strip.
 *
 * The chips WRAP (FlowRow) rather than sitting in one long row, and they are
 * plain boxes: a Box can never take focus, so D-pad navigation walks straight
 * past the strip instead of stopping on seven pieces of read-only data.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MdbListRatingChips(
    sources: List<RatingChipSource>,
    modifier: Modifier = Modifier
) {
    if (sources.isEmpty()) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
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
