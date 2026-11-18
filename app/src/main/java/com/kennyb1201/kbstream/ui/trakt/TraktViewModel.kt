package com.kennyb1201.kbstream.ui.trakt

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.library.TasteRatingSubmission
import com.kennyb1201.kbstream.data.library.TasteRatingTracker
import com.kennyb1201.kbstream.data.sync.KidsTimeGuard
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.trakt.TraktConfig
import com.kennyb1201.kbstream.data.trakt.TraktDeviceFlowRules
import com.kennyb1201.kbstream.data.trakt.TraktDisconnectOutcome
import com.kennyb1201.kbstream.data.trakt.TraktFlowEvidence
import com.kennyb1201.kbstream.data.trakt.TraktPollTrace
import com.kennyb1201.kbstream.data.trakt.TraktRepository
import com.kennyb1201.kbstream.ui.profiles.ParentPinSubmit
import com.kennyb1201.kbstream.ui.profiles.parentPinPromptSpec
import com.kennyb1201.kbstream.ui.profiles.submitParentPinPrompt
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
            // A session Trakt already refused reads as needing a reconnect from
            // the first frame, not as "Connected" with silent dead scrobbles
            // (spec audit 2026-11-18, Onboarding M2).
            needsReconnect = repository.needsReconnect(),
            accountSlug = repository.accountSlug(),
            statusMessage = when {
                repository.needsReconnect() -> TRAKT_NEEDS_RECONNECT_MESSAGE
                repository.hasToken() -> "Connected to Trakt."
                else -> null
            }
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
        val needsReconnect = repository.needsReconnect()
        if (
            connected == _uiState.value.isConnected &&
            needsReconnect == _uiState.value.needsReconnect
        ) {
            return
        }
        stopPolling()
        countsJob?.cancel()
        _uiState.value = TraktUiState(
            isConfigured = repository.isConfigured(),
            isConnected = connected,
            needsReconnect = needsReconnect,
            accountSlug = if (connected) repository.accountSlug() else null,
            statusMessage = when {
                needsReconnect -> TRAKT_NEEDS_RECONNECT_MESSAGE
                connected -> "Connected to Trakt."
                else -> null
            }
        )
        // A refused grant has nothing to read: the totals would only earn
        // another 401.
        if (connected && !needsReconnect) refreshAccount()
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
                // CREATE it here instead of telling the parent to go and find the
                // setting (spec audit 2026-11-18, Onboarding M4). This was a dead
                // end on a fresh device: the sentence named the missing credential
                // and the page offered only UNLOCK, which needs a PIN that does
                // not exist. Simkl lost the same shape on 2026-11-17; this is the
                // same prompt, through the same shared rules.
                _uiState.value = _uiState.value.copy(
                    requiresParentPin = false,
                    parentPinCreate = true,
                    parentPinMessage = null,
                    errorMessage = null
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

    /**
     * The CREATE prompt's submit, for a kids profile on a device with no parent
     * PIN (spec audit 2026-11-18, Onboarding M4).
     *
     * Shares the rules with the profile editors and the Simkl gate
     * ([parentPinPromptSpec] CREATE + [submitParentPinPrompt]), so a short PIN, a
     * mismatch and a failed write behave there, here and on both surfaces
     * identically. An accepted PIN starts the device flow it was blocking.
     */
    fun submitParentPinCreation(newPin: String, confirmPin: String) {
        val spec = parentPinPromptSpec(gated = true, credentialExists = guard.hasParentPin())
        val result = submitParentPinPrompt(
            spec = spec,
            gateTarget = ProfileManager.activeProfile.value,
            currentPin = "",
            newPin = newPin,
            confirmPin = confirmPin,
            writePin = { pin -> guard.setParentPin(pin) }
        )
        when (result) {
            is ParentPinSubmit.Accepted -> {
                _uiState.value = _uiState.value.copy(
                    parentPinCreate = false,
                    parentPinMessage = null,
                    pinError = false
                )
                beginDeviceFlow()
            }

            is ParentPinSubmit.Rejected -> {
                _uiState.value = _uiState.value.copy(parentPinMessage = result.message)
            }
        }
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
                        // A connect is one of the three drain triggers for the
                        // queued taste signals (see TasteRatingSubmission): the
                        // new grant re-opens the leg and anything this profile
                        // left pending is due now.
                        TasteRatingSubmission.onTrackerConnected(
                            appContext,
                            TasteRatingTracker.TRAKT
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
                    // take the clear, so the session is exactly where it was -
                    // including a refusal marker, which is why it is re-read
                    // rather than assumed false (Onboarding M2).
                    isConnected = true,
                    needsReconnect = repository.needsReconnect(),
                    accountSlug = runCatching { repository.accountSlug() }.getOrNull(),
                    errorMessage = "Could not sign out on this device — Trakt is " +
                        "still connected here. Try again."
                )
                return@launch
            }

            // The session is genuinely gone, so the pending TRAKT ratings go
            // with it: a queued rating must not fire into whatever grant a
            // later re-link produces. Ratings already on the tracker stand -
            // disconnecting never takes a rating back.
            TasteRatingSubmission.onTrackerDisconnected(
                appContext,
                TasteRatingTracker.TRAKT
            )

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
        // The CREATE prompt's own inline refusal is cleared on the next keystroke
        // too (spec audit 2026-11-18, Onboarding M4): it belongs to the value
        // that produced it, exactly as the verify gate's `pinError` does.
        _uiState.value = _uiState.value.copy(
            errorMessage = null,
            pinError = false,
            parentPinMessage = null
        )
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

        /**
         * What a refused session says (Onboarding M2). Words the two facts that
         * matter: the link is dead server-side, and nothing local was lost.
         */
        const val TRAKT_NEEDS_RECONNECT_MESSAGE =
            "Trakt needs reconnecting: this device's session was refused, so nothing " +
                "can be read or tracked. Your history, lists and watch data are kept."
    }
}
