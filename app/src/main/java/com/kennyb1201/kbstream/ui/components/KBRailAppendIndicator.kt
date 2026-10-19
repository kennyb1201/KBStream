package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.ui.theme.KBAccent

/**
 * The tail card of a browse rail that is fetching its next page.
 *
 * The rails (Tag, Studio, Decade) threaded `isLoadingMore` all the way down to
 * their sentinel and drew NOTHING for it, so a page in flight looked exactly
 * like the end of the list. The same was true of a page that FAILED: those
 * view-models deliberately keep `hasMore` true and wait for another scroll
 * delta (see their loadMoreSection), which from the couch is a rail that simply
 * stops. `CatalogGridScreen` has drawn both states at its tail for a while -
 * this is that tail, sized to sit in a rail of posters instead of a grid cell.
 *
 * `width`/`height` are the tile box the caller's own cards use, so the spinner
 * lands on the same baseline as the rail it belongs to.
 */
@Composable
fun KBRailAppendIndicator(
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(width)
            .height(height),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            color = KBAccent,
            strokeWidth = 2.dp
        )
    }
}
