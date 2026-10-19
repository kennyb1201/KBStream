package com.kennyb1201.kbstream.ui.components.touch

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.ui.theme.CardShape
import com.kennyb1201.kbstream.ui.theme.KBFocusCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi

/**
 * The touch twin of `ui/components/KBCard.kt`: the app's one tappable plate,
 * built on a plain Material 3 [Card] with `combinedClickable`.
 *
 * Why a twin at all, rather than a flag inside the TV card: a tv-material3
 * `Card` is a *focus* surface. It moves and glows when a D-pad crosses it,
 * takes Select/Enter through `onPreviewKeyEvent`, and deliberately does not
 * ripple - none of which means anything to a finger. A phone wants the
 * opposite: a pressed-state ripple that starts where the thumb landed, plus a
 * long press for the actions the TV puts on the remote's center key (image
 * overrides, the poster context menu).
 *
 * The two behaviours the touch card keeps from TV on purpose:
 *
 *  - **Long press is the secondary action.** `combinedClickable` gives it the
 *    platform's hold threshold and haptics, so the long-press features the TV
 *    reaches through its own 450ms center-key clock are reachable here without
 *    a new gesture.
 *  - **The plate looks the same.** Same [KBSurface] fill, same [KBTextHi]
 *    content colour, same corner radius, so a screen ported to touch does not
 *    change character.
 *
 * [focusedScale] is accepted and ignored: it exists only so a mobile call site
 * reads exactly like its TV counterpart (see the port's "same API surface"
 * rule). There is no focus scale on touch; the press-in below is the
 * equivalent feedback.
 */
@Composable
fun KBCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") focusedScale: Float = KBFocusCard,
    shape: Shape = CardShape,
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    val pressed by interactionSource.collectIsPressedAsState()
    // The TV card pulls back to KBFocusPressed under a remote press. A finger
    // covers the ripple, so the same idea (the plate gives way) is what makes
    // the tap feel physical - a shallower scale, because a phone card is
    // already the whole width of the item.
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        label = "touchCardPress"
    )

    Card(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = indication,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = KBSurface,
            contentColor = KBTextHi
        ),
        // Flat: this app has no shadow language, and a Material elevation
        // shadow around a poster tile is a visible seam against the void.
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}
