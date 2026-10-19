package com.kennyb1201.kbstream.ui.components.touch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.tmdb.TmdbGenre
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * The touch twin of `ui/components/GenreChipRow.kt`: the discover screens'
 * horizontally scrolling genre filter.
 *
 * Same selection model as the TV row, and the same visual language (accent
 * wash plus accent label for the active chip), rebuilt on a Material 3
 * [FilterChip]:
 *
 *  - the TV row needed a 16dp inset dance (`KBFocusChipInset`) because a lazy
 *    row clips its own content and the focused chip *grows*; a touch chip does
 *    not grow, so the row is just the chips, and the caller's own padding
 *    applies;
 *  - a horizontal LazyRow is the right control for a finger as well - a phone
 *    has no D-pad to cross a wrapped row, and thumb-scrolling a filter strip is
 *    the expected gesture;
 *  - selected state is carried by `FilterChip.selected`'s semantics, so a
 *    screen reader announces "selected" instead of leaving the state to colour
 *    alone (which is all the TV chip could do).
 */
@Composable
fun GenreChipRow(
    genres: List<TmdbGenre>,
    selectedGenreId: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        item {
            DiscoverFilterChip(
                label = "All",
                selected = selectedGenreId == null,
                onClick = { onSelect(null) }
            )
        }
        items(genres, key = { it.id }) { genre ->
            DiscoverFilterChip(
                label = genre.name,
                selected = selectedGenreId == genre.id,
                onClick = { onSelect(genre.id) }
            )
        }
    }
}

/**
 * One genre chip.
 *
 * The active chip keeps the TV pair exactly: a 28% accent wash under an accent
 * label. It is not the Material 3 default (a filled secondary container) on
 * purpose - the filter strip is a set of small controls on the void, and a
 * filled chip at full accent is louder than the screen title.
 */
@Composable
private fun DiscoverFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                // A chip is one line by definition - a squeezed chip is what
                // produced the vertical stack of single letters on TV.
                maxLines = 1,
                softWrap = false
            )
        },
        shape = KBShapeCard,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = KBSurface,
            labelColor = KBTextLo,
            selectedContainerColor = KBAccent.copy(alpha = 0.28f),
            selectedLabelColor = KBAccent
        ),
        modifier = Modifier.height(40.dp)
    )
}

/**
 * A selectable pill for the touch screens: the season row, the Media tabs, the
 * search suggestions and the browse category strip.
 *
 * A Material 3 `FilterChip` cannot carry a long press, and two of those callers
 * need one (the season menu, and the browse chip's Add-to-Home/Hide menu), so
 * this is the same visual pair - a surface fill with a 28% accent wash and an
 * accent label when selected - built on [combinedClickable]. A chip with no
 * long-press handler is an ordinary tap target.
 *
 * It lives here rather than in each screen because three screens now draw it
 * and a fourth set of drift would end with one of them looking unlike the rest
 * of the app.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TouchChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    Surface(
        shape = KBShapeCard,
        color = if (selected) KBAccent.copy(alpha = 0.28f) else KBSurface,
        contentColor = if (selected) KBAccent else KBTextLo,
        modifier = modifier
            .height(40.dp)
            .clip(KBShapeCard)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * The touch twin of `ui/components/StudioChip.kt`: a network/company logo on
 * its white plate.
 *
 * The plate stays white because the logos are drawn for a light background (see
 * `BrandMarkImage` for the dark-surface counterpart); inverting it here would
 * make half of TMDB's catalogue unreadable. [onLongClick] is kept - it is the
 * detail screen's "add this network to Home" menu, and a long press is the
 * touch equivalent of the TV's center-key hold.
 */
@Composable
fun StudioChip(
    name: String,
    logoPath: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val shape = KBShapeChip

    Surface(
        shape = shape,
        color = Color.White,
        contentColor = Color.Black,
        border = BorderStroke(1.dp, KBTextHi.copy(alpha = 0.35f)),
        modifier = modifier
            .width(120.dp)
            .height(54.dp)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(end = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (logoPath != null) {
                AsyncImage(
                    model = TmdbRepository.LOGO_BASE + logoPath,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                )
            } else {
                Text(
                    text = name,
                    color = Color.Black,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
