package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.kennyb1201.kbstream.data.tmdb.TmdbGenre
import com.kennyb1201.kbstream.ui.theme.KBAccent
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

@Composable
private fun DiscoverFilterChip(
    label: String,
    selected: Boolean,
    focusedInitial: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(focusedInitial) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    selected -> KBAccent.copy(alpha = 0.28f)
                    focused -> KBSurfaceRaised
                    else -> KBSurface
                }
            )
            .border(
                1.dp,
                when {
                    selected -> KBAccent
                    focused -> KBTextHi
                    else -> KBTextLo.copy(alpha = 0.35f)
                },
                RoundedCornerShape(14.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) KBAccent else KBTextLo
        )
    }
}
