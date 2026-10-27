package com.kennyb1201.kbstream.ui.trakt

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a Trakt disconnect is allowed to claim, and what the connected page can
 * do about it - the two halves of the tracker-connect dive that had no test
 * (trackers C2, C5, I1).
 *
 * Read from the sources for the same reason [data.trakt.TraktWiringContractTest]
 * is: the alternative is a device with a corrupted KeyStore. The store's own
 * fault path is pinned in [data.trakt.TraktTokenStoreRebuildTest]; what is
 * pinned here is that the disconnect consumes it instead of assuming success.
 */
class TraktConnectOutcomeContractTest {

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

    private val repository = main("com/kennyb1201/kbstream/data/trakt/TraktRepository.kt")
    private val store = main("com/kennyb1201/kbstream/data/trakt/TraktTokenStore.kt")
    private val viewModel = main("com/kennyb1201/kbstream/ui/trakt/TraktViewModel.kt")
    private val tvScreen = main("com/kennyb1201/kbstream/ui/trakt/TraktConnectScreen.kt")
    private val phoneScreen = main("com/kennyb1201/kbstream/ui/mobile/TraktConnectScreenMobile.kt")

    @Test
    fun `a disconnect reports what it actually did`() {
        assertTrue(
            "the store has to say whether the tombstone was written: a store " +
                "that will not open is the one case where the session survives",
            store.contains("fun markSignedOut(): Boolean") &&
                store.contains("val editor = prefs?.edit() ?: return false")
        )
        assertTrue(
            "and the disconnect turns that into an outcome rather than a Unit " +
                "the screen cannot read",
            repository.contains("enum class TraktDisconnectOutcome") &&
                repository.contains("suspend fun revokeAndClear(): TraktDisconnectOutcome") &&
                repository.contains(
                    "val cleared = runCatchingCancellable { store.markSignedOut() }" +
                        ".getOrDefault(false)"
                ) &&
                repository.contains("if (!cleared) return TraktDisconnectOutcome.NOT_CLEARED")
        )
        assertTrue(
            "nothing may be published for a session that survived - a tombstone " +
                "would sign the account out on every OTHER device while this one " +
                "stayed connected",
            repository.indexOf("if (!cleared) return TraktDisconnectOutcome.NOT_CLEARED") <
                repository.indexOf("publishAuthToSync()", repository.indexOf("suspend fun revokeAndClear()"))
        )
    }

    @Test
    fun `an uncleared session is not reported as disconnected`() {
        assertTrue(
            "the screen must keep Trakt connected when the clear did not take, " +
                "or the next entry reads the store and shows Connected again",
            viewModel.contains("outcome == TraktDisconnectOutcome.NOT_CLEARED") &&
                viewModel.contains("isConnected = true") &&
                viewModel.contains("Could not sign out on this device")
        )
        assertTrue(
            "and the screen has to publish from that outcome rather than from a " +
                "call whose result is thrown away, which is the whole of C2",
            viewModel.contains(
                "val outcome = runCatchingCancellable { repository.revokeAndClear() }"
            )
        )
    }

    @Test
    fun `a skipped revoke is not reported as a revoked grant`() {
        assertTrue(
            "the revoked case keeps the old wording",
            viewModel.contains("TraktDisconnectOutcome.REVOKED ->") &&
                viewModel.contains("Disconnected from Trakt. Your watch history is untouched.")
        )
        assertTrue(
            "and the other case says what actually happened - the grant outlives " +
                "the sign-out - instead of claiming the same thing (trackers C5)",
            viewModel.contains("Signed out on this device") &&
                viewModel.contains("trakt.tv/settings")
        )
        assertTrue(
            "the revoke stays best-effort, and reports rather than swallowing",
            repository.contains("revoked = runCatchingCancellable {") &&
                repository.contains("}.isSuccess")
        )
    }

    @Test
    fun `the connected page can re-ask for its totals`() {
        assertTrue(
            "a manual refresh exists beside the entry read",
            viewModel.contains("fun refreshWatchedCountsNow()") &&
                viewModel.contains("isLoadingCounts = true")
        )
        listOf(
            "ui/trakt/TraktConnectScreen.kt" to tvScreen,
            "ui/mobile/TraktConnectScreenMobile.kt" to phoneScreen
        ).forEach { (path, screen) ->
            assertTrue(
                "$path must offer the retry its connected branch lacked: a failed " +
                    "totals read hid the row with no way to ask again (trackers I1)",
                screen.contains("vm::refreshWatchedCountsNow") &&
                    screen.contains("REFRESH")
            )
        }
        assertTrue(
            "a manual refresh that fails must keep the totals already on screen - " +
                "a tracker failure is not \"nothing watched\"",
            viewModel.contains("watchedCounts = counts ?: previous") &&
                viewModel.contains("Could not read your Trakt totals. Try again.")
        )
    }

    @Test
    fun `the unconfigured branch is not a dead end on the d-pad`() {
        val panel = tvScreen.indexOf("\"Not available in this build\"")
        assertTrue("the unconfigured panel exists", panel >= 0)
        val branch = tvScreen.substring(panel, tvScreen.indexOf("} else if (uiState.isConnected) {", panel))
        assertFalse(
            "the branch is entered when a build cannot connect at all, so it must " +
                "still hand the viewer a control - there is nothing else focusable " +
                "on the page",
            branch.isEmpty()
        )
        assertTrue(
            "and that control is the page's own back",
            branch.contains("KBButton(label = \"BACK\", onClick = onBack)")
        )
    }
}
