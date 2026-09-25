package com.kennyb1201.kbstream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * How far into the runtime an end-of-episode panel opens: a label, a line
 * explaining what the point means, and the control. Shared by the Up Next card
 * and the Because-you-watched row, so the two control rows - and the band they
 * offer - cannot drift apart.
 *
 * Two rows, because 1% of a short episode is a jump the viewer can feel: the
 * quick jumps (99/98/97/95/90) stay for getting there fast, and the fine row
 * moves the point in 0.5 steps from wherever it is. Both write the same pref, so
 * they are two ways to reach one value rather than two settings.
 *
 * The chip itself is passed in by the caller rather than drawn here: it is the
 * settings screen's own PillChip, and its call sites are the only place that can
 * reach it. This file exists because SettingsScreen.kt is past the point where
 * this project's tooling can edit it in place (the same reason the players'
 * panels live in files of their own), so drawing a chip here would mean a second
 * definition of a pill that has to look like every other one on the screen.
 *
 * [selected] (and [onPick]) speak in TENTHS of a percent - the unit the pref is
 * stored in, so a 0.5 step is exactly [AppPreferences.END_PANEL_POINT_STEP_TENTHS].
 *
 * Greyed out while the panel it configures is switched off: a point that
 * nothing opens at means nothing.
 */
@Composable
internal fun EndPanelPointRow(
    label: String,
    hint: String,
    selected: Int,
    enabled: Boolean,
    chip: @Composable (label: String, selected: Boolean) -> Unit,
    onPick: (Int) -> Unit
) {
    val dim = if (enabled) 1f else 0.35f
    Text(
        text = label,
        color = if (enabled) KBTextHi else KBTextLo,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.alpha(dim)
    ) {
        AppPreferences.END_PANEL_PERCENT_OPTIONS.forEach { percent ->
            val tenths = percent * 10
            KBCard(onClick = { if (enabled) onPick(tenths) }) {
                chip("${percent}%", enabled && selected == tenths)
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(top = 8.dp)
            .alpha(dim)
    ) {
        KBCard(onClick = {
            if (enabled) onPick(AppPreferences.stepEndPanelPoint(selected, -1))
        }) {
            chip("-0.5", false)
        }
        Text(
            text = AppPreferences.endPanelPointLabel(selected),
            color = if (enabled) KBTextHi else KBTextLo,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(64.dp)
        )
        KBCard(onClick = {
            if (enabled) onPick(AppPreferences.stepEndPanelPoint(selected, 1))
        }) {
            chip("+0.5", false)
        }
    }
    Text(
        text = hint,
        color = KBTextLo,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .padding(top = 4.dp)
            .alpha(dim)
    )
}
