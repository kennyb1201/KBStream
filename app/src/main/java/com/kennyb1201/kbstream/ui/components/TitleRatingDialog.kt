package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.library.TasteSignals
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapePill
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * The numeric rating dialog: ten plates, one number, one press.
 *
 * A rating is the viewer's own verdict about a title, and v1 stores it locally
 * only (no tracker endpoint exists to write it to). The control is a NUMBER
 * STRIP, deliberately not stars: the app has no star drawable and the scale is
 * the 1-10 integer scale the trackers use, so a string of five stars would have
 * to invent fractions that no submission could carry.
 *
 * The plate IS the confirm - there is no separate OK step. That is what makes
 * the dialog usable with a D-pad (one press, no focus dance) and honest with a
 * thumb (tap, done). Back/Cancel always exits without writing, and the caller
 * paints from the store's answer, never from the press.
 *
 * One content composable serves both plates: [platesPerRow] is 10 on a TV and 5
 * on a phone, where ten 48dp plates do not fit across 360dp. The number range is
 * read from [TasteSignals] so the strip cannot drift from the scale the store
 * accepts.
 */
internal fun ratingCaption(rating: Int?): String = when {
    rating == null -> "No rating yet"
    rating >= TasteSignals.RATING_LIKE_FLOOR -> "Liked"
    rating <= TasteSignals.RATING_DISLIKE_CEIL -> "Disliked"
    else -> "No thumb — a mid-range rating"
}

/** The strip, the caption and the two rows, shared by both plates. */
@Composable
internal fun RatingDialogContent(
    rating: Int?,
    hasSignal: Boolean,
    platesPerRow: Int,
    onPick: (Int) -> Unit,
    onRemove: (() -> Unit)?,
    onCancel: () -> Unit,
    initialFocus: FocusRequester? = null
) {
    val numbers = remember {
        (TasteSignals.RATING_MIN..TasteSignals.RATING_MAX).toList()
    }
    // The plate focus lands on: the current rating, or the neutral centre when
    // there is none (landing on an extreme would invite an accidental write).
    val initial = rating ?: 5

    if (initialFocus != null) {
        LaunchedEffect(Unit) { initialFocus.requestFocus() }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        numbers.chunked(platesPerRow).forEach { rowNumbers ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                rowNumbers.forEach { number ->
                    val selected = rating == number
                    KBCard(
                        onClick = { onPick(number) },
                        shape = KBShapePill,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .then(
                                if (number == initial && initialFocus != null) {
                                    Modifier.focusRequester(initialFocus)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) KBAccent else Color.Transparent,
                                    KBShapePill
                                )
                                // TalkBack: a digit alone does not say what
                                // pressing it does, and the plate the press
                                // would most likely repeat should say so.
                                .semantics {
                                    contentDescription = if (selected) {
                                        "Rate $number out of 10 — current rating"
                                    } else {
                                        "Rate $number out of 10"
                                    }
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$number",
                                color = if (selected) Color.Black else KBTextHi,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = ratingCaption(rating),
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
        )

        if (onRemove != null && hasSignal) {
            RatingDialogRow(label = "Remove rating", onClick = onRemove)
        }
        // "Cancel" says what it does, but not that it leaves the signal alone.
        RatingDialogRow(label = "Cancel", hint = "Cancel, no changes", onClick = onCancel)
    }
}

/**
 * One full-width action row inside the dialog (Remove, Cancel). [hint] is what
 * TalkBack announces when it differs from the visible label.
 */
@Composable
private fun RatingDialogRow(label: String, hint: String = label, onClick: () -> Unit) {
    Spacer(modifier = Modifier.height(6.dp))
    KBCard(
        onClick = onClick,
        shape = KBShapePill,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            color = KBTextHi,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = hint }
                .padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}

/**
 * The dialog on the TV plate: the app's one dialog chrome, with its focus trap.
 */
@Composable
fun TvTitleRatingDialog(
    title: String,
    rating: Int?,
    hasSignal: Boolean,
    onPick: (Int) -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit
) {
    val plateFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onCancel) {
        KBDialogPanel(
            title = "RATE THIS TITLE",
            subtitle = title.takeIf { it.isNotBlank() }
        ) {
            RatingDialogContent(
                rating = rating,
                hasSignal = hasSignal,
                platesPerRow = 10,
                onPick = onPick,
                onRemove = onRemove,
                onCancel = onCancel,
                initialFocus = plateFocus
            )
        }
    }
}

/**
 * The dialog on the phone plate: the touch twin of [KBDialogPanel], with the
 * strip wrapped to two rows of five.
 */
@Composable
fun TouchTitleRatingDialog(
    title: String,
    rating: Int?,
    hasSignal: Boolean,
    onPick: (Int) -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel(
            title = "RATE THIS TITLE",
            subtitle = title.takeIf { it.isNotBlank() }
        ) {
            RatingDialogContent(
                rating = rating,
                hasSignal = hasSignal,
                platesPerRow = 5,
                onPick = onPick,
                onRemove = onRemove,
                onCancel = onCancel
            )
        }
    }
}
