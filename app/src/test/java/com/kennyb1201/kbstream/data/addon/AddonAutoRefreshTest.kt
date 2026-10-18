package com.kennyb1201.kbstream.data.addon

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The automatic manifest refresh, driven for real.
 *
 * Field report: "my addons never update automatically - if I refresh them
 * manually it says new catalogs, but it should be refreshing automatically
 * periodically." The background pass and the manual "REFRESH ALL" button fetch
 * the same manifest and apply it through the same merge, so there are only two
 * questions to answer, and both are asked here:
 *
 *  1. when the pass DOES run, does a genuinely new catalog reach the stored
 *     list? (the apply itself)
 *  2. does an automatic trigger still consider itself due after a pass that
 *     never reached the add-on? (the throttle, which is what silently ate the
 *     next six hours)
 *
 * It runs the pass against a real manifest served from a loopback socket, with
 * the installed addon written through the manager's own store, so nothing here
 * is a stub of the path under test - only the addon host is local.
 *
 * Robolectric gives the manager its real SharedPreferences; the add-on HTTP
 * client is plain OkHttp, which needs no Android runtime.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = AddonAutoRefreshTest.NoopApplication::class)
class AddonAutoRefreshTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private var host: ServerSocket? = null

    /** Manifest requests this test's addon host has answered. */
    private val requestsServed = AtomicInteger(0)

    @Before
    fun bindAppContext() {
        requestsServed.set(0)
        // The manager resolves its store through this holder, exactly as the
        // Application sets it at startup.
        AppContextHolder.appContext = context.applicationContext
        // The manager is a process-wide singleton, so the in-memory list may
        // still be the previous test's. Reload it from this test's store.
        AddonManager.getInstance(context).refreshAddons()
    }

    @After
    fun clearStore() {
        host?.close()
        host = null
        // No profiles are seeded, so the active store is the legacy one - the
        // same file a profile-less install uses.
        context.getSharedPreferences(LEGACY_STORE, Context.MODE_PRIVATE)
            .edit().clear().commit()
        AppContextHolder.appContext = null
    }

    @Test
    fun `the automatic pass applies a catalog the manifest has added`() {
        val catalogUrl = serveManifest(MANIFEST_WITH_NEW_CATALOG)

        val manager = AddonManager.getInstance(context)
        // Read first (this is what pins the manager to the active store), then
        // write the addon the way an install does.
        manager.getInstalledAddons()
        manager.saveInstalledAddons(listOf(installed(catalogUrl)))

        assertEquals(
            "the addon starts without the catalog the manifest is about to add",
            listOf("top"),
            manager.getInstalledAddons().single().catalogs.map { it.id }
        )

        val report = runBlocking { manager.refreshInstalledAddonsAwait() }

        assertEquals(
            "the pass must reach the addon host",
            AddonRefreshReport(fetched = 1, applied = 1, failed = 0),
            report
        )
        assertTrue(
            "the automatic pass must apply the new catalog, without anyone " +
                "pressing REFRESH ALL: stored=" +
                manager.getInstalledAddons().single().catalogs.map { it.id },
            manager.getInstalledAddons().single().catalogs.any { it.id == "new" }
        )
    }

    /**
     * The throttle, which is the half of the report that no apply-side test
     * covers: a pass that ran becomes not-due for
     * [AddonManager]'s interval, and a forced pass still applies afterwards.
     */
    @Test
    fun `a due trigger refreshes, and the same pass then suppresses the next one`() {
        val url = serveManifest(MANIFEST_WITHOUT_NEW_CATALOG, MANIFEST_WITH_NEW_CATALOG)

        val manager = AddonManager.getInstance(context)
        manager.getInstalledAddons()
        manager.saveInstalledAddons(listOf(installed(url)))

        val first = runBlocking { manager.refreshInstalledAddonsIfDue(minIntervalMs = 0L) }
        assertNotNull("an unthrottled trigger runs the pass", first)
        assertEquals("and it reached the host", 1, first?.fetched)

        // The addon host now serves a manifest WITH the new catalog. A trigger
        // inside the interval must not go and look at it.
        val second = runBlocking { manager.refreshInstalledAddonsIfDue() }
        assertNull("a pass that just ran is not due again", second)
        assertEquals(
            "and the throttled trigger must not have touched the stored catalogs",
            listOf("top"),
            manager.getInstalledAddons().single().catalogs.map { it.id }
        )

        val forced = runBlocking { manager.refreshInstalledAddonsIfDue(minIntervalMs = 0L) }
        assertEquals("a forced pass does apply it", 1, forced?.applied)
        assertTrue(
            "so the stored list tracks the manifest again",
            manager.getInstalledAddons().single().catalogs.any { it.id == "new" }
        )
    }

    /**
     * The exact shape of the field report: a launch that could not reach its
     * add-ons used to stamp the throttle BEFORE the attempt, so the next six
     * hours of automatic refreshes were silently skipped for an addon set that
     * was never actually refreshed.
     */
    @Test
    fun `a pass that reached no addon leaves the trigger due`() {
        // A port with nothing listening on it: the fetch fails the way an
        // offline TV box fails.
        val deadPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val manager = AddonManager.getInstance(context)
        manager.getInstalledAddons()
        manager.saveInstalledAddons(listOf(installed("http://127.0.0.1:$deadPort/manifest.json")))

        val failed = runBlocking { manager.refreshInstalledAddonsIfDue() }
        assertEquals("the addon could not be reached", 0, failed?.fetched)
        assertEquals("and the failure is counted", 1, failed?.failed)
        assertEquals("nothing was applied", 0, failed?.applied)

        assertNotNull(
            "an unreachable pass must not consume the refresh window",
            runBlocking { manager.refreshInstalledAddonsIfDue() }
        )
    }

    /**
     * A cold launch fires BOTH automatic triggers at once (the Application's
     * startup hook and the Activity's foreground hook). The due check and the
     * pass it authorizes are one critical section, so the second one finds the
     * window the first just consumed instead of starting a second pass over
     * every installed manifest.
     */
    @Test
    fun `two triggers arriving together fetch each manifest once`() {
        val url = serveManifest(MANIFEST_WITHOUT_NEW_CATALOG, MANIFEST_WITHOUT_NEW_CATALOG)

        val manager = AddonManager.getInstance(context)
        manager.getInstalledAddons()
        manager.saveInstalledAddons(listOf(installed(url)))

        val reports = runBlocking {
            val first = async(Dispatchers.IO) { manager.refreshInstalledAddonsIfDue() }
            val second = async(Dispatchers.IO) { manager.refreshInstalledAddonsIfDue() }
            listOf(first.await(), second.await())
        }

        assertEquals(
            "exactly one trigger may run the pass",
            1,
            reports.count { it != null }
        )
        assertEquals(
            "and the manifest is fetched once, not once per trigger",
            1,
            requestsServed.get()
        )
    }

    // ── fixture ─────────────────────────────────────────────────────────

    private fun installed(manifestUrl: String) = InstalledAddon(
        manifestUrl = manifestUrl,
        id = ADDON_ID,
        name = "Test Addon",
        resources = listOf("catalog"),
        catalogs = listOf(
            ManifestCatalog(type = "movie", id = "top", name = "Top", order = 0)
        ),
        version = "1.0.0",
        description = "A test addon",
        types = listOf("movie")
    )

    /**
     * Serves [json] at `/manifest.json` on a loopback port until the test ends.
     *
     * A hand-rolled responder rather than a mock-web-server dependency: this is
     * one GET, and a socket plus a fixed HTTP response is less machinery than
     * anything else that could answer it. Each request advances to the next
     * body, which is how a test can change what the addon host serves between
     * two passes.
     */
    private fun serveManifest(vararg json: String): String {
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        host = socket
        val bodies = json.map { it.toByteArray() }
        thread(isDaemon = true, name = "addon-manifest-server") {
            var served = 0
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: return@thread
                val body = bodies[served.coerceAtMost(bodies.lastIndex)]
                served++
                requestsServed.incrementAndGet()
                client.use { connection ->
                    val reader = connection.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val head = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    connection.getOutputStream().apply {
                        write(head.toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
        return "http://127.0.0.1:${socket.localPort}/manifest.json"
    }

    private companion object {
        const val ADDON_ID = "org.kbstream.test"
        const val LEGACY_STORE = "kbstream_addons"

        private fun manifest(vararg catalogs: String): String {
            val catalogLines = catalogs.joinToString(",\n    ")
            return """
                {
                  "id": "$ADDON_ID",
                  "name": "Test Addon",
                  "version": "1.0.0",
                  "description": "A test addon",
                  "resources": ["catalog"],
                  "types": ["movie"],
                  "catalogs": [
                    $catalogLines
                  ]
                }
            """.trimIndent()
        }

        private const val TOP_CATALOG = """{"type": "movie", "id": "top", "name": "Top"}"""

        /** The stored addon's manifest, exactly as stored: no new catalog. */
        val MANIFEST_WITHOUT_NEW_CATALOG: String = manifest(TOP_CATALOG)

        /** The same manifest after its author added a catalog. */
        val MANIFEST_WITH_NEW_CATALOG: String = manifest(
            TOP_CATALOG,
            """{"type": "movie", "id": "new", "name": "Just Added"}"""
        )
    }
}
