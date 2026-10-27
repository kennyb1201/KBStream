package com.kennyb1201.kbstream.ui.trakt

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.sync.KidsTimeGuard
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.trakt.TraktConfig
import com.kennyb1201.kbstream.data.trakt.TraktDeviceFlowRules
import com.kennyb1201.kbstream.data.trakt.TraktDisconnectOutcome
import com.kennyb1201.kbstream.data.trakt.TraktFlowEvidence
import com.kennyb1201.kbstream.data.trakt.TraktPollTrace
import com.kennyb1201.kbstream.data.trakt.TraktRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The device-code flow, as the screen drives it.
 *
 * The shape is Simkl's (request a code, show it, poll until approved) because
 * that is the only flow a remote can complete: no WebView, no Custom Tab, no
 * redirect handler. What differs is the state machine, which lives in
 * [TraktDeviceFlowRules]: 400 means "still waiting", 429 means "poll slower",
 * and both are normal answers rather than failures.
 *
 * Three behaviours are deliberate:
 *
 *  - **Polling is owned by this ViewModel and cancelled on every exit.** The
 *    screen leaving (Back, a profile switch, a new code) kills the loop; a poll
 *    started under one profile can never save a token into the next one.
 *  - **A kids profile needs the parent PIN before a code is generated.** The
 *    plan's rule for the connect path; the check happens here rather than in the
 *    UI so the TV and touch pages cannot disagree about it.
 *  - **A failed poll is retried a bounded number of times.** The guard's
 *    synthetic-429 and a transport blip both land here; an unbounded loop is the
 *    MDBList storm this feature was told not to repeat.
 */
class TraktViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext
    private val repository = TraktRepository.getInstance(appContext)
    private val guard = KidsTimeGuard

    private val _uiState = MutableStateFlow(
        TraktUiState(
            isConfigured = repository.isConfigured(),
            isConnected = repository.hasToken(),
            accountSlug = repository.accountSlug(),
            statusMessage = if (repository.hasToken()) "Connected to Trakt." else null
        )
    )
    val uiState: StateFlow<TraktUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    /**
     * The code REQUEST, before there is a poll to own.
     *
     * It used to be an unnamed launch: backing out, switching profile or
     * disconnecting while requestDeviceCode() was in flight could not cancel
     * it, so the orphan went on to publish a code and start a poll loop for a
     * screen the viewer had left (and two attempts left the first loop
     * uncancellable, because the second overwrote pollJob).
     */
    private var codeJob: Job? = null

    /** The counts read for the connected state; replaced, never stacked. */
    private var countsJob: Job? = null

    init {
        if (repository.hasToken()) refreshAccount()
        observeProfileSwitches()
    }

    /** Profile switches re-derive the whole screen from the incoming store. */
    private fun observeProfileSwitches() {
        viewModelScope.launch {
            var first = true
            ProfileManager.activeProfile.collect {
                if (first) {
                    first = false
                    return@collect
                }
                stopPolling()
                countsJob?.cancel()
                val connected = repository.hasToken()
                _uiState.value = TraktUiState(
                    isConfigured = repository.isConfigured(),
                    isConnected = connected,
                    accountSlug = repository.accountSlug(),
                    statusMessage = if (connected) "Connected to Trakt." else null
                )
                if (connected) {
                    refreshAccount()
                    // The screen's entry effect will not fire again while it is
                    // already open on a true isConnected, so the new account's
                    // tiles are asked for here.
                    refreshWatchedCounts()
                }
            }
        }
    }

    /**
     * The screen's entry check: the store is the truth, not the last state.
     *
     * A ViewModel outlives a visit to the page, so a screen opened after an
     * earlier attempt - including one that saved the session and then died - can
     * otherwise come back showing that attempt's failure. Reading hasToken() on
     * every entry means a session that IS on disk is shown as Connected, which
     * is what the viewer's account actually is. Runs from the screens'
     * `LaunchedEffect(Unit)`, never from composition.
     */
    fun refreshConnectionState() {
        val connected = repository.hasToken()
        if (connected == _uiState.value.isConnected) return
        stopPolling()
        countsJob?.cancel()
        _uiState.value = TraktUiState(
            isConfigured = repository.isConfigured(),
            isConnected = connected,
            accountSlug = if (connected) repository.accountSlug() else null,
            statusMessage = if (connected) "Connected to Trakt." else null
        )
        if (connected) refreshAccount()
    }

    /**
     * Reads the two WATCHED totals once for the screen entry that asks for
     * them. Deliberately not called from composition: the screen runs this in a
     * `LaunchedEffect`, so a recomposition cannot spend another pair of reads.
     *
     * Any failure - no token, a non-2xx, a spent [TraktRateGuard] budget - lands
     * as null, which hides the row and leaves the connected state alone.
     */
    fun refreshWatchedCounts() {
        countsJob?.cancel()
        if (!repository.hasToken()) {
            _uiState.value = _uiState.value.copy(watchedCounts = null)
            return
        }
        countsJob = viewModelScope.launch {
            val counts = runCatchingCancellable { repository.watchedCounts() }.getOrNull()
            _uiState.value = _uiState.value.copy(watchedCounts = counts)
        }
    }

    private fun refreshAccount() {
        viewModelScope.launch {
            val slug = runCatchingCancellable { repository.loadAccountSlug() }.getOrNull()
            if (slug != null) _uiState.value = _uiState.value.copy(accountSlug = slug)
        }
    }

    /** Kicks the flow off, after the kids gate. */
    fun startConnect() {
        if (!repository.isConfigured()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "This build has no Trakt client id, so it cannot connect."
            )
            return
        }
        val profile = ProfileManager.activeProfile.value
        val isKids = profile?.kidsMaxAge != null
        if (isKids) {
            if (!guard.hasParentPin()) {
                _uiState.value = _uiState.value.copy(
                    requiresParentPin = false,
                    errorMessage = "A parent needs to set a parent PIN before Trakt can be " +
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
        beginDeviceFlow()
    }

    /** The parent PIN entry on a kids profile. */
    fun submitParentPin(pin: String) {
        if (!guard.verifyParentPin(pin)) {
            _uiState.value = _uiState.value.copy(pinError = true)
            return
        }
        _uiState.value = _uiState.value.copy(
            requiresParentPin = false,
            pinError = false
        )
        beginDeviceFlow()
    }

    fun cancelParentPin() {
        _uiState.value = _uiState.value.copy(requiresParentPin = false, pinError = false)
    }

    private fun beginDeviceFlow() {
        stopPolling()
        // This attempt owns the Diagnostics tail: an old run's polls must not be
        // read as evidence for the new one.
        TraktPollTrace.clear()
        codeJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isPolling = false,
                userCode = null,
                verificationUrl = null,
                expiresInSeconds = null,
                errorMessage = null,
                statusMessage = "Requesting a Trakt code…"
            )
            val code = runCatchingCancellable { repository.requestDeviceCode() }.getOrElse { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    statusMessage = null,
                    errorMessage = error.message ?: "Could not reach Trakt."
                )
                return@launch
            }
            // Monotonic, never the wall clock: NTP sync or a viewer changing
            // the time can jump `currentTimeMillis` by minutes inside this
            // 1.5-second flow, and `elapsed` would then read as 600s+ on the
            // FIRST poll - "That code expired" while Trakt has just accepted
            // the approval. elapsedRealtime measures real elapsed time, which
            // is the only thing the poll's expiry math actually needs.
            val issuedAtMs = SystemClock.elapsedRealtime()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isPolling = true,
                userCode = code.userCode,
                verificationUrl = code.verificationUrl ?: TraktConfig.ACTIVATE_URL,
                expiresInSeconds = code.expiresInSeconds,
                statusMessage = "Waiting for approval…"
            )
            pollUntilApproved(
                deviceCode = code.deviceCode,
                issuedAtMs = issuedAtMs,
                expiresInSeconds = code.expiresInSeconds,
                intervalSeconds = code.intervalSeconds
            )
        }
    }

    private fun pollUntilApproved(
        deviceCode: String,
        issuedAtMs: Long,
        expiresInSeconds: Int,
        intervalSeconds: Int
    ) {
        pollJob = viewModelScope.launch {
            var interval = intervalSeconds
            var consecutiveFailures = 0
            // The FLOW's evidence, not the last poll's: a 404 after anything
            // in this flow threw is the spent code being read again, not the
            // viewer's code expiring (see [TraktFlowEvidence]).
            val evidence = TraktFlowEvidence()
            while (isActive) {
                delay(TraktDeviceFlowRules.intervalMillis(interval))
                val elapsed = (SystemClock.elapsedRealtime() - issuedAtMs) / 1_000L
                val result = runCatchingCancellable {
                    repository.pollOnce(
                        deviceCode = deviceCode,
                        elapsedSeconds = elapsed,
                        expiresInSeconds = expiresInSeconds
                    )
                }.getOrElse { error ->
                    // Recorded, never swallowed: a throw between "Trakt answered
                    // 200" and "the state reached the screen" used to leave no
                    // evidence at all, so the next poll into an already-spent
                    // code was the only thing a report could show.
                    TraktPollTrace.recordPollError(error)
                    evidence.recordThrow()
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        fail("Could not reach Trakt. Try again.")
                        return@launch
                    }
                    continue
                }
                evidence.recordPoll(result.statusCode)
                when (result.state) {
                    TraktDeviceFlowRules.Poll.APPROVED -> {
                        // Trakt granted the token but this device could not keep
                        // it. The code is spent, so retrying can only read 404:
                        // say what actually happened and stop.
                        if (result.saveError != null) {
                            _uiState.value = _uiState.value.copy(
                                isPolling = false,
                                isLoading = false,
                                userCode = null,
                                verificationUrl = null,
                                expiresInSeconds = null,
                                statusMessage = "Trakt approved this device.",
                                errorMessage = "Trakt approved, but the token couldn't " +
                                    "be saved on this device. Try connecting again."
                            )
                            return@launch
                        }
                        val slug = runCatchingCancellable { repository.loadAccountSlug() }
                            .getOrNull()
                        _uiState.value = _uiState.value.copy(
                            isConnected = true,
                            isPolling = false,
                            userCode = null,
                            verificationUrl = null,
                            expiresInSeconds = null,
                            accountSlug = slug,
                            statusMessage = "Connected to Trakt.",
                            errorMessage = null
                        )
                        // No counts call here: the screen's entry effect sees
                        // isConnected flip and fetches the pair exactly once.
                        return@launch
                    }

                    TraktDeviceFlowRules.Poll.PENDING -> consecutiveFailures = 0

                    TraktDeviceFlowRules.Poll.SLOW_DOWN -> {
                        consecutiveFailures = 0
                        interval = TraktDeviceFlowRules.nextIntervalSeconds(interval)
                        val wait = result.retryAfterSeconds
                        if (wait != null && wait > interval) interval = wait.toInt()
                    }

                    TraktDeviceFlowRules.Poll.EXPIRED -> {
                        // "Expired" is only the honest answer when no poll in
                        // this flow threw. After one did, the code was probably
                        // spent by that very request, and a 404 here is this
                        // app failing to read Trakt's reply.
                        if (evidence.expiredOutcome() == TraktDeviceFlowRules.Poll.FAILED) {
                            fail(
                                "Something went wrong reading Trakt's reply. " +
                                    "Try connecting again."
                            )
                        } else {
                            fail("That code expired. Start again for a fresh one.")
                        }
                        return@launch
                    }

                    TraktDeviceFlowRules.Poll.DENIED -> {
                        fail("Trakt access was denied.")
                        return@launch
                    }

                    TraktDeviceFlowRules.Poll.MISCONFIGURED -> {
                        // Retrying cannot fix a rejected client: say so once and
                        // stop, instead of polling into an expiry that reads as
                        // the viewer's fault.
                        fail(
                            "Trakt rejected this build's client id. A client secret " +
                                "cannot be shipped in the app, so this needs a server-" +
                                "side exchange."
                        )
                        return@launch
                    }

                    TraktDeviceFlowRules.Poll.FAILED -> {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            fail("Could not reach Trakt. Try again.")
                            return@launch
                        }
                        result.retryAfterSeconds?.let { wait ->
                            if (wait > 0) interval = wait.toInt()
                        }
                    }
                }
            }
        }
    }

    private fun fail(message: String) {
        _uiState.value = _uiState.value.copy(
            isPolling = false,
            isLoading = false,
            userCode = null,
            verificationUrl = null,
            expiresInSeconds = null,
            statusMessage = null,
            errorMessage = message
        )
    }

    /**
     * Disconnects: revokes the grant and drops the Trakt session only.
     *
     * The local watch history, resume rows and watched markers are the viewer's
     * own data and are untouched - see TraktRepository.revokeAndClear.
     *
     * What it did is reported from the repository's own outcome rather than
     * assumed: a cleared store that could not be written leaves the session on
     * disk, and publishing "Disconnected" regardless was a screen that lied and
     * then showed Connected again on the next entry (trackers C2). The revoke is
     * best-effort, so a guard-blocked or unreachable one says "signed out on this
     * device" instead of claiming the grant is gone (trackers C5).
     */
    fun disconnect() {
        stopPolling()
        countsJob?.cancel()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val outcome = runCatchingCancellable { repository.revokeAndClear() }
                .getOrDefault(TraktDisconnectOutcome.NOT_CLEARED)

            if (outcome == TraktDisconnectOutcome.NOT_CLEARED) {
                _uiState.value = TraktUiState(
                    isConfigured = repository.isConfigured(),
                    // Still connected, because it still is: the store would not
                    // take the clear, so the session is exactly where it was.
                    isConnected = true,
                    accountSlug = runCatching { repository.accountSlug() }.getOrNull(),
                    errorMessage = "Could not sign out on this device — Trakt is " +
                        "still connected here. Try again."
                )
                return@launch
            }

            _uiState.value = TraktUiState(
                isConfigured = repository.isConfigured(),
                isConnected = false,
                statusMessage = when (outcome) {
                    TraktDisconnectOutcome.REVOKED ->
                        "Disconnected from Trakt. Your watch history is untouched."
                    else ->
                        "Signed out on this device — your watch history is untouched. " +
                            "Trakt still lists this app; remove it at trakt.tv/settings " +
                            "to revoke it there."
                }
            )
        }
    }

    /**
     * The connected branch's REFRESH: the manual twin of the entry read.
     *
     * [refreshWatchedCounts] above stays silent on failure by design - it runs
     * unprompted on entry and on a profile switch, where an error line about a
     * read the viewer never asked for would be noise, and hiding the row keeps
     * the documented "a tracker failure is not nothing watched" rule. A press,
     * though, has to answer: this reports the failure and keeps the totals that
     * were already on screen rather than blanking them (trackers I1).
     */
    fun refreshWatchedCountsNow() {
        countsJob?.cancel()
        if (!repository.hasToken()) {
            _uiState.value = _uiState.value.copy(watchedCounts = null)
            return
        }
        countsJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingCounts = true,
                errorMessage = null,
                statusMessage = "Refreshing Trakt totals…"
            )
            val counts = runCatchingCancellable { repository.watchedCounts() }.getOrNull()
            val previous = _uiState.value.watchedCounts
            _uiState.value = _uiState.value.copy(
                isLoadingCounts = false,
                watchedCounts = counts ?: previous,
                statusMessage = if (counts != null) "Trakt totals refreshed." else null,
                errorMessage = if (counts == null) {
                    "Could not read your Trakt totals. Try again."
                } else {
                    null
                }
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun stopPolling() {
        codeJob?.cancel()
        codeJob = null
        pollJob?.cancel()
        pollJob = null
        if (_uiState.value.isPolling) {
            _uiState.value = _uiState.value.copy(isPolling = false)
        }
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }

    private companion object {
        /** Three failed polls in a row end the attempt rather than loop. */
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
