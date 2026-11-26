package com.kennyb1201.kbstream.data.memory

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trim response is split by what the user is doing (dig four, 2026-11-16,
 * §3b and §3e rank 2).
 *
 * One level-only branch used to serve every delivery: foreground pressure during
 * a scroll and the process leaving the screen got the same graded trim. On API
 * 34+ only UI_HIDDEN (20) and BACKGROUND (40) arrive at all, so the "mid-scroll"
 * case cannot even fire there - the branch was, in practice, always treating a
 * backgrounding as if the viewer were still looking at the rail.
 *
 * The split:
 *
 *  - BACKGROUND (40) and above: the process has left the screen, nothing is on
 *    screen to blank, so the whole bitmap cache goes (the most headroom for the
 *    least visible cost; the return pays a disk re-decode).
 *  - RUNNING_CRITICAL (15) below it: a real foreground pressure event, only
 *    delivered on API 33 and older, so the graded LRU trim keeps the rail being
 *    scrolled.
 *
 * The browsing caches and the warm WebView are released in both cases: they are
 * cheap to rebuild and neither is on screen.
 */
class TwoStageTrimContractTest {

    private val app by lazy { source("com/kennyb1201/kbstream/MainApplication.kt") }

    private fun trimBlock(): String =
        app.substringAfter("override fun onTrimMemory(level: Int) {")
            .substringBefore("private fun trimLevelName")

    @Test
    fun `backgrounding clears and foreground pressure trims`() {
        val block = trimBlock()
        // 2026-11-26 (art-decode audit): the two levels are still mapped
        // separately, but the response now comes from ImageCacheTrim.releaseFor
        // - which is what makes the mid-walk deferral possible at all.
        val background = block.indexOf("backgrounded = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND")
        val critical = block.indexOf(
            "critical = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL"
        )
        assertTrue(
            "both levels are mapped onto the rule: $background/$critical",
            background in 0 until critical
        )
        assertTrue(
            "and the rule is the one that answers",
            block.contains("ImageCacheTrim.releaseFor(")
        )
        val clearArm = block.indexOf("ImageCacheRelease.CLEAR -> releaseImageMemoryCache(this)")
        val trimArm = block.indexOf("ImageCacheRelease.TRIM ->")
        val trim = block.indexOf("trimImageMemoryCache(this, ImageCacheTrim.SCROLL_FRACTION)")
        assertTrue(
            "the clear is the BACKGROUND arm's, and nothing else reaches it: $clearArm",
            clearArm > critical
        )
        assertTrue(
            "the trim is the CRITICAL arm's: $trimArm/$trim",
            trimArm in 0 until trim
        )
    }

    @Test
    fun `the scroll path takes the smaller bite`() {
        val block = trimBlock()
        assertTrue(
            "the mid-scroll trim asks for less than the default half (round four F3c)",
            block.contains("trimImageMemoryCache(this, ImageCacheTrim.SCROLL_FRACTION)")
        )
        assertTrue(
            "and the clear path is not fraction-gated: nothing is on screen",
            block.contains("releaseImageMemoryCache(this)")
        )
    }

    @Test
    fun `the shared releases still run for every level above critical`() {
        val block = trimBlock()
        assertTrue(
            "browsing caches: cheap to rebuild and never on screen",
            block.contains("MemoryPressure.releaseBrowsingCaches()")
        )
        assertTrue(
            "the warm WebView: a renderer, not a token",
            block.contains("PoTokens.releaseWarm()")
        )
        assertFalse(
            "and neither is behind the BACKGROUND-only branch, or a foreground " +
                "CRITICAL would stop releasing them",
            block.substringAfter("if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {")
                .substringBefore("if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {")
                .contains("releaseBrowsingCaches()")
        )
    }

    @Test
    fun `below critical nothing is released at all`() {
        assertTrue(
            "RUNNING_LOW and UI_HIDDEN-less browsing stay untouched: the code's own " +
                "threshold is the gate, so there is no lower branch to find",
            trimBlock().contains(
                "if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {\n" +
                    "                MemoryPressure.releaseBrowsingCaches()"
            )
        )
    }

    private fun source(relative: String): String {
        val file = File(mainRootPath(), relative)
        if (!file.isFile) throw AssertionError("source missing: ${file.absolutePath}")
        return file.readText()
    }

    private fun mainRootPath(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate.absolutePath
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
