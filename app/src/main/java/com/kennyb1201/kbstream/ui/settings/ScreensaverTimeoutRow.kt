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
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * How long TV Home sits idle before the screensaver takes the screen over.
 *
 * The wait is a preference rather than a constant for the same reason the hero
 * trailer's dwell is: "idle" is the viewer's own idea of it, and someone who
 * wants the overlay gone from the room entirely will pick the longest wait
 * rather than reach for the switch. The switch still exists; this only decides
 * when it fires.
 *
 * The chips are passed in by the caller rather than drawn here: this is the
 * settings screen's own `PillChip`, and its call sites are the only place that
 * can reach it (the same arrangement as HeroTrailerDelayRow, and the reason
 * this row is a file of its own rather than another block in
 * SettingsScreen.kt).
 *
 * Dimmed while the screensaver is switched off: a wait nothing waits for means
 * nothing.
 */
@Composable
internal fun ScreensaverTimeoutRow(
    selectedMinutes: Int,
    enabled: Boolean,
    chip: @Composable (label: String, selected: Boolean) -> Unit,
    onPick: (Int) -> Unit
) {
    val dim = if (enabled) 1f else 0.35f
    Text(
        text = "Screensaver After",
        color = if (enabled) KBTextHi else KBTextLo,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.alpha(dim)
    ) {
        AppPreferences.SCREENSAVER_TIMEOUT_OPTIONS_MINUTES.forEach { minutes ->
            KBCard(onClick = { if (enabled) onPick(minutes) }) {
                chip(
                    AppPreferences.screensaverTimeoutLabel(minutes),
                    enabled && selectedMinutes == minutes
                )
            }
        }
    }
    Text(
        text = "How long Home must sit untouched before the screensaver takes the " +
            "screen. Any press on the remote brings Home straight back, exactly where " +
            "it was. Trailers stop while it is up, and a title without both a backdrop " +
            "and a logo is skipped rather than shown as a poster.",
        color = KBTextLo,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .padding(top = 4.dp)
            .alpha(dim)
    )
}
