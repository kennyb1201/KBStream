package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 * Minimum hold duration (ms) between KeyDown and KeyUp for Select/Enter to
 * count as a long press. Measured by wall-clock time rather than the OS's
 * key-repeat mechanism, because many TV remotes / emulators / ADB-injected
 * key events never emit repeat KeyDown events at all - they send a single
 * KeyDown then KeyUp regardless of how long the physical button was held.
 * Timestamp-based detection works no matter how the platform reports it.
 */
private const val LONG_PRESS_THRESHOLD_MS = 450L

@Composable
fun KBCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // Timestamp of the most recent "fresh" KeyDown (repeatCount == 0).
    var pressStartTime by remember { mutableLongStateOf(0L) }
    // Whether the current press has already fired onLongClick, so the
    // matching KeyUp can be swallowed instead of also firing onClick.
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
        // internal clickable sees the event, so we get first refusal on it.
        modifier = modifier.onPreviewKeyEvent { event ->
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
                    val heldMs = System.currentTimeMillis() - pressStartTime
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
