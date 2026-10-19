package com.kennyb1201.kbstream.ui.components.touch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.ui.components.readClipboardText
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * The touch twin of `ui/components/KBTextField.kt`: a Material 3
 * [OutlinedTextField] on the app's raised plate.
 *
 * The TV field is a `BasicTextField` with a hand-drawn decoration box and a
 * pile of D-pad plumbing: Enter has to be intercepted because leanback IMEs
 * never send the IME action, Up/Down have to escape the keyboard or navigation
 * is trapped, and a Fire TV field has to be able to stay read-only until OK is
 * pressed. None of that survives the trip to a touch screen - the soft
 * keyboard is a normal IME, tapping another field moves focus, and there is no
 * D-pad to trap. So the twin keeps the *look and the contract callers rely
 * on*, not the key handling:
 *
 *  - same raised fill, same [KBShapeCard] corner, same accent focus border;
 *  - same `bodyMedium` text and accent cursor;
 *  - [onDone] fires from the keyboard's Done action (and hides the keyboard);
 *  - [focusRequester], [keyboardType], [visualTransformation], [leading] and
 *    [onFocusChanged] are the same parameters, so a screen port reads the same.
 *
 * The TV-only editing parameters (`keepFocusOnDone`, `openKeyboardOnFocus`,
 * `closeKeyboardOnBlur`, `onEditingChanged`, `endEditingSignal`) are
 * deliberately absent: they exist to work around a remote-driven IME, and
 * carrying them across would leave call sites passing switches that can never
 * change anything on a phone.
 */
@Composable
fun KBTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leading: (@Composable () -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = {
            Text(
                text = placeholder,
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
        },
        leadingIcon = leading,
        textStyle = MaterialTheme.typography.bodyMedium,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done,
            keyboardType = keyboardType
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                keyboardController?.hide()
                onDone?.invoke()
            }
        ),
        shape = KBShapeCard,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = KBTextHi,
            unfocusedTextColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            unfocusedContainerColor = KBSurfaceRaised,
            disabledContainerColor = KBSurfaceRaised,
            cursorColor = KBAccent,
            focusedBorderColor = KBAccent,
            unfocusedBorderColor = KBTextLo.copy(alpha = 0.20f)
        ),
        modifier = modifier
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .onFocusChanged { onFocusChanged?.invoke(it.isFocused) }
    )
}

/**
 * The paste affordance that pairs with [KBTextField].
 *
 * The TV chip exists because a leanback keyboard has no paste action; a phone
 * keyboard does, but a URL field for an add-on or a playlist still wants a
 * one-press paste for the common case of copying a key from another app. Kept
 * at the same call-site shape (`onPaste`, `modifier`) so ported forms read the
 * same, implemented as a small text button so it sits in the same row.
 */
@Composable
fun KBPasteChip(
    onPaste: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(modifier = modifier.width(88.dp)) {
        KBButton(
            label = "PASTE",
            onClick = { readClipboardText(context)?.let(onPaste) }
        )
    }
}
