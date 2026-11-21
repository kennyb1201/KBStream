package com.kennyb1201.kbstream.data.bingecat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository's side of the rotation race: adopt, retry ONCE, then refuse.
 *
 * The rules themselves are pinned by [BingeCatRefreshRecoveryTest]; this pins
 * the shape of the call that uses them, because every failure mode here is a
 * cheap mistake with a real cost:
 *
 *  - refusing before the adopt-retry asks for a QR over a live session (the
 *    field report),
 *  - a retry loop turns one revoked grant into a storm,
 *  - an adopted pair that is saved but never published signs the siblings out
 *    at their next expiry,
 *  - and "failed" with no class in the paste is what made `no-token` the only
 *    thing a reconnect could ever be blamed on.
 */
class BingeCatReconnectRaceContractTest {

    private companion object {
        const val REPOSITORY =
            "com/kennyb1201/kbstream/data/bingecat/BingeCatRepository.kt"
        const val STORE =
            "com/kennyb1201/kbstream/data/bingecat/BingeCatTokenStore.kt"

        const val RECOVERY_CALL = "retryWithAdoptedAccountPair(refused = latest)"
        const val REFUSE = "refuseSession()"
        const val ADOPT = "adoptAccountPair()"
    }

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

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    @Test
    fun `the refusal branch adopts and retries once before refusing`() {
        val repository = source(REPOSITORY)

        val recovery = repository.indexOf(RECOVERY_CALL)
        assertTrue("F1: the adopt-retry must be called on a refusal", recovery > 0)

        // The refuseSession() that follows the recovery call is the fallback,
        // not the first move: nothing between them may refuse earlier.
        val afterRecovery = repository.substring(recovery)
        val refuseAfter = afterRecovery.indexOf(REFUSE)
        assertTrue(
            "F1: refuseSession must come AFTER the adopt-retry, or a live session " +
                "still asks for a QR code",
            refuseAfter > 0
        )
        assertTrue(
            "F1: exactly one retry - a retry loop turns a revoked grant into a storm",
            occurrences(repository, "service.refreshToken(") == 2
        )
    }

    @Test
    fun `an adopted pair is saved, published and reported`() {
        val repository = source(REPOSITORY)

        val helperStart = repository.indexOf("private suspend fun retryWithAdoptedAccountPair")
        assertTrue("the retry helper must exist", helperStart > 0)
        val helper = repository.substring(helperStart)

        val save = helper.indexOf("store.save(account)")
        val refresh = helper.indexOf("service.refreshToken(")
        assertTrue(
            "the adopted pair must be saved before it is spent, or the marker survives " +
                "a successful retry",
            save in 0 until refresh
        )
        assertTrue(
            "a retry rotates the pair again: the siblings need the newest one",
            helper.contains("publishAuthToSync()")
        )
        assertTrue(
            "F3: the paste must be able to say the session was recovered",
            helper.contains("BingeCatRefreshRecovery.RECOVERED")
        )
    }

    @Test
    fun `a flagged profile re-reads the account copy before refusing`() {
        val repository = source(REPOSITORY)

        val adoptCalls = occurrences(repository, ADOPT) -
            occurrences(repository, "fun adoptAccountPair()")
        assertEquals(
            "F2: both the pre-lock and in-lock early returns must look for a newer pair",
            2,
            adoptCalls
        )
        val firstAdopt = repository.indexOf(ADOPT)
        val firstRefuseAfterAdopt = repository.indexOf(REFUSE, firstAdopt)
        assertTrue(
            "F2: adoption is what decides the refusal, not a shortcut before it",
            firstRefuseAfterAdopt > firstAdopt
        )
        assertTrue(
            "F3: the refusal's verdict comes from the shared classifier",
            repository.contains("BingeCatRefreshRecovery.verdict(response.code(), oauthError(response))")
        )
    }

    @Test
    fun `the account copy and the marker are readable without changing a read`() {
        val store = source(STORE)

        assertTrue(
            "the recovery rules need the account's pair on its own, not through " +
                "read()'s account-first resolution",
            store.contains("internal fun readAccount(): BingeCatToken? = account.read()")
        )
        assertTrue(
            "and the pair the marker names, so it is never adopted again",
            store.contains("internal fun refusedAccessToken(): String?")
        )
    }
}
