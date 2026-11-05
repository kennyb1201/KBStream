package com.kennyb1201.kbstream.ui.bingecat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two connect-screen fixes, pinned where a device would otherwise be the
 * only witness.
 *
 * F1: a CANCEL mid-poll used to leave `isPolling` set with no job behind it.
 * `isLinking` reads that flag, so the screen stayed in its linking branch -
 * "Asking BingeCat for a device code…" - which offers CANCEL and BACK only, and
 * no re-entry helped because the entry check early-returned over the stale flag.
 * F2: the QR encoded the bare activation page, so "scan to connect" still meant
 * typing the code, which is the path that failed twice on the TV while the
 * direct `?user_code=` link worked.
 */
class BingeCatConnectCancelAndQrContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private val vm =
        source("com/kennyb1201/kbstream/ui/bingecat/BingeCatViewModel.kt")
    private val screen =
        source("com/kennyb1201/kbstream/ui/bingecat/BingeCatConnectScreen.kt")
    private val state =
        source("com/kennyb1201/kbstream/ui/bingecat/BingeCatUiState.kt")

    private fun bodyOf(text: String, header: String): String =
        text.substringAfter(header).substringBefore("\n    }")

    @Test
    fun `the linking branch really does read the polling flag`() {
        assertTrue(
            "this is why a stale flag wedges the screen: isLinking is derived from it",
            squash(state).contains(
                "val isLinking: Boolean get() = userCode != null || isRequestingCode || isPolling"
            )
        )
    }

    @Test
    fun `stopping the poll clears the flag, so CANCEL unwedges the screen`() {
        val stop = squash(bodyOf(vm, "private fun stopPolling() {"))
        assertTrue("the job must be cancelled: $stop", stop.contains("pollJob?.cancel()"))
        assertTrue("and released: $stop", stop.contains("pollJob = null"))
        assertTrue(
            "and the flag the screen reads must go with it: $stop",
            stop.contains("isPolling = false")
        )
        val cancel = squash(bodyOf(vm, "fun cancelCode() {"))
        assertTrue(
            "CANCEL inherits the fix rather than clearing the flag itself: $cancel",
            cancel.contains("stopPolling()") && !cancel.contains("isPolling")
        )
    }

    @Test
    fun `a cancelled code request leaves no flag behind`() {
        val request = squash(bodyOf(vm, "codeJob = viewModelScope.launch {"))
        assertTrue(
            "the reset must cover cancellation, not only Issued/Failed: $request",
            request.contains("} finally {") &&
                request.contains("_uiState.value.copy(isRequestingCode = false)")
        )
        assertTrue(
            "and only the current request may clear it, or a superseded one flips " +
                "the flag under its replacement: $request",
            request.contains("coroutineContext[Job] === codeJob")
        )
    }

    @Test
    fun `re-entry keeps a live attempt but drops a flag with no job`() {
        val refresh = squash(bodyOf(vm, "fun refreshConnectionState() {"))
        assertTrue(
            "a flag without a live job is stale state, not an attempt: $refresh",
            refresh.contains("current.isPolling && pollJob?.isActive != true") &&
                refresh.contains("current.isRequestingCode && codeJob?.isActive != true")
        )
        assertTrue(
            "while a genuine in-flight attempt survives re-entry untouched",
            refresh.contains("isPolling = current.isPolling && !stalePoll") &&
                refresh.contains("isRequestingCode = current.isRequestingCode && !staleRequest")
        )
        assertTrue(
            "and the early return still only fires when nothing changed",
            refresh.contains("next.isConnected == _uiState.value.isConnected") &&
                refresh.contains("next.isMapped == _uiState.value.isMapped")
        )
    }

    @Test
    fun `the QR carries the code that is on the screen`() {
        // Up to the NEXT declaration, not the next closing brace: the panel
        // opens with an `if (code == null) { ... return }` guard.
        val panel = squash(
            screen
                .substringAfter("private fun BingeCatLinkingPanel(")
                .substringBefore("private fun BingeCatProfilePicker(")
        )
        assertTrue(
            "the QR URL must be derived from the address AND the current code: $panel",
            panel.contains("bingecatActivationUrlForQr(url, code)")
        )
        assertTrue(
            "and that URL is what the QR encodes: $panel",
            panel.contains("KBQrCode(url = qrUrl, size = 140.dp)")
        )
        assertTrue(
            "the URL is remembered on the code too, so a fresh code cannot show " +
                "the previous QR: $panel",
            panel.contains("remember(url, code)")
        )
        assertTrue(
            "the manual fallback stays: the code is still drawn large: $panel",
            panel.contains("text = code,") && panel.contains("headlineMedium")
        )
        assertTrue(
            "and the activation address is still named for anyone who cannot scan: $panel",
            panel.contains("Enter the code below at \$url")
        )
        assertTrue(
            "copy leads with scanning now: $panel",
            panel.contains("title = \"Scan to connect BingeCat\"")
        )
    }

    @Test
    fun `Trakt and Simkl keep encoding the plain address`() {
        listOf(
            "com/kennyb1201/kbstream/ui/trakt/TraktConnectScreen.kt",
            "com/kennyb1201/kbstream/ui/simkl/SimklConnectScreen.kt",
            "com/kennyb1201/kbstream/ui/mobile/TraktConnectScreenMobile.kt",
            "com/kennyb1201/kbstream/ui/mobile/SimklConnectScreenMobile.kt"
        ).forEach { path ->
            val text = source(path)
            assertTrue(
                "$path: unchanged plain-URL QR",
                text.contains("KBQrCode(url = url,")
            )
            assertFalse(
                "$path: the BingeCat exception must not leak into the other trackers",
                text.contains("bingecatActivationUrlForQr")
            )
        }
    }
}
