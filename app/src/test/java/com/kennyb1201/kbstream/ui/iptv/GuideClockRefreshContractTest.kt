package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide's two-minute clock must not re-query what it already has.
 *
 * The tick used to re-issue the whole loaded channel set as a fresh lineup
 * query - up to 80 channels x 960 programs, every two minutes, for as long as
 * the guide was open - to recompute a NOW/NEXT that the loaded programs could
 * already answer. [GuideClockAdvance] owns the boundary cases (see
 * `GuideRulesTest`); this pins the wiring that has to keep using it, because the
 * behaviour it replaced was one line and `GuideClockRefreshContractTest` is the
 * only thing that would notice it coming back (there is no Compose harness here
 * to render the guide's flow).
 */
class GuideClockRefreshContractTest {

    @Test
    fun `the clock tick advances the loaded rows instead of re-issuing them`() {
        val body = functionBody("private fun bumpGuideClock(")
        assertTrue(
            "the tick must hand the loaded rows to the in-memory advance",
            body.contains("GuideClockAdvance.step(loaded, now)")
        )
        // The regression, exactly: `_pendingGuideChannelIds.value = queued` with
        // queued = the whole loaded set. bumpGuideClock must never name that set
        // at all - the batch it re-queries is the handful of expired channels.
        assertFalse(
            "the whole loaded channel set must not be re-issued",
            body.contains("_guideChannelIds")
        )
        assertTrue(
            "expired channels join the outstanding queue rather than replacing it",
            body.contains("GuideRequestQueue.enqueue(_pendingGuideChannelIds.value, toRequery)")
        )
        assertTrue(
            "the tick must stay in the request, or distinctUntilChanged swallows the refresh",
            body.contains("_guideClockTick.value += 1")
        )
    }

    @Test
    fun `a channel written off as run out is not asked again every tick`() {
        val body = functionBody("private fun bumpGuideClock(")
        assertTrue(
            "the re-query batch is the expired rows MINUS the ones already written off",
            body.contains("val toRequery = step.expiredIds - exhaustedGuideChannelIds")
        )
        assertTrue(
            "and the rows that were asked for are recorded as written off",
            body.contains("exhaustedGuideChannelIds = exhaustedGuideChannelIds + toRequery")
        )
        assertTrue(
            "a row that can answer the clock again must leave the written-off set",
            body.contains("GuideClockAdvance.canAnswer(it, now)")
        )
    }

    @Test
    fun `a new source or import clears what was written off`() {
        // Otherwise a provider whose listing ended at midnight would keep every
        // channel suppressed long after the guide that covers them landed.
        val body = functionBody("private fun clearGuideMemory(")
        assertTrue(
            "clearGuideMemory must reset the written-off set",
            body.contains("exhaustedGuideChannelIds = emptySet()")
        )
    }

    private fun readSource(): String {
        val file = File(findSourceRoot(), IPTV_VM)
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

    /** The body of the function starting at [signature], up to its closing brace. */
    private fun functionBody(signature: String): String {
        val src = readSource()
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private companion object {
        const val IPTV_VM = "com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt"
    }
}
