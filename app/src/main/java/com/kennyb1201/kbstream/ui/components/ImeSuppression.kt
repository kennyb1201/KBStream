package com.kennyb1201.kbstream.ui.components

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay

/**
 * TV-safe text entry shared by every text field in the app.
 *
 * While [focused] is true, keeps the (leanback) soft keyboard suppressed so
 * it can never take over the whole screen — a single hide() loses the race
 * against the system re-showing it on focus. Input itself is unaffected:
 * physical keyboards still type, and atvTools' "Send text" injects directly
 * into the focused field, which is exactly what we want. Pair this helper
 * with `Modifier.onFocusChanged { focused = it.isFocused }` on the field.
 *
 * On top of the polling hide(), the window's soft-input mode is pinned to
 * STATE_ALWAYS_HIDDEN while the helper is mounted. Some TV IMEs — Fire TV's
 * in particular — ignore hideSoftInputFromWindow() and still pop a
 * full-screen keyboard that swallows every D-pad press, leaving the user
 * unable to navigate to results below the field. Pinning the window mode
 * stops the IME from being shown at all when a field in the window gains
 * focus, so the D-pad never gets trapped.
 */
@Composable
fun SuppressImeWhileFocused(focused: Boolean) {
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(focused) {
        while (focused) {
            keyboardController?.hide()
            delay(150)
        }
    }

    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val previousMode = window?.attributes?.softInputMode
        if (window != null) {
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            )
        }
        onDispose {
            if (window != null && previousMode != null) {
                window.setSoftInputMode(previousMode)
            }
        }
    }
}