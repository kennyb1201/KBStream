package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import com.kennyb1201.kbstream.ui.theme.CardShape
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBFocusCard
import com.kennyb1201.kbstream.ui.theme.KBFocusGlow
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import kotlinx.coroutines.delay

/**
 * Minimum hold duration (ms) between KeyDown and KeyUp for Select/Enter to
 * count as a long press. Measured by wall-clock time rather than the OS's
 * key-repeat mechanism, because many TV remotes / emulators / ADB-injected
 * key events never emit repeat KeyDown events at all - they send a single
 * KeyDown then KeyUp regardless of how long the physical button was held.
 * Timestamp-based detection works no matter how the platform reports it.
 */
private const val LONG_PRESS_THRESHOLD_MS = 450L

/**
 * How long a card must hold the focus before its glow is worth painting.
 *
 * The glow is a BLURRED SHADOW behind the card, re-rasterized on every frame of
 * the focus-scale animation. On a TV, scrolling IS focus movement: a held D-pad
 * moves focus every 100-150 ms, so a rail walk asked for one full-screen blur
 * per card per step, at 3840×2160 on the boxes the 2026-11-14 scrolling spec was
 * written on (see F4). Scale-only at first is nearly free and reads the same at
 * a glance; the glow is what a viewer looks at once the focus has STOPPED, so
 * it is deferred to then.
 *
 * 220 ms: longer than a repeat press, shorter than a viewer's pause. Purposeful
 * stopped focus keeps today's look, one fifth of a second late.
 */
private const val FOCUS_GLOW_SETTLE_MS = 220L

@Composable
fun KBCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    // Which step of the shared focus scale this surface is (see KBFocus* in the
    // theme). The default is the card step; a surface that is much wider or
    // taller than a tile -- a full-width list or guide row -- passes its own
    // class so that growing it does not shove the rows around it.
    focusedScale: Float = KBFocusCard,
    // The corner shape, and the shape the focused border follows. Defaults to
    // the app's card shape; a poster tile passes the viewer's chosen edge
    // (see PosterEdge) so its artwork and its focus ring stay in step.
    shape: Shape = CardShape,
    content: @Composable ColumnScope.() -> Unit
) {
    // Timestamp of the most recent "fresh" KeyDown (repeatCount == 0), or 0L
    // when no Select/Enter press is armed. The sentinel is the point: a KeyUp
    // that arrives WITHOUT its own KeyDown - the press began before this card
    // composed, or focus moved onto the card while the button was already down
    // - used to be measured against this 0L, read as "held since the epoch",
    // and fired onLongClick on a press the viewer never held. Cleared again on
    // KeyUp, so one press yields exactly one verdict.
    var pressStartTime by remember { mutableLongStateOf(0L) }
    // Whether the current press has already fired onLongClick, so the
    // matching KeyUp can be swallowed instead of also firing onClick.
    var longPressTriggered by remember { mutableStateOf(false) }

    // F4 of the 2026-11-14 scrolling spec: the glow waits for the focus to
    // SETTLE. While a D-pad walk is moving focus from card to card, this flag is
    // false at every step, so the blurred shadow is never rasterized for a card
    // the viewer is about to leave - the scale (cheap, transform-only) still
    // animates, which is the beat the walk reads. 220 ms after the focus stops,
    // the glow arms and the card looks exactly as it always did at rest.
    var focused by remember { mutableStateOf(false) }
    var glowArmed by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (!focused) {
            // Blurring disarms immediately: a card that loses focus must not
            // keep a shadow it is no longer earning.
            glowArmed = false
            return@LaunchedEffect
        }
        delay(FOCUS_GLOW_SETTLE_MS)
        glowArmed = true
    }

    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = KBSurface,
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            // Focus is spoken by the ring, the lift and the glow - not by the
            // label. Turning the text accent-colored on focus made a card's
            // own copy change color every time the D-pad crossed it, and it
            // left accent-on-accent cards (a chip already tinted accent)
            // unreadable while selected. Content stays bright in both states.
            focusedContentColor = KBTextHi
        ),
        // Focus grows the tile and lifts it; press pulls it back toward the
        // plate. Every poster, tile and button that goes through KBCard gets
        // the same two beats for free — see the KBFocus* scale in the theme.
        scale = CardDefaults.scale(
            scale = 1f,
            focusedScale = focusedScale,
            pressedScale = KBFocusPressed
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, KBAccent),
                shape = shape
            )
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(
                elevationColor = KBAccent,
                // Zero elevation draws no shadow at all, which is the whole
                // point: the deferred half of the focus treatment costs
                // nothing until the focus has settled (see
                // [FOCUS_GLOW_SETTLE_MS]).
                elevation = if (glowArmed) KBFocusGlow else 0.dp
            )
        ),
        // onPreviewKeyEvent runs on the way DOWN the tree, before Card's own
        // internal clickable sees the event, so we get first refusal on it.
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
            val isSelectKey =
                event.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    event.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_ENTER

            if (onLongClick == null || !isSelectKey) return@onPreviewKeyEvent false

            when (event.type) {
                KeyEventType.KeyDown -> {
                    // Only reset the timer on the initial press, not on any
                    // OS-generated repeat events (if the platform sends them).
                    if (event.nativeKeyEvent.repeatCount == 0) {
                        pressStartTime = System.currentTimeMillis()
                        longPressTriggered = false
                    }
                    // Never consume KeyDown - let focus/ripple behave normally.
                    false
                }

                KeyEventType.KeyUp -> {
                    if (pressStartTime == 0L) return@onPreviewKeyEvent false
                    val heldMs = System.currentTimeMillis() - pressStartTime
                    pressStartTime = 0L
                    if (heldMs >= LONG_PRESS_THRESHOLD_MS) {
                        longPressTriggered = true
                        onLongClick()
                        true // swallow so Card doesn't also fire onClick
                    } else {
                        false // short press - let Card handle its own onClick
                    }
                }

                else -> false
            }
        },
        content = content
    )
}

/**
 * Long-press detection for surfaces that cannot use [KBCard] — the search
 * browse chips, whose border/colors differ from the shared card. Returns a
 * modifier that fires [onLongClick] once Select/Enter has been held past
 * [LONG_PRESS_THRESHOLD_MS], and swallows the matching KeyUp so the
 * surface's own onClick does not also fire. Timestamp-based for the same
 * reason as [KBCard]: many TV remotes never emit repeat KeyDown events, so
 * key-repeat cannot be used to measure the hold.
 */
@Composable
fun rememberLongPressModifier(onLongClick: (() -> Unit)?): Modifier {
    // 0L = no press armed, for the same reason [KBCard]'s own timestamp has one:
    // a KeyUp whose KeyDown this surface never saw must not be read as a hold
    // since the epoch, and must not fire onLongClick.
    var pressStartTime by remember { mutableLongStateOf(0L) }

    return Modifier.onPreviewKeyEvent { event ->
        val isSelectKey =
            event.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                event.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_ENTER

        if (onLongClick == null || !isSelectKey) return@onPreviewKeyEvent false

        when (event.type) {
            KeyEventType.KeyDown -> {
                if (event.nativeKeyEvent.repeatCount == 0) {
                    pressStartTime = System.currentTimeMillis()
                }
                // Never consume KeyDown - focus/ripple behave normally.
                false
            }

            KeyEventType.KeyUp -> {
                if (pressStartTime == 0L) return@onPreviewKeyEvent false
                val heldMs = System.currentTimeMillis() - pressStartTime
                pressStartTime = 0L
                if (heldMs >= LONG_PRESS_THRESHOLD_MS) {
                    onLongClick()
                    true // swallow so the surface does not also fire onClick
                } else {
                    false // short press - let the surface handle its onClick
                }
            }

            else -> false
        }
    }
}
