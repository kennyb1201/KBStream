package com.kennyb1201.kbstream.ui.screensaver

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.home.SCREENSAVER_IDLE_POLL_MS
import com.kennyb1201.kbstream.ui.home.screensaverIdleClockAfterTrailer
import com.kennyb1201.kbstream.ui.home.screensaverIdleTimeoutMs
import kotlinx.coroutines.delay

/**
 * What a screen's screensaver host is doing right now, so the screen can draw
 * the overlay and route its keys without owning the timer.
 *
 * [visible] and [slides] are the show; [onActivity] is every remote press the
 * screen sees (it only pushes the deadline out); [dismiss] is the overlay's own
 * exit, which also hands focus back.
 */
internal class ScreensaverController(
    val visible: Boolean,
    val slides: List<MetaPreview>,
    val onActivity: () -> Unit,
    val dismiss: () -> Unit
)

/**
 * The TV screensaver's arm/dismiss state machine, shared by every screen that
 * owns one (Home's rails, the TV Detail page).
 *
 * This is Home's machinery moved out verbatim rather than copied: the idle
 * clock, the resumed gate, the menu gate, the trailer hold and its restarting
 * falling edge, the frozen pool and the resolve-during-the-wait pass all had to
 * behave identically on both screens, and two copies of it would drift the
 * first time one of them was fixed. What differs per screen is only what the
 * caller supplies - the pool, what counts as "in the way", and how focus is
 * handed back.
 *
 * The overlay itself is still [com.kennyb1201.kbstream.ui.home.HomeScreensaver]:
 * one composable, drawn last by whichever screen is showing it.
 *
 * @param pool what this screen could show, in that screen's own order. Empty
 *   means the screensaver is effectively off for it - there is nothing to arm.
 * @param blocked the viewer is mid-task (a menu, a dialog, a picker, the top
 *   bar): an open overlay holds the gate, exactly as Home's menus do.
 * @param trailerPlaying a trailer is on screen on THIS screen. A screen whose
 *   trailers play in the fullscreen player never reports one: the player
 *   pauses it, so the resumed gate already answers.
 * @param warmPool the resolve pass, run while the viewer idles: everything it
 *   can warm is a picture the moment the timer arms instead of a spinner.
 * @param resolveSlides the arm-time pass: the pool's candidates with the clean
 *   backdrop + clearlogo actually resolved, in pool order. An empty answer
 *   leaves the screen alone rather than arming an empty show.
 * @param captureFocus the control holding focus at arm time.
 * @param restoreFocus puts focus back there on dismiss.
 */
@Composable
internal fun rememberScreensaver(
    pool: List<MetaPreview>,
    blocked: Boolean,
    trailerPlaying: Boolean,
    warmPool: suspend (List<MetaPreview>) -> Unit,
    resolveSlides: suspend (List<MetaPreview>) -> List<MetaPreview>,
    captureFocus: () -> FocusRequester?,
    restoreFocus: (FocusRequester?) -> Unit
): ScreensaverController {
    val context = LocalContext.current

    // The two prefs are device-local, read here and re-read on every resume, so
    // the wait the viewer just picked in Settings is the one this screen uses
    // when they come back rather than the one it was built with.
    var enabled by remember {
        mutableStateOf(AppPreferences.getScreensaverEnabled(context))
    }
    var idleMs by remember {
        mutableStateOf(
            screensaverIdleTimeoutMs(AppPreferences.getScreensaverTimeoutMinutes(context))
        )
    }
    // The last press the remote delivered to this screen. Uptime, not wall
    // clock: a TV whose clock is corrected while the overlay is armed must not
    // idle for either seconds or hours because of it.
    var lastKeyAt by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var visible by remember { mutableStateOf(false) }
    // The control focus was on when the overlay armed: where the dismissing
    // press puts it back.
    var restoreTarget by remember { mutableStateOf<FocusRequester?>(null) }
    // What the overlay is SHOWING, frozen at the moment it armed: content
    // reloading under a running show must never reshuffle it - the next arming
    // re-snapshots.
    var slidesShown by remember { mutableStateOf(emptyList<MetaPreview>()) }
    // So the timer below arms with the pool as it stands at that moment, not
    // the one it was launched with (content can land while the viewer idles).
    val latestPool by rememberUpdatedState(pool)
    val latestResolveSlides by rememberUpdatedState(resolveSlides)
    val latestWarmPool by rememberUpdatedState(warmPool)
    val latestCaptureFocus by rememberUpdatedState(captureFocus)
    val latestRestoreFocus by rememberUpdatedState(restoreFocus)

    // Whether this screen is the one being looked at. A paused activity means
    // the TV is asleep, the input switched or the app is in the background -
    // none of which is "idle on this screen".
    var resumed by remember { mutableStateOf(true) }

    // Coming back (from Settings, from a title, from the player) is activity,
    // and it is also the moment a changed pref has to be picked up. It is what
    // makes a trailer that played in another surface restart the full wait.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumed = true
        enabled = AppPreferences.getScreensaverEnabled(context)
        idleMs = screensaverIdleTimeoutMs(AppPreferences.getScreensaverTimeoutMinutes(context))
        lastKeyAt = SystemClock.uptimeMillis()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        // Drops silently: no hand-back, because nobody is looking at the screen
        // to be landed on. The next entry starts the wait from zero.
        resumed = false
        visible = false
        lastKeyAt = SystemClock.uptimeMillis()
    }

    // Armed only while this screen is the one being looked at, with something to
    // show and nothing of the viewer's in the way. A pool with no
    // backdrop+clearlogo title is the same answer as switched off - the screen
    // simply stays itself, with no empty state to explain.
    val armed =
        enabled &&
            resumed &&
            !visible &&
            pool.isNotEmpty() &&
            !blocked &&
            !trailerPlaying

    // The falling edge of a trailer is activity that produced no key press: the
    // wait restarts from the moment the picture returns rather than arming the
    // overlay the instant the trailer ends. One write, and only on that edge -
    // see screensaverIdleClockAfterTrailer. Written in a side effect rather than
    // in a LaunchedEffect on purpose: the wait loop below reads this clock on
    // its very first statement, and the restart has to have landed before that
    // loop relaunches on the same frame the gate opens again.
    var trailerWasPlaying by remember { mutableStateOf(false) }
    SideEffect {
        if (trailerWasPlaying != trailerPlaying) {
            lastKeyAt = screensaverIdleClockAfterTrailer(
                wasPlaying = trailerWasPlaying,
                playing = trailerPlaying,
                nowMs = SystemClock.uptimeMillis(),
                lastKeyAtMs = lastKeyAt
            )
            trailerWasPlaying = trailerPlaying
        }
    }

    fun exit() {
        visible = false
        // The dismissing press is activity: the next show starts its wait over
        // rather than flashing back the moment the last one ends.
        lastKeyAt = SystemClock.uptimeMillis()
        // Exactly where they were - the caller's own idea of that, because only
        // it knows which of its controls can be handed focus back.
        latestRestoreFocus(restoreTarget)
    }

    // The resolve pass, run DURING the wait rather than at its end: the show is
    // armed by a timer, and the moment it arms has to be a picture, not a spin.
    // It follows the latest pool and dies with the same gate that stops the
    // countdown: exiting the overlay, opening a menu, or the screen pausing.
    LaunchedEffect(armed, pool) {
        if (!armed) return@LaunchedEffect
        latestWarmPool(pool)
    }

    // The wait itself: polled rather than slept, so a key press pushes the
    // deadline out and the check inside the final second still counts.
    LaunchedEffect(armed, idleMs) {
        if (!armed) return@LaunchedEffect
        while (true) {
            val idleFor = SystemClock.uptimeMillis() - lastKeyAt
            val remaining = idleMs - idleFor
            if (remaining <= 0L) break
            delay(minOf(remaining, SCREENSAVER_IDLE_POLL_MS))
        }
        // Only the titles whose clean backdrop + clearlogo resolved are slides,
        // in pool order; an item's own art is never a fallback. Nothing
        // resolved is the same answer as an empty pool.
        val arming = latestResolveSlides(latestPool)
        if (arming.isEmpty()) return@LaunchedEffect
        restoreTarget = latestCaptureFocus()
        // Shuffled ONCE per show, and only here: an unshuffled show is the same
        // titles in the same sequence every time it arms. The SHOWN snapshot is
        // what shuffles, not the pool, so the next arming deals a fresh order
        // from whatever has resolved by then.
        slidesShown = arming.shuffled()
        visible = true
    }

    return ScreensaverController(
        visible = visible,
        slides = slidesShown,
        onActivity = { lastKeyAt = SystemClock.uptimeMillis() },
        dismiss = { exit() }
    )
}
