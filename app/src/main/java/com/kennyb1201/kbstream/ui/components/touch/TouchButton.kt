package com.kennyb1201.kbstream.ui.components.touch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
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
 *
 * [onLongClick] is opt-in, because most buttons have nothing to say on a hold.
 * It exists for the one control whose TV twin's secondary action is a hold -
 * the detail page's Play button, which opens the streams picker here (see
 * [touchLongPress]).
 */
@Composable
fun KBButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    // Last, so a call site can hand the press to a trailing lambda - which is
    // how every button in the app is written (`KBButton(label = "SAVE") { … }`).
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 48.dp)
            // A disabled button takes no press at all, so it must not take a
            // hold either: no menu from a control the viewer cannot tap.
            .touchLongPress(if (enabled) onLongClick else null),
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

/**
 * A held finger on a surface that already owns its tap.
 *
 * [KBCard]'s touch twin gets this from `combinedClickable`, which is the right
 * answer wherever the card owns BOTH gestures. A Material [Button] owns its tap
 * inside its own implementation, so the hold has to be measured above it, in
 * the `Initial` pointer pass - the one pass that reaches a parent before the
 * button's internal clickable sees anything. Three things fall out of that:
 *
 *  - press and release before the platform's long-press timeout is left
 *    completely alone (nothing is consumed), so a tap still ripples and still
 *    fires the button's own `onClick`;
 *  - a hold that reaches the timeout fires [onLongClick] and consumes the
 *    release, so the button's clickable sees an already-consumed up and drops
 *    its tap - the press was the hold, not a tap, and the row must not do both;
 *  - a drag past `touchSlop` (a scroll starting on the button) abandons the
 *    hold, so flicking a list from a button cannot trigger its menu.
 *
 * The timeouts come from [androidx.compose.ui.input.pointer.AwaitPointerEventScope]'s
 * own `withTimeoutOrNull`, the same clock `combinedClickable` uses, so the hold
 * threshold matches every other long press in the app. The callback is read
 * through `rememberUpdatedState` so a recomposition mid-hold cannot swap the
 * gesture out from under the finger.
 */
@Composable
private fun Modifier.touchLongPress(onLongClick: (() -> Unit)?): Modifier {
    val current = rememberUpdatedState(onLongClick)
    val haptics = LocalHapticFeedback.current
    if (onLongClick == null) return this

    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial
            )
            // null means the timeout elapsed while the finger was still down:
            // a hold. `true` means it left first - released (a tap) or dragged
            // past the slop (a scroll) - so this was never a hold.
            val releasedEarly: Boolean? =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var early = false
                    while (!early) {
                        val change = awaitPointerEvent(PointerEventPass.Initial)
                            .changes.firstOrNull { it.id == down.id }
                        early = change == null ||
                            !change.pressed ||
                            (change.position - down.position).getDistance() >
                            viewConfiguration.touchSlop
                    }
                    true
                }
            if (releasedEarly != null) return@awaitEachGesture

            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            current.value?.invoke()

            // Swallow the release (and anything left of the gesture) so the
            // button's own clickable cannot also fire its tap.
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial)
                    .changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    change.consume()
                    break
                }
            }
        }
    }
}
