package com.kennyb1201.kbstream.ui.trakt

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring around a Trakt 200 that the device could not persist.
 *
 * The exchange itself is covered behaviourally by `TraktPollExchangeTest`; what
 * this pins is that the screen and the loop USE its distinct outcome. Each
 * assertion names a seam that a later refactor could quietly undo, and every
 * one of them is a place the original "That code expired" report came from:
 * a save failure indistinguishable from a failed poll, a swallowed exception
 * with no evidence, and an entry that trusted stale state over the store.
 */
class TraktApprovedButUnsavedContractTest {

    private val viewModel = source("com/kennyb1201/kbstream/ui/trakt/TraktViewModel.kt")
    private val repository = source("com/kennyb1201/kbstream/data/trakt/TraktRepository.kt")
    private val store = source("com/kennyb1201/kbstream/data/trakt/TraktTokenStore.kt")

    @Test
    fun `an approved-but-unsaved poll is shown as that, and the loop stops`() {
        val approved = viewModel.substringAfter("TraktDeviceFlowRules.Poll.APPROVED ->")
            .substringBefore("TraktDeviceFlowRules.Poll.PENDING ->")

        assertTrue(
            "the distinct outcome is read: $approved",
            approved.contains("result.saveError")
        )
        assertTrue(
            "the viewer is told what happened",
            approved.contains("Trakt approved, but the token couldn't")
        )
        assertTrue(
            "a spent code is never polled again",
            approved.contains("return@launch")
        )
        assertTrue(
            "and the account is not loaded over an unsaved session",
            approved.indexOf("result.saveError") < approved.indexOf("loadAccountSlug")
        )
    }

    @Test
    fun `the loop records what its catch swallowed`() {
        val loop = viewModel.substringAfter("private fun pollUntilApproved(")
            .substringBefore("private fun fail(")

        assertTrue(
            "the exception class and message reach the diagnostics report",
            loop.contains("TraktPollTrace.recordPollError(error)")
        )
        val handler = loop.substringAfter(".getOrElse { error ->")
            .substringBefore("when (result.state)")
        assertTrue(
            "a retry with no evidence is the bug, not the fix: $handler",
            handler.indexOf("recordPollError(error)") in 0 until handler.indexOf(
                "consecutiveFailures++"
            )
        )
    }

    @Test
    fun `only a saved session is published to sync`() {
        val poll = repository.substringAfter("suspend fun pollOnce(")
            .substringBefore("private suspend fun fetchPoll(")

        assertTrue(
            "the publish is gated on the save having worked",
            poll.contains("result.saveError == null")
        )
        assertFalse(
            "no account read happens before the token is stored",
            poll.contains("loadAccountSlug")
        )
    }

    @Test
    fun `the store rebuilds itself once before giving up`() {
        assertTrue(
            "a store that will not open is discarded, not retried forever",
            store.contains("SecureTokenStore.discard(appContext, name)")
        )
        assertTrue("and the log names the fault", store.contains("would not open"))
    }

    @Test
    fun `both connect screens re-read the store on entry`() {
        listOf(
            "com/kennyb1201/kbstream/ui/trakt/TraktConnectScreen.kt",
            "com/kennyb1201/kbstream/ui/mobile/TraktConnectScreenMobile.kt"
        ).forEach { path ->
            val screen = source(path)
            assertTrue(
                "$path asks the store what the account is when it opens",
                screen.contains("vm.refreshConnectionState()")
            )
        }
    }

    private fun source(path: String): String {
        val root = findMainSourceRoot()
        val file = File(root, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findMainSourceRoot(): File {
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
}
