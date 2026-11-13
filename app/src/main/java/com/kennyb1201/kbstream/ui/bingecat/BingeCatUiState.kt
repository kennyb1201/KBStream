package com.kennyb1201.kbstream.ui.bingecat

import com.kennyb1201.kbstream.data.bingecat.BingeCatProfile

/**
 * The connect screen's whole state.
 *
 * One object rather than loose state, because the screen has four mutually
 * exclusive shapes (not configured / connected+mapped / connected+unmapped /
 * linking) and a half-updated pair of booleans is how a screen ends up drawing
 * a device code over a live account.
 */
data class BingeCatUiState(
    val isConfigured: Boolean = false,
    val isConnected: Boolean = false,
    val isMapped: Boolean = false,

    /**
     * The credential is gone or was refused, so BingeCat cannot be read until
     * the device flow runs again (spec F2).
     *
     * Its own state rather than a second reading of [isConnected]: a mapped
     * profile whose session the server has stopped accepting is not connected
     * AND is not the ordinary "never connected" case, and the page must say
     * which one it is looking at.
     */
    val needsReconnect: Boolean = false,

    /**
     * Connected and mapped, but the grant was issued before `history:write`:
     * the tracker is OFF until the viewer reconnects (spec F4). Never true for
     * an unmapped or disconnected profile - both need a different answer, not a
     * prompt.
     */
    val needsReapproval: Boolean = false,
    val mappedProfileId: Long? = null,
    val mappedProfileName: String? = null,

    /** The linking flow's own state; all null when no code is being shown. */
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val secondsLeft: Int = 0,
    val isRequestingCode: Boolean = false,
    val isPolling: Boolean = false,

    val profiles: List<BingeCatProfile> = emptyList(),
    val isLoadingProfiles: Boolean = false,
    val profilesError: String? = null,

    val isDisconnecting: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
) {
    /** True while a code is on screen, which is what the linking layout draws. */
    val isLinking: Boolean get() = userCode != null || isRequestingCode || isPolling
}
