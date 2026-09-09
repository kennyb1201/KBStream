package com.kennyb1201.kbstream.ui.simkl

import com.kennyb1201.kbstream.data.simkl.SimklContinueWatchingItem
import com.kennyb1201.kbstream.data.simkl.SimklWatchedCounts

data class SimklUiState(
    val isLoading: Boolean = false,
    val isConnected: Boolean = false,
    val userCode: String? = null,
    val deviceCode: String? = null,
    val verificationUrl: String? = null,
    val expiresIn: Int? = null,
    val intervalSeconds: Int = 5,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val isLoadingWatching: Boolean = false,
    val continueWatching: List<SimklContinueWatchingItem> = emptyList(),
    val watchedCounts: SimklWatchedCounts? = null
)
