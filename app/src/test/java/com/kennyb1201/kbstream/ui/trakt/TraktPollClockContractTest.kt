package com.kennyb1201.kbstream.ui.trakt

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A code that expired in 1.5 seconds was a clock jump, not an expiry.
 *
 * The poll loop measures how long the code has lived, and it used to do that
 * with `System.currentTimeMillis()`. Wall-clock time can jump forward (NTP
 * sync, a viewer changing the time) in the middle of the flow, which made the
 * FIRST poll read as 600s+ old and printed "That code expired" while Trakt's
 * own site said the approval succeeded. The interval has to come from a
 * monotonic source; `TraktDeviceFlowRules` and its tests are unchanged because
 * the rule was never wrong.
 *
 * The debug diagnostic rides along so one failed reproduction can say whether
 * Trakt returned 404/409/410 or the local math is still wrong - without ever
 * logging the device code, the user code or a token.
 */
class TraktPollClockContractTest {

    private val viewModel = source("com/kennyb1201/kbstream/ui/trakt/TraktViewModel.kt")
    private val repository = source("com/kennyb1201/kbstream/data/trakt/TraktRepository.kt")

    @Test
    fun `the poll interval is measured on the monotonic clock`() {
        assertTrue(
            "the flow needs the monotonic clock imported at the call site",
            viewModel.contains("import android.os.SystemClock")
        )
        val issuedAt = viewModel.substringAfter("val issuedAtMs =")
            .substringBefore("\n")
        assertTrue(
            "the code's start time is stamped from elapsedRealtime: $issuedAt",
            issuedAt.contains("SystemClock.elapsedRealtime()")
        )

        val poll = viewModel.substringAfter("private fun pollUntilApproved(")
            .substringBefore("private fun fail(")
        assertTrue(
            "and the elapsed math uses the same source",
            poll.contains("(SystemClock.elapsedRealtime() - issuedAtMs) / 1_000L")
        )
        assertFalse(
            "a wall-clock jump must not be able to age the code out on the first poll",
            poll.contains("System.currentTimeMillis()")
        )
    }

    @Test
    fun `the poll logs what Trakt returned in debug builds only`() {
        val poll = repository.substringAfter("suspend fun pollOnce(")
            .substringBefore("suspend fun refreshIfDue(")
        assertTrue("the diagnostic exists", poll.contains("if (BuildConfig.DEBUG) {"))
        listOf(
            "status=",
            "elapsed=",
            "expiresIn=",
            "state="
        ).forEach { field ->
            assertTrue("the log has to carry $field: $poll", poll.contains(field))
        }
        listOf("deviceCode", "userCode", "accessToken", "token").forEach { secret ->
            assertFalse(
                "the poll log must never print $secret",
                poll.substringAfter("Log.d(").substringBefore(")").contains(secret)
            )
        }
    }

    @Test
    fun `the expiry rule itself is untouched`() {
        val rules = source("com/kennyb1201/kbstream/data/trakt/TraktDeviceFlowRules.kt")
        assertTrue(
            "the rule still decides expiry from elapsed vs lifetime; only the " +
                "caller's clock changed",
            rules.contains("fun isExpired(")
        )
    }

    private fun source(path: String): String {
        val file = File(findMainSourceRoot(), path)
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
