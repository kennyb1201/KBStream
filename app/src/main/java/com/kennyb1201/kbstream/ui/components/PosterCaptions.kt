package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * Caption block shown under a poster tile, gated by the global
 * "Poster Titles / Years / Star Ratings" toggles. Every screen renders its
 * captions through this so the toggles behave identically everywhere;
 * screens pass null for parts their data source doesn't have (e.g.
 * collection parts carry no rating).
 *
 * When every enabled part is blank, emits nothing so tiles keep a tight,
 * uniform look instead of reserving empty space.
 */
@Composable
fun PosterCaptions(
    title: String?,
    year: String? = null,
    rating: Double? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val showTitle = AppPreferences.getPosterCaptionTitle(context)
    val showYear = AppPreferences.getPosterCaptionYear(context)
    val showRating = AppPreferences.getPosterCaptionRating(context)

    val titleLine = title?.takeIf { showTitle && it.isNotBlank() }
    val yearLine = year?.takeIf { showYear && it.isNotBlank() }
    val ratingLine = rating
        ?.takeIf { showRating && it > 0.0 }
        ?.let { "★ " + String.format("%.1f", it) }

    if (titleLine == null && yearLine == null && ratingLine == null) {
        return
    }

    Column(modifier = modifier) {
        titleLine?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = KBTextHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (yearLine != null || ratingLine != null) {
            val parts = listOfNotNull(yearLine, ratingLine)
            Text(
                text = parts.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = KBTextLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (titleLine != null) 2.dp else 0.dp)
            )
        }
    }
}
