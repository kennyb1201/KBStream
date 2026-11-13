package com.kennyb1201.kbstream.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.data.tvdb.TvdbConfig
import com.kennyb1201.kbstream.data.tvdb.TvdbOrderOption
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * The per-show `Episode order` control (spec 2026-11-13, F2), for both screens.
 *
 * One row of choices, the aired order first, and nothing at all when the show has
 * no alternate order: S2's "no picker is offered" is a property of this
 * composable rather than a decision each screen makes, so an empty or
 * single-entry list draws nothing.
 *
 * It draws NO data of its own. The numbers on the episode list are where the
 * choice shows; this row only says which numbering they are in, and picking one
 * re-resolves the season in view (see DetailViewModel.selectEpisodeOrder), so the
 * change is visible without a restart.
 *
 * The attribution line is a licensing condition, not decoration: TVDB requires
 * "attribution with a direct link to TheTVDB.com ... to end users viewing
 * metadata from our API" (api-information, read 2026-11-13), and this row is the
 * one place the app shows TVDB-sourced metadata.
 */
@Composable
internal fun EpisodeOrderSection(
    options: List<TvdbOrderOption>,
    selected: String?,
    busy: Boolean,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    // One choice is not a choice: a show with no alternate order gets no row.
    if (options.size <= 1) return

    val uriHandler = LocalUriHandler.current

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = if (busy) "EPISODE ORDER · LOADING" else "EPISODE ORDER",
            color = KBTextLo,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items = options, key = { option -> option.type.ifEmpty { "official" } }) { option ->
                val isSelected = if (option.official) {
                    selected.isNullOrBlank()
                } else {
                    selected == option.type
                }
                OrderChip(
                    label = option.label.uppercase(),
                    selected = isSelected,
                    enabled = !busy,
                    onClick = { onSelect(option.type.ifEmpty { null }) }
                )
            }
        }
        Text(
            text = TvdbConfig.ATTRIBUTION,
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 10.dp)
                .clickable {
                    runCatching { uriHandler.openUri(TvdbConfig.ATTRIBUTION_URL) }
                }
        )
    }
}

@Composable
private fun OrderChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(
                if (selected) KBAccent.copy(alpha = 0.18f) else KBSurfaceRaised.copy(alpha = 0.92f),
                KBShapeCard
            )
            .border(
                1.dp,
                if (selected) KBAccent else KBTextLo.copy(alpha = 0.35f),
                KBShapeCard
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = if (selected) KBAccent else KBTextHi,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
