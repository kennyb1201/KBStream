package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextLo

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
 * Callers that place this inside a `LazyColumn` item should pass
 * `Modifier.fillParentMaxSize()` so the card centres in the rail viewport
 * rather than sitting at the top of the item's content height.
 */
@Composable
fun KBStatusMessage(
    message: String,
    loading: Boolean = false,
    icon: String = "⚠️",
    modifier: Modifier = Modifier.fillMaxSize()
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
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
    }
}

/** Magnifier: an empty result set, as opposed to a failure (the default icon). */
const val KB_STATUS_ICON_EMPTY = "🔍"

/** Shared copy so every screen's spinner reads the same. */
const val KB_STATUS_LOADING = "Loading…"
