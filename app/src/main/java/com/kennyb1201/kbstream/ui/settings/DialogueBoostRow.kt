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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.player.PlayerAudioTuning
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * The dialogue boost as a level rather than a set of choices.
 *
 * It used to be three pills - Off / Low / High - which is a poor fit for a knob
 * whose whole job is "a bit more, no, a bit less": the viewer hears the right
 * amount and has to pick the nearest of three. The pads step one level at a
 * time over [PlayerAudioTuning.DIALOGUE_MAX], and the level itself is what the
 * row shows, so the value on screen is the value being heard.
 *
 * The chips are passed in by the caller rather than drawn here: this is the
 * settings screen's own PillChip, and its call sites are the only place that can
 * reach it. This file exists for the same reason [EndPanelPointRow] does -
 * SettingsScreen.kt is past the point where this project's tooling can edit it
 * in place, so a row added there would have to be written as a second definition
 * of a pill that has to look like every other one on the screen.
 *
 * The players' own panels offer the same control with one extra step at the
 * bottom, "Global", which hands the level back to this setting for that title.
 */
@Composable
internal fun DialogueBoostRow(
    level: Int,
    onLevelChange: (Int) -> Unit,
    chip: @Composable (label: String, selected: Boolean) -> Unit
) {
    Text(
        text = "Dialogue Boost",
        color = KBTextHi,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        KBCard(onClick = { onLevelChange((level - 1).coerceAtLeast(0)) }) {
            chip("-", false)
        }
        Text(
            text = PlayerAudioTuning.dialogueLevelText(level),
            color = KBTextHi,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(96.dp)
        )
        KBCard(
            onClick = {
                onLevelChange((level + 1).coerceAtMost(PlayerAudioTuning.DIALOGUE_MAX))
            }
        ) {
            chip("+", false)
        }
    }
    Text(
        text = "Each step lifts voices (the centre channel, or the phantom centre of a stereo " +
            "track) further over score, ambience and explosions, and trims the surrounds to " +
            "match. Off is the untouched mix. A title can override this from the player's own " +
            "panel.",
        color = KBTextLo,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 4.dp)
    )
}
