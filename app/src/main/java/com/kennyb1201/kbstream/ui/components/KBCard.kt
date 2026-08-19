package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
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
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi

/**
 * Number of key-repeat events (KeyDown with repeatCount > 0) required before
 * a held Select/Enter press is treated as a long press. Android's key-repeat
 * rate varies by device, but ~4 repeats lands around 400-500ms of holding,
 * which feels right for a TV remote long-press gesture. Tune if needed.
 */
private const val LONG_PRESS_REPEAT_THRESHOLD = 4

@Composable
fun KBCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // Tracks whether the current press-and-hold has already fired onLongClick,
    // so we know to swallow the matching KeyUp and not also trigger onClick.
    var longPressTriggered by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = CardShape),
        colors = CardDefaults.colors(
            containerColor = KBSurface,
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBAccent
        ),
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1.08f),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, KBAccent),
                shape = CardShape
            )
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(elevationColor = KBAccent, elevation = 12.dp)
        ),
        // onPreviewKeyEvent runs on the way DOWN the tree, before Card's own
        // internal clickable sees the event. That lets us intercept the
        // Select/Enter key and decide whether this is a long press before
        // Card's default click handling gets a chance to fire onClick.
        modifier = modifier.onPreviewKeyEvent { event ->
            val isSelectKey =
                event.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    event.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_ENTER

            if (onLongClick == null || !isSelectKey) return@onPreviewKeyEvent false

            when (event.type) {
                KeyEventType.KeyDown -> {
                    val repeatCount = event.nativeKeyEvent.repeatCount
                    if (repeatCount == 0) {
                        // Fresh press starting - reset state.
                        longPressTriggered = false
                    } else if (!longPressTriggered && repeatCount >= LONG_PRESS_REPEAT_THRESHOLD) {
                        // Held long enough - fire the long-press action once.
                        longPressTriggered = true
                        onLongClick()
                    }
                    // Consume the event once we've triggered, so it doesn't
                    // continue on to Card's internal click handling.
                    longPressTriggered
                }

                KeyEventType.KeyUp -> {
                    if (longPressTriggered) {
                        // Swallow the release that follows a long press so
                        // Card doesn't also fire a normal onClick.
                        true
                    } else {
                        // Short press - let it fall through to Card's onClick.
                        false
                    }
                }

                else -> false
            }
        },
        content = content
    )
}
