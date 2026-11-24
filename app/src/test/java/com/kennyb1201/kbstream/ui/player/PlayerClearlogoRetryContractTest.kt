package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clearlogo round-3 wiring (spec 2026-11-24): the issue is traced, the dedup
 * keys on LANDING, a throwing issue cannot poison it, and the source switch
 * repaints BOTH title slots.
 *
 * [LogoRetryPolicyTest] pins the retry matrix. This pins the call sites the field
 * report needs, which are view code no JVM test can drive: the load call itself,
 * the landed bookkeeping, and the two repaints that carry the second splash and
 * the overlay.
 */
class PlayerClearlogoRetryContractTest {

    private val chrome: String by lazy { source(CHROME) }
    private val native: String by lazy { source(NATIVE) }

    @Test
    fun `the chrome traces the issue, and dedups on landing`() {
        val issue = method(chrome, "private fun issue(")

        assertTrue(
            "the load call itself is the line the trace was missing - written by the " +
                "helper at the instant the call runs (F1), never by the caller above it",
            issue.contains("loadLogo(") &&
                issue.contains("surface = \"chrome\"") &&
                !issue.contains("PlaybackSessionTrace.note(\"LOGO: issued chrome\")")
        )
        assertTrue(
            "and it records the request so a retry can be timed and capped",
            issue.contains("requestedAtMs = SystemClock.elapsedRealtime()") &&
                issue.contains("logoAttempts++")
        )
        assertFalse(
            "the swallow is gone: a throw is named instead of vanishing",
            issue.contains("runCatching") && !issue.contains("LOGO: issue-threw chrome")
        )
        assertTrue(
            "a throw clears the request, so it cannot poison the dedup for the session",
            issue.contains("LOGO: issue-threw chrome") &&
                issue.contains("requestedLogoUrl = null")
        )

        val show = method(chrome, "internal fun showTitleLogo(")
        assertTrue(
            "landing is what a repaint skips on",
            show.contains("if (url != loadedLogoUrl) {") &&
                !show.contains("url != loadedLogoUrl && url != requestedLogoUrl")
        )
        assertTrue(
            "and a requested-but-unlanded URL goes through the retry policy",
            show.contains("LogoRetryPolicy.shouldRetry(") &&
                show.contains("nowMs = SystemClock.elapsedRealtime()")
        )
        assertTrue(
            "with a line when the retries give up",
            show.contains("LOGO: retry-exhausted chrome")
        )
    }

    @Test
    fun `the splash traces its issue and retries the same way`() {
        val load = method(native, "private fun loadSplashLogo(")
        val issue = method(native, "private fun issueSplashLogo(")

        assertTrue(
            "the splash load is issued through one traced call",
            issue.contains("loadLogo(") &&
                issue.contains("surface = \"splash\"") &&
                !issue.contains("PlaybackSessionTrace.note(\"LOGO: issued splash\")")
        )
        assertTrue(
            "a landed splash is the only thing that skips a load",
            load.contains("if (url == splashLogoLoadedUrl) return")
        )
        assertTrue(
            "and an unlanded one is re-issued by the policy",
            load.contains("LogoRetryPolicy.shouldRetry(") &&
                load.contains("splashLogoRetryExhaustedNoted") &&
                load.contains("LOGO: retry-exhausted splash")
        )
        assertTrue(
            "a failure clears the request rather than reading as landed",
            issue.contains("onFailure = { splashLogoRequestedUrl = null }")
        )
        assertFalse(
            "no runCatching here either: a throw stays visible",
            issue.contains("runCatching")
        )
    }

    @Test
    fun `a source switch repaints both slots and the first frame repaints the chrome`() {
        val switch = method(native, "fun switchToSource(")
        assertTrue(
            "the switch names itself, so an unlanded logo is re-fetched there",
            switch.contains("chrome?.refreshTitle(LogoRepaint.SWITCH)")
        )
        assertTrue(
            "and the splash is repainted from the same facts - setup is its only other caller",
            switch.contains("loadSplashLogo(it, LogoRepaint.SWITCH)") &&
                switch.contains("resolvedHeaderLogoUrl()")
        )

        val firstFrame = method(native, "private fun markFirstFrameRendered() {")
        assertTrue(
            "the first-frame repaint says which repaint it is",
            firstFrame.contains("chrome?.refreshTitle(LogoRepaint.FIRST_FRAME)")
        )
    }

    @Test
    fun `the first-frame sample is measured from the load's own READY`() {
        val firstFrame = method(native, "private fun markFirstFrameRendered() {")
        assertTrue(
            "F5: a source switch resets firstReadyAtMs, so the sample cannot key on it",
            firstFrame.contains("val loadReadyAtMs = currentLoadReadyAtMs") &&
                firstFrame.contains("firstFrameRenderedAtMs - loadReadyAtMs")
        )
        val ready = native.slice(
            "if (firstReadyAtMs == 0L) {",
            "if (rebufferStartedAtMs != 0L) {"
        )
        assertTrue(
            "the field is set by the READY that anchors the load",
            ready.contains("currentLoadReadyAtMs = firstReadyAtMs")
        )
        val createPlayer = method(native, "private fun createPlayer() {")
        assertFalse(
            "and neither the rebuild nor the switch clears it for the load that painted the frame",
            createPlayer.contains("currentLoadReadyAtMs")
        )
    }

    /** The text of the member that starts at [signature], up to its closing brace. */
    private fun method(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = rest.indexOf("\n    }")
        return if (end < 0) rest else rest.substring(0, end)
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
    }

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
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

    private companion object {
        const val CHROME = "com/kennyb1201/kbstream/ui/player/PlayerChrome.kt"
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
