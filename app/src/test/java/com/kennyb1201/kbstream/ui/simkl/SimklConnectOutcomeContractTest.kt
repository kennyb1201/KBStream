package com.kennyb1201.kbstream.ui.simkl

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Simkl half of the tracker-connect dive: the poll's diagnostics (C4), the
 * build-health branch Simkl was missing (I2), and the two things its disconnect
 * must not overclaim (C2's soft variant, C3's grant).
 *
 * Read from the sources, like [data.trakt.TraktWiringContractTest]: the flow
 * needs a live Simkl account and a device-code exchange, and the claims being
 * pinned are the ones the screen makes in words.
 */
class SimklConnectOutcomeContractTest {

    private fun main(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/$relative")
                if (candidate.isFile) return candidate.readText().replace(Regex("\\s+"), " ")
            }
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found")
    }

    private val viewModel = main("com/kennyb1201/kbstream/ui/simkl/SimklViewModel.kt")
    private val state = main("com/kennyb1201/kbstream/ui/simkl/SimklUiState.kt")
    private val tvScreen = main("com/kennyb1201/kbstream/ui/simkl/SimklConnectScreen.kt")
    private val phoneScreen = main("com/kennyb1201/kbstream/ui/mobile/SimklConnectScreenMobile.kt")

    @Test
    fun `a build with no client id says so instead of offering a code that must fail`() {
        assertTrue(
            "the screen cannot branch on a flag it is not given",
            state.contains("val isConfigured: Boolean = false")
        )
        assertTrue(
            "and every state the view model publishes carries it",
            Regex("isConfigured = repository\\.isConfigured\\(\\)")
                .findAll(viewModel).count() >= 5
        )
        assertTrue(
            "the request itself is guarded too, the way Trakt's startConnect is - " +
                "the screen's branch is presentation, not the rule",
            viewModel.contains("if (!repository.isConfigured())") &&
                viewModel.contains(
                    "NOT_CONFIGURED = \"This build has no Simkl client id, so Simkl cannot connect.\""
                )
        )
        listOf(
            "ui/simkl/SimklConnectScreen.kt" to tvScreen,
            "ui/mobile/SimklConnectScreenMobile.kt" to phoneScreen
        ).forEach { (path, screen) ->
            assertTrue(
                "$path must draw the branch Trakt's page has always had (trackers I2)",
                screen.contains("if (!uiState.isConfigured) {") &&
                    screen.contains("\"Not available in this build\"") &&
                    screen.contains("SIMKL_CLIENT_ID")
            )
        }
    }

    @Test
    fun `a poll that cannot be asked is not reported as waiting`() {
        assertTrue(
            "three unreachable polls in a row end the attempt",
            viewModel.contains("MAX_CONSECUTIVE_FAILURES = 3") &&
                viewModel.contains("consecutiveFailures++") &&
                viewModel.contains("fail(\"Could not reach Simkl. Try again.\")")
        )
        assertTrue(
            "and the retrying state says the poll is not reaching Simkl, rather " +
                "than reusing the pending answer (trackers C4)",
            viewModel.contains("statusMessage = \"Could not reach Simkl — retrying…\"")
        )
        assertEquals(
            "the pending wording now belongs to exactly one case: Simkl answered " +
                "and there is no token yet",
            1,
            Regex("Waiting for authorization…").findAll(viewModel).count()
        )
        assertTrue(
            "a reached Simkl resets the failure run, so three answers in a row are " +
                "not needed for a flow that keeps polling",
            viewModel.contains("consecutiveFailures = 0") &&
                viewModel.indexOf("consecutiveFailures = 0") <
                viewModel.indexOf("consecutiveFailures++")
        )
    }

    @Test
    fun `the code's own lifetime is honoured, on the monotonic clock`() {
        assertTrue(
            "the granted expiry has to be consulted, not just stored in state: " +
                "120 polls at five seconds is ten minutes, so a code the server " +
                "said expires sooner used to read as a timeout",
            viewModel.contains("val expiresInSeconds = _uiState.value.expiresIn ?: 0") &&
                viewModel.contains(
                    "(SystemClock.elapsedRealtime() - issuedAtMs) / 1_000L >= expiresInSeconds"
                ) &&
                viewModel.contains("fail(\"That code expired. Generate a new one.\")")
        )
        assertTrue(
            "and it is measured on the monotonic clock, so a wall-clock jump " +
                "cannot read as an expiry",
            viewModel.contains("import android.os.SystemClock") &&
                viewModel.contains("SystemClock.elapsedRealtime()")
        )
        assertFalse(
            "never the wall clock",
            viewModel.contains("System.currentTimeMillis() - issuedAtMs")
        )
    }

    @Test
    fun `a kids profile needs the parent PIN before a Simkl code is generated`() {
        assertTrue(
            "the gate has to live in the view model, so TV, mobile and any later " +
                "caller inherit it: linking was the half of this policy with no " +
                "parent in the loop (trackers C1)",
            viewModel.contains("private val guard = KidsTimeGuard") &&
                viewModel.contains("ProfileManager.activeProfile.value?.kidsMaxAge != null") &&
                viewModel.contains("guard.hasParentPin()") &&
                viewModel.contains("guard.verifyParentPin(pin)")
        )
        val generate = viewModel
            .substringAfter("fun generateCode()")
            .substringBefore("private fun beginCodeRequest()")
        assertTrue(
            "generateCode must stop at the gate rather than launching the " +
                "request itself: $generate",
            generate.contains("requiresParentPin = true") &&
                generate.contains("A parent needs to set a parent PIN") &&
                !generate.contains("viewModelScope.launch")
        )
        assertTrue(
            "and the PIN is checked before the request it unlocks",
            viewModel.indexOf("guard.verifyParentPin(pin)") <
                viewModel.indexOf(
                    "beginCodeRequest()",
                    viewModel.indexOf("fun submitParentPin")
                )
        )
        listOf(
            "ui/simkl/SimklConnectScreen.kt" to tvScreen,
            "ui/mobile/SimklConnectScreenMobile.kt" to phoneScreen
        ).forEach { (path, screen) ->
            assertTrue(
                "$path must draw the gate and hand it the PIN, as Trakt's page does",
                screen.contains("uiState.requiresParentPin") &&
                    screen.contains("Parent PIN required") &&
                    screen.contains("vm.submitParentPin(pin)")
            )
        }
    }

    @Test
    fun `a disconnect that left the session on disk is not reported as a disconnect`() {
        assertTrue(
            "the token has to be re-read after the wipe: the token clear rides " +
                "inside clearAllWatchState(), so a throw there left it on disk " +
                "behind a screen that said Disconnected (trackers C2)",
            viewModel.contains("val stillConnected = repository.hasToken()") &&
                viewModel.contains("stillConnected -> SimklUiState(") &&
                viewModel.contains("isConnected = true") &&
                viewModel.contains("Could not sign out on this device")
        )
        assertTrue(
            "and the wipe's own failure keeps its sentence instead of being " +
                "folded into the sign-out one",
            viewModel.contains("wipe.isFailure -> SimklUiState(") &&
                viewModel.contains("Disconnected, but clearing watch data failed: ")
        )
    }

    @Test
    fun `the dialog names the grant this app cannot revoke`() {
        listOf(
            "ui/simkl/SimklConnectScreen.kt" to tvScreen,
            "ui/mobile/SimklConnectScreenMobile.kt" to phoneScreen
        ).forEach { (path, screen) ->
            assertTrue(
                "$path must say the app cannot revoke its own access, and where " +
                    "the viewer does it instead (trackers C3)",
                screen.contains("simkl.com") && screen.contains("revoke its own")
            )
        }
    }
}
