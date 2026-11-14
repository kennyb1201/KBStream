package com.kennyb1201.kbstream.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.components.touch.KBCard as TouchKBCard
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel as TouchKBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.KBTextField as TouchKBTextField

/**
 * The plate an anime tracker's connect dialog draws on (spec 2026-11-13, F2).
 *
 * ONE dialog per service, two plates. The dialogs themselves live in
 * `ui/settings` as `internal`, which is MODULE scope - `ui/mobile` can already
 * call them, so nothing had to move for the phone rows to reach them. What did
 * have to change is the plate: the TV panel is a fixed 560dp wide, and the app's
 * touch twin exists because that width runs off both edges of a phone. So each
 * dialog asks for the plate of the surface it was raised on, and the content -
 * fields, buttons, copy - is the single shared body either way.
 *
 * [phone] is passed by the caller that knows its surface (the TV Settings pane
 * and the phone Settings pane each build their own rows), never sniffed from the
 * device.
 *
 * THE BODY SPLITS WITH THE PLATE (spec 2026-11-14). The plate was the first
 * thing ported and the last thing that needed to be: the phone rows open these
 * dialogs, the panel draws phone-shaped, and then every button inside it is a
 * tv-material3 `Card` that takes Select from a remote and nothing at all from a
 * thumb, and every field is the D-pad `BasicTextField` that waits for its own
 * focus requester. The dialog appeared and was dead. [AnimeDialogButton] and
 * [AnimeDialogField] are the twins, chosen by the same [phone] flag, so the
 * call sites keep reading as one dialog and the TV path keeps exactly the
 * components it had.
 */
@Composable
internal fun AnimeDialogPanel(
    phone: Boolean,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    if (phone) {
        TouchKBDialogPanel(title = title, content = content)
    } else {
        KBDialogPanel(title = title, content = content)
    }
}

/**
 * A connect dialog's button, on the surface that raised it.
 *
 * The TV card is a focus surface: it glows and grows under the D-pad and fires
 * its click from `onPreviewKeyEvent` on Select/Enter. The touch card is a
 * Material 3 plate with `combinedClickable`, which is what a finger can press.
 *
 * [focusRequester] is applied on the TV plate ONLY, and deliberately: a D-pad
 * dialog lands on its primary action, but touch has no focus to place - a tap
 * on a field focuses it natively - and a requester on a phone would only fight
 * the keyboard's own focus handling.
 */
@Composable
internal fun AnimeDialogButton(
    phone: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (phone) {
        TouchKBCard(onClick = onClick, modifier = modifier, content = content)
    } else {
        KBCard(
            onClick = onClick,
            modifier = if (focusRequester != null) {
                modifier.focusRequester(focusRequester)
            } else {
                modifier
            },
            content = content
        )
    }
}

/**
 * A connect dialog's text field, on the surface that raised it.
 *
 * The TV field drives its own editing session (focus opens or waits for OK,
 * Done closes it) because a leanback IME is a full-screen overlay. The touch
 * field is a Material 3 `OutlinedTextField`: a tap focuses it and raises the
 * ordinary soft keyboard, which is the whole interaction on a phone. Both take
 * the same shape here - [keyboardType] and [visualTransformation] included, so a
 * password stays masked and an email field still asks for an email keyboard -
 * and [onDone] fires from the IME's Done on both.
 */
@Composable
internal fun AnimeDialogField(
    phone: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onDone: (() -> Unit)? = null
) {
    if (phone) {
        TouchKBTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            modifier = modifier,
            keyboardType = keyboardType,
            visualTransformation = visualTransformation,
            onDone = onDone
        )
    } else {
        KBTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            modifier = modifier,
            keyboardType = keyboardType,
            visualTransformation = visualTransformation,
            onDone = onDone
        )
    }
}
