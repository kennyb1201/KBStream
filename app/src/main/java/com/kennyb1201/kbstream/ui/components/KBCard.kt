package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import com.kennyb1201.kbstream.ui.theme.CardShape
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi

@Composable
fun KBCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = CardShape),
        colors = CardDefaults.colors(
            containerColor = KBSurface,
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBAccent
        ),
        scale = CardDefaults.scale(scale = 1f, focusedScale = 1.08f),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, KBAccent),
                shape = CardShape
            )
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(elevationColor = KBAccent, elevation = 12.dp)
        ),
        modifier = modifier.onKeyEvent { event ->
            if (
                onLongClick != null &&
                event.type == KeyEventType.KeyUp &&
                (event.key == Key.Menu || event.key == Key.DirectionCenter)
            ) {
                onLongClick()
                true
            } else {
                false
            }
        },
        content = content
    )
}
