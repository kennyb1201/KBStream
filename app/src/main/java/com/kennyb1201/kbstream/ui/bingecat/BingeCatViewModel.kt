package com.kennyb1201.kbstream.ui.bingecat

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.bingecat.BingeCatCodeResult
import com.kennyb1201.kbstream.data.bingecat.BingeCatDeviceCode
import com.kennyb1201.kbstream.data.bingecat.BingeCatDeviceFlowRules
import com.kennyb1201.kbstream.data.bingecat.BingeCatProfile
import com.kennyb1201.kbstream.data.bingecat.BingeCatRepository
import com.kennyb1201.kbstream.data.sync.ProfileManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The BingeCat device flow, as the screen drives it.
 *
 * The shape is the Trakt one - request a code, show it, poll until approved -
 * because that is the only flow a remote can complete. What differs is the
 * state machine: a real OAuth device flow answers with `error` codes in 400
 * bodies, so "still waiting" and "poll slower" are normal answers rather than
 * failures (see [BingeCatDeviceFlowRules]).
 *
 * Three behaviours are deliberate:
 *
 *  - **Polling is owned here and cancelled on every exit**: Back, a profile
 *    switch, a new code or a disconnect kills the loop, so a token can never be
 *    saved into a profile the viewer has already left.
 *  - **Approval flows into the mapping step, never into a data read.** A fresh
 *    grant has no BingeCat profile chosen, and an unmapped profile must make no
 *    request at all (spec F2).
 *  - **A failed poll is retried a bounded number of times.** A transport blip
 *    gets three tries; the code's own expiry ends the loop.
 */
class BingeCatViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext

    private val repository = BingeCatRepository.getInstance(appContext)

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<BingeCatUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    private var codeJob: Job? = null

    private fun initialState(): BingeCatUiState {
        val connected = repository.hasToken()
        val mappedId = if (connected) repository.mappedProfileId() else null
        return BingeCatUiState(
            isConfigured = repository.isConfigured(),
            isConnected = connected,
            isMapped = mappedId != null,
            // Only a connected AND mapped profile can be "needs re-approval";
            // the repository's own rule, so the screen and the write gates can
            // never disagree about when writing is off.
            needsReapproval = connected && mappedId != null && repository.needsReapproval(),
            mappedProfileId = mappedId,
            mappedProfileName = if (mappedId != null) repository.mappedProfileName() else null,
            statusMessage = when {
                connected && mappedId != null && repository.needsReapproval() ->
                    // Not "tracking is off": a step-2 grant records plays and
                    // cannot save (and a step-1 grant can do neither), so the
                    // line names the thing both gates share.
                    "BingeCat writing is off: this grant was approved before a " +
                        "write scope KBStream needs now."
                connected -> "Connected to BingeCat."
                else -> null
            }
        )
    }

    init {
        observeProfileSwitches()
    }

    /**
     * A profile switch re-derives the whole screen from the incoming store.
     *
     * The session and the mapping are both per KBStream profile, so a switch can
     * turn a connected, mapped screen into an unconnected one - and a poll that
     * was in flight must not save into the profile now active.
     */
    private fun observeProfileSwitches() {
        viewModelScope.launch {
            var first = true
            ProfileManager.activeProfile.collect {
                if (first) {
                    first = false
                    return@collect
                }
                stopPolling()
                codeJob?.cancel()
                _uiState.value = initialState()
            }
        }
    }

    /** The screen's entry check: the store is the truth, not the last attempt. */
    fun refreshConnectionState() {
        val next = initialState()
        // Re-assert an existing session to the account. A grant created before
        // the shared session existed has no other trigger to publish it, and
        // the sibling then keeps asking for a device code (see
        // BingeCatRepository.publishSessionIfConnected). Off the composition
        // thread, and only ever with a real session in the store.
        if (next.isConnected) {
            viewModelScope.launch {
                runCatching { repository.publishSessionIfConnected() }
            }
        }
        if (next.isConnected == _uiState.value.isConnected &&
            next.isMapped == _uiState.value.isMapped &&
            next.needsReapproval == _uiState.value.needsReapproval &&
            next.userCode == null
        ) {
            // The early return exists to keep a GENUINELY in-flight request or
            // poll across re-entry, and those have a live job behind them. A
            // flag with no live job is not an attempt: it is the state a
            // cancelled one can leave behind, and it reads as "still asking"
            // with nothing asking - the linking branch has no GENERATE in it, so
            // re-entering could not get out either. Clear only that half.
            val current = _uiState.value
            val stalePoll = current.isPolling && pollJob?.isActive != true
            val staleRequest = current.isRequestingCode && codeJob?.isActive != true
            if (stalePoll || staleRequest) {
                _uiState.value = current.copy(
                    isPolling = current.isPolling && !stalePoll,
                    isRequestingCode = current.isRequestingCode && !staleRequest
                )
            }
            return
        }
        stopPolling()
        _uiState.value = next
    }

    /** Asks for a code and starts polling it. */
    fun requestCode() {
        if (_uiState.value.isRequestingCode || _uiState.value.isPolling) return
        codeJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isRequestingCode = true,
            errorMessage = null,
            statusMessage = null
        )
        codeJob = viewModelScope.launch {
            try {
                when (val result = repository.requestDeviceCode()) {
                    is BingeCatCodeResult.Issued -> {
                        val code = result.code
                        _uiState.value = _uiState.value.copy(
                            isRequestingCode = false,
                            userCode = code.userCode,
                            verificationUrl = code.verificationUrl,
                            secondsLeft = code.expiresInSeconds
                        )
                        startPolling(code)
                    }

                    is BingeCatCodeResult.Failed -> {
                        _uiState.value = _uiState.value.copy(
                            isRequestingCode = false,
                            errorMessage = result.failure.message
                        )
                    }
                }
            } finally {
                // Cancellation is an exit path too (CANCEL while the request is
                // in flight, a profile switch, a superseding request), and it
                // used to leave the flag set with no job behind it: the screen
                // then read "Asking BingeCat for a device code…" forever, with
                // no code to cancel and no GENERATE to press. Only the CURRENT
                // request may clear it - a superseded one has already handed the
                // flag to its replacement.
                if (coroutineContext[Job] === codeJob &&
                    _uiState.value.isRequestingCode
                ) {
                    _uiState.value = _uiState.value.copy(isRequestingCode = false)
                }
            }
        }
    }

    /** Cancels a code that is on screen, without touching the connection. */
    fun cancelCode() {
        stopPolling()
        codeJob?.cancel()
        _uiState.value = _uiState.value.copy(
            userCode = null,
            verificationUrl = null,
            isRequestingCode = false,
            secondsLeft = 0
        )
    }

    private fun startPolling(code: BingeCatDeviceCode) {
        pollJob?.cancel()
        val startedAt = SystemClock.elapsedRealtime()
        pollJob = viewModelScope.launch {
            var interval = code.intervalSeconds
            var failures = 0
            while (isActive) {
                delay(interval.toLong() * 1_000L)
                val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1_000L
                _uiState.value = _uiState.value.copy(
                    isPolling = true,
                    secondsLeft = (code.expiresInSeconds - elapsed).coerceAtLeast(0).toInt()
                )
                val result = repository.pollOnce(
                    deviceCode = code.deviceCode,
                    elapsedSeconds = elapsed,
                    expiresInSeconds = code.expiresInSeconds,
                    intervalSeconds = interval
                )
                when (result.state) {
                    BingeCatDeviceFlowRules.Poll.APPROVED -> {
                        // Approval is a mapping step, not a data screen: the
                        // grant is account-level, and this profile still has to
                        // say WHICH BingeCat profile it reads.
                        // A grant that could not be KEPT is not a connection:
                        // the account approved it, but this device has no
                        // token, and showing a picker over that would file a
                        // mapping for a session that does not exist.
                        // Re-approval is NOT a disconnect (spec P2): the
                        // mapping and its cached rows survive a new token, so
                        // an already-mapped profile stays mapped and goes
                        // straight back to being a tracker. Only a profile
                        // with no mapping needs the picker.
                        val stillMapped = if (result.saveError == null) {
                            repository.mappedProfileId()
                        } else {
                            null
                        }
                        _uiState.value = _uiState.value.copy(
                            isPolling = false,
                            userCode = null,
                            verificationUrl = null,
                            secondsLeft = 0,
                            isConnected = result.saveError == null,
                            isMapped = stillMapped != null,
                            needsReapproval = false,
                            mappedProfileId = stillMapped,
                            mappedProfileName = if (stillMapped != null) {
                                repository.mappedProfileName()
                            } else {
                                null
                            },
                            statusMessage = result.saveError
                                ?: if (stillMapped != null) {
                                    "Approved. BingeCat tracking is on for this profile."
                                } else {
                                    "Approved. Pick the BingeCat profile this KBStream " +
                                        "profile reads."
                                },
                            errorMessage = result.saveError
                        )
                        if (result.saveError == null && stillMapped == null) loadProfiles()
                        return@launch
                    }

                    BingeCatDeviceFlowRules.Poll.PENDING -> failures = 0

                    BingeCatDeviceFlowRules.Poll.SLOW_DOWN -> {
                        interval = result.retryAfterSeconds?.toInt()?.coerceAtLeast(1)
                            ?: BingeCatDeviceFlowRules.slowedIntervalSeconds(interval)
                        failures = 0
                    }

                    else -> {
                        if (result.state == BingeCatDeviceFlowRules.Poll.FAILED && failures < 2) {
                            failures += 1
                            continue
                        }
                        _uiState.value = _uiState.value.copy(
                            isPolling = false,
                            userCode = null,
                            verificationUrl = null,
                            secondsLeft = 0,
                            errorMessage = result.message
                                ?: "BingeCat did not approve the code."
                        )
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * Stops the loop AND clears the flag that says one is running.
     *
     * The flag is half of what `isLinking` reads, so cancelling the job without
     * it left the screen in the linking branch after a mid-poll CANCEL: "Asking
     * BingeCat for a device code…" with no code, and that branch offers only
     * CANCEL and BACK - nothing that asks for a new code. This is the single
     * owner of `isPolling`; no other exit path clears it on its own.
     */
    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        if (_uiState.value.isPolling) {
            _uiState.value = _uiState.value.copy(isPolling = false)
        }
    }

    /** The account's BingeCat profiles, for the picker. */
    fun loadProfiles() {
        if (!repository.hasToken()) return
        _uiState.value = _uiState.value.copy(isLoadingProfiles = true, profilesError = null)
        viewModelScope.launch {
            val profiles = repository.profiles()
            _uiState.value = _uiState.value.copy(
                isLoadingProfiles = false,
                profiles = profiles.orEmpty(),
                profilesError = if (profiles == null) {
                    "Could not read your BingeCat profiles. Check the connection and try again."
                } else {
                    null
                }
            )
        }
    }

    /** Files the mapping for THIS KBStream profile and retires the code. */
    fun pickProfile(profile: BingeCatProfile) {
        if (profile.id == null) return
        repository.setMappedProfile(profile)
        _uiState.value = _uiState.value.copy(
            isMapped = true,
            mappedProfileId = profile.id,
            mappedProfileName = profile.name,
            statusMessage = "BingeCat profile ${profile.name ?: profile.id} mapped.",
            errorMessage = null
        )
    }

    /** "Not now": the grant stays, the mapping does not, and no read happens. */
    fun skipMapping() {
        _uiState.value = _uiState.value.copy(
            statusMessage = "No BingeCat profile mapped. Nothing is read until you pick one."
        )
    }

    /** Revokes the grant and drops the mapping; local data is untouched. */
    fun disconnect() {
        stopPolling()
        codeJob?.cancel()
        _uiState.value = _uiState.value.copy(isDisconnecting = true, errorMessage = null)
        viewModelScope.launch {
            val revoked = repository.disconnect()
            _uiState.value = initialState().copy(
                isDisconnecting = false,
                statusMessage = if (revoked) {
                    "Disconnected. The grant was revoked."
                } else {
                    "Disconnected on this device. BingeCat could not be reached to " +
                        "revoke the grant."
                }
            )
        }
    }

    /**
     * A profile picker entry that arrived without an id cannot be mapped; the
     * screen does not draw those, and this keeps that rule in one place.
     */
    fun mappingsAvailable(): List<BingeCatProfile> =
        _uiState.value.profiles.filter { (it.id ?: 0L) > 0L }
}
