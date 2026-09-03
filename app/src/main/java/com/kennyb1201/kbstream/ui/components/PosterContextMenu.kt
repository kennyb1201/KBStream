package com.kennyb1201.kbstream.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * One selectable row in a [PosterContextMenu]. The action itself is
 * responsible for dismissing the menu (the caller normally clears its
 * menu state first, then runs the action / restores focus).
 */
data class PosterContextAction(
    val label: String,
    val description: String? = null,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit
)

/**
 * Shared long-press overlay menu for poster cards on every screen (Home
 * rails, actor credits, studio/network rails, genre/keyword rails, ...).
 *
 * Rendered in-window on top of the caller's screen with a dim scrim, just
 * like the per-screen menus it replaced. Two things keep D-pad focus from
 * escaping into the rails behind the scrim:
 *
 *  1. The overlay is its own [focusGroup] - focus search performed from a
 *     focused menu row is bounded by the group, so directional presses
 *     cannot reach focusables under the scrim.
 *  2. Left/Right are swallowed outright (a vertical action list has no
 *     horizontal neighbors), so a stray press can never strand focus on an
 *     invisible item behind the menu - which previously forced users to
 *     press Back to recover.
 */
@Composable
fun PosterContextMenu(
    title: String,
    subtitle: String? = null,
    actions: List<PosterContextAction>,
    onDismiss: () -> Unit
) {
    val firstRowFocusRequester = remember {
        FocusRequester()
    }

    // Focus anchor for the overlay itself: a plain 1x1 focusable spacer that
    // is composed in the same frame as the scrim, so requesting focus on it
    // reliably pulls focus off the poster behind the overlay while the
    // action rows are still attaching. While it (or any row) holds focus,
    // the enclosing focusGroup bounds every directional press to the menu,
    // so the D-pad can never reach the dimmed rails behind it. Once the
    // first row reports focus the anchor stops being focusable so it cannot
    // trap edge navigation itself.
    val overlayFocusRequester = remember {
        FocusRequester()
    }

    // True once the first action row reports it actually holds focus. Used by
    // the LaunchedEffect below to keep re-requesting until focus really lands
    // inside the menu instead of assuming the very first request succeeded.
    var firstActionFocused by remember {
        mutableStateOf(false)
    }

    // True while the invisible overlay anchor holds focus (Phase 1 below).
    var overlayAnchorFocused by remember {
        mutableStateOf(false)
    }

    val dialogShape = RoundedCornerShape(20.dp)

    // Dismiss on system Back. BackHandler is used instead of key-event
    // intercepts because the Activity back dispatcher consumes the Back key
    // before it ever reaches compose key handlers.
    BackHandler {
        onDismiss()
    }

    LaunchedEffect(Unit) {
        if (actions.isEmpty()) {
            return@LaunchedEffect
        }

        // Phase 1: land focus inside the overlay immediately. The anchor
        // spacer is a plain focusable already attached to the composition,
        // so this reliably steals focus from the poster behind the scrim
        // within a frame or two (unlike the rows, whose focus tree attach
        // can race the first request).
        for (attempt in 1..10) {
            runCatching {
                overlayFocusRequester.requestFocus()
            }
            if (overlayAnchorFocused) {
                break
            }
            kotlinx.coroutines.delay(16L)
        }

        // Phase 2: move focus onto the first action row. The rows were just
        // added to the composition; if the request fires before the focus
        // tree has attached them it can silently no-op and leave focus on
        // the anchor (still safely inside the group). Retry until the first
        // row really is focused (or the menu is gone), so a D-pad press
        // right after opening can never escape the overlay.
        repeat(40) {
            runCatching {
                firstRowFocusRequester.requestFocus()
            }
            if (firstActionFocused) {
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(50L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
            .background(KBVoid.copy(alpha = 0.60f))
            .onPreviewKeyEvent { event ->
                // No horizontal focus target exists inside a vertical menu;
                // consuming Left/Right keeps focus trapped on the action
                // list instead of letting it wander behind the scrim.
                if (
                    event.type == KeyEventType.KeyDown &&
                    (
                        event.key == Key.DirectionLeft ||
                            event.key == Key.DirectionRight
                        )
                ) {
                    true
                } else if (
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.Back
                ) {
                    // Some TV platforms deliver BACK straight to the focused
                    // compose hierarchy instead of routing it through the
                    // Activity back dispatcher, which can make the menu look
                    // like it needs two presses to dismiss. Consume it here
                    // too so a single press always closes the menu; the
                    // BackHandler above remains the primary path and simply
                    // no-ops when this already dismissed it.
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Invisible focus anchor: gives the overlay a focused node the moment
        // it appears, so directional presses are bounded by the focusGroup
        // even during the frame(s) before the action rows attach. Drops out
        // of the focus tree once the first row has focus.
        Spacer(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(1.dp)
                .focusProperties {
                    canFocus = !firstActionFocused
                }
                .focusRequester(overlayFocusRequester)
                .onFocusChanged { focusState ->
                    overlayAnchorFocused =
                        focusState.isFocused
                }
        )

        Surface(
            onClick = onDismiss,
            shape = ClickableSurfaceDefaults.shape(
                shape = dialogShape
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = KBSurfaceRaised.copy(alpha = 0.97f),
                contentColor = KBTextHi,
                focusedContainerColor = KBSurfaceRaised.copy(alpha = 0.97f),
                focusedContentColor = KBTextHi
            ),
            scale = ClickableSurfaceDefaults.scale(
                scale = 1f,
                focusedScale = 1f
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(
                    border = BorderStroke(
                        1.dp,
                        KBTextLo.copy(alpha = 0.10f)
                    ),
                    shape = dialogShape
                ),
                focusedBorder = Border(
                    border = BorderStroke(
                        1.dp,
                        KBTextLo.copy(alpha = 0.10f)
                    ),
                    shape = dialogShape
                )
            ),
            modifier = Modifier
                .width(380.dp)
                // The dialog container is click-to-dismiss for pointer users
                // only; keeping it out of the focus search means pressing
                // Down/Up past the last/first action can never land D-pad
                // focus on the container itself.
                .focusProperties {
                    canFocus = false
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                Text(
                    text = title,
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                subtitle
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { sub ->
                        Text(
                            text = sub,
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                Spacer(
                    modifier = Modifier.height(16.dp)
                )

                actions.forEachIndexed { index, action ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    ContextMenuActionRow(
                        label = action.label,
                        isDestructive = action.isDestructive,
                        focusRequester = if (index == 0) {
                            firstRowFocusRequester
                        } else {
                            null
                        },
                        onFocused = if (index == 0) {
                            {
                                firstActionFocused = true
                            }
                        } else {
                            null
                        },
                        onClick = action.onClick
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextMenuActionRow(
    label: String,
    isDestructive: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onFocused: (() -> Unit)? = null
) {
    var focused by remember {
        mutableStateOf(false)
    }

    val rowShape = RoundedCornerShape(12.dp)

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(
            shape = rowShape
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (isDestructive) {
                KBDanger
            } else {
                KBTextHi
            },
            focusedContainerColor = KBAccent.copy(alpha = 0.16f),
            focusedContentColor = KBTextHi
        ),
        scale = ClickableSurfaceDefaults.scale(
            scale = 1f,
            focusedScale = 1.02f
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(0.dp, Color.Transparent),
                shape = rowShape
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, KBAccent),
                shape = rowShape
            )
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .onFocusChanged { focusState ->
                focused = focusState.isFocused

                if (focusState.isFocused) {
                    onFocused?.invoke()
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 16.dp,
                    vertical = 12.dp
                )
        ) {
            Text(
                text = label,
                color = when {
                    focused -> KBTextHi
                    isDestructive -> KBDanger
                    else -> KBTextHi
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
