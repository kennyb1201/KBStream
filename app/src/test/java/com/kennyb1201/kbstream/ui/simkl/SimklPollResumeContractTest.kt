package com.kennyb1201.kbstream.ui.simkl

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failed device-flow poll must not end the sign-in (spec 2026-11-16, F1-F4).
 *
 * The report was a viewer approving the code in the browser while the app sat on
 * the same code with "Could not reach Simkl." and only GENERATE CODE: one thrown
 * request had stopped the poll for good, and the way out minted a new code,
 * abandoning the approved one. These pins keep four things true:
 *  - a FAILED poll is a blip (counted, retried at the same interval),
 *  - only a spent failure run (3) or the code's own expiry ends the attempt,
 *  - a decline or a bad client id still ends it at once,
 *  - a finished attempt can resume the SAME code while it is still valid.
 */
class SimklPollResumeContractTest {

    private val viewModel by lazy { source("com/kennyb1201/kbstream/ui/simkl/SimklViewModel.kt") }
    private val uiState by lazy { source("com/kennyb1201/kbstream/ui/simkl/SimklUiState.kt") }
    private val phone by lazy {
        source("com/kennyb1201/kbstream/ui/mobile/SimklConnectScreenMobile.kt")
    }
    private val tv by lazy { source("com/kennyb1201/kbstream/ui/simkl/SimklConnectScreen.kt") }

    /** The FAILED branch of the poll loop, plus the terminal check inside it. */
    private val failedBranch by lazy {
        viewModel
            .substringAfter("Poll.FAILED -> {")
            .substringBefore("// A decline and a bad client id")
    }

    @Test
    fun `a failed poll is counted and retried, not turned into a verdict`() {
        assertTrue(
            "the failure has to join the same run as a thrown poll, or one blip " +
                "still ends the attempt",
            failedBranch.contains("consecutiveFailures++")
        )
        assertTrue(
            "and the screen says it is retrying rather than reporting a failure",
            failedBranch.contains("statusMessage = RETRYING_MESSAGE")
        )
        assertTrue(
            "the loop carries on at the CURRENT interval - no backoff was asked " +
                "for, and a longer wait only delays a code that is already approved",
            failedBranch.contains("consecutiveFailures++") &&
                !failedBranch.contains("interval = ")
        )
        assertFalse(
            "only the spent run ends the attempt, so the branch must not return " +
                "before the threshold check",
            failedBranch
                .substringBefore("consecutiveFailures >= MAX_CONSECUTIVE_FAILURES")
                .contains("return@launch")
        )
    }

    @Test
    fun `only a spent failure run is terminal, and it says what is true`() {
        assertTrue(
            "three in a row, the same threshold the thrown-poll path uses",
            failedBranch.contains("consecutiveFailures >= MAX_CONSECUTIVE_FAILURES") &&
                viewModel.contains("const val MAX_CONSECUTIVE_FAILURES = 3")
        )
        assertTrue(
            "the terminal message is one spelling, shared by both failure paths, " +
                "so one of them cannot drift",
            viewModel.contains("private const val UNREACHABLE_MESSAGE = \"Could not reach Simkl.\"") &&
                Regex("UNREACHABLE_MESSAGE").findAll(viewModel).count() >= 3
        )
    }

    @Test
    fun `a decline or a bad client id still ends the attempt at once`() {
        val tail = viewModel
            .substringAfter("// A decline and a bad client id")
            .substringBefore("            }\n        }\n    }")
        assertTrue(
            "every other answer (DENIED, MISCONFIGURED) fails out: retrying an " +
                "answer is spinning, not recovering",
            tail.contains("else ->") &&
                tail.contains("fail(outcome.message") &&
                tail.contains("return@launch")
        )
        assertFalse(
            "and it never loops back",
            tail.contains("continue")
        )
        assertTrue(
            "the decline and the bad client id are distinct answers on the wire",
            source("com/kennyb1201/kbstream/data/simkl/SimklAuthV2Rules.kt")
                .contains("DENIED")
        )
    }

    @Test
    fun `the code's own lifetime still ends it, and a restart is still a fresh code`() {
        assertTrue(
            "expiry is measured on the monotonic clock",
            viewModel.contains("SystemClock.elapsedRealtime() - issuedAtMs") ||
                viewModel.contains("(SystemClock.elapsedRealtime() - issuedAtMs) / 1_000L")
        )
        assertTrue(
            "and an expired code still restarts itself",
            viewModel.contains("Poll.EXPIRED -> {") &&
                viewModel.contains("restartExpiredCode()")
        )
        assertTrue(
            "slow_down still widens the interval instead of speeding up",
            viewModel.contains("interval = (outcome.intervalSeconds ?: interval).coerceAtLeast(1)")
        )
    }

    @Test
    fun `a dead poll resumes the same code while it is still valid`() {
        val retry = viewModel.substringAfter("fun retryPoll()").substringBefore("private fun restartExpiredCode()")
        assertTrue(
            "it picks the poll back up rather than minting a new code",
            retry.contains("startPolling()")
        )
        assertTrue(
            "on the code already on screen - never beginCodeRequest, which would " +
                "abandon a code that may already be approved",
            !retry.contains("beginCodeRequest()") && !retry.contains("generateCode()")
        )
        assertTrue(
            "clearing the stale error before the poll restarts",
            retry.contains("errorMessage = null")
        )
        assertTrue(
            "a code that has already run out takes the expiry path instead of " +
                "polling a dead credential",
            retry.contains("elapsed >= expiresInSeconds") &&
                retry.contains("restartExpiredCode()")
        )
        assertTrue(
            "a resumed poll keeps the CODE's clock: restarting it would give an " +
                "expired code another full lifetime",
            viewModel.contains("private var codeIssuedAtMs: Long") &&
                viewModel.contains("val issuedAtMs = codeIssuedAtMs")
        )
    }

    @Test
    fun `both surfaces offer the resume, and only when the code is still alive`() {
        assertTrue(
            "one rule, in the state, so the two screens cannot disagree",
            uiState.contains("val canResumePoll: Boolean") &&
                uiState.contains("(secondsRemaining ?: 0) > 0") &&
                uiState.contains("errorMessage != null")
        )
        listOf(phone to "phone", tv to "TV").forEach { (screen, name) ->
            assertTrue(
                "$name offers RETRY instead of only GENERATE CODE",
                screen.contains("uiState.canResumePoll") &&
                    screen.contains("vm::retryPoll")
            )
        }
        assertTrue(
            "and the retry is offered alongside the code, not instead of it",
            phone.substringAfter("uiState.canResumePoll")
                .contains("GENERATE CODE")
        )
    }

    @Test
    fun `the approved path is untouched by any of this`() {
        assertTrue(
            "the repository still saves the session and the view model still " +
                "reads the token back",
            viewModel.contains("com.kennyb1201.kbstream.data.simkl.SimklAuthV2Rules.Poll.APPROVED ->") &&
                viewModel.contains("onAuthorized()") &&
                viewModel.contains("isConnected = repository.hasToken()")
        )
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    if (file.isFile) return file.readText()
                    throw AssertionError("source missing: ${file.absolutePath}")
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
