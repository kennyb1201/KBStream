package com.kennyb1201.kbstream.ui.components.touch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.theme.KBShapeChip

/**
 * The touch twin of `ui/components/StudioChip.kt`: a network's or production
 * company's mark on a white plate, for the phone's Detail page.
 *
 * White, like the TV chip, because these marks are drawn for a light
 * background - an ink-black wordmark on the app's near-black surface is
 * unreadable, and recolouring somebody's logo is not this app's call. The plate
 * is the TV chip's own 120x54 with the mark fitted inside it, so the same
 * company reads the same size in both layouts; only the interaction differs (a
 * tap here, a D-pad press there), which is why this is a twin file rather than
 * a flag inside the TV one.
 *
 * A company with no logo path drawn by TMDB falls back to its name, as the TV
 * chip does - the plate is the block's shape either way.
 */
@Composable
fun TouchStudioChip(
    name: String,
    logoPath: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(120.dp)
            .height(54.dp)
            .clip(KBShapeChip)
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        val logo = logoPath?.takeIf { it.isNotBlank() }
        if (logo != null) {
            AsyncImage(
                model = TmdbRepository.LOGO_BASE + logo,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
            )
        } else {
            Text(
                text = name,
                color = Color.Black,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
