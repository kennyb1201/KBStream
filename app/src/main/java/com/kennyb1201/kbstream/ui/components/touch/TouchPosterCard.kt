package com.kennyb1201.kbstream.ui.components.touch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.components.WatchedCheckBadge
import com.kennyb1201.kbstream.ui.components.WatchedEyeBadge
import com.kennyb1201.kbstream.ui.components.posterBorderModifier
import com.kennyb1201.kbstream.ui.components.posterEdgeShape
import com.kennyb1201.kbstream.ui.components.posterTileRevision
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * The touch twin of `ui/components/PosterCard.kt`: every catalog poster tile,
 * now tappable.
 *
 * The degradation ladder is deliberately identical to the TV tile, because it
 * is what a first paint looks like and it was tuned in the field there:
 *
 *  - the artwork, with **no** crossfade (a rail paints many posters at once,
 *    and the per-tile fade-in was measurable on Kenney's boxes);
 *  - the fallback title text whenever the poster is missing, failed, or simply
 *    has not painted yet - the last case is the one that used to be a blank
 *    hole in a scrolling grid;
 *  - the caller's overlay first, then the watch badge last, so an episode
 *    card's readability gradient cannot dim its own marker.
 *
 * What changes is only the surface: [KBCard] here is the touch twin, so a tap
 * opens the title and a long press raises the same context menu the TV reaches
 * with a hold on the remote's center key. The chosen poster edge and the faint
 * poster border are read through the same shared helpers, so the shape the
 * viewer picked in Settings follows them to the phone.
 */
@Composable
fun PosterCard(
    posterUrl: String?,
    contentDescription: String?,
    isWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isPartiallyWatched: Boolean = false,
    onPosterError: ((Throwable?) -> Unit)? = null,
    overlayContent: (@Composable BoxScope.() -> Unit)? = null
) {
    val context = LocalContext.current
    var hasError by remember(posterUrl) { mutableStateOf(false) }
    var posterPainted by remember(posterUrl) { mutableStateOf(false) }

    // Settings toggle: the eye badge can be switched off app-wide; the
    // completed checkmark is always unaffected. Keyed on the sync revision
    // because the toggle is a SYNCED pref - the same read as the TV tile.
    val syncedRevision = posterTileRevision()
    val eyeBadgeEnabled = remember(syncedRevision) {
        AppPreferences.getPosterPartialWatchBadge(context)
    }
    val showEyeBadge = isPartiallyWatched && eyeBadgeEnabled

    KBCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = posterEdgeShape()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KBSurface)
                .then(posterBorderModifier())
        ) {
            if (!posterUrl.isNullOrBlank() && !hasError) {
                AsyncImage(
                    model = remember(posterUrl) {
                        ImageRequest.Builder(context)
                            .data(posterUrl)
                            .crossfade(false)
                            .build()
                    },
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { posterPainted = true },
                    onError = { state ->
                        hasError = true
                        onPosterError?.invoke(state.result.throwable)
                    }
                )
            }

            if (posterUrl.isNullOrBlank() || hasError || !posterPainted) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contentDescription ?: "No Image",
                        color = KBTextLo,
                        fontSize = 12.sp,
                        maxLines = 3
                    )
                }
            }

            overlayContent?.invoke(this)

            if (isWatched) {
                WatchedCheckBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            } else if (showEyeBadge) {
                WatchedEyeBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
        }
    }
}
