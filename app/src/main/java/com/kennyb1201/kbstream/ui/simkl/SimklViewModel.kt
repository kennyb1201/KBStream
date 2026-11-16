package com.kennyb1201.kbstream.ui.simkl

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.library.TasteRatingSubmission
import com.kennyb1201.kbstream.data.library.TasteRatingTracker
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.sync.KidsTimeGuard
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SimklViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext
    private val repository = SimklRepository.getInstance(appContext)
    private val watchedStatusRepository = WatchedStatusRepository(appContext)
    private val guard = KidsTimeGuard

    private val _uiState = MutableStateFlow(
        SimklUiState(
            isConfigured = repository.isConfigured(),
            isConnected = repository.hasToken(),
            isLegacySession = repository.isLegacySession(),
            statusMessage = if (repository.hasToken()) "Connected to Simkl." else null
        )
    )
    val uiState: StateFlow<SimklUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    /** Drives the on-screen countdown for the code's own lifetime. */
    private var countdownJob: Job? = null

    /**
     * The PIN request, so a re-entry cancels the previous one.
     *
     * It used to be an unnamed launch: a double-tap left two createPinCode()
     * calls in flight, both wrote state, and the second startPolling() cancelled
     * the first poll - leaving a code on screen from the request that had been
     * discarded.
     */
    private var codeJob: Job? = null

    init {
        if (repository.hasToken()) {
            loadAccountInfo()
            loadContinueWatching()
            loadWatchedCounts()
        }
        observeProfileSwitches()
    }

    /**
     * Profile-switch reset: the token STORE is per-profile (scoped prefs,
     * re-read on every hasToken()/request), but this ViewModel is
     * activity-scoped and was showing the previous profile's snapshot
     * ("Connected" + their continue-watching/counts) after a switch — the
     * Simkl screen looked like one account was shared across profiles.
     * Re-derive the state from the incoming profile's token store and
     * reload its data; drop any in-flight device-code poll started under
     * the other profile.
     */
    private fun observeProfileSwitches() {
        viewModelScope.launch {
            var first = true
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile
                .collect {
                    if (first) {
                        first = false
                        return@collect
                    }
                    pollJob?.cancel()
                    pollJob = null
                    codeJob?.cancel()
                    codeJob = null
                    countdownJob?.cancel()
                    countdownJob = null
                    val connected = repository.hasToken()
                    _uiState.value = SimklUiState(
                        isConfigured = repository.isConfigured(),
                        isConnected = connected,
                        isLegacySession = repository.isLegacySession(),
                        statusMessage = if (connected) "Connected to Simkl." else null
                    )
                    if (connected) {
                        loadAccountInfo()
                        loadContinueWatching()
                        loadWatchedCounts()
                    }
                }
        }
    }

    /**
     * Which Simkl account is connected. Best-effort: on failure the line
     * simply stays hidden. This is the ground truth the user checks to
     * confirm each profile carries its OWN account.
     */
    private fun loadAccountInfo() {
        viewModelScope.launch {
            val user = runCatchingCancellable { repository.getAccountInfo() }.getOrNull()
            _uiState.value = _uiState.value.copy(accountName = user?.name)
        }
    }

    /**
     * Library totals (movies / series watched) for the connect screen.
     * Best-effort: on failure the counters simply stay hidden.
     */
    private fun loadWatchedCounts() {
        viewModelScope.launch {
            val counts = runCatchingCancellable { repository.getWatchedCounts() }.getOrNull()
            counts?.let {
                _uiState.value = _uiState.value.copy(watchedCounts = it)
            }
        }
    }

    /**
     * Requests a code, after the parent gate.
     *
     * Linking an account is a parent-gated act on BOTH trackers (trackers C1):
     * Trakt's startConnect has always required the parent PIN on a kids
     * profile, while Simkl's link was ungated - so a kids profile could hook the
     * household's Simkl account up unaided and sync its watching into it. The
     * rule lives here rather than on the screens, so TV, mobile and any later
     * caller inherit it (Trakt's lives in its own ViewModel for the same
     * reason).
     *
     * Unlinking stays asymmetric on purpose, and the asymmetry is the policy:
     * Simkl's DISCONNECT is hidden on a kids profile because it destroys the
     * local watch corpus, while Trakt's only drops the session and is put back
     * by anyone who can pass this gate again.
     */
    fun generateCode() {
        pollJob?.cancel()
        codeJob?.cancel()

        // Checked here rather than only on the screen, the way Trakt's
        // startConnect does it: a build with no client id cannot request a code,
        // and its failure should be a sentence rather than the request's own
        // thrown error (trackers I2). Checked before the PIN - no PIN fixes a
        // build that was shipped without a client id.
        if (!repository.isConfigured()) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = NOT_CONFIGURED,
                statusMessage = null
            )
            return
        }

        if (ProfileManager.activeProfile.value?.kidsMaxAge != null) {
            if (!guard.hasParentPin()) {
                _uiState.value = _uiState.value.copy(
                    requiresParentPin = false,
                    pinError = false,
                    errorMessage = "A parent needs to set a parent PIN before Simkl can be " +
                        "connected on a kids profile."
                )
                return
            }
            _uiState.value = _uiState.value.copy(
                requiresParentPin = true,
                pinError = false,
                errorMessage = null
            )
            return
        }

        beginCodeRequest()
    }

    /** The parent PIN entry on a kids profile. */
    fun submitParentPin(pin: String) {
        if (!guard.verifyParentPin(pin)) {
            _uiState.value = _uiState.value.copy(pinError = true)
            return
        }
        _uiState.value = _uiState.value.copy(requiresParentPin = false, pinError = false)
        beginCodeRequest()
    }

    fun cancelParentPin() {
        _uiState.value = _uiState.value.copy(requiresParentPin = false, pinError = false)
    }

    /** A keystroke in the PIN field retires the wrong-PIN line. */
    fun clearPinError() {
        if (_uiState.value.pinError) {
            _uiState.value = _uiState.value.copy(pinError = false)
        }
    }

    /**
     * Starts an AUTH V2 device authorisation (spec 2026-11-16, F1).
     *
     * The request asks for `media:read media:write` and the QR carries
     * `verification_uri_complete`, so a scan lands on the consent screen with the
     * code already filled in. The device code stays in this state only - it is
     * the polling credential and is never drawn or logged.
     */
    private fun beginCodeRequest() {
        pollJob?.cancel()
        countdownJob?.cancel()
        codeJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                errorMessage = null,
                userCode = null,
                deviceCode = null,
                verificationUrl = null,
                secondsRemaining = null,
                statusMessage = "Requesting Simkl code…"
            )

            runCatchingCancellable { repository.startDeviceAuthorization() }
                .onSuccess { authorization ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isConnected = repository.hasToken(),
                        isLegacySession = repository.isLegacySession(),
                        userCode = authorization.userCode,
                        deviceCode = authorization.deviceCode,
                        verificationUrl = authorization.verificationUriComplete,
                        expiresIn = authorization.expiresInSeconds,
                        intervalSeconds = authorization.intervalSeconds,
                        secondsRemaining = authorization.expiresInSeconds,
                        statusMessage = "Enter this code on Simkl to connect.",
                        errorMessage = null
                    )
                    startCountdown(authorization.expiresInSeconds)
                    startPolling()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Failed to request Simkl code.",
                        statusMessage = null
                    )
                }
        }
    }

    /**
     * Ticks the code's countdown down to zero (F1).
     *
     * Display only: the poll loop owns expiry, because the same `expires_in` has
     * to end the attempt even on a device whose countdown was missed while
     * backgrounded. Measured on the monotonic clock, so a wall-clock jump cannot
     * make a code look dead early.
     */
    private fun startCountdown(expiresInSeconds: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            val startedAtMs = SystemClock.elapsedRealtime()
            while (true) {
                val elapsed = (SystemClock.elapsedRealtime() - startedAtMs) / 1_000L
                val left = com.kennyb1201.kbstream.data.simkl.SimklAuthV2Rules
                    .secondsRemaining(elapsed, expiresInSeconds)
                val current = _uiState.value
                if (current.userCode == null) return@launch
                _uiState.value = current.copy(secondsRemaining = left)
                if (left <= 0) return@launch
                delay(1_000L)
            }
        }
    }

    /**
     * Polls until the code is approved, the code expires, or the poll cannot be
     * asked.
     *
     * Two answers the first version could not give (trackers C4): a THROWN poll
     * is not a pending one - a viewer whose network drops used to read "Waiting
     * for authorization…" for the full ten minutes with nothing saying the poll
     * was never reaching Simkl - and the code's own lifetime is honoured, so a
     * code the server said expires in two minutes reports an expiry instead of
     * the generic timeout ten minutes later. Expiry is measured on the monotonic
     * clock, as Trakt's loop does: a wall-clock jump must not read as one.
     */
    /**
     * Polls `POST /oauth2/token` until the code is approved, refused, or past its
     * own lifetime (spec 2026-11-16, F1's polling discipline).
     *
     * Three answers are handled rather than lumped together: `authorization_pending`
     * keeps the wait, `slow_down` widens the interval by five seconds before the
     * next poll, and an expiry starts a FRESH code instead of leaving a dead one on
     * screen - the old flow told the viewer to generate a new one, which is the same
     * work done by hand on a D-pad.
     *
     * A decline is invisible on the wire (Simkl writes nothing), so the code's own
     * `expires_in` is the only signal the attempt ever gets.
     */
    private fun startPolling() {
        val deviceCode = _uiState.value.deviceCode ?: return
        var interval = _uiState.value.intervalSeconds.coerceAtLeast(1)
        val expiresInSeconds = _uiState.value.expiresIn ?: 0

        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            val issuedAtMs = SystemClock.elapsedRealtime()
            var consecutiveFailures = 0

            while (true) {
                delay(interval * 1000L)

                val elapsed = (SystemClock.elapsedRealtime() - issuedAtMs) / 1_000L
                if (expiresInSeconds > 0 && elapsed >= expiresInSeconds) {
                    restartExpiredCode()
                    return@launch
                }

                val outcome = runCatchingCancellable {
                    repository.pollDeviceAuthorization(
                        deviceCode = deviceCode,
                        elapsedSeconds = elapsed,
                        expiresInSeconds = expiresInSeconds,
                        intervalSeconds = interval
                    )
                }.getOrNull()

                if (outcome == null) {
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        fail("Could not reach Simkl. Try again.")
                        return@launch
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        statusMessage = "Could not reach Simkl — retrying…"
                    )
                    continue
                }

                // A poll that answered - even "not yet" - is a reached Simkl, so
                // the failure run ends here.
                consecutiveFailures = 0
                when (outcome.state) {
                    com.kennyb1201.kbstream.data.simkl.SimklAuthV2Rules.Poll.APPROVED -> {
                        onAuthorized()
                        return@launch
                    }

                    com.kennyb1201.kbstream.data.simkl.SimklAuthV2Rules.Poll.PENDING -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            statusMessage = "Waiting for authorization…"
                        )
                    }

                    com.kennyb1201.kbstream.data.simkl.SimklAuthV2Rules.Poll.SLOW_DOWN -> {
                        interval = (outcome.intervalSeconds ?: interval).coerceAtLeast(1)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            intervalSeconds = interval,
                            statusMessage = "Waiting for authorization…"
                        )
                    }

                    com.kennyb1201.kbstream.data.simkl.SimklAuthV2Rules.Poll.EXPIRED -> {
                        restartExpiredCode()
                        return@launch
                    }

                    else -> {
                        fail(outcome.message ?: "Simkl could not authorise this app.")
                        return@launch
                    }
                }
            }
        }
    }

    /** One shape for an approved poll: the session is already saved by the repository. */
    private fun onAuthorized() {
        repository.forceClearWatchedActivitySync()
        watchedStatusRepository.clearRemoteSyncCheckpoint()
        countdownJob?.cancel()

        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isConnected = repository.hasToken(),
            isLegacySession = repository.isLegacySession(),
            userCode = null,
            deviceCode = null,
            verificationUrl = null,
            secondsRemaining = null,
            statusMessage = "Connected to Simkl.",
            errorMessage = null
        )
        loadAccountInfo()
        loadContinueWatching()
        loadWatchedCounts()
        // A connect is one of the three drain triggers for the queued taste
        // signals (see TasteRatingSubmission): the fresh grant re-opens the leg
        // and this profile's pending ratings are due.
        TasteRatingSubmission.onTrackerConnected(
            appContext,
            TasteRatingTracker.SIMKL
        )
    }

    /**
     * The code died on its own: issue another, rather than leaving a dead one on
     * screen for the viewer to notice and fix by hand (F1).
     */
    private fun restartExpiredCode() {
        _uiState.value = _uiState.value.copy(
            statusMessage = "That code expired — generating a new one…",
            errorMessage = null
        )
        beginCodeRequest()
    }

    /** One shape for every way the poll can end without a token. */
    private fun fail(message: String) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            errorMessage = message,
            statusMessage = null
        )
    }

    fun loadContinueWatching() {
        if (!repository.hasToken()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingWatching = true,
                errorMessage = null
            )

            runCatchingCancellable { repository.getContinueWatching() }
                .onSuccess { items ->
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
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoadingWatching = false,
                        continueWatching = emptyList(),
                        errorMessage = error.message ?: "Failed to load continue watching."
                    )
                }
        }
    }

    fun refreshSimklNow() {
        if (!repository.hasToken()) {
            _uiState.value = _uiState.value.copy(
                isConnected = false,
                errorMessage = "Connect to Simkl first.",
                statusMessage = null
            )
            return
        }

        viewModelScope.launch {
            repository.forceClearWatchedActivitySync()
            watchedStatusRepository.clearRemoteSyncCheckpoint()

            _uiState.value = _uiState.value.copy(
                isLoadingWatching = true,
                errorMessage = null,
                statusMessage = "Refreshing Simkl data…"
            )

            runCatchingCancellable { repository.getContinueWatching(forceRefresh = true) }
                .onSuccess { items ->
                    runCatchingCancellable { repository.markWatchedActivitySynced() }
                    loadWatchedCounts()
                    loadAccountInfo()

                    _uiState.value = _uiState.value.copy(
                        isConnected = true,
                        isLoadingWatching = false,
                        continueWatching = items,
                        statusMessage = if (items.isEmpty()) {
                            "Simkl refreshed. No continue watching items were found."
                        } else {
                            "Simkl refreshed. Loaded ${items.size} continue watching items."
                        },
                        errorMessage = null
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoadingWatching = false,
                        errorMessage = error.message ?: "Failed to refresh Simkl.",
                        statusMessage = null
                    )
                }
        }
    }

    /**
     * Unlinks the account: the Simkl link and the tracker-side snapshots go;
     * nothing the viewer did locally does.
     *
     * This used to also clear the profile's local history table, "by design" -
     * and that was a REGRESSION of the invariant the Trakt integration
     * documents and this app relies on (playback spec C4/D1, 2026-10-28): a
     * disconnect is not a data wipe. It emptied every resume and history row in
     * the active profile with no tombstone, so the next pull put them back
     * (destructive locally, non-durable) - and Continue Watching was gone in
     * between. See [TraktViewModel.disconnect], which never did this.
     *
     * [WatchedStatusRepository.clearAllWatchState] is the only clear a
     * disconnect runs, and what it clears is TRACKER state: the Simkl link
     * itself ([clearLocalWatchState] → `simklRepository.clearAuth()`), the
     * cached Simkl/MDBList/Trakt snapshots and the resolved badge cache - all of
     * which refill from their own sources. Local history, resume rows, watched
     * markers and title ownership survive, on TV and on mobile alike.
     *
     * The token clear rides inside that call, so a throw in it can leave the
     * session on disk - and the screen used to publish "Disconnected" whatever
     * happened. Re-reading the token is what separates the two outcomes
     * (trackers C2). The grant on Simkl's own side is a different matter, and
     * the confirm dialog names it: this app has no revoke to call (trackers C3).
     */
    fun disconnect() {
        pollJob?.cancel()
        codeJob?.cancel()
        codeJob = null
        countdownJob?.cancel()
        countdownJob = null

        viewModelScope.launch {
            val cleared = runCatchingCancellable {
                watchedStatusRepository.clearAllWatchState()
            }
            val stillConnected = repository.hasToken()

            if (!stillConnected) {
                // The session is gone, so the pending SIMKL ratings go with it:
                // they must not fire into whatever grant a later re-link
                // produces. The TRAKT leg's entries and the local thumbs are
                // untouched, and ratings already on Simkl stand.
                TasteRatingSubmission.onTrackerDisconnected(
                    appContext,
                    TasteRatingTracker.SIMKL
                )
            }

            _uiState.value = when {
                stillConnected -> SimklUiState(
                    isConfigured = repository.isConfigured(),
                    isConnected = true,
                    isLegacySession = repository.isLegacySession(),
                    errorMessage = "Could not sign out on this device — Simkl is still " +
                        "connected here. Try again."
                )
                cleared.isFailure -> SimklUiState(
                    isConfigured = repository.isConfigured(),
                    isConnected = false,
                    errorMessage = "Disconnected, but dropping the Simkl session failed: " +
                        cleared.exceptionOrNull()?.message
                )
                else -> SimklUiState(
                    isConfigured = repository.isConfigured(),
                    isConnected = false,
                    // Says the quiet part out loud, because the old copy promised
                    // the opposite: nothing local was deleted.
                    statusMessage = "Disconnected. Your watch history stays on this device."
                )
            }
        }
    }

    private companion object {
        /**
         * Three unreachable polls in a row end the attempt. A poll that throws
         * is not the answer "not yet", and ten minutes of "Waiting…" over a
         * network that is down tells the viewer nothing (trackers C4).
         */
        const val MAX_CONSECUTIVE_FAILURES = 3

        /** What a code request reports in a build with no client id (trackers I2). */
        const val NOT_CONFIGURED = "This build has no Simkl client id, so Simkl cannot connect."
    }
}
