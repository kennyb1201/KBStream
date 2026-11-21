package com.kennyb1201.kbstream.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kennyb1201.kbstream.ui.home.TopActionBar
import com.kennyb1201.kbstream.ui.theme.KBVoid
import kotlinx.coroutines.delay

/**
 * The top bar, hosted by the app shell over every TV screen that is not Home.
 *
 * Home owns a bar of its own, summoned from its rails (short Up on the first
 * rail, Left on any head card) because the bar is part of Home's focus graph.
 * Every other screen had no way to reach SEARCH, TV GUIDE, LIBRARY, SETTINGS or
 * the profile picker at all: leaving the screen with Back was the only road, and
 * on a deep browse stack that is several presses before the first useful one.
 *
 * Here it is summoned by the same Left press Home's head cards answer: the app
 * shell runs the Left search itself, and a press with nowhere to go opens the
 * bar instead of doing nothing.
 *
 * So this is the same bar drawn in its own window. The window is the reason for
 * the Dialog, not a styling choice: a dialog takes D-pad focus without moving
 * the screen's own focus node, so dismissing the bar hands the remote back to
 * exactly the card the viewer was on, with no restore target to capture. A bar
 * composed into the screen's hierarchy would have to save and re-request that
 * card itself, and every screen would have to provide one.
 *
 * It is modal while up - Back, or Down from any chip, closes it - and it cannot
 * trap the viewer: HOME is the first chip, so the way back is one press from
 * wherever the bar was summoned.
 */
@Composable
internal fun TvTopBarOverlay(
    onDismiss: () -> Unit,
    onOpenHome: () -> Unit,
    onSearch: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchProfile: () -> Unit
) {
    val motionMs = rememberMotionMs(KB_SCREEN_TRANSITION_MS)
    val firstChip = remember { FocusRequester() }
    var barHasFocus by remember { mutableStateOf(false) }
    // The window is new, so the reveal has to be animated explicitly: visible=true
    // on its own would draw the bar in one frame, and the bar drops from the top
    // edge on Home.
    val reveal = remember {
        MutableTransitionState(false).apply { targetState = true }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Some TV platforms deliver Back straight to the focused hierarchy
        // instead of the window's own dismiss path (the poster menus carry the
        // same handler for the same reason). The dialog's dismissOnBackPress is
        // still the primary path and no-ops when this already closed the bar.
        BackHandler { onDismiss() }

        // The window has just attached, so the bar's chips may not be laid out
        // on the first ask. Success is focus OBSERVED inside the bar - the same
        // rule Home's hand-off uses - rather than requestFocus()'s answer.
        LaunchedEffect(Unit) {
            var attempts = 0
            while (!barHasFocus && attempts < TOP_BAR_OVERLAY_FOCUS_ATTEMPTS) {
                runCatching { firstChip.requestFocus() }
                delay(16L)
                attempts++
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visibleState = reveal,
                enter = fadeIn(tween(motionMs)) +
                    slideInVertically(tween(motionMs)) { -it / 2 },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    KBVoid.copy(alpha = .96f),
                                    KBVoid.copy(alpha = .72f),
                                    Color.Transparent
                                )
                            )
                        )
                ) {
                    TopActionBar(
                        onSearch = onSearch,
                        onOpenGuide = onOpenGuide,
                        onOpenLibrary = onOpenLibrary,
                        onOpenSettings = onOpenSettings,
                        onSwitchProfile = onSwitchProfile,
                        firstActionFocusRequester = firstChip,
                        onBarFocusChanged = { barHasFocus = it },
                        onDismiss = onDismiss,
                        onOpenHome = onOpenHome
                    )
                }
            }
        }
    }
}

/**
 * How many frames the hand-off keeps asking for the bar's first chip.
 *
 * A frame per ask, matching Home's drive (which is capped at 180 frames for a
 * bar that is already in the screen). Here the window is brand new, so a couple
 * of frames is the normal case; the cap only keeps a bar that never gets focus
 * from looping forever.
 */
private const val TOP_BAR_OVERLAY_FOCUS_ATTEMPTS = 30
