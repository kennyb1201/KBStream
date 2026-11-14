package com.kennyb1201.kbstream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.ui.theme.KBTextHi

private const val SAVE_LABEL = "SAVE USERNAME"
private const val CLEAR_LABEL = "CLEAR"
private val LABEL_VERTICAL_PADDING = 10.dp

/**
 * The SAVE USERNAME / CLEAR row, laid out gutters-first (spec 2026-11-14,
 * amendment 3b).
 *
 * This row has now failed twice on Kenny's phone, in opposite directions: with
 * no weight, CLEAR wrapped to "CLEA / R"; with SAVE USERNAME weighted, SAVE drew
 * as "SAVE USERNAMI" - cut mid-glyph, because a weighted button is no wider than
 * the slack the row has left, and the row at his font scale did not have enough
 * for both labels.
 *
 * So the row measures its own two labels at the scale the device is running and
 * asks [UsernameActionLayout.fitsSideBySide] whether both fit, whole, with
 * headroom. When they do, it draws the row as before - SAVE USERNAME taking the
 * slack, CLEAR its own width - with the phone's tighter padding buying the room.
 * When they do not, it stacks: SAVE USERNAME full width, CLEAR full width below,
 * a layout that cannot clip at any font scale. Neither label is ever ellipsised
 * in practice; the ellipsis is the backstop for a width that changed between the
 * measurement and the draw, never an accepted state.
 *
 * TV is unchanged: its 560dp panel fits both labels at the paddings it shipped
 * with, so it draws the same row it draws today.
 */
@Composable
internal fun AnimeUsernameActions(
    phone: Boolean,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    val padding = UsernameActionLayout.paddingDp(phone).dp
    val labelStyle = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val sideBySide = UsernameActionLayout.fitsSideBySide(
            availableDp = maxWidth.value,
            saveLabelDp = measurer.labelWidthDp(SAVE_LABEL, labelStyle, density),
            clearLabelDp = measurer.labelWidthDp(CLEAR_LABEL, labelStyle, density),
            paddingDp = padding.value
        )

        if (sideBySide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(UsernameActionLayout.SPACING_DP.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // SAVE USERNAME absorbs the row's slack; CLEAR keeps its own
                // width, so the word CLEAR is never the one that gives way.
                AnimeDialogButton(
                    phone = phone,
                    onClick = onSave,
                    modifier = Modifier.weight(1f)
                ) {
                    // Only the phone's share of the row is worth constraining: a
                    // TV row has room by measurement, and centring the label there
                    // would restyle a row S3 asks to leave exactly as it is.
                    UsernameActionLabel(label = SAVE_LABEL, padding = padding, fillsButton = phone)
                }
                AnimeDialogButton(phone = phone, onClick = onClear) {
                    UsernameActionLabel(label = CLEAR_LABEL, padding = padding, fillsButton = false)
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(UsernameActionLayout.SPACING_DP.dp)
            ) {
                AnimeDialogButton(
                    phone = phone,
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    UsernameActionLabel(label = SAVE_LABEL, padding = padding, fillsButton = true)
                }
                AnimeDialogButton(
                    phone = phone,
                    onClick = onClear,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    UsernameActionLabel(label = CLEAR_LABEL, padding = padding, fillsButton = true)
                }
            }
        }
    }
}

/**
 * One label inside a username-row button.
 *
 * [fillsButton] is the difference between a button sized to its text (CLEAR
 * beside SAVE USERNAME, where a `fillMaxWidth` would make the unweighted button
 * swallow the whole row) and a button whose width is already decided (SAVE
 * USERNAME's weighted share, and both buttons when the row stacks). A filled
 * label is centred in its button and ellipsised at its edge, so a button that is
 * somehow too narrow cuts text where the reader can see an ellipsis instead of
 * slicing a glyph in half.
 */
@Composable
private fun UsernameActionLabel(label: String, padding: Dp, fillsButton: Boolean) {
    Text(
        text = label,
        color = KBTextHi,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        softWrap = false,
        textAlign = if (fillsButton) TextAlign.Center else TextAlign.Start,
        overflow = TextOverflow.Ellipsis,
        modifier = (if (fillsButton) Modifier.fillMaxWidth() else Modifier)
            .padding(horizontal = padding, vertical = LABEL_VERTICAL_PADDING)
    )
}

/** The label's own width, in dp, at the device's font scale. */
private fun TextMeasurer.labelWidthDp(label: String, style: TextStyle, density: Density): Float =
    with(density) {
        measure(AnnotatedString(label), style = style, maxLines = 1).size.width.toDp().value
    }
