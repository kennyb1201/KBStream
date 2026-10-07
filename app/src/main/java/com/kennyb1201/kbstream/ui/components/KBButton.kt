package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * The app's one button.
 *
 * A [KBCard] whose label is drawn in the button style - `labelLarge`, SemiBold,
 * 16x9 padding, ALL-CAPS copy (the caller's job) - so a button reads as a
 * button wherever it is: the settings pane, the profile editor, a guide dialog
 * row. Disabled renders a dimmed, non-focusable plate instead, because a
 * disabled control that still takes D-pad focus is a press that does nothing.
 *
 * Three private copies of this had grown up (SyncSection's SyncActionButton,
 * ProfileEditScreen's ProfileActionButton, and the guide dialogs' hand-rolled
 * rows), each with its own label style and padding, which is why the same
 * "cancel" was 16x9 in one place and 16x12 in another.
 *
 * [danger] tints the label [KBDanger] for a destructive action, the same
 * affordance PosterContextMenu's destructive rows use.
 */
@Composable
fun KBButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    // Last, so a call site can hand the press to a trailing lambda - which is
    // how every button in the app is written (`KBButton(label = "SAVE") { … }`)
    // and keeps an action's body inside its own braces.
    onClick: () -> Unit
) {
    if (!enabled) {
        Surface(
            modifier = modifier,
            shape = KBShapeCard,
            colors = SurfaceDefaults.colors(
                containerColor = KBSurface.copy(alpha = 0.50f),
                contentColor = KBTextLo.copy(alpha = 0.50f)
            )
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = KBTextLo.copy(alpha = 0.50f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }
        return
    }

    KBCard(onClick = onClick, modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (danger) KBDanger else Color.Unspecified,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
        )
    }
}
