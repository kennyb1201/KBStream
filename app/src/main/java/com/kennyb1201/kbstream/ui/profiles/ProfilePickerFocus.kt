package com.kennyb1201.kbstream.ui.profiles

import androidx.compose.runtime.withFrameNanos

/**
 * The profile picker's focus landing, driven to observation.
 *
 * This is the app's entry gate, and the one place where the one-shot ask the
 * app used to be full of is a total lockout rather than a lost keystroke: the
 * field report is a Fire TV that force-closed during playback, relaunched into
 * the picker, and could not be used at all - no ring anywhere, all four
 * directions dead, OK dead - until a paired mouse or a reinstall. The ask was
 * fired once at first composition, lost its race with the (0,0) tile's attach,
 * and was never repeated; and from "nothing holds focus" no directional press
 * can bootstrap one.
 *
 * The rule the rest of the app's landings follow applies here unchanged: a
 * focus ask is retried until the target's OWN focus state says it landed, and
 * a drive that spends its budget says so instead of vanishing. The ask's own
 * answer is deliberately not the verdict - a requester whose node is not
 * attached throws, and one whose node cannot take focus answers false - so the
 * only evidence is [observed].
 */

/**
 * The picker's focus log.
 *
 * One line per landing on entry (`landed: <name> after <n> frame(s)`), the
 * watchdog's repairs, and a warn when a drive exhausts its cap. The field
 * failure had no trace at all, which is why it needed a lockout to diagnose;
 * the next capture names its branch on its own.
 */
internal const val PROFILE_FOCUS_TAG = "PROFILE_FOCUS"

/**
 * How many frame-paced attempts a driven landing may spend before it gives up
 * and says so. About a second at television refresh rates - several orders of
 * magnitude past the frame or two a tile really takes to attach, and short
 * enough that a genuinely unreachable tile is reported rather than the ask
 * running forever.
 */
internal const val PROFILE_LANDING_FRAMES = 60

/**
 * Frames "nothing holds focus" has to hold before the watchdog treats it as
 * lost and re-drives. A rebuilt tile list drops focus for a frame or two, and
 * that is not a viewer needing to be rescued; ten frames is about 160ms, under
 * the time a press takes to be noticed and well over a recomposition.
 */
internal const val PROFILE_NO_FOCUS_FRAMES = 10

/**
 * Asks for the landing until [observed] says the target really holds focus.
 *
 * Returns the frames it took, or 0 when [PROFILE_LANDING_FRAMES] was spent.
 * One frame per attempt, never a same-frame read: the target's focus state is
 * reported from its `onFocusChanged`, which lands with the layout that follows
 * the ask, so a verdict read in the asking frame answers for the frame before.
 *
 * [awaitFrame] is injected only so the retry table can be pinned without a
 * composition (see `ProfilePickerFocusLandingTest`); production passes the
 * frame clock. [ask] is wrapped, so a node that is not attached yet - which
 * throws rather than answering - is an attempt like any other.
 */
internal suspend fun driveProfileLanding(
    ask: () -> Unit,
    awaitFrame: suspend () -> Unit = { withFrameNanos { } },
    observed: () -> Boolean
): Int {
    var frames = 0
    while (frames < PROFILE_LANDING_FRAMES) {
        runCatching { ask() }
        awaitFrame()
        frames++
        if (observed()) return frames
    }
    return 0
}
