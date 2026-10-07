package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi

/**
 * The app's one dialog plate.
 *
 * A dialog is chrome the viewer meets on top of a screen they were already
 * using, so its plate, its ring and its heading have to look the same wherever
 * it is raised. They did not: the guide's three dialogs drew the panel
 * themselves - raised fill, a 45%-opacity ring, 20dp of padding, a heading at
 * titleMedium or a bold titleLarge - while the profile picker's PIN prompt and
 * the add-on manager each picked their own combination, and two of them put the
 * *content* where the heading belongs. This is that chrome, once.
 *
 * [title] is the heading, in the app's ALL-CAPS dialog casing at KBAccent.
 * [subtitle] is the one line of content that belongs beside it (a channel name,
 * a profile name); anything else the dialog shows goes in [content], which is
 * laid out on the 10dp beat every one of these panels already used.
 */
@Composable
fun KBDialogPanel(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    width: Dp = 560.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .width(width)
            // Every panel is a focus group, the way PosterContextMenu's is:
            // focus SEARCH stays inside the dialog before it can walk out into
            // the screen behind the scrim. This is the bounded half of that
            // menu's trap on purpose - the other half (consuming Left/Right
            // and the vertical edges) needs the panel to know how many rows it
            // is holding, which only a specific dialog can say, and swallowing
            // Up/Down blindly here would strand focus on the first row.
            .focusGroup()
            .background(KBSurface, KBShapePanel)
            .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
                overflow = TextOverflow.Ellipsis
            )
        }
        content()
    }
}
