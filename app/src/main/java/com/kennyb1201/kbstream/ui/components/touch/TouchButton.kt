package com.kennyb1201.kbstream.ui.components.touch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * The touch twin of `ui/components/KBButton.kt`: the app's one button, as a
 * Material 3 [Button] on the raised plate.
 *
 * The TV button is a [KBCard] with a label in it, because a tv-material3 card
 * is the only surface that already knows how to be focused and pressed on a
 * remote. A phone has a real button control, so this is one: the same
 * `labelLarge` / SemiBold / ALL-CAPS label, the same 16x9 inset, the same
 * raised-plate fill, with the pressed state and the 48dp minimum touch target
 * the platform gives a finger.
 *
 * [danger] tints the label [KBDanger] without filling the plate, which is the
 * same affordance the TV button and [ui.components.PosterContextMenu]'s
 * destructive rows use: a destructive action is not louder than the others
 * until it is pressed.
 *
 * Disabled renders a dimmed, non-focusable button instead of a live one,
 * mirroring the TV twin: a control that still accepts a tap while disabled is
 * a press that does nothing.
 */
@Composable
fun KBButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    // Last, so a call site can hand the press to a trailing lambda - which is
    // how every button in the app is written (`KBButton(label = "SAVE") { … }`).
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = KBShapeCard,
        colors = ButtonDefaults.buttonColors(
            containerColor = KBSurfaceRaised,
            contentColor = if (danger) KBDanger else KBTextHi,
            disabledContainerColor = KBSurface.copy(alpha = 0.5f),
            disabledContentColor = KBTextLo.copy(alpha = 0.5f)
        ),
        // The resting edge the TV surface gets from its focus ring: without it
        // a raised plate on the void is a rectangle with no outline.
        border = BorderStroke(
            width = 1.dp,
            color = if (danger) {
                KBDanger.copy(alpha = 0.55f)
            } else {
                KBTextLo.copy(alpha = 0.22f)
            }
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.Unspecified
        )
    }
}
