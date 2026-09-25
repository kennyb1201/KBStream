package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import kotlinx.coroutines.android.awaitFrame

/**
 * The app's ONE status card: the loading spinner, the "nothing here" line and
 * the error line all render as this same centred pill on a [KBSurface] plate.
 *
 * Every browse screen needs those three states, and before this they each did
 * it their own way — the actor page had this card, while the genre / decade /
 * studio pages dropped a bare `Text("Nothing found for …")` straight into a
 * LazyColumn (no style, no colour, no padding, flush against the left edge,
 * misaligned with the 20dp-inset rails around it) and a raw spinner in the
 * top-left corner. One shared component is what makes a failed load, an empty
 * result and a spinner recognisable as the same kind of thing wherever the
 * user meets them.
 *
 * [loading] swaps the icon for a spinner; otherwise [icon] is drawn as-is
 * (emoji carry their own colour, unlike a tinted vector).
 *
 * [onRetry] adds a focusable "Press OK to retry" card under the pill. Pass it
 * from FAILED loads only: a failure is the one state where the screen has
 * nothing else to offer, whereas an empty result is a real answer and gets no
 * action. The card takes focus itself, because with nothing focused the OK
 * button on a remote goes nowhere — see the comment in the body.
 *
 * Callers that place this inside a `LazyColumn` item should pass
 * `Modifier.fillParentMaxSize()` so the card centres in the rail viewport
 * rather than sitting at the top of the item's content height.
 */
@Composable
fun KBStatusMessage(
    message: String,
    loading: Boolean = false,
    icon: String = "⚠️",
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val retryRequester = remember { FocusRequester() }

    /*
     * Grab focus for the retry card, retrying across frames: the request can
     * land before the card has attached (this whole branch enters the tree in
     * the frame the load fails), and the request is then silently missed.
     *
     * requestFocus() returns false rather than throwing for a node that is not
     * attached yet, so the RETURN VALUE is the success test. runCatching's
     * isSuccess only ever means "no exception was thrown" -- which is always
     * true -- so testing that instead exits the loop after a single frame with
     * focus still stranded and the retry unreachable by D-pad. (GuideScreen's
     * action row documents the same trap.)
     *
     * Keyed on whether there IS a retry, not on the lambda: a lambda key is a
     * new identity on every recomposition, which would re-run this effect
     * continuously and keep yanking focus back to this card.
     */
    LaunchedEffect(onRetry != null) {
        if (onRetry == null) return@LaunchedEffect
        var focused = false
        var attempts = 0
        while (!focused && attempts < 12) {
            awaitFrame()
            focused = runCatching { retryRequester.requestFocus() }
                .getOrDefault(false)
            attempts++
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(KBSurface, KBShapeCard)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = KBAccent,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(icon, style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    message,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }

            if (onRetry != null) {
                KBCard(
                    onClick = onRetry,
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .focusRequester(retryRequester)
                ) {
                    Text("Press OK to retry.")
                }
            }
        }
    }
}

/** Magnifier: an empty result set, as opposed to a failure (the default icon). */
const val KB_STATUS_ICON_EMPTY = "🔍"

/** Shared copy so every screen's spinner reads the same. */
const val KB_STATUS_LOADING = "Loading…"
