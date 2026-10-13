package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Realtime plugin is CONNECTED before anything can need its websocket.
 *
 * Installing the plugin is not starting it. `subscribeTable()` registers three
 * channels, and supabase-kt keeps their authorization in step with the session
 * by fanning `setAuth()` out to every live channel whenever auth changes -
 * session restore, sign-in, and the periodic token refresh all do it. On a
 * client whose websocket was never opened, that call reaches `getWebsocket()`,
 * which throws `IllegalStateException("Websocket not yet initialized")` on the
 * SDK's own dispatcher: it is not raised inside any coroutine this app owns, so
 * no `runCatching` here can catch it, and the process dies. That was the fatal
 * crash reported from the 0.6.57 build.
 *
 * Hence the pin: `startClient` must open the socket, and it must do so before
 * session restore can raise the first auth event and before `startRealtime()`
 * registers a channel.
 */
class RealtimeConnectContractTest {

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

    private fun startClientBody(): String {
        val start = source.indexOf("fun startClient(context: Context) {")
        assertTrue("startClient not found", start >= 0)
        val end = source.indexOf("\n    }\n", start)
        assertTrue("startClient body end not found", end > start)
        return source.substring(start, end)
    }

    @Test
    fun `startClient connects realtime`() {
        val body = startClientBody()
        assertTrue(
            "installing Realtime and never connecting it leaves the websocket " +
                "null, and the next auth event kills the process: $body",
            body.contains("realtime?.connect()") || body.contains("realtime.connect()")
        )
    }

    @Test
    fun `the connect happens before session restore and any channel`() {
        val body = startClientBody()
        val installAt = body.indexOf("install(Realtime)")
        val connectAt = body.indexOf(".connect()")
        val restoreAt = body.indexOf("restoreSession(context)")

        assertTrue("Realtime must still be installed", installAt >= 0)
        assertTrue("realtime.connect() missing", connectAt >= 0)
        assertTrue("session restore must still run from startClient", restoreAt >= 0)
        assertTrue(
            "the plugin has to be installed before it is connected",
            installAt < connectAt
        )
        assertTrue(
            "session restore raises the first auth event, and subscribeTable() " +
                "registers the channels, so connect() must come first",
            connectAt < restoreAt
        )
    }

    private companion object {
        const val SYNC = "com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
    }
}
