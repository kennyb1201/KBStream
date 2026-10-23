package com.kennyb1201.kbstream.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
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
 * The app's one short alpha fade: focus dims, art reveals, status plates.
 *
 * These lengths were being retyped at each call site - 140 in the guide and the
 * streams list, 200 for a GIF, nothing at all (a default spring) on two scales -
 * so the same-feeling interactions eased three different ways. One constant per
 * *kind* of motion, so a new surface inherits the app's tempo instead of
 * inventing a fourth.
 */
const val KB_ALPHA_FADE_MS = 140

/** Panel/dialog entrance. Exit is shorter: a dismissal should not linger. */
const val KB_PANEL_ENTER_MS = 180
const val KB_PANEL_EXIT_MS = 120

/** Player chrome (a View-world overlay) show/hide. */
const val KB_CHROME_FADE_MS = 160

/** Art arriving into a slot that is already the right size (a GIF, a logo). */
const val KB_ART_FADE_MS = 200

/** The easing every short KB transition uses, so they share one acceleration. */
val KB_EASE: Easing = FastOutSlowInEasing

/**
 * The one press-in spring (touch cards): a decisive but not bouncy give.
 *
 * Explicit rather than the `animateFloatAsState` default, which is a
 * platform-tuned spring that happened to differ from every sibling surface.
 */
val KB_PRESS_SPRING: SpringSpec<Float> =
    spring(dampingRatio = 0.8f, stiffness = 700f)

/**
 * A duration in the app's motion language, collapsed to a hard cut when the
 * viewer has asked the platform to remove animations.
 *
 * Every tween that is not already routed through [screenTransitionMs] should
 * take its length from here, so a reduced-motion user gets the same instant
 * cut the shell gives them instead of motion in exactly the scattered places
 * the system forgot.
 */
@Composable
fun rememberMotionMs(durationMs: Int): Int =
    if (rememberReducedMotion()) 0 else durationMs

/**
 * A 0 -> 1 progress for something that has just entered the composition, read
 * by the caller inside `graphicsLayer {}` so the animation costs no
 * recompositions.
 *
 * A `Dialog` window does not reliably run an `AnimatedVisibility` exit, which is
 * why the dialog plate animates its own entrance this way instead: the panel
 * mounts, then scales and fades up where it is drawn. Reduced motion returns 1
 * immediately - the plate is simply there.
 */
@Composable
fun rememberEntranceProgress(durationMs: Int = KB_PANEL_ENTER_MS): State<Float> {
    if (rememberReducedMotion()) return remember { mutableStateOf(1f) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = durationMs, easing = KB_EASE))
    }
    return progress.asState()
}

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
 * setting gets the right behavior on the next launch, and the alternative is
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
