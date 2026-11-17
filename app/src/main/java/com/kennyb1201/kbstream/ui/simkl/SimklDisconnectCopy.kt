package com.kennyb1201.kbstream.ui.simkl

/**
 * What the Simkl disconnect confirmation SAYS, in one place for both surfaces
 * (spec 2026-11-16, phone dialog F2/F3).
 *
 * The body used to be one fixed paragraph, and it stated the V1 truth for every
 * session: "Simkl keeps this app in your connected apps until you remove it at
 * simkl.com — the app cannot revoke its own access there." That is exactly right
 * for a legacy (V1) grant, which never had a revoke. It is wrong for a V2
 * session: `clearAuth()` calls `revokeBlocking`, which POSTs `oauth2/revoke`, so
 * the app DOES end its own access. The dialog already knows which session it is
 * looking at (`isLegacySession`), so it can stop claiming otherwise.
 *
 * The titles live here too because the phone's heading is one line by design
 * (`KBPanelHeading`, shared by every phone dialog, ellipsizes rather than
 * wraps): "Disconnect from Simkl?" became "DISCONNECT FROM S…" on a narrow
 * panel. The phone gets the short title; the TV panel has the width for the
 * naming one, so it keeps it. Fixing this in the shared heading would have
 * changed every phone dialog for one dialog's problem.
 */
internal object SimklDisconnectCopy {

    /** The phone panel's title: short enough to survive one line at any scale. */
    const val TITLE_PHONE = "Disconnect?"

    /** The TV panel's title: it names the app, and the panel is wide enough. */
    const val TITLE_TV = "Disconnect from Simkl?"

    /**
     * The body for the session in front of the viewer.
     *
     * Both variants keep the fact this dialog exists to state (playback spec
     * C4/D1): the unlink drops the tracker session only, and this device's watch
     * history, resume rows and Continue Watching stay. Only the "and what
     * happens at Simkl" half differs, because only that half is version-specific.
     */
    fun body(isLegacySession: Boolean): String = if (isLegacySession) LEGACY else CURRENT

    private const val KEEPS_LOCAL =
        "This unlinks your Simkl account on this device. Your watch history, " +
            "resume positions and Continue Watching stay here. "

    /**
     * V2: the app revokes its own grant on the way out. Stated as the app
     * ending its own access rather than as a promise about simkl.com's lists,
     * because the revoke is best-effort - a device that is offline still drops
     * the session locally and the grant can outlive it.
     */
    private const val CURRENT = KEEPS_LOCAL +
        "The app also ends its own access at Simkl, so it does not stay in your " +
        "connected apps there."

    /** V1: no revoke ever existed, so the removal really is the viewer's job. */
    private const val LEGACY = KEEPS_LOCAL +
        "This device signed in with the old Simkl sign-in, which the app cannot " +
        "revoke — remove it at simkl.com."
}
