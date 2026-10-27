package com.kennyb1201.kbstream.ui.trakt

import com.kennyb1201.kbstream.data.trakt.TraktWatchedCounts

/**
 * Everything the Trakt connect screen draws.
 *
 * One screen, one state: the three things it can be doing are connecting (with a
 * code on screen), already connected, or unable to - and the failure line is a
 * field rather than a thrown exception because every failure in this flow is
 * something the viewer can act on (restart the code, approve on their phone,
 * ask a parent).
 */
data class TraktUiState(
    /** False in a build that was never given a client id; the screen says so. */
    val isConfigured: Boolean = false,
    val isConnected: Boolean = false,
    /** The account's Trakt slug, when one has been read. */
    val accountSlug: String? = null,
    /**
     * WATCHED tiles for the connected state. Null hides the row - a failed or
     * budget-blocked read must not stop the screen saying "Connected to Trakt."
     */
    val watchedCounts: TraktWatchedCounts? = null,
    /**
     * True while the connected branch's manual REFRESH is reading the pair.
     * The automatic entry read does not set it - it has no button to busy.
     */
    val isLoadingCounts: Boolean = false,
    val isLoading: Boolean = false,
    /** True while the poll loop is waiting for approval. */
    val isPolling: Boolean = false,
    /** The 8-character code the viewer types at trakt.tv/activate. */
    val userCode: String? = null,
    /** Where they type it. */
    val verificationUrl: String? = null,
    /** Seconds the code stays valid, for the countdown line. */
    val expiresInSeconds: Int? = null,
    /** A kids profile must clear the parent PIN before a code is generated. */
    val requiresParentPin: Boolean = false,
    /** The entered PIN was wrong; cleared on the next keystroke. */
    val pinError: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)
