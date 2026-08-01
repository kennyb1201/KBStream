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
            isConnected = repository.hasToken()
        )
    )
    val uiState: StateFlow<SimklUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

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
        val deviceCode = _uiState.value.deviceCode ?: return
        val interval = _uiState.value.intervalSeconds.coerceAtLeast(3)

        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            repeat(120) {
                delay(interval * 1000L)

                val result = runCatching {
                    repository.exchangePin(deviceCode)
                }

                result.onSuccess { token ->
                    if (!token.accessToken.isNullOrBlank()) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isConnected = true,
                            statusMessage = "Connected to Simkl. Syncing shows...",
                            errorMessage = null
                        )

                        runCatching {
                            repository.initialSyncShows()
                        }.onSuccess {
                            _uiState.value = _uiState.value.copy(
                                statusMessage = "Simkl connected and initial sync completed."
                            )
                        }.onFailure { syncError ->
                            _uiState.value = _uiState.value.copy(
                                statusMessage = "Connected, but initial sync failed.",
                                errorMessage = syncError.message
                            )
                        }

                        pollJob?.cancel()
                        return@launch
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

    fun disconnect() {
        pollJob?.cancel()
        repository.clearAuth()
        _uiState.value = SimklUiState(
            isConnected = false,
            statusMessage = "Disconnected from Simkl."
        )
    }
}
