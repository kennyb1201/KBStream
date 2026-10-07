package com.kennyb1201.kbstream.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapeSmall
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised

/**
 * Loading placeholders shaped like the content that is about to arrive.
 *
 * Before these, a loading rail showed either nothing or a lone spinner, so the
 * page arrived in two jolts: the shell appeared, then everything shifted down
 * when the posters landed. A skeleton rail reserves the exact space the real
 * rail needs, so the layout never moves and there is no dead spinner to stare
 * at.
 *
 * The rule this establishes: **loading gets a skeleton, empty and error get
 * the [KBStatusMessage] card.** A skeleton says "this is coming"; the card
 * says "there is nothing here" or "this failed", and those must not look alike.
 */
/**
 * The skeleton pulse, as a State rather than a plain Float.
 *
 * It is deliberately NOT read in composition: a plain value forces every
 * placeholder on screen to recompose at ~60fps for the whole loading window -
 * exactly while the page is also composing the content arriving behind it. The
 * caller reads this inside `Modifier.graphicsLayer { }`, which defers the read
 * to the draw phase, so the pulse animates without a single recomposition.
 */
@Composable
private fun rememberSkeletonAlpha(): State<Float> {
    // Reduced motion: hold a single mid tone instead of pulsing. The
    // placeholders still read as placeholders, they just do not breathe.
    if (rememberReducedMotion()) return remember { mutableStateOf(0.55f) }

    val transition = rememberInfiniteTransition(label = "skeleton")
    return transition.animateFloat(
        initialValue = 0.34f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
}

@Composable
private fun KBSkeletonTile(
    width: Dp,
    height: Dp,
    alpha: State<Float>,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = KBShapeCard
) {
    Box(
        modifier = modifier
            .size(width = width, height = height)
            // graphicsLayer BEFORE the background, so the layer wraps the fill
            // it pulses. Reading [alpha] here (and not in composition) is what
            // keeps the pulse off the recomposition path.
            .graphicsLayer { this.alpha = alpha.value }
            .background(KBSurfaceRaised, shape)
    )
}

/**
 * One rail's worth of placeholders: a title bar and a row of poster-shaped
 * tiles, sized from the Poster Size setting so the skeleton occupies exactly
 * the space the real rail will.
 */
@Composable
fun KBSkeletonRail(
    posterWidth: Dp,
    posterHeight: Dp,
    modifier: Modifier = Modifier,
    itemCount: Int = 6,
    horizontalPadding: Dp = 20.dp,
    titleWidth: Dp = 168.dp,
    // The poster's own edge (see posterEdgeShape): a placeholder that is
    // rounded while the real tile is a pill promises the wrong thing and makes
    // the rail jump in shape as the posters land, which is the one jolt a
    // skeleton exists to prevent.
    shape: androidx.compose.ui.graphics.Shape = KBShapeCard
) {
    val alpha = rememberSkeletonAlpha()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(start = horizontalPadding, bottom = 10.dp)
                .width(titleWidth)
                .height(18.dp)
                .graphicsLayer { this.alpha = alpha.value }
                .background(KBSurfaceRaised, KBShapeSmall)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = horizontalPadding, end = horizontalPadding)
        ) {
            repeat(itemCount) {
                KBSkeletonTile(
                    width = posterWidth,
                    height = posterHeight,
                    alpha = alpha,
                    shape = shape
                )
            }
        }
    }
}

/** A stacked set of skeleton rails, for a whole browse page that is loading. */
@Composable
fun KBSkeletonRailStack(
    posterWidth: Dp,
    posterHeight: Dp,
    modifier: Modifier = Modifier,
    railCount: Int = 3,
    itemCount: Int = 6,
    horizontalPadding: Dp = 20.dp,
    shape: androidx.compose.ui.graphics.Shape = KBShapeCard
) {
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(railCount) {
            KBSkeletonRail(
                posterWidth = posterWidth,
                posterHeight = posterHeight,
                itemCount = itemCount,
                horizontalPadding = horizontalPadding,
                shape = shape
            )
        }
    }
}

/** A grid of poster-shaped placeholders, for a catalog grid that is loading. */
@Composable
fun KBSkeletonGrid(
    cellWidth: Dp,
    cellHeight: Dp,
    columns: Int,
    modifier: Modifier = Modifier,
    rows: Int = 2,
    horizontalPadding: Dp = 20.dp,
    verticalSpacing: Dp = 14.dp,
    shape: androidx.compose.ui.graphics.Shape = KBShapeCard
) {
    val alpha = rememberSkeletonAlpha()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing)
    ) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(columns) {
                    KBSkeletonTile(
                        width = cellWidth,
                        height = cellHeight,
                        alpha = alpha,
                        shape = shape
                    )
                }
            }
        }
    }
}
