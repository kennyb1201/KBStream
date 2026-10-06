package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sign-out stops realtime SYNCHRONOUSLY (SD-7).
 *
 * The realtime channels are subscribed with the account being left. `signOut`
 * used to stop them from inside the coroutine that runs `c.auth.signOut()`,
 * which meant they stayed live for the whole network round-trip: a
 * `postgres_changes` frame arriving in that window was still applied to local
 * state by [SupabaseSync.applyHistoryRow] / applyWatchedRow, on a device the
 * user had already signed out of. Every other part of the sign-out (the session
 * store, the auth state, the sync flag) is deliberately synchronous, and the
 * subscription belongs with them.
 */
class SignOutRealtimeOrderingContractTest {

    private val source: String by lazy {
        val file = File(findSourceRoot(), SYNC)
        assertTrue("source missing: $file", file.isFile)
        file.readText()
    }

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

    private fun signOutBody(): String {
        val start = source.indexOf("fun signOut(context: Context) {")
        assertTrue("signOut not found", start >= 0)
        val end = source.indexOf("\n    }\n", start)
        assertTrue("signOut body end not found", end > start)
        return source.substring(start, end)
    }

    @Test
    fun `the realtime stop happens before the awaited network sign-out`() {
        val body = signOutBody()
        val stopAt = body.indexOf("stopRealtime()")
        // The code, not the prose: stopRealtime()'s own comment names
        // `c.auth.signOut()` too, and matching that would compare against a
        // line ABOVE the call.
        val networkAt = body.indexOf("runCatching { c.auth.signOut() }")
        assertTrue("sign-out must stop realtime at all", stopAt >= 0)
        assertTrue("sign-out must still call the network sign-out", networkAt >= 0)
        assertTrue(
            "stopRealtime() must run before the network round-trip, not after it",
            stopAt < networkAt
        )
    }

    @Test
    fun `the realtime stop is not deferred into the coroutine`() {
        val body = signOutBody()
        val launchAt = body.indexOf("scope.launch {")
        assertTrue("the network sign-out must still be launched", launchAt >= 0)
        assertTrue(
            "deferring stopRealtime() into the coroutine keeps the channels " +
                "live for the whole round-trip - the bug SD-7 fixes",
            !body.substring(launchAt).contains("stopRealtime()")
        )
    }

    private companion object {
        const val SYNC = "com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
    }
}
