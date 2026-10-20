package com.kennyb1201.kbstream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.settings.ResumeAuthority
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * Which tracker's resume point Continue Watching believes.
 *
 * The row lives in its own file for the same reason [HeroTrailerDelayRow] does:
 * SettingsScreen.kt is past the size this project's tooling can edit in place,
 * and the chips are the screen's own `PillChip`, which only the call site can
 * reach.
 *
 * The caption says the part that is easy to get wrong: choosing a source here
 * never stops the others from scrobbling or receiving watched marks. It only
 * decides whose position/episode a title shows when several sources know it.
 * The others remain the fallback for titles the chosen one has never seen, so
 * the rail does not empty.
 */
@Composable
internal fun ResumeAuthorityRow(
    selected: ResumeAuthority,
    chip: @Composable (label: String, selected: Boolean) -> Unit,
    onPick: (ResumeAuthority) -> Unit
) {
    Text(
        text = "Resume Source",
        color = KBTextHi,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ResumeAuthority.entries.forEach { authority ->
            KBCard(onClick = { onPick(authority) }) {
                chip(authority.label, authority == selected)
            }
        }
    }
    Text(
        text = "Which tracker's position Continue Watching shows when more than one " +
            "knows a title. Every linked tracker still scrobbles and gets your " +
            "watched marks; the others fill in titles this one has not seen.",
        color = KBTextLo,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp)
    )
}
