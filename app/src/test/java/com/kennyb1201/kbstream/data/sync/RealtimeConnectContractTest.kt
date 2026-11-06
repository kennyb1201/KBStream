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
 *
 * That ordering narrows the window but cannot close it: the SDK's own
 * `setAuth()` fan-out suspends across the token refresh and reaches
 * `getWebsocket()` again on resume, and by then the SDK's heartbeat/reconnect
 * logic may have closed the socket. 3.8.0 still died there (Sentry ANDROID-Y,
 * 0.9.136, a fatal in NativePlayerActivity on an onn. 4K Plus). supabase-kt
 * 3.9.0 fixes it in the library - nullable websocket checked at every use,
 * plus a `CoroutineExceptionHandler` on the scope an escape used to kill the
 * process - so the catalog version is a floor, pinned below.
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

    // ── Every join, and the stop, goes through the one mutex ────────────────

    @Test
    fun `the health watcher's rebuild joins under the same mutex as startRealtime`() {
        val watcher = between("private fun watchRealtimeHealth()", "private fun onRemoteChange(")
        val rebuildAt = watcher.indexOf("subscribeTable(c, sub.table)")
        assertTrue("the watcher still rebuilds dead channels", rebuildAt >= 0)
        val lockAt = watcher.lastIndexOf("realtimeMutex.withLock", rebuildAt)
        assertTrue(
            "a rebuild that joins outside the mutex can race a foreground force " +
                "re-join: two channels and two collectors for one table, so every " +
                "remote change is applied twice",
            lockAt >= 0
        )
        assertTrue(
            "and it re-checks that its snapshot entry is still tracked under that " +
                "lock, or a forced re-join that already replaced it gets undone",
            watcher.contains("val stillTracked =") &&
                watcher.contains("realtimeSubscriptions.any { it === sub }")
        )
    }

    @Test
    fun `a stop leaves a waiting join with nothing to subscribe`() {
        val stop = between("private fun stopRealtime() {", "Process-foreground hook")
        assertTrue(
            "the stop bumps the epoch synchronously, before it clears",
            stop.contains("realtimeStopEpoch++") &&
                stop.indexOf("realtimeStopEpoch++") <
                stop.indexOf("realtimeSubscriptions.clear()")
        )
        val start = between("private fun startRealtime(force: Boolean = false)", "private suspend fun subscribeTable")
        assertTrue(
            "and a join that queued before it abandons instead of tracking channels " +
                "for the session being discarded (which left the tracked set " +
                "non-empty while the status read \"stopped\", and the next " +
                "non-force join then bailed as already subscribed)",
            start.contains("val epoch = realtimeStopEpoch") &&
                start.contains("if (epoch != realtimeStopEpoch)") &&
                start.contains("join abandoned, the session stopped while it queued")
        )
    }

    private fun between(start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing start: $start", from >= 0)
        val to = source.indexOf(end, from)
        assertTrue("missing end: $end after $start", to > from)
        return source.substring(from, to)
    }

    // ── The library floor ──────────────────────────────────────────────────

    @Test
    fun `supabase-kt is at least 3-9-0`() {
        val pinned = supabaseVersion()

        assertTrue(
            "supabase-kt is $pinned in gradle/libs.versions.toml. Below 3.9.0 a " +
                "token refresh racing the SDK's own disconnect() re-enters " +
                "RealtimeImpl.getWebsocket() after the socket is gone, and that " +
                "IllegalStateException is thrown on a supervisor scope with no " +
                "handler - it is uncaught and kills the process (Sentry " +
                "ANDROID-Y on 0.9.136). App-side ordering cannot catch it; 3.9.0 " +
                "checks the null websocket and installs the exception handler. " +
                "Lower this floor only after re-reading the crash it prevents.",
            pinned.size >= 3 &&
                (pinned[0] > 3 || (pinned[0] == 3 && pinned[1] >= 9))
        )
    }

    /** `supabase = "x.y.z"` from the version catalog, as numbers. */
    private fun supabaseVersion(): List<Int> {
        val catalog = File(findRepoRoot(), CATALOG)
        assertTrue("version catalog missing: $catalog", catalog.isFile)
        val pinned = Regex("""(?m)^supabase\s*=\s*"([0-9]+)\.([0-9]+)(?:\.([0-9]+))?"""")
            .find(catalog.readText())
            ?: throw AssertionError("no `supabase = \"x.y.z\"` entry in $catalog")
        return pinned.groupValues.drop(1).filter { it.isNotEmpty() }.map { it.toInt() }
    }

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, CATALOG).isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError(
            "version catalog not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val SYNC = "com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
        const val CATALOG = "gradle/libs.versions.toml"
    }
}
