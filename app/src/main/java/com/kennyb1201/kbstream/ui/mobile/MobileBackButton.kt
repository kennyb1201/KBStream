package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kennyb1201.kbstream.ui.theme.KBTextHi

/**
 * The one back control on a phone: a top-left arrow.
 *
 * Before this existed, mobile had three answers to "how do I leave this page":
 * the word BACK as a [com.kennyb1201.kbstream.ui.components.touch.KBButton], a
 * hand-rolled arrow on four screens, and nothing at all on the rest (system back
 * only). All three read as three different apps. Every screen now draws this and
 * nothing else, and no mobile screen ships a text BACK again.
 *
 * It is the pattern the four hand-rolled arrows already shared - an [IconButton]
 * with [Icons.AutoMirrored.Filled.ArrowBack], tinted [KBTextHi] - so the only
 * change on those screens is that the shape now lives in one place. The arrow is
 * auto-mirrored, so a right-to-left locale gets the arrow pointing the way that
 * locale reads.
 *
 * [IconButton] brings the standard 48dp minimum touch target, and the
 * [contentDescription] is what a screen reader announces - "Back", the same word
 * the system gesture is called.
 *
 * [onBack] is always the screen's own navigation callback, i.e. the same path the
 * hardware back button takes (see AppRoot's BackHandler). It is never a second
 * mechanism: return-focus records, the catalog grid's restored position and
 * Settings' pane ladder all behave exactly as they do for system back.
 */
@Composable
fun MobileBackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(onClick = onBack, modifier = modifier) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = KBTextHi
        )
    }
}
