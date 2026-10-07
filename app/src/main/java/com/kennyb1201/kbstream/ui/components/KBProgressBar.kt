package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * The bar that says how far into something the viewer is: a poster's resume
 * position, the guide's live program, an Up Next card's progress.
 *
 * Three bars had grown up independently and none of them agreed - a 3dp bar and
 * a 4dp bar, one on a capsule clip and two on square ends, and three different
 * track tones (KBVoid at 55%, KBTextHi at 28%, KBTextLo at 45%). The track is
 * the bar's own plate, so it has to read the same way under a poster on Home as
 * it does under a program in the guide: [KBTextLo] at 45%, the app's dimmest
 * text tone, with [KBAccent] as the fill.
 *
 * The fill is clamped to 0..1 and the caller decides the width; a bar that is
 * 0% is the caller's choice to not draw at all.
 */
@Composable
fun KBProgressBar(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PROGRESS_HEIGHT)
            .background(KBTextLo.copy(alpha = 0.45f), PROGRESS_SHAPE)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(KBAccent, PROGRESS_SHAPE)
        )
    }
}

/** 4dp: thick enough to read at ten feet, thin enough to be a bar not a band. */
private val PROGRESS_HEIGHT = 4.dp

/**
 * A 2dp radius - the one literal the shape scale deliberately does not own. The
 * shape of a bar four dp tall genuinely depends on the exact corner value, which
 * is why Theme.kt leaves these hairline radii at the component (see its note on
 * the corner scale).
 */
private val PROGRESS_SHAPE = RoundedCornerShape(2.dp)
