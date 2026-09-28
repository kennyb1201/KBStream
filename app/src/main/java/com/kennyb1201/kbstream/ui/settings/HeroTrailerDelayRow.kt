package com.kennyb1201.kbstream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * How long the focus has to rest on a title before the hero starts the trailer.
 *
 * The dwell is why the hero can autoplay at all: it is what separates "the
 * viewer stopped on this title" from "the focus passed through it", so the
 * trailer is only ever armed for a stop. That makes it a preference rather than
 * a constant — how long a stop takes is the viewer's own, and someone who finds
 * trailers starting while they browse wants a LONGER wait, not the toggle off.
 *
 * The chips are passed in by the caller rather than drawn here: this is the
 * settings screen's own PillChip, and its call sites are the only place that can
 * reach it. This file exists for the same reason [EndPanelPointRow] does -
 * SettingsScreen.kt is past the point where this project's tooling can edit it
 * in place, so a row added there would have to be written as a second definition
 * of a pill that has to look like every other one on the screen.
 *
 * Greyed out while hero trailer autoplay is switched off: a wait that nothing
 * waits for means nothing.
 */
@Composable
internal fun HeroTrailerDelayRow(
    selectedMs: Long,
    enabled: Boolean,
    chip: @Composable (label: String, selected: Boolean) -> Unit,
    onPick: (Long) -> Unit
) {
    val dim = if (enabled) 1f else 0.35f
    Text(
        text = "Hero Trailer Delay",
        color = if (enabled) KBTextHi else KBTextLo,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.alpha(dim)
    ) {
        AppPreferences.HERO_TRAILER_DELAY_OPTIONS_MS.forEach { ms ->
            KBCard(onClick = { if (enabled) onPick(ms) }) {
                chip(AppPreferences.heroTrailerDelayLabel(ms), enabled && selectedMs == ms)
            }
        }
    }
    Text(
        text = "How long focus must rest on a title before the hero starts its trailer, on " +
            "Home and in a KB folder. A longer wait means scrolling a rail never starts one " +
            "by accident. \"Now\" starts it the moment a title is focused — and follows " +
            "every title the focus touches.",
        color = KBTextLo,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .padding(top = 4.dp)
            .alpha(dim)
    )
}
