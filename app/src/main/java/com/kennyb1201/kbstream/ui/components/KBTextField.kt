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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    closeKeyboardOnBlur: Boolean = false,
    /**
     * Reports the editing session as it starts and ends.
     *
     * Fired once with the initial value too, because a caller's guard is only
     * correct if it knows the session STARTED as well as that it ended. The
     * guide's search overlay uses it to tell whether a Back belongs to the
     * keyboard (close it, keep the results) or to the overlay (close it).
     */
    onEditingChanged: ((Boolean) -> Unit)? = null,
    /**
     * Ends the editing session when this value changes (any non-zero value).
     *
     * The counterpart of [onEditingChanged]: a caller that decides a Back
     * belongs to the keyboard must be able to end the session itself, or a Back
     * it kept would leave the field editable forever and no further Back could
     * ever reach the screen behind it.
     */
    endEditingSignal: Int = 0
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

    // Ask for the IME whenever the field is BOTH editable and focused.
    //
    // Keyed on `focused` as well as `editing`, not just `editing`: the standard
    // field starts editable (openKeyboardOnFocus defaults true), so `editing`
    // does not change when focus arrives and this effect would never re-run on
    // first focus - the explicit show() was dead for exactly the fields that
    // are editable from the start. On Fire TV an app that delivers text through
    // the TV's IME connection (ATV Tools' "Send text") needs that connection to
    // exist, so a field whose implicit IME start never attached accepted no
    // pasted text.
    LaunchedEffect(editing, focused) {
        if (editing && focused) keyboardController?.show()
    }

    // Report the session outward (see [onEditingChanged]).
    LaunchedEffect(editing) { onEditingChanged?.invoke(editing) }

    // ...and honour a caller asking for it to end (see [endEditingSignal]).
    LaunchedEffect(endEditingSignal) {
        if (endEditingSignal > 0) {
            keyboardController?.hide()
            editing = false
        }
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
                        // back handler and drop the whole form — which is
                        // exactly what Fire OS does: its keyboard closes AND
                        // hands the press on, so one Back used to take the
                        // guide's search overlay with it. [closeKeyboardOnBlur]
                        // fields are included for that reason.
                        Key.Back -> {
                            if (editing && (!openKeyboardOnFocus || closeKeyboardOnBlur)) {
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
 * The transformation a secret field draws through: password dots, or the plain
 * text once the viewer has asked to see it.
 *
 * Pure and public-to-the-module so the masking RULE is unit tested (see
 * KBSecretFieldMaskingTest) instead of only being read out of a composable this
 * module's test suite cannot run.
 */
internal fun secretFieldTransformation(revealed: Boolean): VisualTransformation =
    if (revealed) VisualTransformation.None else PasswordVisualTransformation()

/**
 * Whether a revealed key goes back to dots.
 *
 * Reveal is a momentary check - "did I paste the right key" - and never a
 * state to leave behind, so the plaintext goes away as soon as focus holds
 * NEITHER the field NOR its reveal chip: moving on to the paste chip, another
 * row, or Back all re-mask it. The chip is itself a focusable node, so pressing
 * it must not count as having left the field - hence both halves, not just the
 * field's - or the reveal would cancel itself the instant it was asked for.
 */
internal fun shouldRemaskKey(fieldFocused: Boolean, toggleFocused: Boolean): Boolean =
    !fieldFocused && !toggleFocused

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

/**
 * A [KBTextField] for a SECRET - an API key - masked by default, with a
 * focusable reveal chip beside it that shows the plaintext for a look.
 *
 * Masking is presentation only: [value] and [onValueChange] are the real key
 * string, unchanged, and only what is DRAWN goes through
 * [secretFieldTransformation]. Saving, pasting and verifying therefore keep
 * handling the actual key (the status line still reads "Saved"/"Connected"
 * off it), and the dots are a display the viewer can step out of rather than a
 * value this component owns. Nothing here logs, copies or persists the
 * plaintext: the reveal state is UI state (a `remember`, never
 * `rememberSaveable`) and the reveal goes back to dots on focus loss (see
 * [shouldRemaskKey]), so re-entering the screen always comes up masked.
 *
 * Takes the field's place in the caller's Row: pass the `weight(1f)` the field
 * used to take, and keep the [KBPasteChip] beside it as before.
 */
@Composable
fun KBSecretField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onDone: (() -> Unit)? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null
) {
    var revealed by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    var chipFocused by remember { mutableStateOf(false) }

    LaunchedEffect(fieldFocused, chipFocused) {
        if (shouldRemaskKey(fieldFocused, chipFocused)) revealed = false
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        KBTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            modifier = Modifier.weight(1f),
            focusRequester = focusRequester,
            visualTransformation = secretFieldTransformation(revealed),
            onDone = onDone,
            onFocusChanged = { focused ->
                fieldFocused = focused
                onFocusChanged?.invoke(focused)
            }
        )
        Spacer(modifier = Modifier.width(8.dp))
        KBRevealChip(
            revealed = revealed,
            onToggle = { revealed = !revealed },
            onFocusChanged = { chipFocused = it }
        )
    }
}

/**
 * The eye that reveals a masked key, beside it - the [KBPasteChip] pattern.
 *
 * Focusable (a [KBCard] like every other chip), so D-pad Right from the field
 * reaches it and OK reveals. Its contentDescription reports the STATE, not
 * just the glyph, so the reveal is announced as "Show API key"/"Hide API key"
 * rather than as an unlabelled eye - the D-pad user cannot see the icon change.
 * The glyph itself follows the same convention the catalog builder's visibility
 * control uses: the icon shows which way the press will go.
 */
@Composable
fun KBRevealChip(
    revealed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: ((Boolean) -> Unit)? = null
) {
    KBCard(
        onClick = onToggle,
        modifier = modifier.onFocusChanged { onFocusChanged?.invoke(it.isFocused) }
    ) {
        Icon(
            imageVector = if (revealed) Icons.Filled.Visibility
            else Icons.Filled.VisibilityOff,
            contentDescription = if (revealed) "Hide API key" else "Show API key",
            tint = if (revealed) KBAccent else KBTextLo,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .size(16.dp)
        )
    }
}
