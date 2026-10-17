package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AFR engages by display mode id first, and the surface hint only as a backstop.
 *
 * Field, 2026-10-17 08:47 EDT, Onn 4K Plus: `Surface.setFrameRate` was logged
 * for 23.976 and the panel had not moved 1.5 s later, when the confirm fell
 * through to `preferredDisplayModeId`, which did move it - mode 137 to 142 at
 * 23.976 Hz. The dropped-frame burst and the `no present fence` warnings that
 * followed suggest the ignored surface request left the pipeline in a state the
 * later mode switch inherited, so the fix is to stop making it when the mode
 * request can do the job.
 *
 * There is no TV in CI and this class is Android-only, so the order is read out
 * of the source, the way this tree's other player contracts are
 * (`FrameRateProbeWiringContractTest`, `FrameRateResumeContractTest`).
 */
class FrameRateEngageOrderContractTest {

    private val match = squash(source(MATCH))
    private val apply = window(match, "private fun apply(", "private fun askSurfaceIfPossible(")
    private val modeFirst = window(apply, "if (ENGAGE_MODE_FIRST) {", "// Pre-fix ladder")
    private val requestMode = window(match, "private fun requestMode(", "private fun forceMode(")
    private val confirm = window(match, "private fun confirm(", "private fun backstopAfter(")
    private val backstop = window(match, "private fun backstopAfter(", "private fun clearSurfaceRate(")

    @Test
    fun `the new order is the default`() {
        assertTrue(
            "the engage order must default to the mode request: a bisect switch that shipped " +
                "off would leave the field box on the ladder that stutters",
            match.contains("const val ENGAGE_MODE_FIRST = true")
        )
    }

    @Test
    fun `apply requests the mode id before it touches the surface`() {
        val choose = modeFirst.indexOf("FrameRateMatch.chooseMode(contentFps, modes, modeId)")
        val request = modeFirst.indexOf("requestMode(chosen, contentFps, modeId)")
        val surface = modeFirst.indexOf("askSurfaceIfPossible(contentFps, modeId)")
        assertTrue("the mode-first block must search the panel's modes", choose >= 0)
        assertTrue(
            "the mode request is what starts the engage on a panel with a better mode",
            request > choose
        )
        assertTrue(
            "and the surface hint may only be reached after it - or instead of it",
            surface > request
        )
        assertFalse(
            "the mode-first block must not call the mode search a second time as a fallback: " +
                "there is nothing after the surface hint",
            modeFirst.contains("forceMode(")
        )
    }

    @Test
    fun `the mode request is the one the field can verify`() {
        assertTrue(
            "a mode request is a preferredDisplayModeId write, not a surface rate",
            requestMode.contains("attributes.preferredDisplayModeId = chosen.id")
        )
        assertTrue(
            "the restore on teardown depends on knowing which mode this session asked for",
            requestMode.contains("appliedModeId = chosen.id")
        )
        assertTrue(
            "the field log line the acceptance check reads for ('forced mode ... as the FIRST " +
                "engage line') is emitted here",
            requestMode.contains("forced mode \${chosen.id} at \${chosen.refreshRate} Hz for ")
        )
        assertTrue(
            "and it is confirmed like every other request, so a refused mode falls through",
            requestMode.contains("confirm(serial, currentModeId, contentFps, EngageStep.MODE)")
        )
    }

    @Test
    fun `a confirm falls through to the other step, and the last step stops`() {
        assertTrue(
            "a mode request that did not move the panel gets the surface hint as its backstop",
            backstop.contains("ENGAGE_MODE_FIRST && step == EngageStep.MODE -> EngageStep.SURFACE")
        )
        assertTrue(
            "and in the pre-fix order the ladder is the other way round, for the bisect",
            backstop.contains(
                "!ENGAGE_MODE_FIRST && step == EngageStep.SURFACE -> EngageStep.MODE"
            )
        )
        assertTrue(
            "the backstop is resolved from the step that was just confirmed",
            confirm.contains("when (backstopAfter(step)) {")
        )
        assertTrue(
            "a refused surface hint in the new order ends the engage: there is nothing left to try",
            confirm.contains("EngageStep.SURFACE -> askSurfaceIfPossible(contentFps, afterModeId)")
        )
        assertTrue(
            "and a step with no successor must do nothing rather than fall through to a mode",
            confirm.contains("null -> Unit")
        )
    }

    @Test
    fun `a panel with no better mode still gets the surface hint`() {
        assertTrue(
            "a single-60 Hz TV is the regression case: chooseMode finds nothing, and the " +
                "surface hint must still be made",
            modeFirst.contains("askSurfaceIfPossible(contentFps, modeId)") &&
                modeFirst.contains("if (chosen != null)")
        )
        assertTrue(
            "the mode search stays a pure rule the JVM tests drive",
            match.contains("fun chooseMode(")
        )
    }

    private fun source(path: String): String {
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

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /** The squashed source between [from] and the next [to], both asserted to exist. */
    private fun window(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("anchor missing: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("anchor missing after '$from': $to", end > start)
        return text.substring(start, end)
    }

    private companion object {
        const val MATCH = "com/kennyb1201/kbstream/ui/player/FrameRateMatch.kt"
    }
}
