package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.tmdb.TmdbGenre
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * A horizontally scrolling genre chip row for the discover screens
 * (services/networks/studios and decades). "All" clears the filter;
 * picking a genre re-runs the screen's rails with the genre ANDed onto the
 * screen's base dimension. Genre and keyword screens don't use it — the
 * genre screen already is a genre browse and keywords are too specific to
 * slice by genre. Mirrors the Library filter-chip visual style and
 * D-pad focus behavior (focus restores to the previously selected chip).
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
                focusedInitial = selectedGenreId == null,
                onClick = { onSelect(null) }
            )
        }
        items(genres, key = { it.id }) { genre ->
            DiscoverFilterChip(
                label = genre.name,
                selected = selectedGenreId == genre.id,
                focusedInitial = selectedGenreId == genre.id,
                onClick = { onSelect(genre.id) }
            )
        }
    }
}

/**
 * One genre chip.
 *
 * Built on the TV clickable [Surface] rather than a Box with
 * `.focusable().clickable()`: that stack was TWO focus targets, so a D-pad
 * press landed on the outer one and only the second press reached the
 * clickable — the "press twice to filter" behaviour. One Surface means
 * focus, activation and the focused colours are a single target (the same
 * pattern the Library filter chips and the Home rail list use).
 */
@Composable
private fun DiscoverFilterChip(
    label: String,
    selected: Boolean,
    focusedInitial: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(focusedInitial) }
    val shape = KBShapeCard

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                selected -> KBAccent.copy(alpha = 0.28f)
                focused -> KBSurfaceRaised
                else -> KBSurface
            },
            contentColor = when {
                selected -> KBAccent
                focused -> KBTextHi
                else -> KBTextLo
            }
        ),
        modifier = Modifier
            .clip(shape)
            .border(
                1.dp,
                when {
                    selected -> KBAccent
                    focused -> KBTextHi
                    else -> KBTextLo.copy(alpha = 0.35f)
                },
                shape
            )
            .onFocusChanged { focused = it.isFocused }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            // A pill is one line by definition — a squeezed chip is what
            // produced the vertical stack of single letters.
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
