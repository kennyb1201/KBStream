package com.kennyb1201.kbstream.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
}