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
    private val confirm = window(match, "private fun confirm(", "private fun settleMode(")

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
        val best = modeFirst.indexOf("FrameRateMatch.bestMode(contentFps, modes)")
        val request = modeFirst.indexOf("best.id != modeId -> requestMode(best, contentFps, modeId)")
        val nothingBetter = modeFirst.indexOf("best == null -> askSurfaceIfPossible(contentFps, modeId)")
        assertTrue("the mode-first block must search the panel's modes", best >= 0)
        assertTrue(
            "the mode request is what starts the engage on a panel with a better mode",
            request > best
        )
        assertTrue(
            "and the surface hint is reached from one arm only: the panel with no mode answer",
            count(modeFirst, "askSurfaceIfPossible(") == 1
        )
        assertTrue(
            "and the surface hint is only for a panel with nothing better to switch to, or for " +
                "one that is already in its best mode and was never asked (2026-10-18)",
            nothingBetter > best
        )
        assertFalse(
            "the mode-first block must not call a mode search a second time as a fallback: " +
                "there is nothing after the surface hint",
            modeFirst.contains("forceMode(")
        )
        assertFalse(
            "and not the exclusion-free search's pre-fix twin either: it cannot tell 'nothing " +
                "better' from 'already there', which is what fired the hint at the live surface",
            modeFirst.contains("chooseMode(")
        )
    }

    @Test
    fun `the mode request is the one the field can verify`() {
        assertTrue(
            "a mode request is a preferredDisplayModeId write, not a surface rate",
            requestMode.contains("setPreferredModeId(chosen.id)") &&
                match.contains("attributes.preferredDisplayModeId = modeId")
        )
        assertTrue(
            "the restore on teardown and the mid-film hold both depend on knowing which mode " +
                "this session holds (audit AFR C5: one field, so a taken-over panel is held too)",
            requestMode.contains("heldModeId = chosen.id")
        )
        assertTrue(
            "the field log line the acceptance check reads for ('forced mode ... as the FIRST " +
                "engage line') is emitted here",
            requestMode.contains("forced mode \${chosen.id} at \${chosen.refreshRate} Hz for ")
        )
        assertTrue(
            "and it is confirmed like every other request, so a refused mode is seen",
            requestMode.contains("confirm(serial, currentModeId, contentFps, EngageStep.MODE, chosen)")
        )
    }

    @Test
    fun `a mode confirm falls to a later mode look, never to the surface`() {
        assertTrue(
            "a mode that has not moved gets a second look at the MODE, ~3.5 s out " +
                "(2026-10-22, Door 2)",
            confirm.contains("scheduleSecondLook(serial, beforeModeId, contentFps, mode)") &&
                window(match, "private fun scheduleSecondLook(", "private fun clearSurfaceRate(")
                    .contains("EngageStep.MODE_SECOND_LOOK")
        )
        assertFalse(
            "and never the surface hint: once the panel has a mode answer, a rate on the live " +
                "surface under the forced mode is the stutter the field captures convict",
            confirm.contains("askSurfaceIfPossible(")
        )
        assertTrue(
            "the second look is what records a refusal - re-asked by attribute, no surface vote",
            match.contains(
                "FrameRateMatch.describeModeRefused(contentFps, requested, afterModeId)"
            )
        )
        assertTrue(
            "the pre-fix order keeps its own ladder for the bisect: surface first, mode as its " +
                "backstop",
            confirm.contains(
                "if (!ENGAGE_MODE_FIRST) { forceMode(contentFps, supportedModes(display), " +
                    "afterModeId) }"
            )
        )
        assertTrue(
            "and a panel that DID move ends the engage right there",
            confirm.contains("if (afterModeId != beforeModeId) return")
        )
        assertTrue(
            "a paused picture drops every look, the second one included",
            confirm.contains("if (!isPlaybackActive()) return")
        )
    }

    @Test
    fun `a panel with no better mode still gets the surface hint`() {
        assertTrue(
            "a single-60 Hz TV is the regression case: bestMode finds nothing better to switch " +
                "to, and the surface hint must still be made",
            modeFirst.contains("best == null -> askSurfaceIfPossible(contentFps, modeId)")
        )
        assertTrue(
            "the mode search stays a pure rule the JVM tests drive",
            match.contains("fun chooseMode(")
        )
    }

    /** How many times [needle] occurs in [text]. */
    private fun count(text: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(text).count()

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
