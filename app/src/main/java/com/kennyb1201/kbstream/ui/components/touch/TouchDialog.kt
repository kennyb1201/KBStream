package com.kennyb1201.kbstream.ui.components.touch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi

/**
 * The touch twin of `ui/components/KBDialogPanel.kt`: the app's one dialog
 * plate, laid out for a phone.
 *
 * Same API, same plate (raised fill, 38% accent ring, 22dp inset, the ALL-CAPS
 * accent heading), with the one thing that cannot survive the port: a fixed
 * 560dp width. On a 411dp phone that panel would run off both edges, so the
 * width becomes a *cap* - `fillMaxWidth()` inside `widthIn(max = width)` - and
 * the same 560dp value keeps a tablet dialog from stretching edge to edge.
 *
 * The TV version is also a focus group, because a D-pad can otherwise walk out
 * of an open dialog into the dimmed screen behind it. Touch has no focus to
 * trap: a modal on a phone is a separate layer whose scrim consumes taps, and
 * that scrim belongs to the screen raising the dialog (see the touch context
 * menu, which is the one that needs it). So nothing here consumes input; this
 * composes the plate only, exactly as the TV twin does.
 */
@Composable
fun KBDialogPanel(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    width: Dp = 560.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = width),
        shape = KBShapePanel,
        color = KBSurface,
        contentColor = KBTextHi,
        border = BorderStroke(1.dp, KBAccent.copy(alpha = 0.38f))
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            KBPanelHeading(title = title, subtitle = subtitle)
            content()
        }
    }
}

/**
 * A bottom-sheet plate for the menus a phone raises from a long press.
 *
 * This is the sheet *chrome* only - the drag handle, the panel corner and the
 * safe-area padding - so a screen can host it in a Material 3
 * `ModalBottomSheet` (which owns the scrim, the swipe-to-dismiss and Back)
 * without re-deriving the brand's plate each time. [content] gets a
 * [ColumnScope] and is expected to start with a heading; see
 * [com.kennyb1201.kbstream.ui.components.touch.KBPanelHeading].
 */
@Composable
fun KBSheetContent(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        content()
    }
}

/** The heading row a [KBSheetContent] or a [KBDialogPanel] body can share. */
@Composable
fun KBPanelHeading(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = title.uppercase(),
            color = KBAccent,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        subtitle?.takeIf { it.isNotBlank() }?.let { text ->
            Text(
                text = text,
                color = KBTextHi,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
