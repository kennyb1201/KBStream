package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.OswaldFamily

/**
 * Landscape (16:9) rail card for the Home "Landscape Cards" toggle: a
 * backdrop image with no title text and a small clearlogo in the bottom-
 * left corner. Falls back to a small plain-text title in the same corner
 * when no clearlogo is available, and to centered text when the image
 * itself is missing — mirroring PosterCard's degradation ladder.
 * Focus treatment (border/glow/scale) comes from KBCard, identical to
 * PosterCard, so the two card shapes mix cleanly in the D-pad order.
 *
 * [rank] is the pinned "Top ... Today" numbering: the card's position in a row
 * that is a standing rather than a browse order. Those rows are served TMDB
 * artwork, which carries no number (see `LandscapeArtRequest.tmdbOnly`), so the
 * number is drawn here — top-left, the one corner the logo, the title and the
 * watched badge do not already own.
 */
@Composable
fun LandscapeCard(
    backdropUrl: String?,
    logoUrl: String?,
    fallbackTitle: String?,
    contentDescription: String?,
    isWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isPartiallyWatched: Boolean = false,
    rank: Int? = null
) {
    val context = LocalContext.current
    var hasError by remember(backdropUrl) { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // Drives the fallback-title marquee at the bottom of the card. The card
    // itself is focusable, so the tile's own focus state is enough here —
    // nothing has to be threaded down from the rail.

    // Settings toggle: the eye badge (started-but-not-finished shows) can be
    // switched off app-wide; the completed checkmark is always unaffected.
    val showEyeBadge = isPartiallyWatched &&
        AppPreferences.getPosterPartialWatchBadge(context)
    // Logo URL present but unloadable (dead TMDB path, CDN 404): fall back
    // to the plain title text instead of a silent blank corner.
    var logoFailed by remember(logoUrl) { mutableStateOf(false) }
    val showLogo = !logoUrl.isNullOrBlank() && !logoFailed
    // ...and a logo URL that exists but has not LOADED is not art on screen
    // either. The corner used to go blank for as long as the fetch took, which
    // on a rail of cards is the same blank corner the 404 above was fixed for -
    // and the same bug the pre-playback splash had (a title that is missing and
    // then there on the next pass, because that one is a cache hit).
    var logoPainted by remember(logoUrl) { mutableStateOf(false) }
    // What the corner shows: the title until the art has painted, and the
    // title alone when there is no usable art at all. No fade on the handover,
    // unlike the splash's - this composes once per card in a rail and the swap
    // lands in a single frame, because these requests do not crossfade.
    val showTitle = !showLogo || !logoPainted

    KBCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onFocusChanged { focused = it.hasFocus },
        shape = posterEdgeShape()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(KBSurface)
                // Same faint poster edge the tiles draw, so the two card
                // shapes mix cleanly in a rail.
                .then(posterBorderModifier())
        ) {
            // Corner logo scales with the card (210dp-wide rails -> ~28dp
            // logo) instead of the old fixed 20dp that read as tiny.
            val logoHeight = (maxWidth * 0.135f).coerceIn(22.dp, 32.dp)
            val logoMaxWidth = maxWidth * 0.68f

            val effectiveUrl =
                backdropUrl?.takeIf { it.isNotBlank() }

            if (!effectiveUrl.isNullOrBlank() && !hasError) {
                AsyncImage(
                    model = remember(effectiveUrl) {
                        ImageRequest.Builder(context)
                            .data(effectiveUrl)
                            .crossfade(false)
                            .build()
                    },
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onError = { hasError = true }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contentDescription
                            ?: fallbackTitle
                            ?: "No Image",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3
                    )
                }
            }

            // Readability scrim behind the logo/title corner.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                KBVoid.copy(alpha = 0.85f)
                            )
                        )
                    )
            )

            // The rank, on a row that is a standing ("Top Movies Today").
            // Scaled to the card rather than fixed, so the numeral reads the
            // same on a 210dp Home rail and on a wider row; and over a shadow,
            // because it lands on whatever a backdrop happens to be - a white
            // numeral on a bright sky is not a numeral anyone can read.
            //
            // 0.22 rather than the original 0.26: it is a label on the
            // artwork, not a headline, and at 0.26 a 210dp rail drew it ~55dp
            // tall, which read as the latter and competed with the backdrop it
            // sits on. The bounds move with it so the scaling still holds from
            // the narrowest rail to the widest row.
            if (rank != null) {
                val rankSize = with(LocalDensity.current) {
                    (maxWidth * 0.22f).coerceIn(26.dp, 48.dp).toSp()
                }
                Text(
                    text = rank.toString(),
                    color = KBTextHi,
                    fontFamily = OswaldFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = rankSize,
                    style = TextStyle(
                        shadow = Shadow(
                            color = KBVoid,
                            offset = Offset(2f, 2f),
                            blurRadius = 12f
                        )
                    ),
                    // Artwork, like the clearlogo beside it, but this one is
                    // information: labeled rather than left as a stray digit.
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = 4.dp)
                        // `this` is load-bearing: the composable's own
                        // contentDescription parameter is in scope here and
                        // would otherwise be the thing being assigned to.
                        .clearAndSetSemantics {
                            this.contentDescription = "Rank $rank"
                        }
                )
            }

            if (showLogo) {
                AsyncImage(
                    model = remember(logoUrl) {
                        ImageRequest.Builder(context)
                            .data(logoUrl)
                            .crossfade(false)
                            .build()
                    },
                    // The corner clearlogo is artwork, not text; give it the
                    // title it stands in for so it is not an unlabeled image.
                    contentDescription = fallbackTitle?.takeIf { it.isNotBlank() },
                    contentScale = ContentScale.Fit,
                    onError = { logoFailed = true },
                    onSuccess = { logoPainted = true },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, bottom = 6.dp)
                        .height(logoHeight)
                        .widthIn(max = logoMaxWidth)
                )
            }

            // The corner title, in the same slot as the logo: the no-logo and
            // dead-logo case, which is where it always was, plus the load the
            // logo has not finished (see showTitle).
            if (showTitle && !fallbackTitle.isNullOrBlank()) {
                // The on-card title slot, like every other card title: this was
                // the last card title still written at a 13sp literal, a size
                // the type scale does not have.
                Text(
                    text = fallbackTitle,
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, bottom = 6.dp)
                        .kbFocusMarquee(focused)
                )
            }

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
