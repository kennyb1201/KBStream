package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.ui.theme.KBTextHi

/**
 * The name of the screen the user is on — Library, Add-ons, Live TV, a catalog
 * grid.
 *
 * These were four different headings for the same job: Library drew
 * headlineMedium + Bold, Add-ons and the guide drew headlineLarge + SemiBold,
 * and a catalog grid drew headlineSmall + Bold, so the app's own name for the
 * current screen changed size every time the user navigated. One style now,
 * and the typeface comes from the theme rather than from whichever slot the
 * call site happened to pick.
 *
 * [color] stays a parameter because the accent-coloured title on Library is a
 * deliberate brand beat, not an accident — but the size and weight no longer
 * vary.
 */
@Composable
fun KBPageTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = KBTextHi
) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineLarge,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/**
 * A label above a run of content inside a screen — "Suggestions", "Recent
 * searches", "Browse", a settings group.
 *
 * This existed twice, byte-identical, as a private `SectionHeader` in the
 * search screen and in the sync settings section. The player's settings panel
 * keeps its own accent-coloured variant on purpose: it is an overlay on top of
 * video, not a page, and its labels read as chrome there rather than as
 * headings.
 */
@Composable
fun KBSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = KBTextHi
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(bottom = 6.dp)
    )
}
