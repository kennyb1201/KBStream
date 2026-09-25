package com.kennyb1201.kbstream.ui.components

import android.provider.Settings
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Standard screen-transition length. Long enough to read as motion, short
 * enough that a D-pad-happy user never waits on it.
 */
const val KB_SCREEN_TRANSITION_MS = 220

/**
 * True when the user has asked the platform for less motion — turning off
 * "Remove animations" in TV Settings / Developer options writes 0 to the
 * animator duration scale.
 *
 * This did not exist because the app had no screen motion to reduce: every
 * navigation was an instant cut. Now that transitions exist, every animated
 * affordance has to be able to collapse to that same instant cut, because an
 * app that keeps sliding panes around for someone who has explicitly asked it
 * to stop is the motion equivalent of ignoring a text-size preference.
 *
 * Read once per composition rather than observed: a user who changes the
 * setting gets the right behaviour on the next launch, and the alternative is
 * a ContentObserver on a global setting held open for the whole session.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f) == 0f
    }
}

/** [KB_SCREEN_TRANSITION_MS] unless motion is reduced, where it is a hard cut. */
fun screenTransitionMs(reducedMotion: Boolean): Int =
    if (reducedMotion) 0 else KB_SCREEN_TRANSITION_MS

/** Slower than basicMarquee's 30dp/s default: this is read at ten feet. */
private val KB_MARQUEE_VELOCITY = 24.dp

/**
 * Scrolls a one-line title while its tile holds D-pad focus.
 *
 * Every title that has to live on one line behind an ellipsis — the caption
 * under a poster, the fallback title in a landscape card's corner — arrives
 * truncated, and focus is exactly the moment the viewer is asking what the
 * thing is. A D-pad has no hover and no tooltip, so the line itself slides
 * instead. basicMarquee is documented to have no effect when the content
 * already fits, so short titles never move; reduced motion leaves the plain
 * ellipsis in place, the same cut every other animation in the app takes.
 */
@Composable
fun Modifier.kbFocusMarquee(focused: Boolean): Modifier {
    val reducedMotion = rememberReducedMotion()
    if (!focused || reducedMotion) return this
    return basicMarquee(
        iterations = Int.MAX_VALUE,
        // Let the eye land on the words before they move, and pause a beat at
        // the end of each pass instead of snapping straight back.
        initialDelayMillis = 400,
        repeatDelayMillis = 1_500,
        velocity = KB_MARQUEE_VELOCITY
    )
}
