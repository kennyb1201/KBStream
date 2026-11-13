package com.kennyb1201.kbstream.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * Home's seasonal card: one press into the review behind it.
 *
 * A rail would need posters the review does not have; a card says "there is a
 * page here" in the space of a header, which is what this is.
 */
@Composable
fun YearInReviewHomeCard(
    year: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = KBShapeCard),
        colors = ClickableSurfaceDefaults.colors(containerColor = KBSurfaceRaised),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "YEAR IN REVIEW",
                    color = KBAccent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = YearInReviewCard.title(year),
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Hours, finishes, your top shows and your busiest day - " +
                        "counted from this profile's history.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = "OPEN ›",
                color = KBAccent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
