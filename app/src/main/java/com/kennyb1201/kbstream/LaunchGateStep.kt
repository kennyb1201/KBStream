package com.kennyb1201.kbstream

/**
 * What the launch gate does when a navigation entry settles.
 *
 * The gate is the rule that a cold launch with profiles on the device opens on
 * Who's Watching and discards whatever the graph restored. It used to be a
 * condition on every entry change, and its inputs (`profilesExist`,
 * `coldLaunch`, `showProfilePicker`) are launch-time values that never clear -
 * so the one destination that is guaranteed to arrive next, the profile pick,
 * sent the viewer straight back to the picker. This is that condition with the
 * one-shot state made explicit, so the decision can be tested as the sequence
 * it really is.
 */
internal enum class LaunchGateStep {
    /** The gate's one discard: leave the restored destination for the picker. */
    SHOW_PICKER,

    /** Nothing to do; the entry that settled stands. */
    PASS
}

/**
 * The gate's answer for one settled navigation entry.
 *
 * [gateApplied] is the shell's one-shot marker: it becomes true on the first
 * evaluation of a REAL destination, whether that evaluation fired or not. That
 * is what makes both launch shapes safe with one mechanism - a launch whose
 * start destination is already the picker marks the gate applied (so the pick
 * that follows cannot re-trigger it), and a restored non-picker destination
 * fires the discard once and is then marked applied (so the pick after it is
 * safe too).
 *
 * A null entry never reaches this function: the host not being attached yet is
 * the one state that must leave the marker alone, so a restored stack that
 * lands a frame later still gets its single discard.
 */
internal fun launchGateStep(
    pickerOnLaunch: Boolean,
    gateApplied: Boolean,
    entryIsPicker: Boolean
): LaunchGateStep = when {
    // The gate has already had its one look at a real destination.
    gateApplied -> LaunchGateStep.PASS
    // A launch that starts on the picker has nothing to discard: this is the
    // cold-launch case, where the start destination already IS Who's Watching.
    entryIsPicker -> LaunchGateStep.PASS
    pickerOnLaunch -> LaunchGateStep.SHOW_PICKER
    else -> LaunchGateStep.PASS
}
