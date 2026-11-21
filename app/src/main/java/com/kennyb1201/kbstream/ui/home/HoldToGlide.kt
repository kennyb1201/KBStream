package com.kennyb1201.kbstream.ui.home

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hold to glide: a held D-pad direction glides the list instead of walking focus
 * card by card.
 *
 * The mechanism is a behavior port of the one the app this project descends from
 * shipped as "Focus Scrolling" (upstream commit `1ce5d23`, `ModernHomeContent.kt`
 * / `ModernHomeRows.kt`): the FIRST press (a KeyDown with `repeatCount == 0`) is
 * left entirely alone, so the focus system moves exactly one card / row and the
 * hero, the glow and the scale behave exactly as they always did. From the hold
 * onward the app takes the key over: a frame-driven coroutine scrolls the list at
 * a CONSTANT velocity and focus does not move at all, so a walk stops paying a
 * hero resolve, a focus-scale animation and a glow settle per step. On release the
 * scroll stops and focus lands once, on the item the list came to rest on - one
 * focus landing, so the hero resolves for it exactly as it would for a stepped
 * landing (and the Spotlight dwell-expand, which keys off a landing, works on it
 * unchanged).
 *
 * The numbers are [GlideRules]' (horizontal 1600 dp/s, vertical 2000 dp/s, a ramp
 * over the first ~130 ms, per-frame dt capped at 48 ms); they live in a
 * Compose-free file so a test can pin them.
 *
 * TRIGGERED ON THE WALL CLOCK, not on the platform's key repeats. KBCard's
 * long press documents the same field fact: plenty of TV
 * remotes, emulators and ADB-injected key streams never emit repeat KeyDown
 * events at all - one KeyDown, then KeyUp, however long the button was held. A
 * repeat-triggered glide would then be a glide on some hardware only. So a press
 * that is still down after [GlideHoldStartMs] starts gliding on its own, and a
 * platform that DOES send repeats just starts it a little sooner (repeats are
 * consumed, so a repeat can never move focus).
 *
 * A lost KeyUp is the one leak a wall-clock trigger has, because a remote that
 * sends no repeats has no further events to time. Two safety nets close it:
 * [GlideMaxMs] is an absolute ceiling on one glide, and when the platform HAS sent
 * repeats a [GlideLostReleaseMs] gap with no event ends the glide (the last repeat
 * is the release we never saw).
 */

/**
 * Which direction keys belong to an axis.
 *
 * UP IS DELIBERATELY NOT ONE OF THEM on the vertical axis. Up is the one key on
 * this screen that has to stay a plain step for a plain press: it is how the
 * viewer climbs the rails, and the rail drawn first also answers it with the top
 * bar. Gliding up would take the press over and stop focus from ever reaching
 * that rail. So Up keeps exactly today's behavior (a step up), and the glide
 * owns Down, Left and Right.
 */
internal fun glideAxisKeys(axis: GlideAxis): Set<Key> = when (axis) {
    GlideAxis.Horizontal -> setOf(Key.DirectionLeft, Key.DirectionRight)
    GlideAxis.Vertical -> setOf(Key.DirectionDown)
}

/** Whether [key] scrolls the axis forward (Right / Down) rather than back. */
internal fun isGlideForwardKey(axis: GlideAxis, key: Key): Boolean = when (axis) {
    GlideAxis.Horizontal -> key == Key.DirectionRight
    GlideAxis.Vertical -> key == Key.DirectionDown
}

/** [glideLandingIndex] over a list's measured info, in layout coordinates. */
internal fun glideLandingIndex(info: LazyListLayoutInfo): Int? {
    val items = info.visibleItemsInfo
    if (items.isEmpty()) return null
    // An item's own `size` is its extent along the axis the list scrolls on, so
    // the arithmetic is the same for a row and for the column; only the viewport
    // has to be read on the right axis.
    val viewportSize = info.viewportSize
    val viewportEnd = if (info.orientation == Orientation.Vertical) {
        viewportSize.height
    } else {
        viewportSize.width
    }
    val visible = glideLandingIndex(
        offsets = items.map { it.offset },
        sizes = items.map { it.size },
        viewportStart = 0,
        viewportEnd = viewportEnd
    ) ?: return null
    return items[visible].index
}

/**
 * The glide hook for one scrollable list.
 *
 * Applied to a Home RAIL ROW with [GlideAxis.Horizontal], and to the rails COLUMN
 * with [GlideAxis.Vertical]. Keys arrive here before the focus system sees them (a
 * preview key event travels down from the root), so this is where a hold is taken
 * over - and the first press is deliberately NOT consumed, which is what keeps a
 * single press byte-identical.
 *
 * [onGlideActiveChange] hides the focus chrome while the glide runs (see
 * `LocalGlideActive` and KBCard). [onLanded] puts focus back on the item the list
 * came to rest on; it is the only focus move of a hold.
 *
 * A glide scrolls the list under a focus that does not move, so the focused card
 * is exactly the item a lazy list disposes first - taking the key stream with it.
 * That is what `Modifier.keepComposedWhileFocused` (on the cards and on the rows)
 * exists for.
 */
@Composable
internal fun rememberHoldToGlide(
    state: LazyListState,
    axis: GlideAxis,
    enabled: Boolean,
    onGlideActiveChange: (Boolean) -> Unit,
    onLanded: (LazyListLayoutInfo) -> Unit
): Modifier {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val keys = remember(axis) { glideAxisKeys(axis) }

    // The keycode of the press this hook is timing, or 0 when none is open. The
    // sentinel matters for the same reason KBCard documents: a KeyUp that arrives
    // without its own KeyDown must not be read as a hold that began at the epoch.
    var pressCode by remember { mutableLongStateOf(0L) }
    var gliding by remember { mutableStateOf(false) }
    var sawRepeat by remember { mutableStateOf(false) }
    var lastEventNs by remember { mutableLongStateOf(0L) }
    var armJob by remember { mutableStateOf<Job?>(null) }
    var glideJob by remember { mutableStateOf<Job?>(null) }
    // Whether focus is anywhere inside this list. A glide must never run on a list
    // the viewer has left: a card that consumes the press itself (a head card's
    // Left, which summons the top bar) takes the focus out of the row on the SAME
    // press that armed this hook, and the release then arrives somewhere else.
    var listHasFocus by remember { mutableStateOf(false) }

    fun stopGlide(land: Boolean) {
        armJob?.cancel()
        armJob = null
        glideJob?.cancel()
        glideJob = null
        pressCode = 0L
        sawRepeat = false
        if (gliding) {
            gliding = false
            onGlideActiveChange(false)
            // A landing is only ours to make while the focus is still in here.
            if (land && listHasFocus) onLanded(state.layoutInfo)
        }
    }

    fun startGlide(forward: Boolean) {
        armJob?.cancel()
        armJob = null
        if (gliding || !listHasFocus) return
        gliding = true
        sawRepeat = false
        onGlideActiveChange(true)
        glideJob = scope.launch {
            var last = withFrameNanos { it }
            val started = last
            val sign = if (forward) 1f else -1f
            // Whether the stop that ends this loop should also land the focus.
            var land = false
            while (isActive) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1_000_000_000f
                last = now
                val heldMs = (now - started) / 1_000_000L
                // scrollBy suspends, so the loop is paced by the frame it is
                // measuring instead of running back to back.
                state.scrollBy(sign * glideDpForFrame(axis, heldMs, dt) * density)
                // Focus left the list: whatever took it owns the press now, so
                // this stops silently rather than pulling focus back.
                if (!listHasFocus) break
                if (heldMs >= GlideMaxMs) {
                    land = true
                    break
                }
                if (sawRepeat && (now - lastEventNs) / 1_000_000L >= GlideLostReleaseMs) {
                    land = true
                    break
                }
            }
            // Cancelled by a release: stopGlide already landed. Ran out on its
            // own (max duration, or a release we never saw): land here.
            if (isActive) {
                glideJob = null
                gliding = false
                onGlideActiveChange(false)
                if (land && listHasFocus) onLanded(state.layoutInfo)
            }
        }
    }

    return Modifier
        .onFocusChanged { listHasFocus = it.hasFocus }
        .onPreviewKeyEvent { event ->
        // Off is exactly today's hold: the key reaches the focus system and the
        // walk bounces card to card, as it always did.
        if (!enabled || event.key !in keys) {
            false
        } else {
            val nowNs = System.nanoTime()
            when (event.type) {
                KeyEventType.KeyDown -> {
                    lastEventNs = nowNs
                    val code = event.nativeKeyEvent.keyCode.toLong()
                    if (gliding) {
                        // Held: the glide owns the key, so focus never moves.
                        sawRepeat = sawRepeat || event.nativeKeyEvent.repeatCount > 0
                        true
                    } else if (event.nativeKeyEvent.repeatCount > 0) {
                        // A repeat with no glide yet means the press is already
                        // past the hold threshold (the platform just beat the
                        // wall clock to it).
                        sawRepeat = true
                        pressCode = code
                        startGlide(isGlideForwardKey(axis, event.key))
                        true
                    } else {
                        pressCode = code
                        sawRepeat = false
                        val forward = isGlideForwardKey(axis, event.key)
                        armJob?.cancel()
                        armJob = scope.launch {
                            delay(GlideHoldStartMs)
                            if (pressCode == code) {
                                startGlide(forward)
                            }
                        }
                        // The FIRST press is never consumed: the focus system
                        // moves exactly one item, which is today's behavior.
                        false
                    }
                }

                KeyEventType.KeyUp -> {
                    val wasGliding = gliding
                    stopGlide(land = true)
                    // A release that ended a hold is swallowed whole: the press it
                    // belongs to was already spent on the glide, not on a step.
                    wasGliding
                }

                else -> false
            }
        }
    }
}
