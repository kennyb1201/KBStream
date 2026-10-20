package com.kennyb1201.kbstream.ui.components.touch

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.spoiler.SpoilerFree
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
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
 * The one deliberate difference from the TV card is where the words go. The
 * television writes the episode number, name and synopsis over the frame,
 * which works on a 260x170dp card seen from a sofa; a phone card is narrow
 * enough that the same stack would cover the still it is describing, so the
 * still keeps only its badges and the caption sits beneath it. Everything the
 * TV card says is still said - number, name, synopsis, runtime, watched state -
 * and spoiler-free masking removes the name and synopsis here exactly as it
 * does from the list row.
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
 * One episode in the touch rail: the TV card's own information, with the
 * caption beneath the still instead of over it.
 *
 * The still carries the marks the list row puts on its own thumbnail - the
 * EPISODE number, the watched tick and the resume progress - plus the
 * UNAVAILABLE badge on an episode that has not aired, which dims the frame the
 * way the television's card does. None of them is re-derived here.
 *
 * [hiddenTitle] is the caller's spoiler-free answer: when true the name is
 * never used (not in the label, not in the accessibility description) and the
 * synopsis is dropped, which is what the vertical row does with the same flag.
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

    Column(
        modifier = modifier
            .width(EpisodeCardWidth)
            .clip(KBShapeCard)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(KBShapeCard)
                .background(KBSurface)
        ) {
            AsyncImage(
                model = episode.thumbnail,
                // The caption below names the episode; an announcement from the
                // frame as well would read the title twice.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // An unaired episode is dimmed, not only badged: the badge says
                // what the frame cannot.
                alpha = if (unavailable) 0.45f else 1f,
                modifier = Modifier.fillMaxSize()
            )

            EpisodeNumberBadge(
                number = episode.episodeNumber,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
            )

            if (isWatched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
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
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = 9.dp)
                        .background(KBVoid.copy(alpha = 0.78f), KBShapeChip)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "UNAVAILABLE",
                        color = KBTextHi,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
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

        Column(
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                color = KBTextHi,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!hiddenTitle) {
                episode.overview?.takeIf { it.isNotBlank() }?.let { text ->
                    Text(
                        text = text,
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                episode.runtimeMinutes?.takeIf { it > 0 }?.let { minutes ->
                    Text(
                        text = "$minutes min",
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
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
 * How wide one card is: a 16:9 still at the size the phone's season rail reads
 * as a card rather than a thumbnail, and narrow enough that the next episode is
 * visible at the edge of a 360dp screen - which is the affordance that says the
 * row scrolls sideways.
 */
private val EpisodeCardWidth = 220.dp
