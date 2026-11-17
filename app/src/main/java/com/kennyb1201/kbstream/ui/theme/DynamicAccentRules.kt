package com.kennyb1201.kbstream.ui.theme

/**
 * How the app's ONE accent resolves while Dynamic color is on (spec 2026-11-16,
 * Part A).
 *
 * The reported bug was exact: "I turned dynamic color on and don't see no
 * difference". The switch was wired - it swapped the phone's Material 3 scheme
 * for the wallpaper's palette - but nothing in the app reads
 * `colorScheme.primary`. Every screen reads [KBAccent], the picked accent, so the
 * switch changed an object the screen never consulted.
 *
 * The fix is to make the accent itself follow the wallpaper while the switch is
 * on, so the dozens of existing [KBAccent] call sites - buttons, highlights,
 * focus rings - follow it with no per-call-site change. This object is the whole
 * of the decision, and it speaks ARGB ints so it can be pinned by a plain JVM
 * test: each of its ways to be wrong is a shipped bug otherwise.
 *
 *  - OFF must restore the picked accent EXACTLY (the stored preference is never
 *    written while the switch is on - that is what makes the restore exact);
 *  - pre-Android-12 must be a no-op, not a crash and not a guess;
 *  - a wallpaper colour that has not been published yet must fall back to the
 *    picked accent rather than to something invisible.
 */
internal object DynamicAccentRules {

    /**
     * The first API level with a wallpaper palette (Material You).
     *
     * Below this there is nothing to ask for: `dynamicDarkColorScheme` does not
     * exist, so the switch is a no-op and Settings says so.
     */
    const val MIN_SDK = 31

    /** Whether the wallpaper palette applies at all: the switch AND the platform. */
    fun applies(sdkInt: Int, switchOn: Boolean): Boolean = switchOn && sdkInt >= MIN_SDK

    /**
     * The accent the UI draws, as ARGB.
     *
     * [wallpaperPrimaryArgb] is the system palette's primary, published by the
     * phone theme from the scheme it just built; null while it has not been
     * published (the first pass of a composition, or the moment the switch is
     * turned off). The null case falls back to the picked accent on purpose: an
     * accent that vanished for a frame would look like a bug, and the wallpaper
     * colour arrives on the very next pass.
     */
    fun resolve(applies: Boolean, wallpaperPrimaryArgb: Int?, pickedArgb: Int): Int =
        if (applies) wallpaperPrimaryArgb ?: pickedArgb else pickedArgb
}
