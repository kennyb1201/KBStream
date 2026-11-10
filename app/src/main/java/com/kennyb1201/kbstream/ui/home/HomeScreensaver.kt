package com.kennyb1201.kbstream.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.ui.components.rememberReducedMotion
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay

/**
 * The TV Home screensaver: Home's own artwork, full screen, when nobody is
 * touching the remote.
 *
 * This is a Home-LOCAL overlay, not a navigation destination and not a platform
 * Daydream: a DreamService would live outside the app, need its own manifest
 * service and could only be switched on from the TV's own settings - the
 * opposite of a row inside KBStream. So the back stack is untouched, no
 * navigation event fires on entry or exit, and the switch is a Settings toggle.
 *
 * What it shows is the slide list the caller snapshotted when it armed, one
 * slide per [SCREENSAVER_DWELL_MS], each a fullscreen 16:9 backdrop sweeping
 * with the billboard's own Ken Burns move (`billboardSweep`, applied to the
 * IMAGE through a graphics layer, so the page never recomposes per frame) with
 * the title's clearlogo and, where the title has resolved them, the first two
 * genres beneath it as a tracked-capitals label ("ACTION · DRAMA"). The caller only hands titles whose
 * CLEAN backdrop has resolved (the hero's ranked, textless-first artwork), so
 * the picture here is never the promo image a rail ships. Nothing here is
 * clickable; the whole surface is one ambient picture.
 *
 * Any key exits, and every key is CONSUMED: the press that dismisses the overlay
 * must not also move Home's focus, open a rail or start a title. The caller
 * owns what happens next (focus hand-back, and the flag that keeps the panel
 * awake while the overlay is up).
 *
 * Reduced motion: no sweep at all, per the billboard's own rule - the slides
 * still advance (this is a stills show, not an animation), each one is just a
 * still. The crossfade stays: it is the dissolve between two pictures rather
 * than movement inside one.
 */
@Composable
internal fun HomeScreensaver(
    slides: List<MetaPreview>,
    genreOf: (MetaPreview) -> String?,
    onExit: () -> Unit
) {
    if (slides.isEmpty()) return

    val reducedMotion = rememberReducedMotion()
    val focusRequester = remember { FocusRequester() }
    val view = LocalView.current

    // One cycle per dwell, counting up for the life of the overlay. The sweep's
    // direction follows THIS, not the slide index: a pool of one title has to
    // alternate its move every cycle rather than repeat the same push forever.
    var cycle by remember { mutableIntStateOf(0) }
    val sweepProgress = remember { Animatable(0f) }


    // The screen stays on: the point of the overlay is the picture, and the
    // panel's own screensaver firing over it would hide the thing the viewer
    // asked for. The flag is the same window flag the players hold, set on the
    // Home view for as long as this composable exists, and put back to whatever
    // it was on exit (Home's normal state leaves it clear, so the system keeps
    // its own timing when the overlay is down).
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }

    // Take the D-pad. The rails behind are still composed; while this node holds
    // focus their cards cannot receive a press, and the handler below consumes
    // every key before the focus system can walk anywhere.
    //
    // m4: retried frame by frame until the node reports focus, not asked once.
    // One unretried request can land before this overlay has attached, and it is
    // then silently missed - the trap the top-bar drive documents (HOME_FOCUS)
    // and KBStatusMessage retries around. Missed, the overlay holds no focus,
    // its onPreviewKeyEvent never sees a key, and a press acts on the rail card
    // behind it while the picture stays up. The cap is a runaway guard only.
    var overlayHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var frames = 0
        while (!overlayHasFocus && frames < SCREENSAVER_FOCUS_MAX_FRAMES) {
            awaitFrame()
            if (overlayHasFocus) break
            runCatching { focusRequester.requestFocus() }
            frames++
        }
    }

    // The dwell: advance to the next cycle, which is the next slide. Keyed on
    // the pool so a republished Home cannot restart a running show mid-slide -
    // the caller hands a snapshot, and this loop reads it as one.
    LaunchedEffect(slides.size) {
        while (true) {
            delay(SCREENSAVER_DWELL_MS)
            cycle++
        }
    }

    // The move itself. Snap to the start of the sweep, then run it over the
    // dwell so it lands exactly as the next slide arrives. With animations off
    // the progress is pinned at the END of the move, which draws the image at
    // its final scale and pan - a still, not a slower sweep.
    LaunchedEffect(cycle, reducedMotion) {
        if (reducedMotion) {
            sweepProgress.snapTo(1f)
            return@LaunchedEffect
        }
        sweepProgress.snapTo(0f)
        sweepProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = SCREENSAVER_DWELL_MS.toInt(),
                easing = LinearEasing
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            // Read by the focus drive above: the overlay knows whether it
            // actually took the D-pad, which is the only evidence the retry has.
            .onFocusChanged { overlayHasFocus = it.isFocused }
            .onPreviewKeyEvent { event ->
                // m13: the volume keys are not this overlay's to consume. It
                // swallowed every event, so a volume press dismissed the slides
                // and left the volume alone. They fall through to the window
                // (which is what actually changes it) and do not exit.
                when (event.key.nativeKeyCode) {
                    android.view.KeyEvent.KEYCODE_VOLUME_UP,
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                    android.view.KeyEvent.KEYCODE_VOLUME_MUTE -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) {
                    onExit()
                }
                // Consumed either way, including the up event: the press that
                // dismisses must not reach Home as a second instruction.
                true
            }
    ) {
        Crossfade(
            targetState = cycle,
            animationSpec = tween(durationMillis = SCREENSAVER_CROSSFADE_MS),
            label = "home-screensaver"
        ) { shownCycle ->
            val slide = slides[shownCycle % slides.size]
            // The billboard's own rule, over this overlay's dwell - one sweep per
            // slide, so the outgoing picture keeps ITS end pose while it
            // dissolves instead of jumping to the new cycle's move.
            val sweep = remember(shownCycle) {
                billboardSweep(shownCycle, SCREENSAVER_DWELL_MS)
            }
            // The outgoing slide reads as FINISHED while it fades (see the layer
            // below). Composition-time values, not state: the progress itself is
            // read INSIDE the layer, so the overlay does not recompose per frame.
            val current = shownCycle == cycle

            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = slide.background,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (reducedMotion) return@graphicsLayer
                            val progress =
                                (if (current) sweepProgress.value else 1f).coerceIn(0f, 1f)
                            val scale = sweep.startScale +
                                (sweep.endScale - sweep.startScale) * progress
                            scaleX = scale
                            scaleY = scale
                            // A fraction of the image's own width, so the pan
                            // is the same move on any panel.
                            translationX = (
                                sweep.startPanFraction +
                                    (sweep.endPanFraction - sweep.startPanFraction) * progress
                                ) * size.width
                        }
                )

                // The logo needs something under it: the billboard's meta sits on
                // the hero's bottom gradient, and a bright backdrop would swallow
                // a wordmark drawn straight onto it.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.55f)
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = ScreensaverCornerPadding, bottom = ScreensaverCornerPadding),
                    // Centered, because a fitted wordmark centers ITSELF in its
                    // box: most clearlogos are narrower than the 380dp box, so
                    // start-aligning the label pinned it to the box's left edge
                    // while the art it belongs to sat in the middle.
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Clean backdrop + clearlogo or nothing: the caller built
                    // this list from titles whose resolvable artwork landed, so
                    // this is the whole picture and never a poster, and never a
                    // rail's raw promo frame, standing in for it.
                    AsyncImage(
                        model = slide.logo,
                        contentDescription = slide.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .width(ScreensaverLogoWidth)
                            .height(ScreensaverLogoHeight)
                    )
                    // No genre until this title has resolved one: the label
                    // never delays a slide, and a placeholder would be text
                    // about nothing. An EYEBROW, not a second title line -
                    // tracked capitals in the muted grey - two genres at most
                    // - one line, capped at the wordmark's own width so a long
                    // line ellipsizes instead of running past the art it
                    // labels.
                    genreOf(slide)?.let { genre ->
                        Text(
                            text = genre.uppercase(),
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            letterSpacing = ScreensaverGenreTracking,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = ScreensaverGenreGap)
                                .width(ScreensaverLogoWidth)
                        )
                    }
                }
            }
        }
    }
}

/** The corner block's inset from the panel's edges. */
private val ScreensaverCornerPadding = 56.dp

/** The wordmark's box in the corner: the hero's proportions, drawn larger. */
private val ScreensaverLogoWidth = 380.dp
private val ScreensaverLogoHeight = 104.dp

/** Between the wordmark and the genre line under it, as the hero sets them. */
private val ScreensaverGenreGap = 10.dp

/**
 * Tracking on the genre label: tracked capitals are what make a small line read
 * as a category tag rather than as a title - the onboarding labels' 4-6sp,
 * tightened for a line this small.
 */
private val ScreensaverGenreTracking = 2.sp

/**
 * How many frames the overlay keeps asking for focus before it stops trying.
 * A runaway guard, not a schedule: the drive ends the moment the node reports
 * focus, and 12 frames is the cap the app's other key hand-offs use.
 */
private const val SCREENSAVER_FOCUS_MAX_FRAMES = 12
