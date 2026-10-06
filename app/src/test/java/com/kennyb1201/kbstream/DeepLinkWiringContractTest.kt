package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The warm-deep-link wiring, asserted at the seam a unit test cannot reach.
 *
 * `android:launchMode="singleTop"` keeps the running MainActivity and delivers a
 * new intent to `onNewIntent`, whose platform default does nothing - and the
 * compose tree reads `activity.intent` once, at first composition. So every warm
 * deep link (Watch Next card, global-search suggestion, spoken query, reminder
 * tap, new-episode alert) depends on four connections that are each one line and
 * each silent when missing: the override exists, it calls `setIntent`, it stores
 * the intent where the tree can see it, and BOTH the warm effect and the cold
 * launch route through the same table. The routing itself is tested
 * behaviourally in LaunchIntentRoutingTest; this pins that it is reached.
 */
class DeepLinkWiringContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }
    private val mainRoot: File by lazy { sourceRoot.parentFile }

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

    @Test
    fun `the launcher activity is single top, which is what makes this necessary`() {
        val manifest = File(mainRoot, "AndroidManifest.xml")
        assertTrue("manifest missing: $manifest", manifest.isFile)
        assertTrue(
            "singleTop is the delivery mode these checks exist for",
            manifest.readText().contains("android:launchMode=\"singleTop\"")
        )
    }

    @Test
    fun `a new intent is kept and handed to the tree once`() {
        val main = source(MAIN)
        assertTrue(
            "the platform default for onNewIntent does nothing",
            main.contains("override fun onNewIntent(intent: Intent)")
        )
        assertTrue(
            "activity.intent must stay current for the re-create paths",
            main.contains("setIntent(intent)")
        )
        assertTrue(
            "the intent has to reach the compose tree",
            main.contains("incomingIntent = intent")
        )
        assertTrue(
            "a deep link is a handoff, so it is consumed rather than kept",
            main.contains("fun consumeIncomingIntent(): Intent?")
        )
        assertTrue(
            "and the tree must say when it has taken it",
            main.contains("onIncomingIntentConsumed()")
        )
    }

    @Test
    fun `both the cold launch and a new intent route through one table`() {
        val main = source(MAIN)
        assertTrue(
            "the routing table is the shared entry point",
            main.contains("fun applyLaunchIntentExtras(intent: Intent?): Boolean")
        )
        // Declaration + warm call + cold call: all three, or one path was left
        // reading `activity.intent` inline again.
        assertEquals(
            "both paths must call the shared routing",
            3,
            Regex("applyLaunchIntentExtras").findAll(main).count()
        )
        assertTrue(
            "the warm path is keyed on the incoming intent",
            main.contains("LaunchedEffect(incomingIntent)")
        )
        assertTrue(
            "the cold path still reads the activity's own intent",
            main.contains("applyLaunchIntentExtras((context as? android.app.Activity)?.intent)")
        )
        assertTrue(
            "the destination decision lives in the pure table",
            main.contains("resolveLaunchIntentRoute(")
        )
    }

    private companion object {
        const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
    }
}
