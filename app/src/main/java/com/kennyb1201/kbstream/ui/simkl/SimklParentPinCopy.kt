package com.kennyb1201.kbstream.ui.simkl

/**
 * The parent-PIN gate's sentence, in one place for every surface that shows it
 * (2026-11-17; same split as [SimklDisconnectCopy]).
 *
 * This copy used to live only inside `SimklViewModel`'s private companion, which
 * was enough while the outcome was only ever read as `errorMessage`. The tap-time
 * gate (a link or a disconnect pressed on a kids profile that has no PIN) shows
 * the sentence from the SCREEN, because the screen is what knows which of the two
 * the viewer pressed - and a private companion cannot see the screen's own
 * `PARENT_PIN_MISSING`, nor the screen it. One sentence, one destination: the
 * parent sets the PIN where they already set it.
 */
internal object SimklParentPinCopy {

    /** Shown for a link AND for a disconnect; the link's dialog says the rest. */
    const val MISSING =
        "A parent needs to set a parent PIN before Simkl can be connected " +
            "on a kids profile."
}
