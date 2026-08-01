package com.kennyb1201.kbstream.ui.simkl

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SimklViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SimklRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(
        SimklUiState(
            isConnected = repository.hasToken(),
            statusMessage = if (repository.hasToken()) "Connected to Simkl." else null
        )
    )
    val uiState: StateFlow<SimklUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        if (repository.hasToken()) {
            loadContinueWatching()
        }
    }

    fun generateCode() {
        pollJob?.cancel()

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                errorMessage = null,
                statusMessage = "Requesting Simkl code..."
            )

            runCatching {
                repository.createPinCode()
            }.onSuccess { pin ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isConnected = repository.hasToken(),
                    userCode = pin.userCode,
                    deviceCode = pin.deviceCode,
                    verificationUrl = pin.verificationUrl ?: "https://simkl.com/pin",
                    expiresIn = pin.expiresIn,
                    intervalSeconds = pin.interval ?: 5,
                    statusMessage = "Enter this code on Simkl to connect.",
                    errorMessage = null
                )
                startPolling()
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "Failed to request Simkl code.",
                    statusMessage = null
                )
            }
        }
    }

    private fun startPolling() {
        val userCode = _uiState.value.userCode ?: return
        val interval = _uiState.value.intervalSeconds.coerceAtLeast(3)

        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            repeat(120) {
                delay(interval * 1000L)

                runCatching {
                    repository.checkPin(userCode)
                }.onSuccess { token ->
                    if (!token.accessToken.isNullOrBlank()) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isConnected = repository.hasToken(),
                            statusMessage = "Connected to Simkl.",
                            errorMessage = null
                        )
                        loadContinueWatching()
                        pollJob?.cancel()
                        return@launch
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            statusMessage = "Waiting for authorization..."
                        )
                    }
                }.onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        statusMessage = "Waiting for authorization..."
                    )
                }
            }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = "Authorization timed out. Please generate a new code.",
                statusMessage = null
            )
        }
    }

    fun loadContinueWatching() {
        if (!repository.hasToken()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingWatching = true,
                errorMessage = null
            )

            runCatching {
                repository.getContinueWatching()
            }.onSuccess { items ->
                _uiState.value = _uiState.value.copy(
                    isConnected = true,
                    isLoadingWatching = false,
                    continueWatching = items,
                    statusMessage = if (items.isEmpty()) {
                        "Connected to Simkl, but no continue watching items were found."
                    } else {
                        "Loaded ${items.size} continue watching items."
                    },
                    errorMessage = null
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoadingWatching = false,
                    errorMessage = error.message ?: "Failed to load continue watching."
                )
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        repository.clearAuth()
        _uiState.value = SimklUiState(
            isConnected = false,
            statusMessage = "Disconnected from Simkl."
        )
    }
}
