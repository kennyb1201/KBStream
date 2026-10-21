package com.kennyb1201.kbstream.ui.components.touch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.ui.components.KBButton
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * The phone's spacing scale.
 *
 * These are the numbers the portrait screens had already converged on one copy
 * at a time, so they are named rather than re-drawn: [edge] is the screen
 * gutter, [section] the breathing room above a new block, [gap] the space
 * between a heading and its rail, [chip] the space between two chips and
 * [row] the space between a row's label and its control. Naming them is what
 * stops the next screen from inventing 14dp.
 *
 * Not everything belongs here - a one-off inset inside a card is not a token,
 * and pretending otherwise turns the scale into a second set of magic numbers.
 */
object MobileSpacing {
    val edge: Dp = 16.dp
    val section: Dp = 18.dp
    val gap: Dp = 10.dp
    val chip: Dp = 8.dp
    val row: Dp = 6.dp

    /** Overlay chip padding on artwork (the poster badges). */
    val chipInsetX: Dp = 6.dp

    /** Overlay chip padding on artwork (the poster badges). */
    val chipInsetY: Dp = 3.dp

    /** The breathing room inside a status card or an empty state. */
    val panel: Dp = 18.dp
}

/**
 * The one heading above a rail, list or block.
 *
 * Every portrait screen had grown its own copy of "titleMedium, semibold, one
 * line, 16dp gutter", and the copies had started to disagree about the gutter.
 * This is that design, once. A caller that needs a different inset passes
 * [modifier] instead of rebuilding the text style.
 */
@Composable
fun MobileSectionHeader(
    title: String,
    modifier: Modifier = Modifier.padding(horizontal = MobileSpacing.edge)
) {
    Text(
        text = title,
        color = KBTextHi,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/**
 * The small accent label that opens a group of settings or controls.
 *
 * Kept separate from [MobileSectionHeader] because the two are different
 * ranks, not the same heading at two sizes: an eyebrow names the group
 * ("PLAYBACK", "SOURCES"), a header names the content below it ("Continue
 * Watching"). The settings panes and the catalog builder both draw the eyebrow,
 * and they now draw the same one.
 */
@Composable
fun MobileEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    first: Boolean = false
) {
    Text(
        text = text.uppercase(),
        color = KBAccent,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(
            top = if (first) 2.dp else MobileSpacing.section,
            bottom = MobileSpacing.row
        )
    )
}

/**
 * A hairline between two settings rows.
 *
 * The phone had three divider recipes (a 1dp box, a `HorizontalDivider` with a
 * colour, and a box at 0.5f alpha). One width, one colour.
 */
@Composable
fun MobileDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(KBTextLo.copy(alpha = 0.16f))
    )
}

/** The centred spinner every portrait screen was drawing by hand. */
@Composable
fun MobileLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = KBAccent)
    }
}

/** How a [MobileStatus] sits on its screen. */
enum class MobileStatusTone {
    /** Centred on the page's own background - an empty rail or a failed load. */
    Plain,

    /** A filled card - a whole page that has nothing to show yet. */
    Card
}

/**
 * The one loading/empty/error message.
 *
 * Portrait screens had eight shapes for this: a centred dim line, a card with a
 * title and a retry, an uppercase headline with a paragraph, and in two places
 * a spinner that never stopped. They all mean one of three things, so they now
 * come from one place and differ only by [title], the optional [actionLabel]
 * and the [tone].
 *
 * [message] carries the detail; [title] is optional because a short "No matches
 * found." needs no headline above it. An action is only drawn when both the
 * label and the callback are present, so a caller cannot ship a dead button.
 */
@Composable
fun MobileStatus(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    tone: MobileStatusTone = MobileStatusTone.Plain,
    centeredVertically: Boolean = false
) {
    val gap = MobileSpacing.row + 4.dp
    when (tone) {
        MobileStatusTone.Plain -> Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = MobileSpacing.edge, vertical = MobileSpacing.panel),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (centeredVertically) {
                Arrangement.spacedBy(gap, Alignment.CenterVertically)
            } else {
                Arrangement.spacedBy(gap)
            }
        ) {
            if (title != null) {
                Text(
                    text = title,
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = message,
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )
            if (actionLabel != null && onAction != null) {
                KBButton(label = actionLabel, onClick = onAction)
            }
        }

        MobileStatusTone.Card -> Column(
            modifier = modifier
                .padding(MobileSpacing.edge)
                .fillMaxWidth()
                .background(KBSurface, KBShapeCard)
                .padding(MobileSpacing.panel)
        ) {
            if (title != null) {
                Text(
                    text = title,
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Box(modifier = Modifier.height(MobileSpacing.row))
            }
            Text(
                text = message,
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )
            if (actionLabel != null && onAction != null) {
                Box(modifier = Modifier.height(MobileSpacing.gap + 2.dp))
                KBButton(label = actionLabel, onClick = onAction)
            }
        }
    }
}
