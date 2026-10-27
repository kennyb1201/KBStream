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
    val userCode: String? = null,
    val deviceCode: String? = null,
    val verificationUrl: String? = null,
    val expiresIn: Int? = null,
    val intervalSeconds: Int = 5,
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
)
