package com.kennyb1201.kbstream.ui.detail

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.kennyb1201.kbstream.ui.browse.FocusLandingVerdict
import kotlinx.coroutines.android.awaitFrame

/** The logcat tag a delivered (or abandoned) hand-back is reported under. */
internal const val DETAIL_FOCUS_TAG = "DETAIL_FOCUS"

/**
 * How many frames a hand-back keeps trying before it is reported abandoned.
 *
 * Frame-paced rather than timed, because what it waits for is a composition:
 * the overlay's focused node is disposed and the card underneath is recomposed
 * on the next frame, and a rail that has to rebuild may take a handful more.
 * Sixty frames is about a second at television refresh rates - far past the
 * frame or two a hand-back really needs, and short enough that a target that
 * never came back is reported instead of being retried forever.
 */
internal const val DETAIL_HAND_BACK_FRAMES = 60

/** What one delivery attempt answers: keep going, done, or out of budget. */
internal enum class DetailHandBackStep { RETRY, DELIVERED, ABANDONED }

/**
 * The delivery loop's decision, pure so the budget is pinnable in a unit test.
 *
 * [landed] is a VERIFIED landing, not one ask's answer: the request's own answer
 * is the only focus observation Compose exposes, so a landing is two consecutive
 * acceptances across frames (see [FocusLandingVerdict]) - the one extra check
 * that catches a node which accepted and was disposed a frame later, which used
 * to log "delivered" while the viewer was left with focus nowhere.
 */
internal fun detailHandBackStep(
    frame: Int,
    landed: Boolean,
    cap: Int = DETAIL_HAND_BACK_FRAMES
): DetailHandBackStep =
    when {
        landed -> DetailHandBackStep.DELIVERED
        frame >= cap -> DetailHandBackStep.ABANDONED
        else -> DetailHandBackStep.RETRY
    }

/**
 * One recorded hand-back: the surface a dismissal returns to, and how to find
 * that surface NOW.
 *
 * [captured] is the requester filed when the overlay opened, exactly as before
 * this driver existed. [live] is the rail's registry read at delivery time, so
 * a card whose requester instance was rebuilt while the overlay was up is
 * found again rather than asked through a dead one - the captured instance is
 * only the fallback for surfaces with no registry (the play button, a backdrop
 * card, a media image).
 */
internal class DetailHandBack(
    val label: String,
    private val captured: FocusRequester?,
    private val live: (() -> FocusRequester?)?
) {
    fun resolve(): FocusRequester? = live?.invoke() ?: captured
}

/**
 * The detail page's hand-back driver: dismissals RECORD a target, one effect in
 * the page's composition DELIVERS it.
 *
 * Record-then-deliver rather than ask-on-dismiss for three reasons:
 *
 *  - the ask happens after the frame that closed the overlay, not in the middle
 *    of the key handler that requested the close, so the overlay no longer holds
 *    a focused node when the target is asked;
 *  - a newer record REPLACES the pending one (the effect keys on it), so a
 *    dismissal the viewer already superseded cannot land its focus after the
 *    next menu opened;
 *  - the effect lives in this screen's composition, so a hand-back still
 *    pending when Detail leaves (an action that navigates to the player, Back
 *    out of the page) dies with it and can never fire into Home.
 */
@Stable
internal class DetailHandBackDriver {

    /** The hand-back waiting to be delivered, or null. */
    var pending by mutableStateOf<DetailHandBack?>(null)
        private set

    operator fun invoke(
        label: String,
        captured: FocusRequester?,
        live: (() -> FocusRequester?)? = null
    ) {
        pending = DetailHandBack(label, captured, live)
    }

    /** Consumes [handBack] if it is still the pending one. */
    fun consumed(handBack: DetailHandBack) {
        if (pending === handBack) {
            pending = null
        }
    }
}

/**
 * The detail page's one hand-back, held in its composition.
 *
 * The delivery is this effect: it runs once the composition that CLEARED the
 * overlay has been applied, waits a frame, and then asks the recorded target -
 * retrying across frames until the request reports it landed
 * ([DETAIL_HAND_BACK_FRAMES] at most). Every outcome is logged under
 * [DETAIL_FOCUS_TAG]: one line naming the target and the frames it took, or a
 * warning if the budget ran out undelivered.
 */
@Composable
internal fun rememberDetailHandBack(): DetailHandBackDriver {
    val driver = remember { DetailHandBackDriver() }
    val pending = driver.pending
    LaunchedEffect(pending) {
        val handBack = pending ?: return@LaunchedEffect
        deliverDetailHandBack(handBack)
        driver.consumed(handBack)
    }
    return driver
}

/**
 * Delivers [handBack], answering whether the focus landed.
 *
 * Each attempt follows a frame, so the first ask is made against the tree the
 * dismissal produced rather than the one that still held the overlay. A
 * requester that resolves to null (a rail that has not composed its card yet)
 * spends its frame like a missed request and is asked again.
 */
internal suspend fun deliverDetailHandBack(
    handBack: DetailHandBack,
    cap: Int = DETAIL_HAND_BACK_FRAMES
): Boolean {
    var frame = 0
    val verdict = FocusLandingVerdict()
    while (true) {
        awaitFrame()
        frame += 1
        val requester = handBack.resolve()
        // Two consecutive acceptances, not one: see [detailHandBackStep].
        val landed = verdict.accepted(
            requester != null &&
                runCatching { requester.requestFocus() }.getOrDefault(false)
        )
        when (detailHandBackStep(frame, landed, cap)) {
            DetailHandBackStep.DELIVERED -> {
                Log.i(
                    DETAIL_FOCUS_TAG,
                    "hand-back delivered: ${handBack.label} after $frame frame(s), verified"
                )
                return true
            }

            DetailHandBackStep.ABANDONED -> {
                Log.w(
                    DETAIL_FOCUS_TAG,
                    "hand-back undelivered: ${handBack.label} after $frame frame(s)"
                )
                return false
            }

            DetailHandBackStep.RETRY -> Unit
        }
    }
}
