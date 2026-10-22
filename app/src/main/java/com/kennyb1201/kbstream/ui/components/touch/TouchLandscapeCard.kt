package com.kennyb1201.kbstream.ui.components.touch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.components.BrandMarkImage
import com.kennyb1201.kbstream.ui.components.WatchedCheckBadge
import com.kennyb1201.kbstream.ui.components.WatchedEyeBadge
import com.kennyb1201.kbstream.ui.components.posterBorderModifier
import com.kennyb1201.kbstream.ui.components.posterEdgeShape
import com.kennyb1201.kbstream.ui.components.posterTileRevision
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.OswaldFamily

/**
 * The touch twin of `ui/components/LandscapeCard.kt`: a 16:9 backdrop with a
 * clearlogo (or its title) in the bottom-left corner.
 *
 * The corner logo keeps the TV treatment, including `BrandMarkImage`'s
 * whitening rule: a black wordmark on this near-black corner is unreadable, a
 * coloured or already-light one is drawn as the artist made it, and a mark
 * that cannot be drawn at all hands the corner back to the plain title. That
 * logic is form-factor neutral, so it is reused rather than re-derived - the
 * whole point of the touch twins being new *files* is that the parts that are
 * not about input stay shared.
 *
 * [rank] draws the "Top … Today" numeral in the top-left, scaled to the card
 * the same way, over the same shadow; it is labelled for a screen reader
 * rather than left as a stray digit. The only TV-only affordance dropped is
 * the focus marquee on the corner title - on touch there is no focus to follow,
 * so a long title simply ellipsizes.
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

    val syncedRevision = posterTileRevision()
    val eyeBadgeEnabled = remember(syncedRevision) {
        AppPreferences.getPosterPartialWatchBadge(context)
    }
    val showEyeBadge = isPartiallyWatched && eyeBadgeEnabled

    var logoFailed by remember(logoUrl) { mutableStateOf(false) }
    val logo = logoUrl?.takeIf { it.isNotBlank() }
    val showLogo = logo != null && !logoFailed
    var logoPainted by remember(logoUrl) { mutableStateOf(false) }
    // The title until the art paints, and the title alone when there is no
    // usable art at all - the same handover the TV card makes, without a fade.
    val showTitle = !showLogo || !logoPainted

    KBCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = posterEdgeShape()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(KBSurface)
                .then(posterBorderModifier())
        ) {
            // Same scaling as TV: a 210dp rail card draws a ~28dp logo, clamped
            // so the widest row does not grow a wordmark past the corner.
            val logoHeight = (maxWidth * 0.135f).coerceIn(22.dp, 32.dp)
            val logoMaxWidth = maxWidth * 0.68f

            val effectiveUrl = backdropUrl?.takeIf { it.isNotBlank() }

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
                        text = contentDescription ?: fallbackTitle ?: "No Image",
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
                BrandMarkImage(
                    url = logo,
                    contentDescription = fallbackTitle?.takeIf { it.isNotBlank() },
                    onDrawn = { logoPainted = true },
                    onUnusable = { logoFailed = true },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, bottom = 6.dp)
                        .height(logoHeight)
                        .widthIn(max = logoMaxWidth)
                )
            }

            if (showTitle && !fallbackTitle.isNullOrBlank()) {
                Text(
                    text = fallbackTitle,
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, bottom = 6.dp)
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
