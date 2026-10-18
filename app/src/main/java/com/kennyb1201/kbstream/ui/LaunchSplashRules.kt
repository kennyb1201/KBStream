package com.kennyb1201.kbstream.ui

/**
 * Which loading beat the launch splash is standing in front of.
 *
 * Two, because a launch can open on two screens and each becomes ready when a
 * different thing arrives: the picker waits for the profile list, Home waits
 * for its rails.
 */
internal enum class LaunchSplashTarget {
    /** Who's Watching: held until `ProfileManager.profiles` has emitted. */
    PROFILE_PICKER,

    /** Home: held until the viewer has something to look at. */
    HOME,
}

/**
 * The longest the splash may hold the screen.
 *
 * A readiness signal is the only thing that should ever dismiss it, but a load
 * that never lands - airplane mode, an add-on that hangs, a broken playlist -
 * would otherwise trap the viewer on a logo with no way forward. Past this the
 * splash gives up and hands the screen to the ordinary skeleton / error / retry
 * UI, which is at least a screen with a next step on it.
 */
internal const val LAUNCH_SPLASH_TIMEOUT_MS = 8_000L

/**
 * Whether the launch splash is still covering the screen.
 *
 * Readiness, never a timer: "quick" means the splash never holds the user
 * longer than the content takes, so each target names the one condition that
 * replaces it with real content.
 *
 *  - [LaunchSplashTarget.PROFILE_PICKER] ends the moment the profile list has
 *    emitted. The list is populated synchronously in `MainActivity.onCreate`,
 *    before anything composes, so in practice this beat is the window-to-Compose
 *    handoff alone.
 *  - [LaunchSplashTarget.HOME] ends as soon as Home has something to show:
 *    either the load has finished (so the empty/error/retry card is the right
 *    thing to be looking at) or rails exist to look at already. It is
 *    deliberately the same condition that replaces the skeleton stack
 *    (`!isLoading || rails non-empty`), so the two can never trade a skeleton
 *    flash back and forth.
 *
 *    [railsStale] covers the other half of that beat: a profile pick re-arms
 *    the splash while the PREVIOUS profile's rails are still on screen, so for
 *    that first frame nothing is loading and rails are present - the rule used
 *    to answer "ready" and the latch disarmed before the switch had
 *    propagated. Rails that were built for another profile are never
 *    "something to look at": the viewer just chose a different identity, and
 *    what is on screen belongs to the one they left.
 *
 * [elapsedMs] is measured from the moment the splash was armed. It is only
 * consulted for the failsafe, and it outranks every other input: a stuck load
 * must never trap the user.
 */
internal fun launchSplashVisible(
    target: LaunchSplashTarget,
    profilesEmitted: Boolean,
    homeLoading: Boolean,
    railsPresent: Boolean,
    elapsedMs: Long,
    railsStale: Boolean = false,
): Boolean {
    // Checked first, so no target can forget its own escape hatch.
    if (elapsedMs >= LAUNCH_SPLASH_TIMEOUT_MS) return false

    return when (target) {
        LaunchSplashTarget.PROFILE_PICKER -> !profilesEmitted
        LaunchSplashTarget.HOME -> (homeLoading && !railsPresent) || railsStale
    }
}
