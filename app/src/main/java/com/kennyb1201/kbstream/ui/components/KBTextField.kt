package com.kennyb1201.kbstream.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.data.runCatchingCancellable

private val KBFieldShape = KBShapeCard

/**
 * Reads plain text from the system clipboard. Returns null silently when the
 * clipboard is empty/unreadable. Shared by every PASTE chip in the app (the
 * TV leanback keyboard has no paste action, so paste is an explicit button).
 */
fun readClipboardText(context: Context): String? =
    runCatchingCancellable {
        context.getSystemService(ClipboardManager::class.java)
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

/**
 * The one text field used across the app: same rounded surface, same accent
 * focus border, same cursor, same IME behavior, same TV D-pad escape.
 *
 * Behavior contract (identical on every screen):
 *  - Enter / IME Done: hides the keyboard, clears focus, fires [onDone].
 *    Raw ENTER is intercepted because many TV IMEs never send the IME action.
 *  - D-pad Up/Down: hides the keyboard and moves focus out — the leanback IME
 *    otherwise swallows the D-pad and traps navigation.
 *  - [focusRequester]: lets a parent card/button raise the keyboard on OK.
 *  - [onFocusChanged]: lets callers persist on blur (e.g. save on navigate-away).
 *  - Paste: pair with [KBPasteChip] as a sibling in a Row (see screens).
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
    onFocusChanged: ((Boolean) -> Unit)? = null,
    /**
     * Enter/Done normally commits AND drops focus (the field is "finished").
     * Inside modal overlays (playlist setup) dropping focus lets the next
     * key press fall through to controls behind the overlay, so callers can
     * keep focus on the field instead: commit + hide the IME, keep focus.
     */
    keepFocusOnDone: Boolean = false,
    /**
     * Whether gaining focus opens the system keyboard.
     *
     * On Fire TV the leanback IME is a full-screen overlay that takes the
     * D-pad for itself: while it is up nothing below it can be reached, and
     * Back only closes it onto the field it came from. Long TV forms (the
     * profile editor) pass false so focus alone never opens it — the field
     * takes focus silently, OK starts editing, and Done ends editing without
     * pushing focus somewhere else.
     */
    openKeyboardOnFocus: Boolean = true,
    /**
     * Whether leaving the field ends its editing session.
     *
     * Only for a field that shares one focus group with a list of OTHER
     * focusable controls — the guide's search overlay, where up to 40 result
     * cards sit below the query box. An editable [BasicTextField] asks for the
     * IME every time it takes focus, so a field that stays editable for the
     * whole life of the overlay re-opens the keyboard (a full-screen window on
     * Fire TV) every time the D-pad walks back over it while the user is
     * scrolling the hits — the results could only be reached by pressing Back,
     * and any move that crossed the field put the keyboard back up.
     *
     * With this on, the field is editable for its FIRST focus (so the keyboard
     * still comes up by itself when the overlay opens), and read-only from the
     * moment focus or Done leaves it. The keyboard then only returns when OK is
     * pressed on the field again, which is what keeps the rest of the screen
     * reachable while the list is being browsed.
     */
    closeKeyboardOnBlur: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    // Manual mode starts inert: the field only becomes editable (and only then
    // asks for the IME) once OK is pressed on it.
    var editing by remember { mutableStateOf(openKeyboardOnFocus) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // What [editing] goes back to once this field stops being the focused one.
    // True for the usual field: focus (re)opens the IME. False for manual mode
    // and for closeKeyboardOnBlur, where only an explicit OK starts a session.
    val editingWhenLeft = openKeyboardOnFocus && !closeKeyboardOnBlur

    fun finishEditing() {
        keyboardController?.hide()
        if (!editingWhenLeft) {
            // Manual mode / list-sharing field: end the session but KEEP focus,
            // so the D-pad carries on from here instead of jumping back to
            // whatever held focus before the field.
            editing = false
        } else if (!keepFocusOnDone) {
            focusManager.clearFocus()
        }
        onDone?.invoke()
    }

    // The field was readOnly when it took focus, so the IME has to be asked
    // for once it becomes editable.
    LaunchedEffect(editing) {
        if (editing && focused) keyboardController?.show()
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = !editing,
        singleLine = true,
        textStyle = TextStyle(
            color = KBTextHi,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize
        ),
        cursorBrush = SolidColor(KBAccent),
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done,
            keyboardType = keyboardType
        ),
        visualTransformation = visualTransformation,
        keyboardActions = KeyboardActions(onDone = { finishEditing() }),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .clip(KBFieldShape)
                    .background(KBSurfaceRaised)
                    .border(
                        width = if (focused) 2.dp else 1.dp,
                        color = if (focused) KBAccent
                        else KBTextLo.copy(alpha = 0.20f),
                        shape = KBFieldShape
                    )
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leading != null) {
                    leading()
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isBlank()) {
                        Text(
                            text = placeholder,
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
            }
        },
        modifier = modifier
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .onFocusChanged {
                focused = it.isFocused
                // Blurring a manual-mode (or closeKeyboardOnBlur) field ends its
                // editing state, so it is inert again the next time the D-pad
                // lands on it instead of pulling the IME back up over whatever
                // the user navigated to.
                if (!it.isFocused && !editingWhenLeft) editing = false
                onFocusChanged?.invoke(it.isFocused)
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.DirectionDown -> {
                            val moved = focusManager.moveFocus(FocusDirection.Down)
                            if (moved) {
                                keyboardController?.hide()
                                editing = editingWhenLeft
                            }
                            moved
                        }
                        Key.DirectionUp -> {
                            val moved = focusManager.moveFocus(FocusDirection.Up)
                            if (moved) {
                                keyboardController?.hide()
                                editing = editingWhenLeft
                            }
                            moved
                        }
                        // OK on a remote. In manual mode it is what starts
                        // editing; in the default mode the IME owns it.
                        Key.DirectionCenter -> {
                            if (editing) {
                                false
                            } else {
                                editing = true
                                true
                            }
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            if (!editing) {
                                editing = true
                                true
                            } else {
                                finishEditing()
                                true
                            }
                        }
                        // Back while editing normally belongs to the IME (it
                        // closes the keyboard). When it reaches the app instead
                        // it must end editing, not fall through to the screen's
                        // back handler and drop the whole form.
                        Key.Back -> {
                            if (editing && !openKeyboardOnFocus) {
                                finishEditing()
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                }
            }
    )
}

/**
 * The one paste button used next to every URL/key field: reads the shared
 * clipboard helper and hands the text to [onPaste]. Silently ignores an
 * empty clipboard. Focusable, so D-pad Right from the field reaches it.
 * Icon-only: the clipboard glyph reads universally, and the label was
 * redundant next to fields whose placeholder already says what goes in.
 */
@Composable
fun KBPasteChip(
    onPaste: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    KBCard(
        onClick = {
            readClipboardText(context)?.let(onPaste)
        },
        modifier = modifier
    ) {
        Icon(
            imageVector = Icons.Filled.ContentPaste,
            contentDescription = "Paste from clipboard",
            tint = KBTextLo,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .size(16.dp)
        )
    }
}
