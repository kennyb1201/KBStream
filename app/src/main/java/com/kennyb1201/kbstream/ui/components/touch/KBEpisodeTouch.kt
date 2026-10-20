package com.kennyb1201.kbstream.ui.components.touch

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.spoiler.SpoilerFree
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.ui.components.KBImdbTint
import com.kennyb1201.kbstream.ui.components.formatRuntimeMinutes
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * The touch twin of the television's episode rail (`DetailScreen.kt`'s
 * `EpisodeCard`, drawn in a `LazyRow`), and of the card it is filled with.
 *
 * The phone's Detail page listed a season vertically and could not show it any
 * other way; this is the same season as the TV draws it, for a thumb. What is
 * NOT decided here is anything about an episode's state: watched, resume
 * progress, unaired/UNAVAILABLE and spoiler-free masking are all inputs,
 * computed by the screen from the same ViewModel flows the vertical row reads,
 * so the two layouts cannot disagree about an episode - only about the shape it
 * is drawn in. Nor is the tap decided here: [onClick] and [onLongClick] are the
 * callers' own handlers, the same pair the list row is given.
 *
 * The card is the television's own: a 260x170 frame with the caption over it,
 * on the TV's gradient, and the same bottom line of runtime and IMDb score.
 * The only phone adaptation is the size of the margins - 10dp, as on the TV -
 * so the still and the words stay legible at arm's length while the next card
 * peeks at the screen edge to say the rail scrolls.
 */
@Composable
fun KBEpisodeRailTouch(
    episodes: List<ResolvedEpisode>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(12.dp),
    key: (ResolvedEpisode) -> Any = { it.streamId },
    card: @Composable (ResolvedEpisode) -> Unit
) {
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = horizontalArrangement
    ) {
        items(episodes, key = key) { episode -> card(episode) }
    }
}

/**
 * One episode in the touch rail, drawn like the television's card: the still,
 * a legibility gradient, the EPISODE badge and name over the frame, and a
 * bottom line carrying the runtime (`42m`, or `12m left` while part-watched)
 * and the episode's IMDb score.
 *
 * The still carries the marks the list row puts on its own thumbnail - the
 * EPISODE number, the watched tick and the resume progress - plus the
 * UNAVAILABLE badge on an episode that has not aired, which dims the frame the
 * way the television's card does. None of them is re-derived here.
 *
 * [hiddenTitle] is the caller's spoiler-free answer: when true the name is
 * never used (not in the label, not in the accessibility description) and the
 * synopsis is dropped, which is what the vertical row does with the same flag.
 * The score is suppressed on an unaired episode for the TV card's reason: a
 * future episode's vote average is a handful of early votes and reads as a real
 * rating beside the UNAVAILABLE mark.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KBEpisodeRailCardTouch(
    episode: ResolvedEpisode,
    hiddenTitle: Boolean,
    isWatched: Boolean,
    unavailable: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    val label = SpoilerFree.episodeLabel(
        hidden = hiddenTitle,
        episodeNumber = episode.episodeNumber,
        realTitle = episode.name
    )
    val runtime = episodeRuntimeLabel(episode.runtimeMinutes, progress)
    val rating = episode.voteAverage
        ?.takeIf { !unavailable }
        ?.takeIf { it > 0.0 }

    Box(
        modifier = modifier
            .width(EpisodeCardWidth)
            .height(EpisodeCardHeight)
            .clip(KBShapeCard)
            .background(KBSurface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = episode.thumbnail,
            // The caption over the frame names the episode; an announcement
            // from the image as well would read the title twice.
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // An unaired episode is dimmed, not only badged: the badge says
            // what the frame cannot.
            alpha = if (unavailable) 0.45f else 1f,
            modifier = Modifier.fillMaxSize()
        )

        // The television card's gradient: the frame survives at the top and
        // the words sit on a dark floor, which is what keeps a bright still
        // from swallowing the title.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            KBVoid.copy(alpha = 0.2f),
                            KBVoid.copy(alpha = 0.7f),
                            KBVoid.copy(alpha = 0.95f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                EpisodeNumberBadge(number = episode.episodeNumber)
                Text(
                    text = label,
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (!hiddenTitle) {
                    episode.overview?.takeIf { it.isNotBlank() }?.let { text ->
                        Text(
                            text = text,
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                runtime?.let { text ->
                    Text(
                        text = "🕒 $text",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                rating?.let { EpisodeRatingChip(rating = it) }
            }
        }

        if (isWatched) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(KBVoid.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Watched",
                    tint = KBAccent,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        if (unavailable) {
            Surface(
                shape = KBShapeChip,
                color = KBTextLo.copy(alpha = 0.35f),
                contentColor = KBTextHi,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
            ) {
                Text(
                    text = "UNAVAILABLE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }

        progress?.let { fraction ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(KBVoid.copy(alpha = 0.7f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(KBAccent)
                )
            }
        }
    }
}

/**
 * `42m`, or `12m left` while part-watched - the television episode card's own
 * runtime rule, shared so the list row and the rail cannot word it differently.
 *
 * Null when the episode has no runtime; the "left" form needs a fraction
 * strictly between 0 and 1, because a finished episode's total is the useful
 * number, not a zero-minute remainder.
 */
fun episodeRuntimeLabel(runtimeMinutes: Int?, progress: Float?): String? {
    val runtime = runtimeMinutes?.takeIf { it > 0 } ?: return null
    val fraction = progress?.takeIf { it > 0f && it < 1f } ?: return formatRuntimeMinutes(runtime)
    val remaining = (runtime * (1f - fraction)).toInt().coerceAtLeast(1)
    return "${formatRuntimeMinutes(remaining)} left"
}

/**
 * The yellow IMDb chip and score the television's episode card shows. One
 * composable rather than a copy in each layout, so the phone cannot end up with
 * two ratings that look different for the same episode.
 */
@Composable
fun EpisodeRatingChip(rating: Double, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = KBShapeChip,
            color = KBImdbTint
        ) {
            Text(
                text = "IMDb",
                color = Color.Black,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }
        Text(
            text = "%.1f".format(rating),
            color = KBTextHi,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** The `EPISODE n` chip the television's card carries over its frame. */
@Composable
private fun EpisodeNumberBadge(
    number: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(KBVoid.copy(alpha = 0.75f), KBShapeChip)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = "EPISODE $number",
            color = KBTextHi,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * The television card's frame: 260x170dp, the size its caption was laid out
 * for. A 16:9 still at this width is what the phone's rail already read as a
 * card, and the next episode still peeks at the edge of a 360dp screen - the
 * affordance that says the row scrolls sideways.
 */
private val EpisodeCardWidth = 260.dp
private val EpisodeCardHeight = 170.dp
