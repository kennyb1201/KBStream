package com.kennyb1201.kbstream.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.R
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * The Compose half of the launch splash: the brass play-button mark with the
 * "KBStream" wordmark under it, on the void.
 *
 * It continues the frame the launch theme's window background was already
 * painting (see @drawable/splash_background / Theme.KBStream.Splash), so the
 * mark is drawn at the SAME dp size and the SAME place - both read
 * `@dimen/splash_logo_size` and both centre the mark's own 108dp box on the
 * screen. Only the wordmark is new, which is why it sits below the mark
 * without moving it: the mark cannot shift between the two frames.
 *
 * Purely visual, on purpose. It takes no focus, handles no D-pad key and
 * intercepts no Back: the screen underneath is already composing and already
 * owns the input, and a splash that swallowed a press would lose it on the
 * frame it disappears. It goes away by itself the moment the content behind it
 * is ready (see launchSplashVisible), never on a timer.
 *
 * The colour comes from [KBVoid], not a literal, so an AMOLED viewer gets the
 * pure-black background the rest of the app is painting under it.
 */
@Composable
fun LaunchSplash() {
    val logoSize = dimensionResource(R.dimen.splash_logo_size)
    val pulse = rememberSplashPulse()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                // The mark's BOX is what stays put. This column is centred on
                // the screen, which puts the mark's centre half of the
                // trailing block too high; pushing the column back down by
                // exactly that much leaves the mark itself dead centre, where
                // the window background drew it.
                .offset(y = (WordmarkGap + WordmarkHeight) / 2),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                // Decorative: the wordmark below is the name, and the splash
                // is not a control a screen reader should stop on.
                contentDescription = null,
                modifier = Modifier
                    .size(logoSize)
                    // Read in the draw phase (like the skeleton rails) so the
                    // pulse costs no recomposition at all.
                    .graphicsLayer { alpha = pulse.value }
            )
            Spacer(Modifier.height(WordmarkGap))
            Text(
                text = "KBStream",
                modifier = Modifier
                    // A fixed slot, because the offset above has to know how
                    // tall the wordmark is to leave the mark centred - and a
                    // text measured at composition time cannot be asked.
                    .height(WordmarkHeight)
                    .wrapContentHeight(Alignment.CenterVertically),
                style = MaterialTheme.typography.headlineLarge.copy(
                    letterSpacing = 6.sp
                ),
                color = KBAccent,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Gap between the mark's box and the wordmark. */
private val WordmarkGap = 4.dp

/** The wordmark's slot; taller than the 28sp line so a large font setting still fits. */
private val WordmarkHeight = 44.dp

/**
 * The splash's breathing mark, as a State rather than a plain Float.
 *
 * Same reasoning as the skeleton's pulse: the caller reads it inside
 * `Modifier.graphicsLayer { }`, so it animates on the draw path instead of
 * recomposing the splash at 60fps. Reduced motion holds it still - the logo is
 * already the one thing on screen, so it does not need to move to be read.
 */
@Composable
private fun rememberSplashPulse(): State<Float> {
    if (rememberReducedMotion()) return remember { mutableStateOf(1f) }

    val transition = rememberInfiniteTransition(label = "launchSplash")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.76f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "launchSplashPulse"
    )
}
