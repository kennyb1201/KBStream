package com.kennyb1201.kbstream.work

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the automatic add-on manifest refresh is TRIGGERED, and who waits for
 * it. Both halves are source contracts, like the other `*ContractTest`s here:
 * the bug they pin is a wiring bug, and a wiring bug is visible in the source.
 *
 * The report was "my addons never update automatically, but Refresh addons
 * finds the new catalogs". Two shapes produced exactly that:
 *
 *  - the periodic worker launched the pass and returned immediately, so
 *    WorkManager was free to stop the process before a single manifest had been
 *    fetched - the pass ran nowhere, while `doWork` still reported success;
 *  - and the only launch-side trigger was Application.onCreate, which on a TV
 *    box never fires again once the process is warm.
 */
class AddonRefreshTriggerContractTest {

    private fun flat(src: String): String = src.replace(Regex("\\s+"), " ")

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
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

    private val worker: String by lazy { readSource(ADDON_WORKER) }
    private val application: String by lazy { readSource(MAIN_APPLICATION) }
    private val activity: String by lazy { readSource(MAIN_ACTIVITY) }
    private val manager: String by lazy { readSource(ADDON_MANAGER) }

    @Test
    fun `the periodic worker awaits the pass it started`() {
        // The old shape: call the manager, return success. The process can be
        // stopped the moment doWork returns, so the manifests were never
        // fetched - while every run reported success.
        assertTrue(
            "the worker must await the manager's pass",
            flat(worker).contains(
                "val report = AddonManager.getInstance(applicationContext)" +
                    ".refreshInstalledAddonsIfDue()"
            )
        )
        assertFalse(
            "and never go back to a fire-and-forget call plus Result.success()",
            flat(worker).contains("refreshInstalledAddonsSoon()")
        )
        assertTrue(
            "a pass that reached no addon at all must be retried, not reported done",
            flat(worker).contains("report.fetched == 0 && report.failed > 0") &&
                flat(worker).contains("Result.retry()")
        )
    }

    @Test
    fun `the launch trigger and the foreground trigger both fire`() {
        // Process creation (Application.onCreate) - the cold start, and the
        // first launch after the app was killed.
        assertTrue(
            "the application must kick off a throttled pass on startup",
            flat(application).contains("AddonManager .getInstance(this@MainApplication) .refreshInstalledAddonsSoon()")
        )
        // Foreground transition (Activity.onStart) - the one that actually
        // happens on a box the launcher sits in front of for weeks.
        assertTrue(
            "returning to the app must kick off the same throttled pass",
            flat(activity).contains("override fun onStart()") &&
                flat(activity).contains(
                    "AddonManager .getInstance(applicationContext) .refreshInstalledAddonsSoon()"
                )
        )
    }

    @Test
    fun `one interval governs every automatic trigger`() {
        // The triggers must share a cadence and a stamp; two independent
        // throttles fetch every manifest twice (or, worse, a launch that never
        // reached a host silences the worker for the same window).
        assertTrue(
            "the launch/foreground hook goes through the shared due check",
            flat(manager).contains("fun refreshInstalledAddonsSoon() {") &&
                flat(manager).contains("runCatchingCancellable { refreshInstalledAddonsIfDue() }")
        )
        assertFalse(
            "and no separate launch-only interval is left behind",
            manager.contains("LAUNCH_REFRESH_MIN_INTERVAL_MS")
        )
    }

    private companion object {
        const val ADDON_WORKER = "com/kennyb1201/kbstream/work/AddonManifestRefreshWorker.kt"
        const val ADDON_MANAGER = "com/kennyb1201/kbstream/data/addon/AddonManager.kt"
        const val MAIN_APPLICATION = "com/kennyb1201/kbstream/MainApplication.kt"
        const val MAIN_ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
    }
}
