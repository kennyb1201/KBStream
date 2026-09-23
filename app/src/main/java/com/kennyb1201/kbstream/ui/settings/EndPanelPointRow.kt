package com.kennyb1201.kbstream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * How far into the runtime an end-of-episode panel opens: a label, a line
 * explaining what the point means, and the percentage chips. Shared by the Up
 * Next card and the Because-you-watched row, so the two control rows - and the
 * band they offer - cannot drift apart.
 *
 * The chip itself is passed in by the caller rather than drawn here: it is the
 * settings screen's own PillChip, and its call sites are the only place that can
 * reach it. This file exists because SettingsScreen.kt is past the point where
 * this project's tooling can edit it in place (the same reason the players'
 * panels live in files of their own), so drawing a chip here would mean a second
 * definition of a pill that has to look like every other one on the screen.
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
    Text(
        text = label,
        color = if (enabled) KBTextHi else KBTextLo,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.alpha(if (enabled) 1f else 0.35f)
    ) {
        AppPreferences.END_PANEL_PERCENT_OPTIONS.forEach { percent ->
            KBCard(onClick = { if (enabled) onPick(percent) }) {
                chip("${percent}%", enabled && selected == percent)
            }
        }
    }
    Text(
        text = hint,
        color = KBTextLo,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .padding(top = 4.dp)
            .alpha(if (enabled) 1f else 0.35f)
    )
}
