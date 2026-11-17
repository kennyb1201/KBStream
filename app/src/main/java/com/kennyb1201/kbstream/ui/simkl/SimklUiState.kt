package com.kennyb1201.kbstream.ui.simkl

import com.kennyb1201.kbstream.data.simkl.SimklContinueWatchingItem
import com.kennyb1201.kbstream.data.simkl.SimklWatchedCounts

data class SimklUiState(
    /**
     * False in a build that was never given a client id; the screen says so
     * instead of offering a code request that can only throw (trackers I2).
     */
    val isConfigured: Boolean = false,
    val isLoading: Boolean = false,
    val isConnected: Boolean = false,
    /**
     * The code as the viewer types it - `XXXX-YYYY` under AUTH V2 (spec
     * 2026-11-16, F1). Already formatted by the repository.
     */
    val userCode: String? = null,

    /**
     * The POLLING credential. Never drawn, never logged: it is the half of the
     * pair that can exchange itself for a token.
     */
    val deviceCode: String? = null,

    /**
     * What the QR encodes: V2's `verification_uri_complete`, which pre-fills the
     * code and still shows the consent screen.
     */
    val verificationUrl: String? = null,
    val expiresIn: Int? = null,
    val intervalSeconds: Int = 5,

    /** Countdown against [expiresIn]; the code restarts itself at zero (F1). */
    val secondsRemaining: Int? = null,

    /**
     * Connected on a LEGACY (V1) grant. The screen offers RECONNECT and the
     * settings pane names the state (spec F4) - a V2 session is not legacy, and
     * a disconnected profile is neither.
     */
    val isLegacySession: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    /**
     * A kids profile must clear the parent PIN before a code is generated - the
     * rule Trakt's page has always had (trackers C1).
     */
    val requiresParentPin: Boolean = false,
    /** The entered PIN was wrong; cleared on the next keystroke. */
    val pinError: Boolean = false,
    val isLoadingWatching: Boolean = false,
    val continueWatching: List<SimklContinueWatchingItem> = emptyList(),
    val watchedCounts: SimklWatchedCounts? = null,
    val accountName: String? = null
) {
    /**
     * A finished attempt whose code is still alive on screen.
     *
     * The screen offers RETRY for this case, which resumes polling the SAME code
     * (spec 2026-11-16, F4). Without it the only offer after a failure was
     * GENERATE CODE, which mints a new code and abandons one the viewer may
     * already have approved in the browser.
     */
    val canResumePoll: Boolean
        get() = errorMessage != null &&
            deviceCode != null &&
            userCode != null &&
            (secondsRemaining ?: 0) > 0
}
