package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.badges.StreamBadge

/** Height of one badge chip row (chips: 20dp image + padding). */
private val BadgeChipShape = RoundedCornerShape(6.dp)
private const val BADGE_ROW_HEIGHT = 22

private fun String.toBadgeColorOrNull(): Color? {
    val hex = trim().removePrefix("#")
    val argb = when (hex.length) {
        6 -> "FF$hex"
        8 -> hex
        else -> return null
    }
    return argb.toLongOrNull(16)?.let { Color(it) }
}

/**
 * One Nuvio-compatible stream badge: hosted image art sized like Nuvio's
 * chips, with the filter's optional tag/border colors. Text-only filters
 * (no imageURL) render their name in a colored pill instead.
 */
@Composable
fun StreamBadgeChip(
    badge: StreamBadge,
    modifier: Modifier = Modifier,
    imageHeight: Int = 16
) {
    val filled = badge.tagStyle.equals("filled", ignoreCase = true)
    val background = if (filled) badge.tagColor.toBadgeColorOrNull() else null
    val outline = badge.borderColor.toBadgeColorOrNull()

    Box(
        modifier = modifier
            .height((imageHeight + 6).dp)
            .clip(BadgeChipShape)
            .then(
                if (background != null) {
                    Modifier.background(background, BadgeChipShape)
                } else {
                    Modifier
                }
            )
            .then(
                if (outline != null) {
                    Modifier.border(1.dp, outline, BadgeChipShape)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 3.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        if (badge.imageURL.isNotBlank()) {
            // A failed image load (dead URL, unsupported format) must never
            // collapse into an invisible empty chip — fall back to a text
            // pill so the badge pack's name is always visible.
            var imageFailed by remember(badge.imageURL) { mutableStateOf(false) }
            if (imageFailed) {
                BadgeTextPill(badge)
            } else {
                AsyncImage(
                    model = badge.imageURL,
                    contentDescription = badge.name,
                    contentScale = ContentScale.Fit,
                    onError = { imageFailed = true },
                    modifier = Modifier
                        .height(imageHeight.dp)
                        .widthIn(min = 34.dp, max = 92.dp)
                )
            }
        } else {
            BadgeTextPill(badge)
        }
    }
}

/** Text-only rendering of a badge (no art or art failed to load). */
@Composable
private fun BadgeTextPill(badge: StreamBadge) {
    androidx.compose.material3.Text(
        text = badge.name,
        color = badge.textColor.toBadgeColorOrNull() ?: Color.White,
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

/** A horizontal row of badge chips; empty when the stream has no badges. */
@Composable
fun StreamBadgeRow(
    badges: List<StreamBadge>,
    modifier: Modifier = Modifier,
    imageHeight: Int = 16
) {
    if (badges.isEmpty()) return
    Row(
        modifier = modifier.height(BADGE_ROW_HEIGHT.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        badges.forEach { badge ->
            StreamBadgeChip(badge = badge, imageHeight = imageHeight)
        }
    }
}
