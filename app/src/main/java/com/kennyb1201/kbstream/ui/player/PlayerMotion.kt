package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.kennyb1201.kbstream.ui.components.KB_CHROME_FADE_MS
import com.kennyb1201.kbstream.ui.components.KB_PANEL_ENTER_MS

/**
 * The View-world half of the app's motion system.
 *
 * The player is the one surface built out of Android Views rather than Compose,
 * and it is where the app's most frequent transitions happen: the chrome rising
 * and falling on every D-pad press, the audio/subtitle/source panels, the
 * loading splash, the skip prompt. Every one of them used to be a bare
 * `visibility = VISIBLE/GONE` flip - there was no alpha anywhere in
 * PlayerChrome - so the bar "popped" dozens of times per episode while every
 * Compose surface around it faded.
 *
 * Two things this deliberately does NOT do:
 *
 *  - it never delays a focus decision. Callers keep restoring focus
 *    synchronously, exactly as before; only pixels are animated.
 *  - it never moves a panel's layout. Translation and alpha are drawn, so a
 *    focused row's bounds are where they always were.
 *
 * Reduced motion is honoured the way the View world expresses it: the platform's
 * animator duration scale multiplies every duration, so a viewer who set it to
 * zero gets the instant cut it gave before.
 */
internal object KbViewMotion {

    /** [baseMs] scaled by the platform's animator duration scale (0 = off). */
    private fun durationFor(context: Context?, baseMs: Long): Long {
        val scale = runCatching {
            Settings.Global.getFloat(
                context?.contentResolver ?: return 0L,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f)
        return (baseMs * scale).toLong()
    }

    /**
     * Makes [view] visible and fades it in.
     *
     * Visibility is set synchronously - so every caller's `isVisible` and
     * `visibility != View.VISIBLE` check reads exactly what it did before - and
     * only the alpha is animated.
     */
    fun fadeIn(view: View?, baseMs: Long = KB_CHROME_FADE_MS.toLong()) {
        val target = view ?: return
        val ms = durationFor(target.context, baseMs)
        target.animate().cancel()
        if (target.visibility != View.VISIBLE) {
            target.alpha = if (ms > 0L) 0f else 1f
            target.visibility = View.VISIBLE
        }
        if (ms <= 0L) {
            target.alpha = 1f
            return
        }
        target.animate()
            .alpha(1f)
            .setDuration(ms)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * Fades [view] out and only then marks it GONE.
     *
     * [onGone] runs after the view is gone, so a caller that has to hand focus
     * somewhere once the panel is really gone can still do it - but any caller
     * whose focus hand-back must be synchronous should keep doing it before
     * this call, not in the callback. The focus contract is unchanged: callers
     * move focus themselves, and the fading view is not focused.
     */
    fun fadeOut(
        view: View?,
        baseMs: Long = KB_CHROME_FADE_MS.toLong(),
        onGone: (() -> Unit)? = null
    ) {
        val target = view ?: return
        if (target.visibility != View.VISIBLE) {
            onGone?.invoke()
            return
        }
        val ms = durationFor(target.context, baseMs)
        target.animate().cancel()
        if (ms <= 0L) {
            target.alpha = 1f
            target.visibility = View.GONE
            onGone?.invoke()
            return
        }
        target.animate()
            .alpha(0f)
            .setDuration(ms)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                // Only take it down if nothing has faded it back in meanwhile.
                if (target.alpha < 0.05f) {
                    target.visibility = View.GONE
                    target.alpha = 1f
                    onGone?.invoke()
                }
            }
            .start()
    }

    /**
     * Brings a panel in the way [fadeIn] does, plus a short slide from
     * [fromXPx]. The offset is drawn, never laid out, so a row's focusable
     * bounds are stationary from the first frame.
     *
     * For PANELS. A control whose own plate has to stay inside its box (the
     * rounded SKIP INTRO pill) must use [fadeIn] instead: a translated view is
     * composited from a render node whose bounds were captured while the offset
     * was still on it, so the leading edge of that plate can be clipped by a
     * couple of dp - a square left corner on a pill whose right one is round
     * (field, 2026-10-24).
     */
    fun slideFadeIn(
        view: View?,
        fromXPx: Float,
        baseMs: Long = KB_PANEL_ENTER_MS.toLong()
    ) {
        val target = view ?: return
        val ms = durationFor(target.context, baseMs)
        target.animate().cancel()
        if (ms <= 0L) {
            target.translationX = 0f
            fadeIn(target, 0L)
            return
        }
        // Callers invoke this on a transition, not on every frame, so it always
        // replays from the offset - including when the caller has already made
        // the view VISIBLE in the same frame it was raised (the picker does
        // exactly that, because its "is the panel up" flag is read
        // synchronously by the key handling around it).
        if (target.visibility != View.VISIBLE) {
            target.visibility = View.VISIBLE
        }
        target.alpha = 0f
        target.translationX = fromXPx
        target.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(ms)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** The outgoing half of [slideFadeIn]. */
    fun slideFadeOut(
        view: View?,
        baseMs: Long = KB_CHROME_FADE_MS.toLong(),
        onGone: (() -> Unit)? = null
    ) {
        val target = view ?: return
        val ms = durationFor(target.context, baseMs)
        target.animate().cancel()
        if (ms <= 0L || target.visibility != View.VISIBLE) {
            target.translationX = 0f
            fadeOut(target, 0L, onGone)
            return
        }
        target.animate()
            .alpha(0f)
            .translationX(-12f * target.resources.displayMetrics.density)
            .setDuration(ms)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                target.visibility = View.GONE
                target.alpha = 1f
                target.translationX = 0f
                onGone?.invoke()
            }
            .start()
    }
}
