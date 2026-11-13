package com.kennyb1201.kbstream.data.bingecat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-11-12 field report (build 0.9.253), pinned at the source.
 *
 * The paste said `connected=yes mapped=3957 (kennyb12) last_refresh=never
 * cached=none ... last_error=no-token needs_reapproval=no` while the Library and
 * the pinned Home rails sat empty. Two things were wrong at once, and neither
 * one throws:
 *
 *  - the app DELETED its own refresh token. A 401 refuses the ACCESS token, and
 *    every 401 handler called `store.clear()`, which removed the refresh token
 *    too - so an expiry that one refresh would have fixed became a permanent
 *    `no-token` with the mapping still in place. `ensureFreshToken` helped: with
 *    no refresh token it handed the EXPIRED access token back, which is what
 *    earned the 401 in the first place.
 *  - the state lied. A missing credential still read as connected, so no screen
 *    offered the connect flow and nothing said BingeCat needed attention.
 *
 * These pins are the rules that keep both from coming back. They are source
 * contracts, like the rest of this package: there is no device in CI, and every
 * one of them is a decision that is invisible until a viewer's lists vanish.
 */
class BingeCatReconnectStateContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The text between two markers, so a test names one function's body. */
    private fun between(text: String, start: String, end: String): String {
        val at = text.indexOf(start)
        assertTrue("marker not found: $start", at >= 0)
        val rest = text.substring(at + start.length)
        val stop = rest.indexOf(end)
        assertTrue("end marker not found after $start: $end", stop >= 0)
        return rest.substring(0, stop)
    }

    private val repository =
        source("com/kennyb1201/kbstream/data/bingecat/BingeCatRepository.kt")
    private val store = source("com/kennyb1201/kbstream/data/bingecat/BingeCatTokenStore.kt")
    private val trace = source("com/kennyb1201/kbstream/data/bingecat/BingeCatTrace.kt")
    private val payload = source("com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt")

    @Test
    fun `a refused session is flagged for a reconnect, never deleted`() {
        assertTrue(
            "the marker is local-only state: it names a fault of THIS device's " +
                "credential, so it must not travel to a sibling or be confused with " +
                "a deliberate sign-out",
            store.contains("const val KEY_NEEDS_RECONNECT = \"needs_reconnect\"")
        )
        val mark = between(store, "fun markNeedsReconnect()", "fun needsReconnect()")
        assertTrue(
            "the marker is what the state is read from",
            mark.contains("putBoolean(KEY_NEEDS_RECONNECT, true)")
        )
        assertFalse(
            "a 401 refuses the access token, not the refresh token: removing either " +
                "here is the token loss this test exists for",
            mark.contains(".remove(")
        )
        assertTrue(
            "and a live pair (connect, refresh, an adopted account session) retires it",
            between(store, "fun save(token: BingeCatToken)", "fun isSignedOut()")
                .contains("putBoolean(KEY_NEEDS_RECONNECT, false)")
        )
        assertFalse(
            "no 401 handler may clear the pair again",
            repository.contains("store.clear()")
        )
        assertTrue(
            "they flag the session instead",
            repository.contains("store.markNeedsReconnect()")
        )
    }

    @Test
    fun `an expired access token is never handed to a request`() {
        val ensure = between(
            repository,
            "private suspend fun ensureFreshToken(",
            "/**\n     * The honest report of a session that cannot call BingeCat."
        )
        assertFalse(
            "with no refresh token the old code returned the EXPIRED token, which " +
                "earned the 401 that deleted the pair",
            ensure.contains("return if (force) null else current.accessToken")
        )
        assertTrue(
            "an expired token is offered AT MOST once (a clock-skewed expiry can " +
                "still be accepted), so a 401 is what turns it into the honest state",
            ensure.contains("return current.accessToken")
        )
        assertTrue(
            "and a session the server refuses is reported as the reconnect state",
            ensure.contains("refuseSession()")
        )
        assertTrue(
            "a pair the server already refused is not retried on every read",
            ensure.contains("store.needsReconnect()")
        )
        val refuse = between(
            repository,
            "private fun refuseSession()",
            "/** The OAuth `error` code in a non-2xx body"
        )
        assertTrue(
            "the honest report: not connected, reconnect needed, same reason in the " +
                "trace the field paste carries",
            refuse.contains("BingeCatTrace.recordSession(false)") &&
                refuse.contains("BingeCatTrace.recordNeedsReconnect(") &&
                refuse.contains("BingeCatTrace.recordError(\"no-token\")")
        )
        assertTrue(
            "and it only marks a STORED pair as refused - a profile that never " +
                "connected is not asked to reconnect",
            refuse.contains("if (store.read() != null) store.markNeedsReconnect()")
        )
    }

    @Test
    fun `connected means a session we can call with`() {
        val hasToken = between(repository, "fun hasToken()", "fun needsReconnect()")
        assertTrue(
            "a refused pair is not a connection, which is what made the paste say " +
                "connected=yes over an empty Library",
            hasToken.contains("val usable = store.read() != null && !store.needsReconnect()")
        )
        assertTrue(
            "the reconnect state is its own answer: a mapped profile with no usable " +
                "session, or one the server refused",
            repository.contains("fun needsReconnect(): Boolean") &&
                repository.contains("return refused || mappedProfileId() != null")
        )
        assertTrue(
            "the diagnostics line must say it, so the next paste explains itself",
            trace.contains("needs_reconnect=") &&
                trace.contains("fun recordNeedsReconnect(")
        )
        assertFalse(
            "and scope re-approval must not be claimed for a session that cannot run",
            repository.contains(
                "fun needsReapproval(): Boolean =\n" +
                    "        isConfigured() && hasToken()"
            )
        )
    }

    @Test
    fun `the connect flow is offered, not a silent empty Library`() {
        val uiState = source("com/kennyb1201/kbstream/ui/bingecat/BingeCatUiState.kt")
        val viewModel = source("com/kennyb1201/kbstream/ui/bingecat/BingeCatViewModel.kt")
        val tvScreen = source("com/kennyb1201/kbstream/ui/bingecat/BingeCatConnectScreen.kt")
        val mobileScreen =
            source("com/kennyb1201/kbstream/ui/mobile/BingeCatConnectScreenMobile.kt")
        val libraryState = source("com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt")
        val tvLibrary = source("com/kennyb1201/kbstream/ui/library/LibraryScreen.kt")
        val mobileLibrary =
            source("com/kennyb1201/kbstream/ui/mobile/LibraryScreenMobile.kt")

        assertTrue(uiState.contains("val needsReconnect: Boolean = false"))
        assertTrue(
            "the page derives it from the store, not from the last attempt",
            viewModel.contains("val needsReconnect = repository.needsReconnect()") &&
                viewModel.contains("needsReconnect = needsReconnect,")
        )
        assertTrue(
            "and both form factors word the device flow as a RECONNECT for it",
            tvScreen.contains("\"Reconnect required\"") &&
                tvScreen.contains("Generate a code to reconnect.") &&
                mobileScreen.contains("\"Reconnect required\"") &&
                mobileScreen.contains("reconnect.")
        )
        val loader = source("com/kennyb1201/kbstream/ui/home/LibraryHomeRailLoader.kt")
        val tvHome = source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")
        val mobileHome = source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
        assertTrue(
            "Home says why a pinned BingeCat rail is missing, instead of coming up a " +
                "row short - and the pins are kept, so reconnecting brings them back",
            loader.contains("fun rememberBingeCatReconnectNotice()") &&
                loader.contains("BingeCatRepository.getInstance(context).needsReconnect()") &&
                tvHome.contains("val bingecatNeedsReconnect = rememberBingeCatReconnectNotice()") &&
                tvHome.contains("BingeCat needs reconnecting: your pinned ") &&
                tvHome.contains("rails are kept but cannot load") &&
                mobileHome.contains(
                    "val bingecatNeedsReconnect = rememberBingeCatReconnectNotice()"
                ) &&
                mobileHome.contains("BingeCat needs reconnecting: your pinned BingeCat rails ")
        )
        assertTrue(
            "the Library says so too, instead of drawing nothing",
            libraryState.contains("val bingecatNeedsReconnect: Boolean = false") &&
                libraryState.contains(
                    "bingecatNeedsReconnect = bingecatRepository.needsReconnect()"
                ) &&
                tvLibrary.contains("if (state.bingecatNeedsReconnect)") &&
                mobileLibrary.contains("if (state.bingecatNeedsReconnect)")
        )
    }

    @Test
    fun `a stale account copy cannot overwrite the live refresh token`() {
        // Scoped to BingeCat's own applier: Trakt's has the same shape and the
        // same coalesce, and it is not this report's to change.
        val apply = between(
            payload,
            "private fun applyBingeCatAuth(",
            "private fun applyBingeCatProfileMap("
        )
        assertTrue(
            "BingeCat rotates the pair, so adopting on a matching access token alone " +
                "installs the refresh token the rotation already consumed",
            apply.contains("BingeCatAuthRules.adoptedRefreshToken(")
        )
        assertFalse(
            "the old coalesce kept any non-blank incoming refresh token",
            apply.contains("refresh.takeIf { it.isNotBlank() } ?: local?.refreshToken")
        )
    }
}
