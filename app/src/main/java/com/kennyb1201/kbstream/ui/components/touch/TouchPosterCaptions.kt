package com.kennyb1201.kbstream.ui.components.touch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.components.posterTileRevision
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * The touch twin of `ui/components/PosterCaptions.kt`: the caption block under a
 * phone poster tile, gated by the same three synced Settings switches
 * ("Poster titles", "Poster years", "Poster star ratings").
 *
 * The phone tiles each hand-rolled their own title and year · ★ line, so the
 * Settings toggles stopped at the TV: a viewer who switched titles off got bare
 * posters on the television and captions on every phone page. The block itself
 * is also the bug: a caption drawn by the tile cannot be switched off by
 * anything but the tile, which is why the copies of it drifted apart in size as
 * well as in whether they obeyed the prefs.
 *
 * Every part the caller does not have is null, exactly as on TV (collection
 * parts carry no rating, a KB folder entry may carry no year). When every
 * enabled part is blank this emits nothing, so tiles keep a tight, uniform look
 * instead of reserving empty space.
 *
 * No marquee: a phone tile has no focus for one to follow, and a phone grid does
 * not need it - the title wraps to [titleMaxLines] and ellipsizes.
 */
@Composable
fun TouchPosterCaptions(
    title: String?,
    year: String? = null,
    rating: Double? = null,
    modifier: Modifier = Modifier,
    titleMaxLines: Int = 2
) {
    // The three toggles are SYNCED prefs, so the captions are keyed on the sync
    // revision: a part switched off on another device has to leave the tiles
    // already on screen, and a bare pref read could not do that (see
    // [PrefsPayloadApplier.revision]).
    val context = LocalContext.current
    val syncedRevision = posterTileRevision()
    val showTitle = remember(syncedRevision) { AppPreferences.getPosterCaptionTitle(context) }
    val showYear = remember(syncedRevision) { AppPreferences.getPosterCaptionYear(context) }
    val showRating = remember(syncedRevision) { AppPreferences.getPosterCaptionRating(context) }

    val titleLine = title?.takeIf { showTitle && it.isNotBlank() }
    val yearLine = year?.takeIf { showYear && it.isNotBlank() }
    val ratingLine = rating
        ?.takeIf { showRating && it > 0.0 }
        ?.let { "★ %.1f".format(it) }

    if (titleLine == null && yearLine == null && ratingLine == null) {
        return
    }

    Column(modifier = modifier) {
        titleLine?.let {
            Text(
                text = it,
                color = KBTextHi,
                style = MaterialTheme.typography.labelMedium,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (yearLine != null || ratingLine != null) {
            Text(
                text = listOfNotNull(yearLine, ratingLine).joinToString(" · "),
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (titleLine != null) 2.dp else 0.dp)
            )
        }
    }
}
