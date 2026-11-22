package com.kennyb1201.kbstream.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * What is open on the Settings page right now, for the TV screensaver's gate.
 *
 * The screensaver host already takes a `blocked` input; on Home that is the
 * menus and the top bar, on Detail the page's menus and dialogs. Settings is a
 * page of dozens of rows and a dozen dialogs, and the ones opened from a
 * section are not visible to the screen's own state - so they announce
 * themselves here instead. The host then treats an open picker exactly as Home
 * treats an open tray: the viewer is mid-task, not idle, and the show never
 * arms over it.
 *
 * A text-entry surface holds the gate for the same reason: a focused editable
 * field is a viewer about to type, not a room that has been left alone.
 */
internal class SettingsModalGate {
    private val dialogs = mutableStateMapOf<Any, Boolean>()
    private val textEntries = mutableStateMapOf<Any, Boolean>()

    /** True while any dialog, picker or text field holds the gate. */
    val blocked: Boolean get() = dialogs.isNotEmpty() || textEntries.isNotEmpty()

    fun holdDialog(key: Any) {
        dialogs[key] = true
    }

    fun releaseDialog(key: Any) {
        dialogs.remove(key)
    }

    fun setTextEntryFocused(key: Any, focused: Boolean) {
        if (focused) textEntries[key] = true else textEntries.remove(key)
    }
}

/**
 * The screen's gate, or null outside Settings. A dialog shared with the phone's
 * Settings composes there with no provider, and [SettingsModalHold] is then a
 * no-op rather than a second store.
 */
internal val LocalSettingsModalGate = compositionLocalOf<SettingsModalGate?> { null }

/**
 * Marks the calling surface as open for as long as it is composed.
 *
 * One line at the top of a dialog's content. Keyed on the gate, not on a
 * remembered token: the token is created inside the effect so a dialog that
 * stays composed across a recomposition keeps ONE hold, and disposal releases
 * exactly that hold.
 */
@Composable
internal fun SettingsModalHold() {
    val gate = LocalSettingsModalGate.current ?: return
    DisposableEffect(gate) {
        val key = Any()
        gate.holdDialog(key)
        onDispose { gate.releaseDialog(key) }
    }
}

/**
 * The focus hold for one text-entry surface: hand the returned lambda to the
 * field's `onFocusChanged`, and the gate is held exactly while it is focused.
 *
 * The key is per call site and stable across recompositions, so two fields
 * focused in sequence (Down from one to the next) do not release the other's
 * hold. Disposal clears it too, so a field unmounted while focused cannot wedge
 * the gate shut.
 */
@Composable
internal fun rememberSettingsTextEntryHold(): (Boolean) -> Unit {
    val gate = LocalSettingsModalGate.current
    val key = remember { Any() }
    DisposableEffect(gate, key) {
        onDispose { gate?.setTextEntryFocused(key, false) }
    }
    return remember(gate, key) {
        { focused: Boolean -> gate?.setTextEntryFocused(key, focused) }
    }
}

/**
 * Holds the gate while this field, or anything inside it, has focus.
 *
 * A modifier rather than another parameter to thread: the field's own
 * `onFocusChanged` already belongs to its save/verify behaviour, and this adds
 * the screensaver's half without touching it. `hasFocus` (not `isFocused`) so a
 * reveal chip inside the field's own row still counts as "the viewer is here".
 */
@Composable
internal fun Modifier.settingsTextEntryHold(): Modifier {
    val hold = rememberSettingsTextEntryHold()
    return this.onFocusChanged { state -> hold(state.hasFocus) }
}
